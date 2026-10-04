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
package com.ash.messaging.pravaha.server.state;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.common.config.Configuration;

/**
 * What survives a restart: the registry journal, and query state.
 *
 * <p>Two different durability questions that are easy to confuse, so they are named together here.
 * The <strong>journal</strong> remembers which queries exist; replaying it re-registers them, and
 * re-authorizes each one against the policy as it is now. <strong>Checkpoints</strong> remember what
 * those queries had accumulated. A node with a journal and no checkpoint directory comes back
 * knowing every question and none of the answers -- which is what this server did, because nothing
 * ever constructed a checkpointer.
 *
 * <p>A properties object rather than more {@code @Value} parameters. The node's constructor reached
 * fifteen arguments and every new setting broke five test call sites, which is the point at which
 * grouping stops being tidiness.
 */
@Component
@ConfigurationProperties(prefix = "pravaha")
public class PersistenceProperties {

    private final Registry registry = new Registry();
    private final Checkpoint checkpoint = new Checkpoint();
    private final Dlq dlq = new Dlq();
    private final Debug debug = new Debug();

    public Debug getDebug() {
        return debug;
    }

    public Dlq getDlq() {
        return dlq;
    }

    /**
     * What bounds every dead-letter file this node writes (B5).
     *
     * <p>Read even when no directory is set, so that switching the directory on later gets the
     * bound the operator configured rather than the default.
     */
    public com.ash.messaging.pravaha.runtime.dlq.DeadLetterRetention dlqRetention() {
        return new com.ash.messaging.pravaha.runtime.dlq.DeadLetterRetention(
                dlq.getMaxBytes(), dlq.getMaxEntries(), dlq.getMaxAge());
    }

    /**
     * Where a record the engine could not decode is written, or empty when nowhere.
     *
     * <p>TIME-4/W8-11. The dead-letter path exists and works and a server had no key to switch it
     * on, so every node ran the unguarded path -- where a decode failure ends the poll and stops the
     * source, taking every other row in the file with it. `pravaha run --dlq` had this; a server did
     * not, which is the deployment that matters.
     */
    public Optional<Path> dlqPath() {
        String directory = dlq.getDirectory();
        return directory == null || directory.isBlank() ? Optional.empty() : Optional.of(Path.of(directory));
    }

    public Registry getRegistry() {
        return registry;
    }

    public Checkpoint getCheckpoint() {
        return checkpoint;
    }

    /** Where registered queries are written down, if anywhere. */
    public Optional<Path> journalPath() {
        return pathOf(registry.journal);
    }

    /** Where checkpoints are written, if anywhere. */
    public Optional<Path> checkpointPath() {
        return pathOf(checkpoint.directory);
    }

    /**
     * The checkpointer's own settings, in the engine's configuration type.
     *
     * <p>Nanoseconds, and not {@code Duration.toString()}. Spring parses {@code 2s} into a {@code
     * Duration} and {@code toString} renders it back as ISO-8601 {@code PT2S}, which the engine's
     * own parser rejects -- so a node with checkpointing configured started cleanly and then failed
     * every registration with "expected a number with a unit". Two duration formats met in the
     * middle and neither was wrong on its own.
     */
    public Configuration checkpointConfiguration() {
        return Configuration.builder()
                .set("pravaha.checkpoint.interval", checkpoint.interval.toNanos() + "ns")
                .set("pravaha.checkpoint.keep", String.valueOf(checkpoint.keep))
                // PeriodicCheckpointer.from reads three keys; this wrote two, so the timeout it
                // documents was bound to nothing.
                .set("pravaha.checkpoint.timeout", checkpoint.timeout.toNanos() + "ns")
                .build();
    }

    /**
     * The debugger's own bounds, in the engine's configuration type (ADR-048).
     *
     * <p>Durations in nanoseconds for the same reason the checkpointer's are: Spring parses
     * {@code 15m} into a {@code Duration} whose {@code toString} is ISO-8601, and the engine's own
     * parser does not read ISO-8601.
     */
    public Configuration debugConfiguration() {
        return Configuration.builder()
                .set("pravaha.debug.sessions.max", String.valueOf(debug.sessions.max))
                .set("pravaha.debug.session.ttl", debug.session.ttl.toNanos() + "ns")
                .set("pravaha.debug.session.max-rows", String.valueOf(debug.session.maxRows))
                .set("pravaha.debug.step.max-rows", String.valueOf(debug.step.maxRows))
                .build();
    }

    /** {@code pravaha.debug.*}: how many forks a node will hold and how long they live. */
    public static class Debug {

