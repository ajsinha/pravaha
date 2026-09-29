/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.messaging.pravaha.common.observe;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Where the engine says "this unit of work started, and ended" -- a registration, a replacement, a
 * checkpoint, an alert's notification -- for a tracer to turn into spans.
 *
 * <p><strong>A facade, not a tracer.</strong> The engine has no tracing library on its classpath and
 * must not gain one: an embedded engine is a library inside somebody else's process, which has its own
 * tracer, or none. The server installs a {@link Backend} over Micrometer Tracing when {@code
 * pravaha.tracing.enabled} is set; until something is installed every call here is a no-op costing one
 * volatile read.
 *
 * <p><strong>Process-wide</strong>, as OpenTelemetry's own global is: a span belongs to the thread that
 * does the work, and threading a tracer through every constructor from the registry down to a
 * checkpointer would change a dozen signatures to carry something most deployments leave off. Two
 * nodes in one JVM -- a test -- share it, which is what their spans would do under any global tracer.
 *
 * <p>Attributes are for spans only. None of them may become a metric label: a query's name here is
 * fine on a trace and would be a cardinality bill on a meter.
 */
public final class EngineSpans {

    /** One unit of work being traced. Closed exactly once, on the thread that started it. */
    public interface Span extends AutoCloseable {

        /** Adds an attribute to the span. */
        void attribute(String key, String value);

        /** Marks the span as failed by {@code failure}; it is still closed by {@link #close()}. */
        void failed(Throwable failure);

        /** Ends the span and leaves its scope. Never throws. */
        @Override
        void close();
    }

    /** What turns the engine's spans into a tracer's. */
    @FunctionalInterface
    public interface Backend {

        /**
         * Starts a span named {@code name}, child of whatever span is current on this thread, and makes
         * it current until it is closed.
         */
        Span start(String name, Map<String, String> attributes);
    }

    /** The span of a backend that traces nothing. */
    public static final Span NO_SPAN = new Span() {
        @Override
        public void attribute(String key, String value) {}

        @Override
        public void failed(Throwable failure) {}

        @Override
        public void close() {}
    };

    /** Traces nothing: what is installed until a server installs something else. */
    public static final Backend NONE = (name, attributes) -> NO_SPAN;

    private static volatile Backend installed = NONE;

    private EngineSpans() {}

    /** Installs {@code backend} for the whole process, replacing whatever was installed. */
    public static void install(Backend backend) {
        installed = Objects.requireNonNull(backend, "backend");
    }

    /** Uninstalls {@code backend} if it is the one installed; another's installation is left alone. */
    public static void uninstall(Backend backend) {
        if (installed == backend) {
            installed = NONE;
        }
    }

    /** Whether anything but {@link #NONE} is installed. */
    public static boolean tracing() {
        return installed != NONE;
    }

    /**
     * Starts a span; {@code keyValues} are alternating attribute names and values, and a null value is
     * left out. Use in a try-with-resources.
     */
    public static Span start(String name, String... keyValues) {
        Backend backend = installed;
        if (backend == NONE) {
            return NO_SPAN;
        }
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("span attributes come in name/value pairs, got " + keyValues.length);
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                attributes.put(keyValues[i], keyValues[i + 1]);
            }
        }
        try {
            Span span = backend.start(name, attributes);
            return span == null ? NO_SPAN : span;
        } catch (RuntimeException tracerFailed) {
            // A tracer that fails must not fail the registration or checkpoint it was describing.
            return NO_SPAN;
        }
    }

    /**
     * Runs {@code body} inside a span carrying one attribute, marking the span failed if it throws. The
     * body is last so a caller's block reads as the block it was before it was traced.
     */
    public static <T> T traced(String name, String key, String value, Supplier<T> body) {
        Span span = start(name, key, value);
        try {
            return body.get();
        } catch (RuntimeException | Error failure) {
            span.failed(failure);
            throw failure;
        } finally {
            span.close();
        }
    }

    /** As {@link #traced(String, String, String, Supplier)} for work that answers nothing. */
    public static void run(String name, String key, String value, Runnable body) {
        traced(name, key, value, () -> {
            body.run();
            return null;
        });
    }
}
