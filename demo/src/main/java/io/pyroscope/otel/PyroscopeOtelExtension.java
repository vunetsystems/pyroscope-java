package io.pyroscope.otel;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

/**
 * PyroscopeOtelExtension registers {@link PyroscopeSpanProcessor} with the
 * OpenTelemetry SDK autoconfigure mechanism so that it is active for every span
 * without any application-code changes.
 *
 * <p>Loaded via Java SPI — see
 * {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider}.
 *
 * <h3>Usage</h3>
 * Build the demo shadow jar and pass it as an OTel agent extension:
 * <pre>{@code
 * java \
 *   -javaagent:opentelemetry-javaagent.jar \
 *   -Dotel.javaagent.extensions=demo-with-exporters.jar \
 *   -DPYROSCOPE_APPLICATION_NAME=myapp \
 *   -DPYROSCOPE_SERVER_ADDRESS=http://localhost:4040 \
 *   -jar myapp.jar
 * }</pre>
 */
public final class PyroscopeOtelExtension implements AutoConfigurationCustomizerProvider {

    @Override
    public void customize(AutoConfigurationCustomizer customizer) {
        customizer.addTracerProviderCustomizer((tracerProviderBuilder, config) -> {
            try {
                tracerProviderBuilder.addSpanProcessor(new PyroscopeSpanProcessor());
            } catch (Throwable t) {
                // Never break OTel TracerProvider setup — Pyroscope is best-effort.
                java.util.logging.Logger.getLogger(getClass().getName())
                        .warning("PyroscopeOtelExtension: failed to register SpanProcessor: " + t);
            }
            return tracerProviderBuilder;
        });
    }
}