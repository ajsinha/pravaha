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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;
import com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore;

/**
 * Where a registry's computations checkpoint, and how one is restored.
 *
 * <p>Split out of {@link QueryRegistry}, which had the directory naming, the restore, the
 * checkpointer's wiring and the deletion inline. They are one subject and the registry was the only
 * thing holding them together.
 *
 * <p>A directory per computation. Sharing one between queries would make pruning global -- the
 * newest three checkpoints across a node rather than the newest three of each query -- so a busy
 * query would evict a quiet one's only fallback.
 */
final class QueryCheckpoints {

    /** A registry that checkpoints nothing, which is the default and correct for an embedder. */
    static final QueryCheckpoints NONE = new QueryCheckpoints(null, null);

    private final Path root;
    private final Configuration configuration;

    QueryCheckpoints(Path root, Configuration configuration) {
        this.root = root;
        this.configuration = configuration == null ? Configuration.builder().build() : configuration;
    }

    boolean enabled() {
        return root != null;
    }

    /** Where {@code directory} is under the root, or null when nothing is checkpointed. */
    Path pathOf(String directory) {
        return root == null ? null : root.resolve(directory);
    }

    /** What a journal records for {@code directory}: its path under the checkpoint root (SHAREDLOSS-1). */
    String nameOf(Path directory) {
        return root == null ? directory.toString() : root.relativize(directory).toString();
    }

    /**
     * The directory a new computation named {@code name} checkpoints into: the one its name implies,
     * unless a running computation already checkpoints there -- another tenant's view registered before
     * names were per tenant keeps the directory its bare name implied (ADR-060) -- and then one beside it.
     */
    String freeDirectoryFor(String name, java.util.Collection<RegisteredQuery> running) {
        String wanted = directoryFor(name);
        java.util.Set<Path> taken = new java.util.HashSet<>();
        running.forEach(query -> query.checkpointDirectory().ifPresent(taken::add));
        String directory = wanted;
        for (int attempt = 1; enabled() && taken.contains(pathOf(directory)); attempt++) {
            directory = wanted + "-" + attempt;
        }
        return directory;
    }

    /**
     * Journals the drop of {@code name} from {@code query} (SHAREDLOSS-1). A shared computation
     * checkpoints into the directory of the name that started it, and each other name's record implies
     * a directory of its own; dropping the starting name used to leave the survivors pointing at their
     * own, empty, directories, so a restart brought them back with none of the computation's state. So
     * the drop re-homes every survivor whose name implies another directory, in the same append.
     */
    void journalDrop(RegistryJournal journal, String name, RegisteredQuery query) {
        java.util.Set<String> survivors = new java.util.TreeSet<>(query.names());
        survivors.remove(name);
        Path directory = query.checkpointDirectory().orElse(null);
        java.util.List<String> rehomed = new java.util.ArrayList<>();
        for (String survivor : survivors) {
            if (directory != null && !directory.equals(pathOf(directoryFor(survivor)))) {
                rehomed.add(survivor);
            }
        }
        if (rehomed.isEmpty()) {
            journal.recordDrop(name);
        } else {
            journal.recordDrop(name, rehomed, nameOf(directory));
        }
    }

    /**
     * Restores the newest readable checkpoint, returning the offsets its sources should resume from.
     *
     * <p>The newest checkpoint is the likeliest to be unreadable, because it is the one that was
     * being written when the process died. Falling back to the previous one costs reprocessing;
     * failing the registration costs the query.
     */
    Map<String, String> restore(String directory, QueryExecution execution, RegisteredQuery query) {
        if (root == null) {
            return Map.of();
        }
        FileCheckpointStore store = new FileCheckpointStore(root.resolve(directory));
        Optional<Checkpoint> latest = Optional.empty();
        try {
            latest = store.latest();
            if (latest.isEmpty()) {
                return Map.of();
            }
            // RETYPERESTORE-1: a checkpoint of another output schema is rebuilt from, not restored.
            CheckpointSchemas.requireSame(latest.get(), query.outputSchema(), query.anyName());
            execution.restore(latest.get(), RESTORE_TIMEOUT);
            try {
                // What it recorded about sinks, for each registration to claim as it attaches: the
                // handles to commit, and the view each sink holds once they are.
                query.restoredFrom(latest.get());
            } catch (RuntimeException sinksRefused) {
                // The execution's half came back and this half did not: put the execution's back too,
                // or the replay below would count every row before the checkpoint twice.
                execution.forgetRestoredState(RESTORE_TIMEOUT);
                throw sinksRefused;
            }
            return latest.get().offsets();
        } catch (RuntimeException e) {
            if (execution.holdsPartlyRestoredState()) {
                // RESTOREPART-1. Part of the checkpoint came back and could not be put back. Starting
                // from the sources now would count again every row those parts already hold, so the
                // registration is refused instead: a recovery reports it by name among the refused,
                // rather than serving an answer that is wrong with nothing to say so.
                throw e;
            }
            // A query that starts from nothing is worse than one that starts from an older
            // checkpoint and better than one that does not start. Reprocessing is visible in the
            // numbers; a refusal to register is visible immediately; silent corruption is neither.
            // Starting from nothing is sound only because the restore above is all or nothing: a
            // refusal half-way through leaves every part as empty as it was (RESTOREPART-1). And it
            // is said, not only done: the log names the query and the cause, and the query carries
            // it as a checkpoint failure, which the console and the metrics already surface.
            String what = "checkpoint " + latest.map(c -> Long.toString(c.id())).orElse("?")
                    + " of query '" + query.anyName() + "' could not be restored, so the query starts from the "
                    + "beginning of its sources (reprocessing, never a double count): " + e.getMessage();
            LOG.log(System.Logger.Level.WARNING, what);
            query.recordCheckpointFailure(what);
            return Map.of();
        }
    }

