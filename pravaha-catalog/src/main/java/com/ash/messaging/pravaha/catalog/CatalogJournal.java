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
package com.ash.messaging.pravaha.catalog;

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
 * The catalogue written down: an append-only file of records, replayed into memory at start.
 *
 * <p>The registry journal's discipline, and for the same reasons (ADR-059 §3: every change is a
 * journal record). Length-prefixed {@link ControlWire} records; the file is created owner-only before
 * its first byte, since it names every principal and what they may read; every append is forced to
 * the device before the change is acknowledged, because a grant that is only in a page cache is a
 * revocation that silently un-happens at the next restart; and a torn final record -- a crash
 * mid-append -- is dropped rather than refusing the whole file, since everything before it is intact.
 *
 * <p>A compaction rewrites the file with only what is live, into a sibling and an atomic rename, and
 * syncs the directory so the rename itself survives a crash.
 *
 * <p>Not thread-safe; {@link Catalog} serialises every call.
 */
final class CatalogJournal {

    private final Path file;

    /** Records appended since the last compaction or replay, for deciding when to compact. */
    private int records;

    /** A journal that keeps nothing, for an embedded engine or a test that wants no file. */
    static CatalogJournal inMemory() {
        return new CatalogJournal(null);
    }

    static CatalogJournal at(Path file) {
        return new CatalogJournal(file);
    }

    private CatalogJournal(Path file) {
        this.file = file;
    }

    Path file() {
        return file;
    }

    boolean durable() {
        return file != null;
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
                    CatalogErrors.JOURNAL_FAILED,
                    "cannot read the catalogue journal at " + file + "; the node refuses to start without the "
                            + "grants it holds rather than start with none",
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
        records += batch.size();
        if (file == null || batch.isEmpty()) {
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
                    CatalogErrors.JOURNAL_FAILED, "cannot compact the catalogue journal at " + file, failure);
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
                // A grant or a revocation that is only in a page cache is worse than refusing it.
                channel.force(true);
            }
        } catch (IOException e) {
            throw new PravahaException(
                    CatalogErrors.JOURNAL_FAILED,
                    "cannot append to the catalogue journal at " + target
                            + "; the change is refused rather than acknowledged and lost at the next restart",
                    e);
        }
    }
}
