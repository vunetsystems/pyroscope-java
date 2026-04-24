import io.pyroscope.http.Format;
import io.pyroscope.javaagent.EventType;
import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.api.Logger;
import io.pyroscope.javaagent.config.Config;
import io.pyroscope.javaagent.impl.DefaultLogger;
import io.pyroscope.javaagent.impl.QueuedExporter;
import io.pyroscope.labels.v2.LabelsSet;
import io.pyroscope.labels.v2.Pyroscope;
import io.pyroscope.otlp.ConsoleProfileExporter;
import io.pyroscope.otlp.FanOutExporter;
import io.pyroscope.otlp.OtlpGrpcProfileExporter;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Demo: sends JVM profiles to the OTel Collector's gRPC endpoint (port 4317)
 * using OtlpGrpcProfileExporter.
 *
 * Context details sent per profile/sample:
 *
 *  ┌─────────────────────────┬────────────────────────────────────────────────┐
 *  │ Context type            │ Where it appears in OTel Profile proto         │
 *  ├─────────────────────────┼────────────────────────────────────────────────┤
 *  │ Static labels           │ ResourceProfiles.resource.attributes           │
 *  │ (PYROSCOPE_LABELS)      │ e.g. service.name, env, region                 │
 *  ├─────────────────────────┼────────────────────────────────────────────────┤
 *  │ Dynamic labels          │ Sample.attributes (via Profile.attribute_table) │
 *  │ (LabelsWrapper)         │ e.g. thread_name, user_id, request_path        │
 *  ├─────────────────────────┼────────────────────────────────────────────────┤
 *  │ Trace context           │ Sample.link → Profile.link_table[].traceId     │
 *  │ (trace_id + span_id     │                               .spanId          │
 *  │  via LabelsWrapper)     │ Enables trace ↔ profile correlation in Grafana │
 *  └─────────────────────────┴────────────────────────────────────────────────┘
 */
public class OtlpApp {

    public static void main(String[] args) {
        Config config = new Config.Builder()
            .setApplicationName("demo.otlp-app")
            .setFormat(Format.JFR)
            .setProfilingEvent(EventType.ITIMER)
            .setProfilingAlloc("512k")
            .setLogLevel(Logger.Level.DEBUG)
            .setServerAddress("http://localhost:4040") // unused by custom exporter; must be valid URL
            .build();

        Logger logger = new DefaultLogger(config.logLevel, System.err);

        // ── Exporter 1: print to console ──────────────────────────────────
        ConsoleProfileExporter consoleExporter = ConsoleProfileExporter.builder()
            .appName(config.applicationName)
            .topN(10)
            .maxFrames(8)
            .logger(logger)
            .build();

        // ── Exporter 2: send to OTel Collector via gRPC ───────────────────
        OtlpGrpcProfileExporter otlpExporter = OtlpGrpcProfileExporter.builder()
            .endpoint("localhost:4317")
            .usePlaintext()
            .config(config)
            .logger(logger)
            .build();

        // ── Fan-out: both exporters receive every snapshot ─────────────────
        // Wrap in QueuedExporter so the profiling thread is never blocked.
        PyroscopeAgent.start(
            new PyroscopeAgent.Options.Builder(config)
                .setExporter(new QueuedExporter(config,
                    FanOutExporter.of(logger, consoleExporter, otlpExporter),
                    logger))
                .build()
        );

        appLogic();
    }

    private static void appLogic() {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                while (true) {
                    try {
                        handleRequest();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
        }
    }

    /**
     * Simulates a request handler that injects trace context + dynamic labels
     * into the profiler via LabelsWrapper.
     *
     * These labels are written by async-profiler as a contextId onto each JFR event.
     * JfrToOtlpConverter decodes them back and maps:
     *   - thread_name, user_id → Sample.attributes
     *   - trace_id, span_id    → Sample.link → Profile.link_table (OTel trace correlation)
     */
    private static void handleRequest() throws InterruptedException {
        // In a real app these come from the OTel SDK active span:
        //   Span span = Span.current();
        //   String traceId = span.getSpanContext().getTraceId();
        //   String spanId  = span.getSpanContext().getSpanId();
        String traceId = UUID.randomUUID().toString().replace("-", ""); // 32 hex chars
        String spanId  = traceId.substring(0, 16);                      //  8 bytes → 16 hex

        Pyroscope.LabelsWrapper.run(
            new LabelsSet(
                "trace_id",   traceId,
                "span_id",    spanId,
                "thread_name", Thread.currentThread().getName()
            ),
            () -> {
                try { fib(30L); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        );
    }

    private static long fib(long n) throws InterruptedException {
        if (n <= 1) return n;
        return fib(n - 1) + fib(n - 2);
    }
}