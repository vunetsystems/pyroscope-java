package io.pyroscope.otlp;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.profiles.v1development.Function;
import io.opentelemetry.proto.profiles.v1development.Line;
import io.opentelemetry.proto.profiles.v1development.Link;
import io.opentelemetry.proto.profiles.v1development.Location;
import io.opentelemetry.proto.profiles.v1development.Mapping;
import io.opentelemetry.proto.profiles.v1development.Profile;
import io.opentelemetry.proto.profiles.v1development.Sample;
import io.opentelemetry.proto.profiles.v1development.ValueType;
import io.pyroscope.javaagent.EventType;
import io.pyroscope.javaagent.Snapshot;
import io.pyroscope.labels.pb.JfrLabels;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Converts a Pyroscope {@link Snapshot} (raw JFR binary) into an OTel {@link Profile} proto.
 *
 * <h3>Context details sent</h3>
 * <ul>
 *   <li><b>Static labels</b> ({@code PYROSCOPE_LABELS}) — added as Resource attributes
 *       by {@link OtlpGrpcProfileExporter}.</li>
 *   <li><b>Dynamic labels</b> ({@code Pyroscope.LabelsWrapper}) — decoded from
 *       {@code snapshot.labels} (LabelsSnapshot) via the per-event {@code contextId} field
 *       injected by async-profiler. Stored as {@code Sample.attributes} so each sample
 *       carries its own label set.</li>
 *   <li><b>Trace context</b> ({@code trace_id} / {@code span_id}) — if present in the
 *       dynamic labels (injected via {@code Pyroscope.LabelsWrapper}), they are promoted
 *       to {@code Profile.link_table} / {@code Sample.link} for proper trace-profile
 *       correlation as per the OTel Profiles spec.</li>
 * </ul>
 *
 * <h3>How dynamic labels get into the JFR events</h3>
 * async-profiler writes a {@code contextId} (long) onto each JFR event. That ID maps to a
 * {@code Context} entry in {@code LabelsSnapshot.contexts}. Each {@code Context.labels} maps
 * interned string IDs (key → value) using {@code LabelsSnapshot.strings} as the string table.
 *
 * <p>Requires Java 11+ ({@code jdk.jfr.consumer.RecordingFile}).
 */
final class JfrToOtlpConverter {

    private JfrToOtlpConverter() {}

    static Profile convert(Snapshot snapshot) throws IOException {
        Path tmp = Files.createTempFile("pyroscope-jfr-", ".jfr");
        try {
            Files.write(tmp, snapshot.data);
            return parseJfr(tmp, snapshot);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ── Core parsing ──────────────────────────────────────────────────────────

    private static Profile parseJfr(Path jfrFile, Snapshot snapshot) throws IOException {

        // ── Label decoding helpers ─────────────────────────────────────────
        // snapshot.labels.strings  : map<long, String>  — interned string table
        // snapshot.labels.contexts : map<long, Context> — per-thread label state
        //   Context.labels          : map<long, long>    — key stringId → value stringId
        Map<Long, String>            labelStrings  = snapshot.labels.getStringsMap();
        Map<Long, JfrLabels.Context> labelContexts = snapshot.labels.getContextsMap();

        // ── OTel Profile state ─────────────────────────────────────────────
        StringTable strings = new StringTable();   // Profile.string_table

        Map<String, Integer>  functionCache = new LinkedHashMap<>();
        List<Function.Builder> functions    = new ArrayList<>();
        List<Location.Builder> locations    = new ArrayList<>();

        // attribute_table: unique KeyValue entries referenced by Sample.attributes
        Map<String, Integer> attrTableIndex = new LinkedHashMap<>(); // "key\0val" → index
        List<KeyValue>       attrTable      = new ArrayList<>();

        // link_table: unique (traceId, spanId) pairs referenced by Sample.link
        // Index 0 is a reserved "no-link" sentinel (empty bytes) so that
        // samples with no trace context can safely default to link=0.
        Map<String, Integer> linkTableIndex = new LinkedHashMap<>();
        List<Link>           linkTable      = new ArrayList<>();
        // Reserve index 0
        linkTable.add(Link.newBuilder()
            .setTraceId(ByteString.EMPTY)
            .setSpanId(ByteString.EMPTY)
            .build());
        linkTableIndex.put("", 0);

        // fingerprint → accumulated sample
        // Fingerprint includes context labels so same stack with different labels
        // produces separate samples (correct per-thread attribution).
        Map<String, SampleAccumulator> sampleCache = new LinkedHashMap<>();

        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                if (!isRelevant(event, snapshot.eventType)) continue;

                jdk.jfr.consumer.RecordedStackTrace stackTrace = event.getStackTrace();
                if (stackTrace == null || stackTrace.getFrames().isEmpty()) continue;

                long sampleValue = extractValue(event, snapshot.eventType);

                // ── Decode per-sample labels from LabelsSnapshot ──────────
                long contextId = event.hasField("contextId")
                    ? event.getLong("contextId") : 0L;
                Map<String, String> sampleLabels =
                    decodeContext(contextId, labelContexts, labelStrings);

                // ── Build stack fingerprint (stack + label set) ───────────
                StringBuilder fp = new StringBuilder();
                List<Integer> frameLocIndices = new ArrayList<>();

                for (RecordedFrame frame : stackTrace.getFrames()) {
                    if (!frame.isJavaFrame()) continue;

                    RecordedMethod method     = frame.getMethod();
                    String         className  = method.getType().getName();
                    String         methodName = method.getName();
                    String         descriptor = method.getDescriptor();
                    int            lineNumber = frame.getLineNumber();

                    String funcKey = className + "." + methodName + descriptor;
                    int funcIdx = functionCache.computeIfAbsent(funcKey, k -> {
                        int idx = functions.size();
                        functions.add(Function.newBuilder()
                            .setNameStrindex(strings.intern(className + "." + methodName))
                            .setSystemNameStrindex(strings.intern(funcKey))
                            .setFilenameStrindex(strings.intern(
                                className.replace('.', '/') + ".java"))
                            .setStartLine(Math.max(lineNumber, 0)));
                        return idx;
                    });

                    frameLocIndices.add(locations.size());
                    locations.add(Location.newBuilder()
                        .setMappingIndex(0)
                        .addLine(Line.newBuilder()
                            .setFunctionIndex(funcIdx)
                            .setLine(lineNumber > 0 ? lineNumber : 0)));

                    fp.append(funcKey).append(':').append(lineNumber).append(';');
                }

                if (frameLocIndices.isEmpty()) continue;

                // Append label fingerprint so same stack + different labels → different sample
                sampleLabels.forEach((k, v) -> fp.append('|').append(k).append('=').append(v));

                String fingerprint = fp.toString();
                SampleAccumulator acc = sampleCache.get(fingerprint);
                if (acc == null) {
                    int startIdx = locations.size() - frameLocIndices.size();
                    acc = new SampleAccumulator(startIdx, frameLocIndices.size(),
                                                sampleValue, sampleLabels);
                    sampleCache.put(fingerprint, acc);
                } else {
                    acc.value += sampleValue;
                }
            }
        }

        return buildProfile(snapshot, strings, functions, locations,
                            sampleCache, attrTable, attrTableIndex,
                            linkTable, linkTableIndex);
    }

