package io.pyroscope.otlp;

import io.pyroscope.javaagent.Snapshot;
import io.pyroscope.javaagent.api.Exporter;
import io.pyroscope.javaagent.api.Logger;

import java.util.Arrays;
import java.util.List;

/**
 * Forwards each {@link Snapshot} to multiple {@link Exporter} instances in order.
 *
 * <p>A failure in one exporter is logged and skipped — remaining exporters still run.
 *
 * <pre>{@code
 * Exporter fanOut = FanOutExporter.of(consoleExporter, otlpExporter);
 * PyroscopeAgent.start(
 *     new PyroscopeAgent.Options.Builder(config)
 *         .setExporter(new QueuedExporter(config, fanOut, logger))
 *         .build()
 * );
 * }</pre>
 */
public final class FanOutExporter implements Exporter {

    private final List<Exporter> exporters;
    private final Logger         logger;

    private FanOutExporter(List<Exporter> exporters, Logger logger) {
        this.exporters = exporters;
        this.logger    = logger;
    }

    public static FanOutExporter of(Logger logger, Exporter... exporters) {
        return new FanOutExporter(Arrays.asList(exporters), logger);
    }

    @Override
    public void export(Snapshot snapshot) {
        for (Exporter exporter : exporters) {
            try {
                exporter.export(snapshot);
            } catch (Exception e) {
                logger.log(Logger.Level.ERROR,
                    "FanOutExporter: %s failed: %s",
                    exporter.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    @Override
    public void stop() {
        for (Exporter exporter : exporters) {
            try {
                exporter.stop();
            } catch (Exception e) {
                logger.log(Logger.Level.WARN,
                    "FanOutExporter: error stopping %s: %s",
                    exporter.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}