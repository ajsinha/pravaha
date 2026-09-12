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
package com.ash.messaging.pravaha.state.checkpoint;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.ash.messaging.pravaha.common.io.SensitiveFiles;

/**
 * Checkpoints in a directory, one file each, published by atomic rename.
 *
 * <p>The rename is the mechanism, and it is worth being explicit about why. A checkpoint written
 * directly to its final name is readable while it is still being written, so a crash mid-write
 * leaves a file that looks like a checkpoint and is half of one -- and a restore reads it and
 * believes it, which is worse than finding nothing. Written to a temporary name and renamed, the
 * file appears complete or not at all, because rename within a filesystem is atomic.
 *
 * <p>A trailer holding the record count is written last and checked on read. Rename covers the crash
 * case; the trailer covers the rest -- a truncated file from a full disk, a partial copy, a
 * half-finished restore from backup. A checkpoint that cannot be trusted has to be skipped rather
 * than half-read, and skipping requires being able to tell.
 */
public final class FileCheckpointStore implements CheckpointStore {

    private static final String PREFIX = "checkpoint-";
    private static final String SUFFIX = ".bin";
    private static final int MAGIC = 0x50525643; // "PRVC"
    private static final int FORMAT_VERSION = 1;

    private final Path directory;

    public FileCheckpointStore(Path directory) {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the checkpoint directory " + directory, e);
        }
    }

    @Override
    public void store(Checkpoint checkpoint) {
        Path target = directory.resolve(PREFIX + checkpoint.id() + SUFFIX);
        Path temporary = directory.resolve(PREFIX + checkpoint.id() + ".tmp");
        try {
            // Serialised operator state is the aggregated data itself, so the file is created
            // owner-only before anything is written into it.
            SensitiveFiles.createOwnerOnly(temporary);
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(temporary))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT_VERSION);
                out.writeLong(checkpoint.id());
                out.writeLong(checkpoint.timestampNanos());

                out.writeInt(checkpoint.offsets().size());
                for (Map.Entry<String, String> entry : checkpoint.offsets().entrySet()) {
                    out.writeUTF(entry.getKey());
                    out.writeUTF(entry.getValue());
                }

                out.writeInt(checkpoint.operatorState().size());
                for (Map.Entry<String, byte[]> entry :
                        checkpoint.operatorState().entrySet()) {
                    out.writeUTF(entry.getKey());
                    out.writeInt(entry.getValue().length);
                    out.write(entry.getValue());
                }

                // The trailer, written last and read first. Its presence is the file's own statement
                // that it finished being written.
                out.writeInt(checkpoint.offsets().size());
                out.writeInt(checkpoint.operatorState().size());
                out.writeInt(MAGIC);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(temporary);
            throw new UncheckedIOException("cannot store checkpoint " + checkpoint.id(), e);
        }
    }

    @Override
    public Optional<Checkpoint> latest() {
        // Newest first, and skip any that will not read: after a crash the newest file is exactly
        // the one most likely to be damaged, and falling back to the previous one is the whole
        // reason more than one is kept.
        for (long id : availableIds()) {
            Optional<Checkpoint> loaded = load(id);
            if (loaded.isPresent()) {
                return loaded;
            }
        }
        return Optional.empty();
    }

    @Override
    public List<Long> availableIds() {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(PREFIX) && name.endsWith(SUFFIX))
                    .map(name -> Long.parseLong(name.substring(PREFIX.length(), name.length() - SUFFIX.length())))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list checkpoints in " + directory, e);
        }
    }

    @Override
    public Optional<Checkpoint> load(long id) {
        Path file = directory.resolve(PREFIX + id + SUFFIX);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            if (in.readInt() != MAGIC) {
                return Optional.empty();
            }
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                throw new IllegalStateException("checkpoint " + id + " is format version " + version
                        + " and this engine reads " + FORMAT_VERSION + ". Refusing to guess at the difference.");
            }
            long storedId = in.readLong();
            long timestamp = in.readLong();

            Map<String, String> offsets = new HashMap<>();
            int offsetCount = in.readInt();
            for (int i = 0; i < offsetCount; i++) {
                offsets.put(in.readUTF(), in.readUTF());
            }

            Map<String, byte[]> state = new HashMap<>();
            int stateCount = in.readInt();
            for (int i = 0; i < stateCount; i++) {
                String operator = in.readUTF();
                byte[] bytes = new byte[in.readInt()];
                in.readFully(bytes);
                state.put(operator, bytes);
            }

            // The trailer has to agree with what was actually read. A truncated file usually fails
            // before here; one that does not is caught by the counts disagreeing.
            if (in.readInt() != offsetCount || in.readInt() != stateCount || in.readInt() != MAGIC) {
                return Optional.empty();
            }
            return Optional.of(new Checkpoint(storedId, timestamp, offsets, state));
        } catch (IOException e) {
            // Unreadable is not exceptional: it is the expected state of a file that was being
            // written when the process died, and the caller's job is to fall back to an older one.
            return Optional.empty();
        }
    }

    @Override
    public int prune(int keep) {
        if (keep < 1) {
            throw new IllegalArgumentException("at least one checkpoint must be kept, asked to keep " + keep);
        }
        List<Long> ids = new ArrayList<>(availableIds());
        int removed = 0;
        for (int i = keep; i < ids.size(); i++) {
            deleteQuietly(directory.resolve(PREFIX + ids.get(i) + SUFFIX));
            removed++;
        }
        return removed;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Leaving a stale file is untidy; failing a checkpoint because a stale file could not be
            // removed would trade a tidiness problem for an availability one.
        }
    }

    public Path directory() {
        return directory;
    }
}
