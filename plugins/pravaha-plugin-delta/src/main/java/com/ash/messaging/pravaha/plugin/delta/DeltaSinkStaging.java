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
package com.ash.messaging.pravaha.plugin.delta;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Where a transactional Delta sink keeps a checkpoint's changes until the checkpoint is durable.
 *
 * <p>The same idea as {@code jdbc-sink}'s staging table and {@code kafka-sink}'s staging topic, and
 * for the same reason: the SPI wants a transaction that is <em>durable and not yet visible</em>, and
 * a Delta commit has no such state -- a commit is visible the instant its log entry lands. So the
 * changes are written to files first, and the commit that makes them visible happens once, later,
 * when the checkpoint recording it is durable.
 *
 * <p>The layout, under {@code <staging.dir>/<transaction.id>/}:
 *
 * <pre>
 *   0000000000000007/000000.batch   one write() call's changes, encoded
 *   0000000000000007/000001.batch
 * </pre>
 *
 * <p>A batch is written to {@code .tmp} and renamed, so a file that is there is a file that is
 * whole; a process that died mid-write leaves a {@code .tmp} nothing reads. The directory sits
 * <em>inside</em> the table so it travels with it, and its name begins with an underscore, which is
 * what Delta's own {@code VACUUM} skips -- the same rule that keeps {@code _delta_log} safe.
 */
final class DeltaSinkStaging {

    private static final String SUFFIX = ".batch";
    private static final String PENDING = ".tmp";

    private final Path root;

    DeltaSinkStaging(Path root) {
        this.root = root;
    }

    /** Where this sink stages, for a message that has to name it. */
    Path root() {
        return root;
    }

    /** Appends one write's changes to the open transaction's directory. */
    void stage(long label, int seq, byte[] payload) {
        Path directory = labelDirectory(label);
        try {
            Files.createDirectories(directory);
            Path pending = directory.resolve(String.format("%06d%s%s", seq, SUFFIX, PENDING));
            Files.write(pending, payload);
            Files.move(
                    pending, directory.resolve(String.format("%06d%s", seq, SUFFIX)), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new PravahaException(
                    DeltaErrors.SINK_WRITE_FAILED,
                    "cannot stage a batch for transaction " + label + " under " + directory + ": " + e.getMessage(),
                    e);
        }
    }

    /** Everything staged under a label, oldest first. Empty when the label has nothing, or is gone. */
    List<byte[]> staged(long label) {
        Path directory = labelDirectory(label);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> listing = Files.list(directory)) {
            listing.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).forEach(files::add);
        } catch (IOException e) {
            throw new PravahaException(
                    DeltaErrors.SINK_WRITE_FAILED, "cannot read staged batches under " + directory + ": " + e, e);
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        List<byte[]> payloads = new ArrayList<>(files.size());
        for (Path file : files) {
            try {
                payloads.add(Files.readAllBytes(file));
            } catch (IOException e) {
                throw new PravahaException(
                        DeltaErrors.SINK_WRITE_FAILED, "cannot read the staged batch " + file + ": " + e, e);
            }
        }
        return payloads;
    }

    /** Forgets one transaction's staged changes. Silent when there are none. */
    void discard(long label) {
        deleteRecursively(labelDirectory(label));
    }

    /**
     * Forgets every transaction begun after {@code checkpointId}: what a dead process had open, and
     * what it prepared at a checkpoint that never became durable. The replay writes all of it again.
     */
    void discardAfter(long checkpointId) {
        for (long label : labels()) {
            if (label > checkpointId) {
                discard(label);
            }
        }
    }

    /** The labels that have something staged, which is what an operator's clean-up needs to see. */
    List<Long> labels() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Long> labels = new ArrayList<>();
        try (Stream<Path> listing = Files.list(root)) {
            listing.forEach(entry -> {
                try {
                    labels.add(Long.parseLong(entry.getFileName().toString()));
                } catch (NumberFormatException notALabel) {
                    // Somebody else's directory. Left alone rather than guessed at.
                }
            });
        } catch (IOException e) {
            throw new PravahaException(
                    DeltaErrors.SINK_WRITE_FAILED, "cannot list the staging directory " + root + ": " + e, e);
        }
        labels.sort(Comparator.naturalOrder());
        return labels;
    }

    private Path labelDirectory(long label) {
        return root.resolve(String.format("%016d", label));
    }

    private static void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            List<Path> entries = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot remove the staging directory " + directory, e);
        }
    }
}