        private final Sessions sessions = new Sessions();
        private final Session session = new Session();
        private final Step step = new Step();

        public Sessions getSessions() {
            return sessions;
        }

        public Session getSession() {
            return session;
        }

        public Step getStep() {
            return step;
        }

        /** {@code pravaha.debug.sessions.*} */
        public static class Sessions {

            private int max = 4;

            public int getMax() {
                return max;
            }

            public void setMax(int max) {
                this.max = max;
            }
        }

        /** {@code pravaha.debug.session.*} */
        public static class Session {

            private Duration ttl = Duration.ofMinutes(15);
            private long maxRows = 20_000;

            public Duration getTtl() {
                return ttl;
            }

            public void setTtl(Duration ttl) {
                this.ttl = ttl;
            }

            public long getMaxRows() {
                return maxRows;
            }

            public void setMaxRows(long maxRows) {
                this.maxRows = maxRows;
            }
        }

        /** {@code pravaha.debug.step.*} */
        public static class Step {

            private long maxRows = 10_000;

            public long getMaxRows() {
                return maxRows;
            }

            public void setMaxRows(long maxRows) {
                this.maxRows = maxRows;
            }
        }
    }

    /**
     * Refuses a persistence setting that cannot do what it says, before the node is built on it.
     *
     * <p>CFG-7 and CFG-16, which are one defect with two faces: a value that is wrong in the file
     * and is discovered <em>per registration</em>. {@code keep: 0} started a node that logged
     * "checkpointing registered queries under …", reported {@code UP} on every probe, and then
     * refused every {@code register} with {@code PRV-1041}; a {@code checkpoint.directory} naming a
     * regular file did the same thing with a different message. One bad value should be one startup
     * failure, which is exactly the argument {@code PravahaNode} already makes for
     * {@code pravaha.watermark.idle-after}, and made for nothing else.
     *
     * <p>Runs while this bean is being initialised, so it is ahead of the node's own start and
     * ahead of the web server -- the CFG-21 placement, for the same reason.
     */
    @jakarta.annotation.PostConstruct
    public void validate() {
        if (checkpoint.keep < 1) {
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    com.ash.messaging.pravaha.common.config.ConfigErrors.OUT_OF_RANGE,
                    "pravaha.checkpoint.keep is " + checkpoint.keep + ", and at least one checkpoint must "
                            + "be kept: keeping none means every restart starts from nothing, which is what "
                            + "leaving pravaha.checkpoint.directory unset already means. More than one is "
                            + "kept by default because the newest is the likeliest to be unreadable -- it is "
                            + "the one that was being written when a process died.");
        }
        requirePositive("pravaha.checkpoint.interval", checkpoint.interval);
        requirePositive("pravaha.checkpoint.timeout", checkpoint.timeout);
        checkpointPath().ifPresent(PersistenceProperties::requireCheckpointDirectory);
        journalPath().ifPresent(PersistenceProperties::requireJournalPath);
    }

    private static void requirePositive(String key, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    com.ash.messaging.pravaha.common.config.ConfigErrors.OUT_OF_RANGE,
                    key + " is " + value + ", and it has to be positive. Zero or less is a tight loop or a "
                            + "checkpoint that has already timed out before it starts.");
        }
    }

    /**
     * The checkpoint root has to be a directory this node can write into, or become one.
     *
     * <p>CFG-7. Pointing it at an existing regular file started a node that announced checkpointing
     * and then failed every registration with {@code PRV-1041 cannot create the checkpoint directory
     * …/txnA.csv/QW} -- up, green, and unable to accept work.
     */
    private static void requireCheckpointDirectory(Path directory) {
        if (java.nio.file.Files.exists(directory) && !java.nio.file.Files.isDirectory(directory)) {
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    com.ash.messaging.pravaha.state.StateErrors.CHECKPOINT_DIRECTORY_UNUSABLE,
                    "pravaha.checkpoint.directory is " + directory + ", which exists and is not a "
                            + "directory. Each query checkpoints into its own directory beneath this one, so "
                            + "this has to be a directory; name one, or leave the key unset to run without "
                            + "checkpoints.");
        }
        if (java.nio.file.Files.isDirectory(directory) && !java.nio.file.Files.isWritable(directory)) {
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    com.ash.messaging.pravaha.state.StateErrors.CHECKPOINT_DIRECTORY_UNUSABLE,
                    "pravaha.checkpoint.directory is " + directory + ", which this process cannot write "
                            + "to. Every checkpoint would fail, one registration at a time.");
        }
        Path parent = directory.toAbsolutePath().getParent();
        if (!java.nio.file.Files.exists(directory) && parent != null && !java.nio.file.Files.isDirectory(parent)) {
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    com.ash.messaging.pravaha.state.StateErrors.CHECKPOINT_DIRECTORY_UNUSABLE,
                    "pravaha.checkpoint.directory is " + directory + " and " + parent + " does not exist, "
                            + "so the whole path is about to be created from a value nobody has checked. "
                            + "Create the parent, or correct the path.");
        }
    }

    /**
     * The journal has to be a writable file path with a directory already under it.
     *
     * <p>CFG-7, both remaining cells. A journal path that is itself a directory failed at startup
     * -- correctly -- with a bare {@code UncheckedIOException: cannot read the registry journal at
     * …}, no code and no help URL, so the one shape caught early had the worst message. A journal
     * inside a directory that does not exist was silently created by {@code RegistryJournal.append}
     * through {@code Files.createDirectories}, so {@code PRV-8006} never fired for the commonest
     * typo and a node came up journalling to a path nobody meant, empty, looking correct.
     */
    private static void requireJournalPath(Path journal) {
        if (java.nio.file.Files.isDirectory(journal)) {
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    com.ash.messaging.pravaha.registry.RegistryErrors.JOURNAL_UNWRITABLE,
                    "pravaha.registry.journal is " + journal + ", which is a directory. The journal is one "
                            + "append-only file; name the file, not the directory it lives in.");
        }
        Path parent = journal.toAbsolutePath().getParent();
        if (parent != null && !java.nio.file.Files.isDirectory(parent)) {
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    com.ash.messaging.pravaha.registry.RegistryErrors.JOURNAL_UNWRITABLE,
                    "pravaha.registry.journal is " + journal + " and its directory " + parent + " does not "
                            + "exist. It used to be created silently on the first append, so a typo in this "
                            + "path produced a node that journalled correctly to the wrong place and a real "
                            + "journal that stayed empty. Create the directory, or correct the path.");
        }
        if (parent != null && !java.nio.file.Files.isWritable(parent)) {
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    com.ash.messaging.pravaha.registry.RegistryErrors.JOURNAL_UNWRITABLE,
                    "pravaha.registry.journal is " + journal + " and this process cannot write into "
                            + parent + ". Every registration would be accepted in memory and lost on "
                            + "restart.");
        }
    }

    private static Optional<Path> pathOf(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
    }

    /** {@code pravaha.dlq.*}: where records that could not be decoded go. */
    public static class Dlq {

        /**
         * Directory for dead-letter files, one per query. Empty means no queue.
         *
         * <p>Empty is the default and stays the default: a record must not be dropped merely
         * because nobody arranged somewhere to put it, which is the rule the unguarded path keeps
         * by failing. What changes is that an operator can now choose the other rule -- keep going,
         * keep the record -- without editing code.
         */
        private String directory = "";

        /**
         * The largest one query's dead-letter file may grow, in bytes. Zero means no byte bound.
         *
         * <p>B5. Named after {@code pravaha.state.spill.max-bytes}, which is the same question
         * about the other file this node writes without an upper limit. On by default at 256 MiB,
         * because a bound that defaults to off is not a bound: the failure it stops is one renamed
         * column in a busy feed filling the disk the checkpoints are on, and that happens to a
         * deployment that set a directory and nothing else.
         */
        private long maxBytes = com.ash.messaging.pravaha.runtime.dlq.DeadLetterRetention.DEFAULT_MAX_BYTES;

        /** The most entries one query's file may hold, or zero for no count bound. */
        private long maxEntries;

        /** How long an entry is kept, or zero for no age bound. */
        private Duration maxAge = Duration.ZERO;

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        public long getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(long maxEntries) {
            this.maxEntries = maxEntries;
        }

        public Duration getMaxAge() {
            return maxAge;
        }

        public void setMaxAge(Duration maxAge) {
            this.maxAge = maxAge == null ? Duration.ZERO : maxAge;
        }
    }

    /** {@code pravaha.registry.*} */
    public static class Registry {

        private String journal = "";

        public String getJournal() {
            return journal;
        }

        public void setJournal(String journal) {
            this.journal = journal;
        }
    }

    /** {@code pravaha.checkpoint.*} */
    public static class Checkpoint {

        private String directory = "";
        private Duration interval = Duration.ofMinutes(1);
        private int keep = 3;
        private Duration timeout = Duration.ofSeconds(30);

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public int getKeep() {
            return keep;
        }

        public void setKeep(int keep) {
            this.keep = keep;
        }
    }
}
