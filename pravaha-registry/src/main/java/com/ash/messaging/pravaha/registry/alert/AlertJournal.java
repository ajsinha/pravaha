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
package com.ash.messaging.pravaha.registry.alert;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.common.io.SensitiveFiles;

/**
 * Alerts written down: their definitions, and every decision about every key -- fired, cleared,
 * notified, acknowledged -- appended and forced to the device <em>before</em> anything is sent
 * (ADR-057). That order is what makes the state exactly once: a restart replays the file and knows
 * which keys are firing and what each receiver was last told, so it neither re-fires what is firing nor
 * forgets a clear it decided and had not yet delivered.
 *
 * <p>The registry and catalogue journals' discipline: length-prefixed {@link ControlWire} records, the
 * file owner-only from its first byte (it names principals and holds the rows that fired), a torn final
 * record dropped rather than refusing the file, and a compaction -- the checkpoint -- that rewrites only
 * what is live into a sibling and renames it over the file atomically.
 *
 * <p>Not thread-safe; {@link AlertService} serialises every call.
 */
final class AlertJournal {

    private final Path file;

    /** Records appended since the last compaction or replay, for deciding when to compact. */
    private int records;

    static AlertJournal inMemory() {
        return new AlertJournal(null);
    }

    static AlertJournal at(Path file) {
        return new AlertJournal(file);
    }

    private AlertJournal(Path file) {
        this.file = file;
    }

    Path file() {
        return file;
    }

    int records() {
        return records;
    }

    /** Hands every intact record to {@code apply}, in order. */
    void replay(Consumer<List<String>> apply) {
        records = 0;
        if (file == null || !Files.exists(file)) {
            return;
        }
        byte[] all;
        try {
            all = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new PravahaException(
                    AlertErrors.JOURNAL_FAILED,
                    "cannot read the alert journal at " + file + "; the node refuses to start rather than forget "
                            + "which keys are firing and re-announce every one of them",
                    e);
        }
        ByteBuffer buffer = ByteBuffer.wrap(all);
        while (buffer.remaining() > 4) {
            int length = buffer.getInt();
            if (length < 0 || length > buffer.remaining()) {
                break; // a torn final record: everything before it is intact
            }
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            apply.accept(ControlWire.decode(bytes));
            records++;
        }
    }

    /** Appends records in one write and one force. */
    void append(List<List<String>> batch) {
        if (batch.isEmpty()) {
            return;
        }
        records += batch.size();
        if (file == null) {
            return;
        }
        write(file, batch, true);
    }

    /** Replaces the file with {@code live}, atomically. */
    void rewrite(List<List<String>> live) {
        records = live.size();
        if (file == null) {
            return;
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".compacting");
        try {
            Files.deleteIfExists(temporary);
            write(temporary, live, false);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Path parent = file.getParent();
            if (parent != null) {
                SensitiveFiles.syncDirectory(parent);
            }
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Reported below; a leftover .compacting file is never read as the journal.
            }
            throw new PravahaException(
                    AlertErrors.JOURNAL_FAILED, "cannot compact the alert journal at " + file, failure);
        }
    }

    private static void write(Path target, List<List<String>> batch, boolean append) {
        List<byte[]> payloads = new ArrayList<>();
        int size = 0;
        for (List<String> fields : batch) {
            byte[] payload = ControlWire.encode(fields);
            payloads.add(payload);
            size += 4 + payload.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        for (byte[] payload : payloads) {
            buffer.putInt(payload.length).put(payload);
        }
        buffer.flip();
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            SensitiveFiles.createOwnerOnly(target);
            StandardOpenOption mode = append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING;
            try (FileChannel channel =
                    FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, mode)) {
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                // A fire that is only in a page cache is re-sent after a crash as a new one; a clear in a
                // page cache is lost. Both are what this file exists to prevent.
                channel.force(true);
            }
        } catch (IOException e) {
            throw new PravahaException(
                    AlertErrors.JOURNAL_FAILED,
                    "cannot append to the alert journal at " + target
                            + "; the decision is not acted on, since it would not survive a restart",
                    e);
        }
    }
}
