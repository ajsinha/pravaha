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
package com.ash.messaging.pravaha.registry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * The journalled registrations a recovery refused, kept visible until they are dropped or registered
 * again (RECOVERYHEALTH-1).
 *
 * <p>A registration refused at restart -- a CDC slot overtaken ({@code PRV-5115}), a binlog purged
 * ({@code PRV-5155}), an owner who lost the right ({@code PRV-8007}) -- used to vanish from every
 * listing with one WARN line while the node reported {@code UP}. The journal keeps the entry, so it is
 * tried again at the next start; these are the same entries, held so the listings can show each as
 * {@code FAILED} with its code, the node's health can say {@code DEGRADED}, and a {@code DROP} of the
 * name can remove it from the journal for good.
 */
public final class RecoveryRefusals {

    /**
     * One refused registration, as the listings show it.
     *
     * @param name the engine name the journal recorded
     * @param code the refusal's code, empty when the failure carried none
     * @param reason what the failure said
     * @param sql the registration's SQL, from the journal; empty when the entry was a replacement
     * @param owner the id the journal recorded as its owner; empty when it recorded none
     */
    public record Refused(String name, String code, String reason, String sql, String owner) {}

    private final Map<String, Refused> refused = new LinkedHashMap<>();

    /** Each refusal's checkpoint directory, as the journal recorded it, when it had one. */
    private final Map<String, String> directories = new LinkedHashMap<>();

    /** What the last recovery refused, replacing anything recorded before. */
    synchronized void record(
            List<QueryRegistry.Recovery.Refusal> refusals, Map<String, RegistryJournal.Entry> entries) {
        refused.clear();
        directories.clear();
        for (QueryRegistry.Recovery.Refusal refusal : refusals) {
            RegistryJournal.Entry entry = entries.get(refusal.query());
            refused.put(
                    refusal.query(),
                    new Refused(
                            refusal.query(),
                            refusal.code().map(code -> code.code()).orElse(""),
                            refusal.reason(),
                            entry == null ? "" : entry.sql(),
                            entry == null || entry.owner() == null ? "" : entry.owner()));
            if (entry != null) {
                directories.put(refusal.query(), entry.directory().orElse(QueryCheckpoints.directoryFor(entry.name())));
            }
        }
    }

    /** Every registration the last recovery refused that is still neither dropped nor registered again. */
    public synchronized List<Refused> all() {
        return List.copyOf(new ArrayList<>(refused.values()));
    }

    /** The refusal recorded under {@code name}, by engine name. */
    public synchronized Optional<Refused> find(String name) {
        return Optional.ofNullable(refused.get(name));
    }

    /** The name was registered again: it is a running query now, not a refused one. */
    synchronized void forget(String name) {
        refused.remove(name);
        directories.remove(name);
    }

    /**
     * Drops a refused registration: journalled as dropped, so the next start does not try it again,
     * and its checkpoints deleted -- unless a running computation keeps its state in the same
     * directory -- so a later registration under the name starts afresh rather than restoring the
     * position that was refused.
     *
     * @return whether {@code name} was a refused registration; false leaves the drop to the registry
     */
    synchronized boolean drop(
            String name,
            @Nullable RegistryJournal journal,
            QueryCheckpoints checkpoints,
            List<RegisteredQuery> running) {
        if (!refused.containsKey(name)) {
            return false;
        }
        if (journal != null) {
            journal.recordDrop(name);
        }
        String directory = directories.get(name);
        if (directory != null && checkpoints.enabled()) {
            Path path = checkpoints.pathOf(directory);
            boolean shared = running.stream()
                    .map(RegisteredQuery::checkpointDirectory)
                    .flatMap(Optional::stream)
                    .anyMatch(java.util.Objects.requireNonNull(path, "checkpoints are enabled")::equals);
            if (!shared) {
                checkpoints.delete(path);
            }
        }
        forget(name);
        return true;
    }
}
