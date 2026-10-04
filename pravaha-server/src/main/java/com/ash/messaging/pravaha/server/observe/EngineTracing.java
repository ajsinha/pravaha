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
package com.ash.messaging.pravaha.server.observe;

import java.util.Map;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.common.observe.EngineSpans;

/**
 * Gives the engine's spans -- a registration, a replacement, a checkpoint, an alert's notification --
 * to Micrometer Tracing, when the node traces.
 *
 * <p>Installed only when a real tracer exists: with {@code pravaha.tracing.enabled} off the tracing
 * auto-configuration is excluded ({@link ObservabilityEnvironment}) and the tracer is Micrometer's
 * no-op, so the engine's facade stays at {@link EngineSpans#NONE} and costs one volatile read a call.
 */
@Component
public class EngineTracing implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(EngineTracing.class);

    private final EngineSpans.@Nullable Backend backend;

    @SuppressWarnings("ReferenceEquality") // identity is the question here: a sentinel, a thread or the very object
    public EngineTracing(ObjectProvider<Tracer> tracers) {
        Tracer tracer = tracers.getIfAvailable();
        if (tracer == null || tracer == Tracer.NOOP) {
            backend = null;
            return;
        }
        backend = (name, attributes) -> start(tracer, name, attributes);
        EngineSpans.install(backend);
        log.info("tracing: engine spans (registration, replacement, checkpoint, alert notification) are traced");
    }

    /** Whether the engine's spans are going to a tracer. */
    public boolean installed() {
        return backend != null;
    }

    private static EngineSpans.Span start(Tracer tracer, String name, Map<String, String> attributes) {
        Span span = tracer.nextSpan().name(name);
        attributes.forEach(span::tag);
        span.start();
        Tracer.SpanInScope scope = tracer.withSpan(span);
        return new EngineSpans.Span() {
            @Override
            public void attribute(String key, String value) {
                if (value != null) {
                    span.tag(key, value);
                }
            }

            @Override
            public void failed(Throwable failure) {
                span.error(failure);
            }

            @Override
            public void close() {
                try {
                    scope.close();
                } finally {
                    span.end();
                }
            }
        };
    }

    @Override
    public void destroy() {
        if (backend != null) {
            EngineSpans.uninstall(backend);
        }
    }
}
