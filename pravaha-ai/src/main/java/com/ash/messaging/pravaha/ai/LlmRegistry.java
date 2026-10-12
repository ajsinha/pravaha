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
package com.ash.messaging.pravaha.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The providers and models by logical name, and how each is doing. {@link #reload()} reads the source
 * again, builds the new providers through their {@link ProviderFactory} and swaps them in one step; calls
 * in flight finish on the provider they started on. A file that fails is listed with its reason and the
 * rest are unaffected; a provider that could not be built is built again on the {@link RetryBoard}'s
 * schedule, not at every look.
 */
public final class LlmRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LlmRegistry.class);

    /** Provider health as the registry knows it: from configuration and from the calls it has seen (no probe is sent). */
    public enum Status {
        OFF,
        ERROR,
        UNKNOWN,
        UP,
        DOWN
    }

    /**
     * A provider's health.
     *
     * @param status what it is
     * @param detail why
     * @param lastOk when a call last succeeded
     * @param lastFailure when a call last failed
     * @param calls calls seen
     * @param failures failed calls seen
     */
    public record Health(
            Status status,
            String detail,
            @Nullable Instant lastOk,
            @Nullable Instant lastFailure,
            long calls,
            long failures) {}

    /**
     * A provider in the registry.
     *
     * @param config its file
     * @param provider the built provider, or null when it is off or could not be built
     * @param error why it could not be built, or null
     * @param state its call counters
     */
    public record Entry(
            ProviderConfig config,
            @Nullable LlmProvider provider,
            @Nullable String error,
            State state) {

        /** Whether calls may use it. */
        public boolean usable() {
            return provider != null && config.enabled();
        }

        /** The provider, which {@link #usable} promises. */
        public LlmProvider built() {
            LlmProvider p = provider;
            if (p == null) {
                throw new LlmException(LlmException.Kind.CONFIG, "provider " + config.name() + " is not built");
            }
            return p;
        }
    }

    /** Counters the registry keeps per provider. */
    public static final class State {
        final AtomicLong calls = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        volatile @Nullable Instant lastOk;
        volatile @Nullable Instant lastFailure;
        volatile String lastError = "";
        volatile boolean lastWasFailure;
    }

    /**
     * A chat model picked by name.
     *
     * @param entry its provider
     * @param model the model
     */
    public record Resolved(Entry entry, LlmModel model) {

        /** The model as configured. */
        public ModelInfo info() {
            return model.info();
        }
    }

    private record Snapshot(Map<String, Entry> entries, Map<String, String> fileErrors, Instant loadedAt) {}

    private final Supplier<ProviderConfigLoader.Loaded> source;
    private final Map<String, ProviderFactory> factories = new LinkedHashMap<>();
    private final String defaultModel;
    private volatile Snapshot snap = new Snapshot(Map.of(), Map.of(), Instant.EPOCH);
    private final RetryBoard retry;
    private final ReentrantLock reloadLock = new ReentrantLock();
    private volatile String lastSummary = "";

    public LlmRegistry(
            List<ProviderFactory> factories,
            Supplier<ProviderConfigLoader.Loaded> source,
            @Nullable String defaultModel) {
        this(factories, source, defaultModel, RetryBoard.defaults());
    }

    public LlmRegistry(
            List<ProviderFactory> factories,
            Supplier<ProviderConfigLoader.Loaded> source,
            @Nullable String defaultModel,
            RetryBoard retry) {
        this.retry = retry;
        for (ProviderFactory f : factories) {
            this.factories.putIfAbsent(f.type().toLowerCase(Locale.ROOT), f);
        }
        this.source = source;
        this.defaultModel = defaultModel == null ? "" : defaultModel.strip();
    }

    /** Reads the source again and swaps in what it now says. */
    public void reload() {
        reloadLock.lock();
        try {
            reloadLocked();
        } finally {
            reloadLock.unlock();
        }
    }

    /** Retry state of the providers that could not be built. */
    public RetryBoard retry() {
        return retry;
    }

    /** "Retry now": every provider that failed to build is built again at once. Returns how many were failing. */
    public int retryNow() {
        int n = retry.retryAllNow();
        reload();
        return n;
    }

    private void reloadLocked() {
        ProviderConfigLoader.Loaded loaded = source.get();
        Set<String> names = new HashSet<>();
        Map<String, Entry> entries = new TreeMap<>();
        Map<String, String> errors = new TreeMap<>(loaded.errors());
        Snapshot old = snap;
        List<String> due = retry.due();
        for (ProviderConfig c : loaded.providers()) {
            names.add(c.name());
            Entry prev = old.entries().get(c.name());
            State state = prev != null ? prev.state() : new State();
            if (!c.enabled()) {
                retry.forget(c.name());
                entries.put(c.name(), new Entry(c, null, null, state));
                continue;
            }
            if (prev != null
                    && prev.error() != null
                    && prev.config().equals(c)
                    && retry.failing(c.name())
                    && !due.contains(c.name())) {
                entries.put(c.name(), prev); // refused before, same file, not yet time to try again
                continue;
            }
            if (prev != null && !prev.config().equals(c)) {
                retry.forget(c.name()); // the file changed: a fresh start
            }
            if (prev != null && prev.provider() != null && prev.config().equals(c)) {
                entries.put(c.name(), prev); // the same file: keep the provider (and its connections)
                continue;
            }
            ProviderFactory f = factories.get(c.type().toLowerCase(Locale.ROOT));
            if (f == null) {
                String why = "no provider type '" + c.type() + "' is installed (have: " + factories.keySet() + ")";
                retry.failed(c.name(), why, true);
                entries.put(c.name(), new Entry(c, null, why, state));
                continue;
            }
            try {
                entries.put(c.name(), new Entry(c, f.create(c), null, state));
                retry.succeeded(c.name());
            } catch (LlmException e) {
                retry.failed(c.name(), e.detail(), e.kind() == LlmException.Kind.CONFIG);
                entries.put(c.name(), new Entry(c, null, e.detail(), state));
            } catch (RuntimeException e) {
                String why = "the provider could not be built (" + e.getClass().getSimpleName() + ")";
                retry.failed(c.name(), why, false);
                entries.put(c.name(), new Entry(c, null, why, state));
            }
        }
        retry.statuses().keySet().stream().filter(k -> !names.contains(k)).forEach(retry::forget);
        snap = new Snapshot(Map.copyOf(entries), Map.copyOf(errors), Instant.now());
        for (Entry e : old.entries().values()) {
            LlmProvider p = e.provider();
            if (p != null && !e.equals(entries.get(e.config().name()))) {
                p.close();
            }
        }
        String summary = entries.size() + " provider(s), "
                + entries.values().stream().filter(Entry::usable).count() + " usable, " + errors.size()
                + " file error(s)";
        if (!summary.equals(lastSummary)) { // the folder is looked at every few seconds: say it when it changed
            LOG.info("ai registry: {}", summary);
            lastSummary = summary;
        }
    }

    /** The provider types installed. */
    public List<String> types() {
        return List.copyOf(factories.keySet());
    }

    /** Every provider, by name. */
    public List<Entry> entries() {
        return snap.entries().values().stream()
                .sorted(Comparator.comparing(e -> e.config().name()))
                .toList();
    }

    /** Files that could not be read, and why. */
    public Map<String, String> fileErrors() {
        return snap.fileErrors();
    }

    /** When the source was last read. */
    public Instant loadedAt() {
        return snap.loadedAt();
    }

    /** A provider by name. */
    public Optional<Entry> provider(String name) {
        return Optional.ofNullable(snap.entries().get(name));
    }

    /** Every model of every usable provider. */
    public List<ModelInfo> models() {
        List<ModelInfo> out = new ArrayList<>();
        for (Entry e : entries()) {
            if (e.usable()) {
                out.addAll(e.built().models());
            }
        }
        return out;
    }

    /**
     * The chat model for a logical reference: {@code provider/model}, a model name that exactly one provider
     * has, or (null/blank) the registry default, then the only chat model there is.
     */
    public Resolved chat(@Nullable String ref) {
        String r = ref == null || ref.isBlank() ? defaultModel : ref.strip();
        List<Resolved> hits = new ArrayList<>();
        if (r.isEmpty()) {
            for (Entry e : entries()) {
                if (e.usable()) {
                    String d = e.config().defaultModel();
                    for (ModelInfo m : e.built().models()) {
                        if (!m.embedding() && (d.isEmpty() || d.equals(m.name()))) {
                            e.built().chatModel(m.name()).ifPresent(x -> hits.add(new Resolved(e, x)));
                        }
                    }
                }
            }
            if (hits.size() == 1) {
                return hits.get(0);
            }
            throw new LlmException(
                    LlmException.Kind.NOT_FOUND,
                    hits.isEmpty()
                            ? "no chat model is configured"
                            : "several chat models are configured; name one (provider/model) or set a default-model");
        }
        int slash = r.indexOf('/');
        for (Entry e : entries()) {
            if (!e.usable() || (slash >= 0 && !e.config().name().equals(r.substring(0, slash)))) {
                continue;
            }
            String model = slash >= 0 ? r.substring(slash + 1) : r;
            e.built().chatModel(model).ifPresent(x -> hits.add(new Resolved(e, x)));
        }
        if (hits.isEmpty()) {
            boolean embedding = embedding(r).isPresent();
            throw new LlmException(
                    embedding ? LlmException.Kind.UNSUPPORTED : LlmException.Kind.NOT_FOUND,
                    embedding
                            ? "model '" + r + "' is an embedding model; it cannot chat"
                            : "no chat model '" + r + "' is configured");
        }
        if (hits.size() > 1) {
            throw new LlmException(
                    LlmException.Kind.NOT_FOUND, "model name '" + r + "' is in several providers; use provider/model");
        }
        return hits.get(0);
    }

    /** An embedding model by reference ({@code provider/model} or a model name). */
    public Optional<EmbeddingModel> embedding(@Nullable String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        int slash = ref.indexOf('/');
        for (Entry e : entries()) {
            if (e.usable() && (slash < 0 || e.config().name().equals(ref.substring(0, slash)))) {
                Optional<EmbeddingModel> m = e.built().embeddingModel(slash < 0 ? ref : ref.substring(slash + 1));
                if (m.isPresent()) {
                    return m;
                }
            }
        }
        return Optional.empty();
    }

    /** Called by the gateway after each call so health reflects use. */
    public void record(Entry entry, boolean ok, @Nullable String error) {
        State s = entry.state();
        s.calls.incrementAndGet();
        if (ok) {
            s.lastOk = Instant.now();
            s.lastWasFailure = false;
        } else {
            s.failures.incrementAndGet();
            s.lastFailure = Instant.now();
            s.lastError = error == null ? "" : error;
            s.lastWasFailure = true;
        }
    }

    /** A provider's health, from its file and the calls seen: never a network call. */
    public Health health(Entry e) {
        State s = e.state();
        if (!e.config().enabled()) {
            return new Health(Status.OFF, "enabled: false in the provider file", null, null, 0, 0);
        }
        String error = e.error();
        if (error != null) {
            return new Health(Status.ERROR, error, null, null, 0, 0);
        }
        Status st = s.calls.get() == 0 ? Status.UNKNOWN : s.lastWasFailure ? Status.DOWN : Status.UP;
        String detail =
                switch (st) {
                    case UNKNOWN -> "configured; no call yet";
                    case DOWN -> s.lastError;
                    default -> "last call succeeded";
                };
        return new Health(st, detail, s.lastOk, s.lastFailure, s.calls.get(), s.failures.get());
    }

    @Override
    public void close() {
        Snapshot last;
        reloadLock.lock();
        try {
            last = snap;
            snap = new Snapshot(Map.of(), Map.of(), Instant.EPOCH);
        } finally {
            reloadLock.unlock();
        }
        for (Entry e : last.entries().values()) {
            LlmProvider p = e.provider();
            if (p != null) {
                p.close();
            }
        }
    }
}