    // ── Profile assembly ──────────────────────────────────────────────────────

    private static Profile buildProfile(
            Snapshot snapshot,
            StringTable strings,
            List<Function.Builder> functions,
            List<Location.Builder> locations,
            Map<String, SampleAccumulator> sampleCache,
            List<KeyValue> attrTable,
            Map<String, Integer> attrTableIndex,
            List<Link> linkTable,
            Map<String, Integer> linkTableIndex) {

        long startNs    = snapshot.started.toEpochMilli() * 1_000_000L;
        long durationNs = (snapshot.ended.toEpochMilli()
                        -  snapshot.started.toEpochMilli()) * 1_000_000L;

        Profile.Builder profile = Profile.newBuilder()
            .setProfileId(randomProfileId())
            .setTimeNanos(startNs)
            .setDurationNanos(durationNs)
            .addSampleType(ValueType.newBuilder()
                .setTypeStrindex(strings.intern(sampleTypeName(snapshot.eventType)))
                .setUnitStrindex(strings.intern(sampleTypeUnit(snapshot.eventType))))
            .setOriginalPayloadFormat("jfr")
            .setOriginalPayload(ByteString.copyFrom(snapshot.data));

        profile.addMappingTable(Mapping.newBuilder()
            .setFilenameStrindex(strings.intern("jvm"))
            .setHasFunctions(true)
            .setHasLineNumbers(true));

        functions.forEach(f -> profile.addFunctionTable(f.build()));
        locations.forEach(l -> profile.addLocationTable(l.build()));

        // ── Build samples with attribute + link resolution ─────────────────
        for (SampleAccumulator acc : sampleCache.values()) {
            Sample.Builder sample = Sample.newBuilder()
                .setLocationsStartIndex(acc.startIndex)
                .setLocationsLength(acc.length)
                .addValue(acc.value);

            for (Map.Entry<String, String> label : acc.labels.entrySet()) {
                String key = label.getKey();
                String val = label.getValue();

                // trace_id and span_id are promoted to Profile.link_table / Sample.link
                // rather than stored as plain attributes (OTel Profiles spec convention).
                if ("trace_id".equals(key) || "span_id".equals(key)) continue;

                int attrIdx = internAttr(key, val, attrTableIndex, attrTable);
                sample.addAttributeIndices(attrIdx);
            }

            // ── Trace context → Sample.link ───────────────────────────────
            String traceId = acc.labels.get("trace_id");
            String spanId  = acc.labels.get("span_id");
            if (traceId != null && !traceId.isEmpty()
                    && spanId != null && !spanId.isEmpty()) {
                int linkIdx = internLink(traceId, spanId, linkTableIndex, linkTable);
                sample.setLinkIndex(linkIdx);
            }
            // samples with no trace context: link stays at default 0 (the sentinel)

            profile.addSample(sample);
        }

        // ── Attribute table ────────────────────────────────────────────────
        attrTable.forEach(profile::addAttributeTable);

        // ── Link table (only add if there are real links beyond the sentinel) ─
        if (linkTable.size() > 1) {
            linkTable.forEach(profile::addLinkTable);
        }

        // ── String table (must be last — all intern() calls must precede this) ─
        strings.getTable().forEach(profile::addStringTable);

        return profile.build();
    }

