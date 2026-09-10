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
package com.ash.messaging.pravaha.codegen;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps the generated source of running queries, so "what did it actually run?" has an answer.
 *
 * <p>When a generated plan produces a wrong answer, the source is the first thing anybody needs, and
 * regenerating it later is not the same artefact -- the plan may have been replanned since, the
 * catalog may have changed, and the bug may not reproduce. A few kilobytes of text per query is a
 * cheap price for being able to answer the question at all (design section 12.4).
 *
 * <p><strong>Bounded and off by default.</strong> Retention is a debug facility, and a debug
 * facility that grows without limit becomes the incident. The registry holds at most
 * {@code maxEntries} sources and evicts the oldest, so at ten thousand concurrent queries (NFR-2d)
 * an operator who turns retention on gets the most recent few hundred rather than a heap dump.
 * Generated source can also contain literals from the query, which are data -- another reason it is
 * opt-in rather than always on.
 *
 * <p>Not thread-confined: registration happens on whichever thread compiles, and reads come from the
 * API. Small and off the hot path, so a plain lock is right and a concurrent map would be
 * false economy.
 */
public final class GeneratedSourceRegistry {

    /** What is kept about one generated stage. */
    public record Entry(String queryId, String className, String source, long compileMillis, int sourceLines) {}

    private final boolean enabled;
    private final int maxEntries;
    private final Map<String, Entry> entries;

    public GeneratedSourceRegistry(boolean enabled, int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("retention limit must be at least 1, got " + maxEntries);
        }
        this.enabled = enabled;
        this.maxEntries = maxEntries;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > GeneratedSourceRegistry.this.maxEntries;
            }
        };
    }

    /** Retention off, which is the default a node starts with. */
    public static GeneratedSourceRegistry disabled() {
        return new GeneratedSourceRegistry(false, 1);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Records a stage's source. A no-op when retention is off, so callers need no flag check. */
    public void retain(String queryId, GeneratedStage stage) {
        if (!enabled) {
            return;
        }
        synchronized (entries) {
            entries.put(
                    queryId,
                    new Entry(queryId, stage.className(), stage.source(), stage.compileMillis(), stage.sourceLines()));
        }
    }

    /**
     * The source for a query, if it was retained.
     *
     * <p>Empty means one of three different things -- retention is off, the query is unknown, or its
     * source was evicted -- and the caller has to distinguish them for the operator, because "no
     * source" and "you did not turn retention on" lead to very different next steps.
     */
    public Optional<Entry> find(String queryId) {
        synchronized (entries) {
            return Optional.ofNullable(entries.get(queryId));
        }
    }

    /** Forgets one query's source, which is what dropping a query should do. */
    public void forget(String queryId) {
        synchronized (entries) {
            entries.remove(queryId);
        }
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    @Override
    public String toString() {
        return "GeneratedSourceRegistry[" + (enabled ? size() + "/" + maxEntries : "disabled") + "]";
    }
}
