package io.pyroscope.otel;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Injects trace_id + span_id into Pyroscope profiling data for every OTel span.
 *
 * Uses TWO mechanisms in parallel so whichever path the Pyroscope server reads works:
 *
 *  1. setTracingContext(spanId, traceId) — writes span/trace IDs as native longs
 *     directly into each JFR sample event (no LabelsSnapshot needed).
 *
 *  2. ScopedContext{trace_id, span_id} — writes a contextId into JFR events;
 *     the LabelsSnapshot sent at dump time maps contextId → {trace_id, span_id}.
 *
 * All Pyroscope classes are loaded via reflection from the system classloader
 * (-javaagent appends pyroscope.jar to the system classpath) so the OTel extension
 * classloader isolation never causes a NoClassDefFoundError.
 */
public final class PyroscopeSpanProcessor implements SpanProcessor {

    private static final String TAG = "[PYROSCOPE-OTEL] ";

    // ── Reflection handles (loaded once) ─────────────────────────────────────
    private static volatile boolean initialized = false;

    // mechanism 1 — native setTracingContext
    private static volatile Object    asyncProfilerInstance;  // one.profiler.AsyncProfiler
    private static volatile Method    setTracingContext;       // setTracingContext(long, long)

    // mechanism 2 — ScopedContext labels
    private static volatile Constructor<?> labelsSetCtor;      // LabelsSet(String[])
    private static volatile Constructor<?> scopedContextCtor;  // ScopedContext(LabelsSet)
    private static volatile Field          enabledField;       // ScopedContext.ENABLED

    // Per-thread stack — stores (ScopedContext | null) as AutoCloseable
    private static final ThreadLocal<Deque<AutoCloseable>> CTX_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    // ── Init ──────────────────────────────────────────────────────────────────

    private static synchronized void init() {
        if (initialized) return;
        initialized = true;

        // -javaagent ALWAYS adds the jar to the system classpath (JVM spec).
        ClassLoader[] cls = {
                ClassLoader.getSystemClassLoader(),
                Thread.currentThread().getContextClassLoader()
        };

        for (ClassLoader cl : cls) {
            if (cl == null) continue;
            try {
                // ── mechanism 1: AsyncProfiler.setTracingContext ────────────
                Class<?> pyroAP = Class.forName(
                        "io.pyroscope.PyroscopeAsyncProfiler", true, cl);
                Object ap = pyroAP.getMethod("getAsyncProfiler").invoke(null);
                Method stc = ap.getClass().getMethod("setTracingContext", long.class, long.class);

                asyncProfilerInstance = ap;
                setTracingContext      = stc;

                // ── mechanism 2: ScopedContext labels ──────────────────────
                Class<?> lsClass  = Class.forName("io.pyroscope.labels.v2.LabelsSet",    true, cl);
                Class<?> scClass  = Class.forName("io.pyroscope.labels.v2.ScopedContext", true, cl);
                labelsSetCtor    = lsClass.getConstructor(String[].class);
                scopedContextCtor = scClass.getConstructor(lsClass);
                enabledField      = scClass.getField("ENABLED");

                System.err.println(TAG + "OK — Pyroscope classes loaded from "
                        + cl.getClass().getSimpleName()
                        + ". Both setTracingContext + ScopedContext active.");
                return;

            } catch (Throwable t) {
                System.err.println(TAG + "classloader " + cl.getClass().getSimpleName()
                        + " failed: " + t);
            }
        }

        System.err.println(TAG + "WARN — could not load Pyroscope classes. "
                + "Is -javaagent:pyroscope.jar present? Profiling labels will be absent.");
    }

    // ── SpanProcessor ─────────────────────────────────────────────────────────

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        try {
            if (!initialized) init();

            SpanContext sc = span.getSpanContext();
            if (!sc.isValid()) return;

            String traceId = sc.getTraceId();  // 32 hex chars
            String spanId  = sc.getSpanId();   // 16 hex chars

            // ── mechanism 1: native setTracingContext ─────────────────────
            if (setTracingContext != null && asyncProfilerInstance != null) {
                try {
                    // traceId: take lower 16 hex chars (8 bytes) as unsigned long
                    long traceIdLow = Long.parseUnsignedLong(traceId.substring(16), 16);
                    long spanIdLong = Long.parseUnsignedLong(spanId, 16);
                    setTracingContext.invoke(asyncProfilerInstance, spanIdLong, traceIdLow);
                } catch (Throwable t) {
                    System.err.println(TAG + "setTracingContext failed: " + t);
                }
            }

            // ── mechanism 2: ScopedContext labels ─────────────────────────
            if (scopedContextCtor != null && enabledField != null) {
                try {
                    AtomicBoolean enabled = (AtomicBoolean) enabledField.get(null);
                    if (enabled.get()) {
                        Object labelsSet = labelsSetCtor.newInstance(
                                (Object) new String[]{
                                        "trace_id", traceId,
                                        "span_id",  spanId
                                });
                        AutoCloseable ctx =
                                (AutoCloseable) scopedContextCtor.newInstance(labelsSet);
                        CTX_STACK.get().push(ctx);
                    }
                } catch (Throwable t) {
                    System.err.println(TAG + "ScopedContext create failed: " + t);
                }
            }

        } catch (Throwable t) {
            // Never propagate — must not break the OTel span pipeline.
            System.err.println(TAG + "onStart unexpected error: " + t);
        }
    }

    @Override
    public void onEnd(ReadableSpan span) {
        try {
            // ── clear mechanism 1 ─────────────────────────────────────────
            if (setTracingContext != null && asyncProfilerInstance != null) {
                try {
                    setTracingContext.invoke(asyncProfilerInstance, 0L, 0L);
                } catch (Throwable ignored) {}
            }

            // ── close mechanism 2 ─────────────────────────────────────────
            Deque<AutoCloseable> stack = CTX_STACK.get();
            AutoCloseable ctx = stack.poll();
            if (ctx != null) ctx.close();
            if (stack.isEmpty()) CTX_STACK.remove();

        } catch (Throwable t) {
            System.err.println(TAG + "onEnd unexpected error: " + t);
        }
    }

    @Override public boolean isStartRequired() { return true;  }
    @Override public boolean isEndRequired()   { return true;  }
}