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
     * Where a record the engine could not decode is written, or empty when nowhere.
     *
     * <p>TIME-4/W8-11. The dead-letter path exists and works and a server had no key to switch it
     * on, so every node ran the unguarded path -- where a decode failure ends the poll and stops the
     * source, taking every other row in the file with it. `pravaha run --dlq` had this; a server did
     * not, which is the deployment that matters.
     */
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

    private static Optional<Path> pathOf(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
    }

    /** {@code pravaha.registry.*} */
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
