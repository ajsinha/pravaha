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
package com.ash.messaging.pravaha.common.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * How many file descriptors this process holds, and how many it may.
 *
 * <p>The ceiling nobody set and nothing checked. One bound source is one descriptor -- measured at
 * exactly 1.00 by {@code SourceScaleTest} -- and an Aerospike-backed one about 1.3 at idle, growing
 * towards {@code maxConnsPerNode} under load. At the common {@code ulimit -n} default of 1024 that
 * is somewhere under a thousand sources, which is the same order as the target of thousands and so
 * not a distant ceiling at all.
 *
 * <p><strong>What makes this worth a class rather than a log line is how it fails.</strong> Under
 * {@code ulimit -n 300} the 224th Aerospike-backed source is refused with "cannot reach Aerospike at
 * 127.0.0.1:3000 ... Check the host list, that the cluster is up, and that this process can reach
 * the service port". The cluster is up, the host list is right, the port is reachable, and all three
 * remedies are wrong. The words "file descriptor" appear nowhere.
 *
 * <p>And it cannot be fixed by catching the cause, because there is no cause: the Aerospike client's
 * {@code AerospikeException$Connection} discards the underlying {@code SocketException}. The only
 * way to tell this failure from a genuine connectivity one is to have looked at the descriptor count
 * before the attempt -- which is what this is for.
 *
 * <p>Linux only, by reading {@code /proc}. Everything returns empty elsewhere, and a caller must
 * treat "do not know" as different from "fine": saying nothing is the right behaviour on a platform
 * this cannot measure, and claiming health would be worse than the silence it replaced.
 */
public final class FileDescriptors {

    private static final Path SELF_FD = Path.of("/proc/self/fd");
    private static final Path SELF_LIMITS = Path.of("/proc/self/limits");

    /** Descriptors held and permitted, at one moment. */
    public record Usage(long open, long limit) {

        /** How many more this process may open. */
        public long remaining() {
            return Math.max(0, limit - open);
        }

        /** Whether the process is close enough to its ceiling that the next failure is likely this. */
        public boolean isNearLimit() {
            // A tenth, or a hundred, whichever is larger. Small enough not to cry wolf on a node
            // with a generous limit, generous enough to fire before the failure rather than with it.
            return remaining() <= Math.max(100, limit / 10);
        }

        @Override
        public String toString() {
            return open + " of " + limit + " file descriptors in use, " + remaining() + " remaining";
        }
    }

    private FileDescriptors() {}

    /** What this process holds now, or empty where that cannot be read. */
    public static Optional<Usage> usage() {
        Optional<Long> limit = softLimit();
        Optional<Long> open = openCount();
        if (limit.isEmpty() || open.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Usage(open.get(), limit.get()));
    }

    /**
     * A sentence to append to a failure that may have been descriptor exhaustion.
     *
     * <p>Returns empty when the count cannot be read, or when there is plenty of headroom -- in which
     * case this was not the cause and saying so would send the reader somewhere else wrong.
     */
    public static Optional<String> exhaustionHint() {
        return usage().filter(Usage::isNearLimit)
                .map(usage -> "This process is at " + usage + ", which is close enough to its limit that "
                        + "descriptor exhaustion is the likelier cause than anything this message suggests. "
                        + "One bound source costs about one descriptor. Raise it with ulimit -n, or LimitNOFILE "
                        + "in a systemd unit.");
    }

    private static Optional<Long> openCount() {
        if (!Files.isDirectory(SELF_FD)) {
            return Optional.empty();
        }
        try (Stream<Path> held = Files.list(SELF_FD)) {
            return Optional.of(held.count());
        } catch (IOException | RuntimeException cannot) {
            return Optional.empty();
        }
    }

    private static Optional<Long> softLimit() {
        if (!Files.isReadable(SELF_LIMITS)) {
            return Optional.empty();
        }
        try {
            for (String line : Files.readAllLines(SELF_LIMITS, StandardCharsets.UTF_8)) {
                if (!line.startsWith("Max open files")) {
                    continue;
                }
                // "Max open files            1024                 4096                 files"
                String[] fields =
                        line.substring("Max open files".length()).trim().split("\\s+", -1);
                if (fields.length == 0) {
                    return Optional.empty();
                }
                if ("unlimited".equals(fields[0])) {
                    return Optional.of(Long.MAX_VALUE);
                }
                return Optional.of(Long.parseLong(fields[0]));
            }
            return Optional.empty();
        } catch (IOException | RuntimeException cannot) {
            return Optional.empty();
        }
    }
}
