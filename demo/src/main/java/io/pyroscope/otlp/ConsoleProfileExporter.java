package io.pyroscope.otlp;

import io.pyroscope.javaagent.Snapshot;
import io.pyroscope.javaagent.api.Exporter;
import io.pyroscope.javaagent.api.Logger;
import io.pyroscope.labels.pb.JfrLabels;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prints profiling snapshots to a {@link PrintStream} (default: {@code System.out}).
 *
 * <p>Output per snapshot:
 * <pre>
 * ─────────────────────────────────────────────────────────────
 * [PROFILE] app=demo.otlp-app  event=itimer  2024-03-11T10:00:00Z → +15.000s  340 samples
 * ─────────────────────────────────────────────────────────────
 * Labels (dynamic):
 *   thread_name = worker-1
 *   trace_id    = 4bf92f3577b34da6a3ce929d0e0e4736
 *   span_id     = 00f067aa0ba902b7
 *
 * Top 10 stacks by sample count:
 *   [  87]  App.fib(App.java:72)
 *           App.fib(App.java:72)
 *           App.handleRequest(App.java:55)
 *   [  43]  java.lang.Thread.sleep(Native)
 *           ...
 * ─────────────────────────────────────────────────────────────
 * </pre>
 *
 * <p>Requires Java 11+ ({@code jdk.jfr.consumer}).
 */
public final class ConsoleProfileExporter implements Exporter {

    private static final int DEFAULT_TOP_N = 10;
    private static final int DEFAULT_MAX_FRAMES = 8;

    private final PrintStream out;
    private final Logger      logger;
    private final int         topN;
    private final int         maxFrames;
    private final String      appName;

    private ConsoleProfileExporter(Builder b) {
        this.out       = b.out;
        this.logger    = b.logger;
        this.topN      = b.topN;
        this.maxFrames = b.maxFrames;
        this.appName   = b.appName;
    }

    // ── Exporter ──────────────────────────────────────────────────────────────

    @Override
    public void export(Snapshot snapshot) {
        try {
            String report = buildReport(snapshot);
            out.println(report);
        } catch (Exception e) {
            logger.log(Logger.Level.WARN, "ConsoleProfileExporter: failed to render snapshot: %s",
                e.getMessage());
        }
    }

    @Override
    public void stop() {
        // no resources to release
    }

    // ── Report builder ────────────────────────────────────────────────────────

    private String buildReport(Snapshot snapshot) throws IOException {
        StringBuilder sb = new StringBuilder();
        String divider = "─".repeat(65);

        long durationMs = Duration.between(snapshot.started, snapshot.ended).toMillis();

        sb.append('\n').append(divider).append('\n');
        sb.append(String.format("[PROFILE]  app=%-25s  event=%-8s  %s → +%.3fs%n",
            appName,
            snapshot.eventType.id,
            snapshot.started,
            durationMs / 1000.0));
        sb.append(String.format("           format=%-6s  data=%d bytes%n",
            snapshot.format.name(),
            snapshot.data.length));
        sb.append(divider).append('\n');

        // ── Dynamic labels from LabelsSnapshot ────────────────────────────
        Map<Long, String>            strings  = snapshot.labels.getStringsMap();
        Map<Long, JfrLabels.Context> contexts = snapshot.labels.getContextsMap();

        if (!contexts.isEmpty()) {
            sb.append("Labels (dynamic, per active context):\n");
            // Print unique label key→value pairs across all active contexts
            Map<String, String> seen = new LinkedHashMap<>();
            for (JfrLabels.Context ctx : contexts.values()) {
                for (Map.Entry<Long, Long> e : ctx.getLabelsMap().entrySet()) {
                    String k = strings.get(e.getKey());
                    String v = strings.get(e.getValue());
                    if (k != null && v != null) seen.putIfAbsent(k, v);
                }
            }
            seen.forEach((k, v) ->
                sb.append(String.format("  %-20s = %s%n", k, v)));
            sb.append('\n');
        }

        // ── Top-N stacks from JFR ─────────────────────────────────────────
        Map<String, long[]> stackCounts = parseStacks(snapshot);

        // Sort descending by sample count
        List<Map.Entry<String, long[]>> sorted = new ArrayList<>(stackCounts.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));

