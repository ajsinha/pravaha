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

    private static Optional<Path> pathOf(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
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