    private static final Duration RESTORE_TIMEOUT = Duration.ofSeconds(30);

    private static final System.Logger LOG = System.getLogger(QueryCheckpoints.class.getName());

    /** Starts checkpointing {@code query} into its directory, and records both on it. */
    void start(String directory, QueryExecution execution, RegisteredQuery query) {
        if (root == null) {
            return;
        }
        Path checkpointDirectory = root.resolve(directory);
        PeriodicCheckpointer checkpointer = PeriodicCheckpointer.from(
                        execution,
                        new FileCheckpointStore(checkpointDirectory),
                        configuration,
                        // The narrative channel: start-up line, a line per success, a line per
                        // failure. Not the failure counter -- wiring the counter here counted all
                        // three, so a query whose checkpoints were all succeeding reported a rising
                        // failure count and named a success as its last failure.
                        message -> {})
                // Failures only. PeriodicCheckpointer reports every one rather than the first,
                // precisely so that a query which has silently not checkpointed for six hours does
                // not look like one that has -- and the registry used to throw each report away.
                .reportingFailuresTo(query::recordCheckpointFailure)
                // The second phase: what each transactional sink prepared at the cut is committed
                // once, and only once, the checkpoint recording it is durable.
                .tellingWhenDurable(query::checkpointDurable);
        checkpointer.start();
        query.checkpointWith(checkpointer, checkpointDirectory);
    }

    /**
     * Deletes a checkpoint directory a released computation was writing to.
     *
     * <p>Takes the path the checkpointer was given rather than a name to re-derive it from. The
     * previous version took a name and was called after the last name had already been removed, so
     * it resolved a fingerprint digest that had never been a directory and deleted nothing.
     */
    void delete(Path directory) {
        try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                Files.deleteIfExists(entry);
            }
            Files.deleteIfExists(directory);
        } catch (java.io.IOException e) {
            // A drop must succeed even if the disk will not co-operate. Leftover files cost space;
            // a drop that fails half way costs a query nobody can remove.
        }
    }

    /**
     * The directory a query's checkpoints live in.
     *
     * <p>Percent-style hex encoding, which is injective: two different names cannot produce one
     * directory. Replacing unsafe characters with '_' is many-to-one, and a hash suffix does not
     * rescue it -- a collision was constructed from first principles on the first attempt, and
     * {@code a#!b} and {@code a"@b} still shared a directory. Two queries in one directory share a
     * checkpoint id sequence and prune each other's fallbacks away, which is the exact failure the
     * per-query directory exists to prevent. Dots are encoded too, so a query named {@code ..}
     * cannot write above the configured root, and so is '_', or {@code a_b} and {@code a b} would
     * still meet.
     */
    static String directoryFor(String name) {
        StringBuilder encoded = new StringBuilder(name.length() + 8);
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') {
                encoded.append(c);
            } else {
                encoded.append('_').append(String.format("%02x", b & 0xFF));
            }
        }
        return encoded.toString();
    }

    /**
     * A directory for a replacement's shadow, distinct from the name's own and from any other.
     *
     * <p>The shadow keeps its own checkpoints while it backfills, and keeps them after it takes the
     * name: moving files at the cutover would leave a window in which a crash has the name pointing
     * at a directory holding the <em>other</em> version's state. So the directory travels with the
     * registration instead -- it is written into the journal's cutover record -- and nothing is
     * moved at all.
     */
    static String shadowDirectoryFor(String name, long startedAtMillis) {
        return directoryFor(name) + "-shadow-" + Long.toHexString(startedAtMillis);
    }
}