        long total = sorted.stream().mapToLong(e -> e.getValue()[0]).sum();
        sb.append(String.format("Top %d stacks  (%d total samples):%n", topN, total));

        int shown = Math.min(topN, sorted.size());
        for (int i = 0; i < shown; i++) {
            Map.Entry<String, long[]> entry = sorted.get(i);
            long   count   = entry.getValue()[0];
            double pct     = total > 0 ? (count * 100.0 / total) : 0;
            String[] frames = entry.getKey().split("\n");

            sb.append(String.format("%n  [%4d | %5.1f%%]  %s%n", count, pct, frames[0]));
            int frameLimit = Math.min(maxFrames, frames.length);
            for (int f = 1; f < frameLimit; f++) {
                sb.append(String.format("  %14s  %s%n", "", frames[f]));
            }
            if (frames.length > maxFrames) {
                sb.append(String.format("  %14s  ... (%d more frames)%n",
                    "", frames.length - maxFrames));
            }
        }

        if (sorted.isEmpty()) {
            sb.append("  (no samples in this window)\n");
        }

        sb.append('\n').append(divider).append('\n');
        return sb.toString();
    }

    // ── JFR stack parsing ─────────────────────────────────────────────────────

    /**
     * Parses the JFR binary and returns a map of
     * {@code "frame0\nframe1\n..." → [sampleCount]}.
     */
    private Map<String, long[]> parseStacks(Snapshot snapshot) throws IOException {
        Map<String, long[]> counts = new LinkedHashMap<>();

        Path tmp = Files.createTempFile("pyroscope-console-", ".jfr");
        try {
            Files.write(tmp, snapshot.data);
            try (RecordingFile rf = new RecordingFile(tmp)) {
                while (rf.hasMoreEvents()) {
                    RecordedEvent event = rf.readEvent();
                    if (!isRelevant(event, snapshot)) continue;

                    RecordedStackTrace stackTrace = event.getStackTrace();
                    if (stackTrace == null || stackTrace.getFrames().isEmpty()) continue;

                    long value = extractValue(event, snapshot);
                    String key = buildStackKey(stackTrace);

                    counts.computeIfAbsent(key, k -> new long[]{0})[0] += value;
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return counts;
    }

    private String buildStackKey(RecordedStackTrace stackTrace) {
        StringBuilder sb = new StringBuilder();
        for (RecordedFrame frame : stackTrace.getFrames()) {
            if (!frame.isJavaFrame()) continue;
            String className  = frame.getMethod().getType().getName();
            String methodName = frame.getMethod().getName();
            int    line       = frame.getLineNumber();
            if (sb.length() > 0) sb.append('\n');
            sb.append(className).append('.').append(methodName)
              .append('(').append(simpleClass(className)).append(".java:")
              .append(line > 0 ? line : "?").append(')');
        }
        return sb.toString();
    }

    private static String simpleClass(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }

    private static boolean isRelevant(RecordedEvent event, Snapshot snapshot) {
        String name = event.getEventType().getName();
        switch (snapshot.eventType) {
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

    private static long extractValue(RecordedEvent event, Snapshot snapshot) {
        switch (snapshot.eventType) {
            case ALLOC:
                if (event.hasField("tlabSize"))       return event.getLong("tlabSize");
                if (event.hasField("allocationSize")) return event.getLong("allocationSize");
                return 1L;
            case LOCK:  return event.getDuration().toNanos();
            default:    return 1L;
        }
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private PrintStream out       = System.out;
        private Logger      logger    = (l, msg, args) -> System.err.printf("[ConsoleExporter] " + msg + "%n", args);
        private int         topN      = DEFAULT_TOP_N;
        private int         maxFrames = DEFAULT_MAX_FRAMES;
        private String      appName   = "app";

        /** Output destination. Defaults to {@code System.out}. */
        public Builder out(PrintStream out)          { this.out = out;           return this; }
        public Builder logger(Logger logger)          { this.logger = logger;     return this; }
        /** How many top stacks to print per snapshot. Default 10. */
        public Builder topN(int topN)                { this.topN = topN;         return this; }
        /** Max frames shown per stack. Default 8. */
        public Builder maxFrames(int maxFrames)       { this.maxFrames = maxFrames; return this; }
        public Builder appName(String appName)        { this.appName = appName;   return this; }

        public ConsoleProfileExporter build() { return new ConsoleProfileExporter(this); }
    }
}