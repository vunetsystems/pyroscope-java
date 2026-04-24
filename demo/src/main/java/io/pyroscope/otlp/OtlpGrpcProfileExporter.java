package io.pyroscope.otlp;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.opentelemetry.proto.collector.profiles.v1development.ExportProfilesServiceRequest;
import io.opentelemetry.proto.collector.profiles.v1development.ProfilesServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.profiles.v1development.Profile;
import io.opentelemetry.proto.profiles.v1development.ResourceProfiles;
import io.opentelemetry.proto.profiles.v1development.ScopeProfiles;
import io.opentelemetry.proto.resource.v1.Resource;
import io.pyroscope.javaagent.Snapshot;
import io.pyroscope.javaagent.api.Exporter;
import io.pyroscope.javaagent.api.Logger;
import io.pyroscope.javaagent.config.Config;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Exports JVM profiles directly to any OTel Collector gRPC endpoint
 * (port 4317) using the OTLP Profiles signal.
 *
 * <p>Usage — wire into {@link io.pyroscope.javaagent.PyroscopeAgent}:
 * <pre>{@code
 * Config config = new Config.Builder()
 *     .setApplicationName("my-service")
 *     .setProfilingEvent(EventType.ITIMER)
 *     .setFormat(Format.JFR)
 *     .build();
 *
 * OtlpGrpcProfileExporter otlpExporter = OtlpGrpcProfileExporter.builder()
 *     .endpoint("localhost:4317")   // OTel Collector gRPC
 *     .config(config)
 *     .usePlaintext()               // remove for TLS
 *     .build();
 *
 * PyroscopeAgent.start(
 *     new PyroscopeAgent.Options.Builder(config)
 *         .setExporter(new QueuedExporter(config, otlpExporter, logger))
 *         .build()
 * );
 * }</pre>
 *
 * <p>Requires Java 11+ (JFR consumer API used by {@link JfrToOtlpConverter}).
 */
public final class OtlpGrpcProfileExporter implements Exporter {

    private static final int DEFAULT_TIMEOUT_SECONDS = 10;

    private final ManagedChannel channel;
    private final ProfilesServiceGrpc.ProfilesServiceBlockingStub stub;
    private final Resource resource;
    private final Logger logger;

    private OtlpGrpcProfileExporter(Builder builder) {
        ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder
            .forTarget(builder.endpoint);

        if (builder.usePlaintext) {
            channelBuilder.usePlaintext();
        }

        this.channel  = channelBuilder.build();
        this.stub     = ProfilesServiceGrpc
            .newBlockingStub(channel)
            .withDeadlineAfter(builder.timeoutSeconds, TimeUnit.SECONDS);
        this.resource = buildResource(builder.config);
        this.logger   = builder.logger;
    }

    // ── Exporter ──────────────────────────────────────────────────────────────

    @Override
    public void export(Snapshot snapshot) {
        try {
            Profile profile = JfrToOtlpConverter.convert(snapshot);

            ExportProfilesServiceRequest request = ExportProfilesServiceRequest.newBuilder()
                .addResourceProfiles(
                    ResourceProfiles.newBuilder()
                        .setResource(resource)
                        .addScopeProfiles(
                            ScopeProfiles.newBuilder()
                                .addProfiles(profile)
                        )
                )
                .build();

            stub.export(request);

            logger.log(Logger.Level.DEBUG,
                "Exported profile [%s] %s → %s via OTLP gRPC (%d samples)",
                snapshot.eventType.id,
                snapshot.started,
                snapshot.ended,
                profile.getSampleCount());

        } catch (StatusRuntimeException e) {
            // Log but do not throw — profiling must not disrupt the application.
            Status status = e.getStatus();
            logger.log(Logger.Level.ERROR,
                "OTLP gRPC export failed: %s %s", status.getCode(), status.getDescription());
        } catch (Exception e) {
            logger.log(Logger.Level.ERROR, "OTLP gRPC export error: %s", e.getMessage());
        }
    }

    @Override
    public void stop() {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }

    // ── Resource builder ──────────────────────────────────────────────────────

    /**
     * Builds an OTel Resource from {@link Config}.
     * Static labels ({@code PYROSCOPE_LABELS}) become resource attributes so they
     * appear as dimensions in Grafana Pyroscope / any OTel-compatible backend.
     */
    private static Resource buildResource(Config config) {
        Resource.Builder resource = Resource.newBuilder()
            .addAttributes(kv("service.name", config.applicationName))
            .addAttributes(kv("telemetry.sdk.name",     "pyroscope-java"))
            .addAttributes(kv("telemetry.sdk.language", "java"))
            .addAttributes(kv("profiler.type",          config.profilerType.name()))
            .addAttributes(kv("profiler.event",         config.profilingEvent.id));

        // Static labels from PYROSCOPE_LABELS env / pyroscope.properties
        for (Map.Entry<String, String> entry : config.labels.entrySet()) {
            resource.addAttributes(kv(entry.getKey(), entry.getValue()));
        }

        return resource.build();
    }

    private static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
            .setKey(key)
            .setValue(AnyValue.newBuilder().setStringValue(value))
            .build();
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String  endpoint       = "localhost:4317";
        private boolean usePlaintext   = false;
        private int     timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        private Config  config;
        private Logger  logger;

        private Builder() {}

        /** OTel Collector gRPC address, e.g. {@code "otel-collector:4317"}. */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /** Disable TLS — use for local / in-cluster plaintext connections. */
        public Builder usePlaintext() {
            this.usePlaintext = true;
            return this;
        }

        /** Per-export gRPC deadline. Defaults to 10 s. */
        public Builder timeout(int seconds) {
            this.timeoutSeconds = seconds;
            return this;
        }

        /**
         * Required — used to populate Resource attributes (app name, static labels,
         * profiler type, etc.).
         */
        public Builder config(Config config) {
            this.config = config;
            return this;
        }

        public Builder logger(Logger logger) {
            this.logger = logger;
            return this;
        }

        public OtlpGrpcProfileExporter build() {
            if (config == null) {
                throw new IllegalStateException("config() is required");
            }
            if (logger == null) {
                logger = new io.pyroscope.javaagent.impl.DefaultLogger(
                    Logger.Level.INFO, System.err);
            }
            return new OtlpGrpcProfileExporter(this);
        }
    }
}