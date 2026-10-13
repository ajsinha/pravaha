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
package com.ash.messaging.pravaha.server.folders;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.ConfigErrors;

/**
 * {@code pravaha.config.*}: where the per-object declaration files live, and how often they are looked at
 * (ADR-064).
 *
 * <pre>
 * pravaha:
 *   config:
 *     dir: config                     # the root; under PRAVAHA_HOME, $PRAVAHA_HOME/conf
 *     streams-dir: config/streams     # default ${dir}/streams: one &lt;stream&gt;.yaml per stream
 *     lookups-dir: config/lookups     # default ${dir}/lookups: one &lt;lookup&gt;.yaml per dimension table
 *     sinks-dir: config/sinks         # default ${dir}/sinks: one &lt;sink&gt;.yaml per sink
 *     connectors-dir: config/connectors  # default ${dir}/connectors: one named connection per file
 *     watch-interval: 10m             # 0 or off: read at start only
 * </pre>
 *
 * <p>Not {@code pravaha.streams.dir}: {@code pravaha.streams} is the map of inline stream declarations,
 * so {@code dir} under it would be read as a stream named "dir" with a scalar for a body, and no stream
 * could ever be called {@code dir}. The folders get a block of their own.
 *
 * <p>Unset, a folder is its convention under {@code pravaha.config.dir}, and a folder found by convention that does
 * not exist is simply empty. Set, it must exist ({@code PRV-1060}): a path somebody wrote and mistyped
 * would otherwise be a node that quietly declares nothing.
 */
@Component
@ConfigurationProperties(prefix = "pravaha.config")
public class ConfigFolderProperties {

    private String dir = "config";
    private @Nullable String streamsDir;
    private @Nullable String lookupsDir;
    private @Nullable String sinksDir;
    private @Nullable String connectorsDir;
    private String watchInterval = "10m";

    /** {@code pravaha.config.dir}: the configuration root the folders below are found in by convention. */
    public String getDir() {
        return dir;
    }

    public void setDir(@Nullable String dir) {
        this.dir = dir == null || dir.isBlank() ? "config" : dir.strip();
    }

    public @Nullable String getStreamsDir() {
        return streamsDir;
    }

    public void setStreamsDir(@Nullable String streamsDir) {
        this.streamsDir = streamsDir;
    }

    public @Nullable String getLookupsDir() {
        return lookupsDir;
    }

    public void setLookupsDir(@Nullable String lookupsDir) {
        this.lookupsDir = lookupsDir;
    }

    public @Nullable String getSinksDir() {
        return sinksDir;
    }

    public void setSinksDir(@Nullable String sinksDir) {
        this.sinksDir = sinksDir;
    }

    public @Nullable String getConnectorsDir() {
        return connectorsDir;
    }

    public void setConnectorsDir(@Nullable String connectorsDir) {
        this.connectorsDir = connectorsDir;
    }

    public String getWatchInterval() {
        return watchInterval;
    }

    public void setWatchInterval(@Nullable String watchInterval) {
        this.watchInterval = watchInterval == null ? "" : watchInterval;
    }

    /** One kind's folder, and whether somebody named it -- a named folder must exist, a default need not. */
    public record Folder(DeclarationKind kind, Path path, boolean configured) {

        /** The setting that names this folder. */
        public String setting() {
            return "pravaha.config." + kind.plural() + "-dir";
        }
    }

    /** Where {@code kind}'s files are read from. */
    public Folder folder(DeclarationKind kind) {
        String written =
                switch (kind) {
                    case STREAM -> streamsDir;
                    case LOOKUP -> lookupsDir;
                    case SINK -> sinksDir;
                    case CONNECTOR -> connectorsDir;
                };
        if (written != null && !written.isBlank()) {
            return new Folder(kind, Path.of(written.strip()), true);
        }
        return new Folder(kind, Path.of(dir).resolve(kind.plural()), false);
    }

    /**
     * How often the folders are looked at; empty when watching is off ({@code 0}, {@code off}).
     *
     * <p>A number with no unit is refused, as {@code pravaha.checkpoint.interval}'s is (CFG-15): Spring
     * would read {@code 10} as ten milliseconds, and nobody polls a folder a hundred times a second on
     * purpose. Below a second is refused too ({@code PRV-1026}).
     */
    public Optional<Duration> interval() {
        String written = watchInterval.strip().toLowerCase(Locale.ROOT);
        if (written.isEmpty() || written.equals("0") || written.equals("off") || written.equals("false")) {
            return Optional.empty();
        }
        if (written.matches("-?\\d+")) {
            throw new PravahaException(
                    ConfigErrors.NOT_A_DURATION,
                    "pravaha.config.watch-interval is '" + written + "', a number with no unit. Write the unit -- "
                            + "30s, 10m -- or 0 / off to read the folders at start only.");
        }
        Duration every;
        try {
            every = DurationStyle.detectAndParse(written);
        } catch (IllegalArgumentException e) {
            throw new PravahaException(
                    ConfigErrors.NOT_A_DURATION,
                    "pravaha.config.watch-interval is '" + written + "', which is not a duration. Write one such "
                            + "as 30s or 10m, or 0 / off to read the folders at start only.");
        }
        if (every.isZero()) {
            return Optional.empty();
        }
        if (every.isNegative() || every.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new PravahaException(
                    ConfigErrors.OUT_OF_RANGE,
                    "pravaha.config.watch-interval is " + every + "; the folders are looked at no more often than "
                            + "once a second. Write 1s or more, or 0 / off to read them at start only.");
        }
        return Optional.of(every);
    }
}
