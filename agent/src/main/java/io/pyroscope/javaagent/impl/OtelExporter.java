package io.pyroscope.javaagent.impl;

import io.pyroscope.javaagent.Snapshot;
import io.pyroscope.javaagent.api.Exporter;
import io.pyroscope.javaagent.api.Logger;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Base64;

/**
 * Exports profiling snapshots to an OpenTelemetry collector via OTLP/HTTP (Logs signal).
 * The JFR data is sent as a base64-encoded bytes value in the log body,
 * with profiling metadata attached as log attributes.
 *
 * Configure your OTel Collector to receive on port 4040:
 * <pre>
 * receivers:
 *   otlp:
 *     protocols:
 *       http:
 *         endpoint: "0.0.0.0:4040"
 * </pre>
 */
public class OtelExporter implements Exporter {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String OTLP_LOGS_PATH = "/v1/logs";

    private final String collectorEndpoint;
    private final String serviceName;
    private final Logger logger;
    private final OkHttpClient client;

    /**
     * @param collectorEndpoint base URL of the OTel collector, e.g. "http://localhost:4040"
     * @param serviceName       service name reported as the resource attribute
     * @param logger            logger instance
     */
    public OtelExporter(String collectorEndpoint, String serviceName, Logger logger) {
        this.collectorEndpoint = collectorEndpoint.replaceAll("/$", "");
        this.serviceName = serviceName;
        this.logger = logger;
        this.client = new OkHttpClient.Builder().build();
    }

    @Override
    public void export(@NotNull Snapshot snapshot) {
        String jfrBase64 = Base64.getEncoder().encodeToString(snapshot.data);
        long startNanos = snapshot.started.getEpochSecond() * 1_000_000_000L
                          + snapshot.started.getNano();
        long endNanos = snapshot.ended.getEpochSecond() * 1_000_000_000L
                        + snapshot.ended.getNano();

        String body = buildOtlpLogsJson(jfrBase64, startNanos, endNanos,
                snapshot.eventType.id, serviceName);

        Request request = new Request.Builder()
                .url(collectorEndpoint + OTLP_LOGS_PATH)
                .post(RequestBody.create(body, JSON))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                ResponseBody rb = response.body();
                String msg = rb != null ? rb.string() : "";
                logger.log(Logger.Level.ERROR,
                        "OtelExporter: failed to export snapshot: HTTP %d %s", response.code(), msg);
            } else {
                logger.log(Logger.Level.DEBUG,
                        "OtelExporter: exported snapshot to %s", collectorEndpoint);
            }
        } catch (IOException e) {
            logger.log(Logger.Level.ERROR, "OtelExporter: error exporting snapshot: %s", e.getMessage());
        }
    }

    @Override
    public void stop() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
        try {
            if (client.cache() != null) {
                client.cache().close();
            }
        } catch (IOException ignored) {}
    }

    private static String buildOtlpLogsJson(String jfrBase64, long startNanos, long endNanos,
                                             String eventType, String serviceName) {
        return "{"
             + "\"resourceLogs\":[{"
             +   "\"resource\":{"
             +     "\"attributes\":[{"
             +       "\"key\":\"service.name\","
             +       "\"value\":{\"stringValue\":\"" + escapeJson(serviceName) + "\"}"
             +     "}]"
             +   "},"
             +   "\"scopeLogs\":[{"
             +     "\"scope\":{\"name\":\"pyroscope-java\"},"
             +     "\"logRecords\":[{"
             +       "\"timeUnixNano\":\"" + startNanos + "\","
             +       "\"body\":{\"bytesValue\":\"" + jfrBase64 + "\"},"
             +       "\"attributes\":["
             +         "{\"key\":\"profile.format\",\"value\":{\"stringValue\":\"jfr\"}},"
             +         "{\"key\":\"profile.event_type\",\"value\":{\"stringValue\":\"" + escapeJson(eventType) + "\"}},"
             +         "{\"key\":\"profile.start_unix_nano\",\"value\":{\"stringValue\":\"" + startNanos + "\"}},"
             +         "{\"key\":\"profile.end_unix_nano\",\"value\":{\"stringValue\":\"" + endNanos + "\"}}"
             +       "]"
             +     "}]"
             +   "}]"
             + "}]"
             + "}";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}