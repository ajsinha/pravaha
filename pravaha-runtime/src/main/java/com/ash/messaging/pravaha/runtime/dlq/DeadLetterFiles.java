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
package com.ash.messaging.pravaha.runtime.dlq;

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

/**
 * What a query's dead-letter files are called, in the one place both ends agree on it.
 *
 * <p>Three files a query, and the split is the point. {@code <query>.dlq} is exactly what it
 * always was -- one JSON object per rejected record, and nothing else -- because it is read during
 * incidents with {@code grep} and {@code jq} and every recipe on the help page depends on that
 * being true. What B5 added goes beside it: {@code .dlq.replays} for what has been replayed, and
 * {@code .dlq.evicted} for what retention took. A marker interleaved into the main file would have
 * saved two inodes and broken {@code jq -r .raw}.
 */
public final class DeadLetterFiles {

    /** The documented extension: {@code <query>.dlq}. */
    public static final String EXTENSION = ".dlq";

    private static final String REPLAYS = ".dlq.replays";
    private static final String EVICTED = ".dlq.evicted";

    private DeadLetterFiles() {}

    /** The entries themselves. */
    public static Path letters(Path directory, String query) {
        return directory.resolve(safe(query) + EXTENSION);
    }

    /** What has been replayed, and how it went. */
    public static Path replays(Path directory, String query) {
        return directory.resolve(safe(query) + REPLAYS);
    }

    /** What retention removed, so that the loss outlives the process that caused it. */
    public static Path evicted(Path directory, String query) {
        return directory.resolve(safe(query) + EVICTED);
    }

    /** The query a {@code .dlq} file belongs to, or null when the file is not one. */
    public static @Nullable String queryOf(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(EXTENSION) ? name.substring(0, name.length() - EXTENSION.length()) : null;
    }

    /**
     * A query name that cannot escape the directory.
     *
     * <p>A registered name is validated by the registry and cannot contain a separator today. This
     * is here anyway, because the file path is built from a caller-supplied string on the read side
     * too -- {@code GET /api/v1/queries/{name}/dead-letters} -- and a path built from a request is
     * a path traversal waiting for the day somebody relaxes the name rule.
     */
    private static String safe(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("a dead-letter file needs a query name");
        }
        String cleaned = query.replace('/', '_').replace('\\', '_').replace("..", "__");
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("'" + query + "' is not a usable query name");
        }
        return cleaned;
    }
}