    // ── Label decoding ────────────────────────────────────────────────────────

    /**
     * Decodes the label map for a given async-profiler contextId.
     *
     * <pre>
     * LabelsSnapshot.strings  : { 1→"trace_id", 2→"abc123", 3→"thread_name", 4→"worker-1" }
     * LabelsSnapshot.contexts : { 42→Context{ labels:{1→2, 3→4} } }
     *
     * contextId=42  →  { "trace_id"→"abc123", "thread_name"→"worker-1" }
     * </pre>
     */
    private static Map<String, String> decodeContext(
            long contextId,
            Map<Long, JfrLabels.Context> contexts,
            Map<Long, String> strings) {

        if (contextId == 0L) return Collections.emptyMap();

        JfrLabels.Context ctx = contexts.get(contextId);
        if (ctx == null) return Collections.emptyMap();

        Map<String, String> result = new LinkedHashMap<>(ctx.getLabelsMap().size());
        for (Map.Entry<Long, Long> entry : ctx.getLabelsMap().entrySet()) {
            String key = strings.get(entry.getKey());
            String val = strings.get(entry.getValue());
            if (key != null && val != null) {
                result.put(key, val);
            }
        }
        return result;
    }

    // ── Attribute table helpers ───────────────────────────────────────────────

    private static int internAttr(
            String key, String value,
            Map<String, Integer> index,
            List<KeyValue> table) {
        return index.computeIfAbsent(key + "\0" + value, k -> {
            int idx = table.size();
            table.add(KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value))
                .build());
            return idx;
        });
    }

    // ── Link table helpers ────────────────────────────────────────────────────

    /**
     * Interns a (traceId hex, spanId hex) pair into the link table.
     * traceId is 32 hex chars → 16 bytes; spanId is 16 hex chars → 8 bytes.
     */
    private static int internLink(
            String traceIdHex, String spanIdHex,
            Map<String, Integer> index,
            List<Link> table) {
        String key = traceIdHex + ":" + spanIdHex;
        return index.computeIfAbsent(key, k -> {
            int idx = table.size();
            table.add(Link.newBuilder()
                .setTraceId(hexToBytes(traceIdHex))
                .setSpanId(hexToBytes(spanIdHex))
                .build());
            return idx;
        });
    }

    private static ByteString hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) return ByteString.EMPTY;
        int len = hex.length();
        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < bytes.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2),     16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            bytes[i] = (byte) ((hi << 4) | lo);
        }
        return ByteString.copyFrom(bytes);
    }

    // ── JFR event filtering & value extraction ────────────────────────────────

    private static boolean isRelevant(RecordedEvent event, EventType eventType) {
        String name = event.getEventType().getName();
        switch (eventType) {
            case CPU: case ITIMER: case CTIMER: case WALL:
                return "jdk.ExecutionSample".equals(name)
                    || "jdk.NativeMethodSample".equals(name);
            case ALLOC:
                return "jdk.ObjectAllocationInNewTLAB".equals(name)
                    || "jdk.ObjectAllocationOutsideTLAB".equals(name);
            case LOCK:
                return "jdk.JavaMonitorEnter".equals(name)
                    || "jdk.ThreadPark".equals(name);
            default:
                return "jdk.ExecutionSample".equals(name);
        }
    }

    private static long extractValue(RecordedEvent event, EventType eventType) {
        switch (eventType) {
            case ALLOC:
                if (event.hasField("tlabSize"))       return event.getLong("tlabSize");
                if (event.hasField("allocationSize")) return event.getLong("allocationSize");
                return 1L;
            case LOCK:
                return event.getDuration().toNanos();
            default:
                return 1L;
        }
    }

    private static String sampleTypeName(EventType t) {
        switch (t) { case ALLOC: return "alloc_space"; case LOCK: return "contentions";
                     default: return "cpu"; }
    }

    private static String sampleTypeUnit(EventType t) {
        switch (t) { case ALLOC: return "bytes"; case LOCK: return "nanoseconds";
                     default: return "nanoseconds"; }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ByteString randomProfileId() {
        UUID uuid = UUID.randomUUID();
        byte[] b = new byte[16];
        long msb = uuid.getMostSignificantBits(), lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            b[i]     = (byte) (msb >>> (56 - 8 * i));
            b[i + 8] = (byte) (lsb >>> (56 - 8 * i));
        }
        return ByteString.copyFrom(b);
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private static final class SampleAccumulator {
        final int                startIndex;
        final int                length;
        final Map<String, String> labels;    // decoded from LabelsSnapshot
        long                     value;

        SampleAccumulator(int startIndex, int length, long value,
                          Map<String, String> labels) {
            this.startIndex = startIndex;
            this.length     = length;
            this.value      = value;
            this.labels     = labels;
        }
    }
}