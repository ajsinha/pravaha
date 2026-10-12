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
package com.ash.messaging.pravaha.server.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ash.messaging.pravaha.ai.LlmRegistry;
import com.ash.messaging.pravaha.ai.ProviderConfigLoader;

/**
 * Hot reload of {@code pravaha.ai.dir}: every interval, the provider files' names, sizes and modification
 * times are compared with the last look, and the registry is reloaded when they differ or when a provider
 * that could not be built is due to be tried again. No restart, and a call in flight finishes on the
 * provider it started on.
 */
public final class AiFolderWatch implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AiFolderWatch.class);

    private final Path dir;
    private final LlmRegistry registry;
    private @Nullable ScheduledExecutorService executor;
    private java.util.concurrent.@Nullable ScheduledFuture<?> task;
    private List<String> fingerprint;

    public AiFolderWatch(Path dir, LlmRegistry registry) {
        this.dir = dir;
        this.registry = registry;
        this.fingerprint = fingerprint(dir);
    }

    synchronized void start(Duration every) {
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pravaha-ai-watch");
            t.setDaemon(true);
            return t;
        });
        long ms = every.toMillis();
        task = s.scheduleWithFixedDelay(this::safeTick, ms, ms, TimeUnit.MILLISECONDS);
        executor = s;
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            LOG.warn("ai folder watch: one look failed; it looks again", e);
        }
    }

    /** One look; whether the registry was reloaded. */
    public synchronized boolean tick() {
        List<String> now = fingerprint(dir);
        boolean due = !registry.retry().due().isEmpty();
        if (now.equals(fingerprint) && !due) {
            return false;
        }
        fingerprint = now;
        registry.reload();
        return true;
    }

    static List<String> fingerprint(Path dir) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p :
                    s.filter(ProviderConfigLoader::isProviderFile).sorted().toList()) {
                out.add(p.getFileName() + ":" + Files.size(p) + ":"
                        + Files.getLastModifiedTime(p).toMillis());
            }
        } catch (IOException e) {
            out.add("unreadable:" + e.getClass().getSimpleName());
        }
        return out;
    }

    @Override
    public synchronized void close() {
        ScheduledExecutorService s = executor;
        java.util.concurrent.ScheduledFuture<?> t = task;
        if (t != null) {
            t.cancel(true);
            task = null;
        }
        if (s != null) {
            s.shutdownNow();
            executor = null;
        }
    }
}
