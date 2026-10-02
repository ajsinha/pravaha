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

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.io.SensitiveFiles;
import com.ash.messaging.pravaha.state.StateErrors;

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
 *
 * <p>A CRC32C over everything before it is written after the trailer (CKPTSUM-1) and checked before
 * anything is parsed. The trailer proves the file was finished; it says nothing about the bytes
 * between header and trailer, and a single flipped bit there was restored as state and published as
 * an answer for ever. A checkpoint whose checksum does not match is skipped with {@code PRV-4094} and
 * the restore falls back to the one before it, as for a truncated one. The checksum is a tail beside
 * an unchanged format-1 body, so a checkpoint written before it -- which ends at the trailer -- is
 * still read, and logged as unverified; and an engine from before it reads a new one and ignores the
 * tail. A new checkpoint also carries an empty operator entry saying it was checksummed, which an
 * older engine ignores as it ignores any operator it does not run: a new file whose tail has been cut
 * off is then refused rather than read as an old, unverified one.
 */
public final class FileCheckpointStore implements CheckpointStore {

    private static final String PREFIX = "checkpoint-";
    private static final String SUFFIX = ".bin";
    private static final int MAGIC = 0x50525643; // "PRVC"
    private static final int FORMAT_VERSION = 1;

    /** Marks the checksum tail: "PRVK", then the CRC32C of every byte before the tail. */
    private static final int CHECKSUM_MAGIC = 0x5052564B;

    private static final int TAIL_BYTES = Integer.BYTES + Long.BYTES;

    /** The operator entry every checksummed checkpoint carries, so a cut-off tail is detected. */
    private static final String CHECKSUMMED = "checksum:crc32c";

    /** What a file's tail says: how many bytes precede it, and whether it was a checksum. */
    private record Body(long length, boolean checksummed) {}

    private static final System.Logger LOG = System.getLogger(FileCheckpointStore.class.getName());

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
            java.util.zip.CheckedOutputStream checked = new java.util.zip.CheckedOutputStream(
                    new java.io.BufferedOutputStream(Files.newOutputStream(temporary)), new java.util.zip.CRC32C());
            try (DataOutputStream out = new DataOutputStream(checked)) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT_VERSION);
                out.writeLong(checkpoint.id());
                out.writeLong(checkpoint.timestampNanos());

                out.writeInt(checkpoint.offsets().size());
                for (Map.Entry<String, String> entry : checkpoint.offsets().entrySet()) {
                    out.writeUTF(entry.getKey());
                    out.writeUTF(entry.getValue());
                }

                out.writeInt(checkpoint.operatorState().size() + 1);
                out.writeUTF(CHECKSUMMED);
                out.writeInt(0);
                for (Map.Entry<String, byte[]> entry :
                        checkpoint.operatorState().entrySet()) {
                    out.writeUTF(entry.getKey());
                    out.writeInt(entry.getValue().length);
                    out.write(entry.getValue());
                }

                // The trailer, written last and read first. Its presence is the file's own statement
                // that it finished being written.
                out.writeInt(checkpoint.offsets().size());
                out.writeInt(checkpoint.operatorState().size() + 1);
                out.writeInt(MAGIC);
                // The checksum of everything above, after it (CKPTSUM-1).
                out.flush();
                long crc = checked.getChecksum().getValue();
                out.writeInt(CHECKSUM_MAGIC);
                out.writeLong(crc);
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
        Body verified;
        try {
            verified = verified(file, id);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (verified == null) {
            return Optional.empty();
        }
        long length = verified.length();
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC) {
                return Optional.empty();
            }
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                throw new PravahaException(
                        StateErrors.STATE_UNREADABLE,
                        "checkpoint " + id + " is format version " + version + " and this engine reads "
                                + FORMAT_VERSION + ". Refusing to guess at the difference.");
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
                int size = in.readInt();
                if (size < 0 || size > length) {
                    // Only a file written before checksums can get here damaged; a length it does
                    // not hold is that damage, and allocating it would be a second failure.
                    return Optional.empty();
                }
                byte[] bytes = new byte[size];
                in.readFully(bytes);
                state.put(operator, bytes);
            }

            // The trailer has to agree with what was actually read. A truncated file usually fails
            // before here; one that does not is caught by the counts disagreeing.
            if (in.readInt() != offsetCount || in.readInt() != stateCount || in.readInt() != MAGIC) {
                return Optional.empty();
            }
            if (state.remove(CHECKSUMMED) != null && !verified.checksummed()) {
                // Written with a checksum, and the checksum is gone: a tail cut off exactly.
                corrupt(id, "it was written with a checksum and the checksum has been cut off");
                return Optional.empty();
            }
            return Optional.of(new Checkpoint(storedId, timestamp, offsets, state));
        } catch (IOException e) {
            // Unreadable is not exceptional: it is the expected state of a file that was being
            // written when the process died, and the caller's job is to fall back to an older one.
            return Optional.empty();
        }
    }

    /**
     * Checks {@code file}'s checksum before anything in it is believed (CKPTSUM-1).
     *
     * @return how many bytes precede the checksum tail -- the whole file for one written before
     *     checksums, which is read unverified and logged -- or null when the file cannot be trusted
     */
    private Body verified(Path file, long id) throws IOException {
        long size = Files.size(file);
        if (size < TAIL_BYTES + Integer.BYTES) {
            return null;
        }
        int tailMagic;
        long stored;
        int lastInt;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
            raf.seek(size - TAIL_BYTES);
            tailMagic = raf.readInt();
            stored = raf.readLong();
            raf.seek(size - Integer.BYTES);
            lastInt = raf.readInt();
        }
        if (tailMagic != CHECKSUM_MAGIC) {
            if (lastInt == MAGIC) {
                // Ends at the trailer: written before checksums. Read as it always was, and said.
                LOG.log(
                        System.Logger.Level.INFO,
                        "checkpoint " + id + " in " + directory + " was written before checkpoints carried a "
                                + "checksum, so it is restored unverified; the next checkpoint will carry one");
                return new Body(size, false);
            }
            corrupt(id, "its checksum tail is missing or damaged");
            return null;
        }
        long body = size - TAIL_BYTES;
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        byte[] buffer = new byte[64 * 1024];
        try (java.io.InputStream in = Files.newInputStream(file)) {
            long remaining = body;
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    return null;
                }
                crc.update(buffer, 0, read);
                remaining -= read;
            }
        }
        if (crc.getValue() != stored) {
            corrupt(
                    id,
                    "its contents do not match the checksum written with them (stored " + Long.toHexString(stored)
                            + ", computed " + Long.toHexString(crc.getValue()) + ")");
            return null;
        }
        return new Body(body, true);
    }

    private void corrupt(long id, String why) {
        LOG.log(
                System.Logger.Level.WARNING,
                StateErrors.CHECKPOINT_CORRUPT.code() + " checkpoint " + id + " in " + directory + " is skipped: "
                        + why + ". The restore falls back to the checkpoint before it, or to the beginning of "
                        + "the sources when there is none -- reprocessing, never a damaged answer.");
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
