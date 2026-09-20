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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ash.messaging.pravaha.common.io.SensitiveFiles;

/**
 * Reads the dead-letter files a {@link FileDeadLetterQueue} wrote.
 *
 * <p>Streamed, never slurped. The bound in {@link DeadLetterRetention} keeps a file to a few
 * hundred megabytes, which is small for a disk and much too large for {@code readAllLines} on a
 * node that is already in trouble -- and a dead-letter queue is read precisely when the node is. So
 * a page walks the file once keeping only the window it needs, which costs one pass and a few
 * hundred entries of memory whatever the file's size.
 *
 * <p>Newest first means reading forwards and keeping the tail, because the file is append-only and
 * lines are not fixed width: there is no seek to "the last fifty". For {@code offset + limit} in
 * the low hundreds, which is every page any surface asks for, a deque of that size is the whole
 * cost.
 *
 * <p><strong>Nothing here throws for a missing file.</strong> A query that has never rejected a
 * record has no file, which is the ordinary case and not an error; it reads as a queue of zero.
 */
public final class FileDeadLetterStore implements DeadLetterStore {

    private static final System.Logger LOG = System.getLogger(FileDeadLetterStore.class.getName());

    private final Path directory;
    private final DeadLetterRetention retention;

    public FileDeadLetterStore(Path directory) {
        this(directory, DeadLetterRetention.defaults());
    }

    public FileDeadLetterStore(Path directory, DeadLetterRetention retention) {
        this.directory = directory;
        this.retention = retention == null ? DeadLetterRetention.defaults() : retention;
    }

    public Path directory() {
        return directory;
    }

    @Override
    public DeadLetterRetention retention() {
        return retention;
    }

    @Override
    public List<String> queries() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (var files = Files.list(directory)) {
            files.forEach(file -> {
                String query = DeadLetterFiles.queryOf(file);
                if (query != null) {
                    names.add(query);
                }
            });
        } catch (IOException unreadable) {
            return List.of();
        }
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    @Override
    public DeadLetterCounts counts(String query) {
        Path file = DeadLetterFiles.letters(directory, query);
        if (!Files.isRegularFile(file)) {
            return DeadLetterCounts.empty();
        }
        long entries = 0;
        long oldest = 0;
        long newest = 0;
        try (BufferedReader lines = reader(file)) {
            String line;
            while ((line = lines.readLine()) != null) {
                Optional<DeadLetter> letter = DeadLetterJson.read(line);
                if (letter.isEmpty()) {
                    continue;
                }
                entries++;
                long wall = letter.get().wallMillis();
                if (wall > 0) {
                    oldest = oldest == 0 ? wall : Math.min(oldest, wall);
                    newest = Math.max(newest, wall);
                }
            }
        } catch (IOException unreadable) {
            return DeadLetterCounts.empty();
        }
        Eviction evicted = evictionsOf(query);
        Map<String, Marker> replays = replaysOf(query);
        long replayed = replays.values().stream()
                .filter(marker -> marker.outcome() == DeadLetterEntry.Replay.REPLAYED)
                .count();
        long failedAgain = replays.size() - replayed;
        return new DeadLetterCounts(
                entries, sizeOf(file), evicted.entries(), evicted.bytes(), oldest, newest, replayed, failedAgain);
    }

    @Override
    public DeadLetterPage page(String query, int offset, int limit) {
        int skip = Math.max(0, offset);
        int size = DeadLetterPage.clamp(limit);
        Path file = DeadLetterFiles.letters(directory, query);
        if (!Files.isRegularFile(file)) {
            return DeadLetterPage.empty(skip, size);
        }
        // The window is the last (skip + size) entries of the file, and the page is the first
        // `size` of that window once it is reversed. Holding that many is the whole memory cost.
        int window = skip + size;
        Deque<DeadLetterEntry> tail = new ArrayDeque<>(window);
        long total = 0;
        try (BufferedReader lines = reader(file)) {
            String line;
            long sequence = 0;
            while ((line = lines.readLine()) != null) {
                Optional<DeadLetter> letter = DeadLetterJson.read(line);
                if (letter.isEmpty()) {
                    continue;
                }
                sequence++;
                total++;
                tail.addLast(DeadLetterEntry.of(sequence, letter.get()));
                if (tail.size() > window) {
                    tail.removeFirst();
                }
            }
        } catch (IOException unreadable) {
            return DeadLetterPage.empty(skip, size);
        }
        Map<String, Marker> replays = replaysOf(query);
        List<DeadLetterEntry> newestFirst = new ArrayList<>(tail.size());
        // Reversed by draining the deque from the back: the last line written is the newest entry.
        while (!tail.isEmpty()) {
            newestFirst.add(withReplay(tail.removeLast(), replays));
        }
        List<DeadLetterEntry> page =
                newestFirst.size() <= skip ? List.of() : List.copyOf(newestFirst.subList(skip, newestFirst.size()));
        return new DeadLetterPage(page, skip, size, total);
    }

    @Override
    public Optional<DeadLetterEntry> find(String query, String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        Path file = DeadLetterFiles.letters(directory, query);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (BufferedReader lines = reader(file)) {
            String line;
            long sequence = 0;
            DeadLetterEntry found = null;
            while ((line = lines.readLine()) != null) {
                Optional<DeadLetter> letter = DeadLetterJson.read(line);
                if (letter.isEmpty()) {
                    continue;
                }
                sequence++;
                if (id.equals(letter.get().id())) {
                    // The newest match wins rather than the first. An id is a fresh UUID per
                    // rejection so there is normally exactly one, and if a source ever repeated one
                    // the entry a person means is the one that just arrived.
                    found = DeadLetterEntry.of(sequence, letter.get());
                }
            }
            return Optional.ofNullable(found).map(entry -> withReplay(entry, replaysOf(query)));
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    @Override
    public void recordReplay(String query, String id, DeadLetterEntry.Replay outcome) {
        if (outcome == null || outcome == DeadLetterEntry.Replay.NEW) {
            return;
        }
        Path file = DeadLetterFiles.replays(directory, query);
        String line = "{\"id\":\"" + DeadLetterJson.escape(id) + "\",\"outcome\":\"" + outcome.name() + "\",\"wall\":"
                + System.currentTimeMillis() + "}" + System.lineSeparator();
        try {
            Files.createDirectories(directory);
            // Owner-only like the letters themselves: the id is a handle on bytes that are data.
            SensitiveFiles.createOwnerOnly(file);
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException unwritable) {
            // A replay that happened and could not be noted is still a replay, and failing the call
            // afterwards would tell the caller their record was not fed in when it was -- which is
            // the worse of the two wrong answers, because it invites them to do it again. The note
            // is an aid to the next person, not the transaction, so the failure is logged and the
            // replay stands.
            LOG.log(
                    System.Logger.Level.WARNING,
                    "replayed " + id + " for '" + query + "' and could not record it in "
                            + DeadLetterFiles.replays(directory, query) + ": " + unwritable
                            + ". The record went through; the entry will still read as NEW.");
        }
    }

    /** What retention has taken from this query's queue, from the record beside it. */
    Eviction evictionsOf(String query) {
        Path file = DeadLetterFiles.evicted(directory, query);
        if (!Files.isRegularFile(file)) {
            return new Eviction(0, 0);
        }
        long entries = 0;
        long bytes = 0;
        try (BufferedReader lines = reader(file)) {
            String line;
            while ((line = lines.readLine()) != null) {
                entries += DeadLetterJson.number(line, "entries");
                bytes += DeadLetterJson.number(line, "bytes");
            }
        } catch (IOException unreadable) {
            return new Eviction(entries, bytes);
        }
        return new Eviction(entries, bytes);
    }

    private Map<String, Marker> replaysOf(String query) {
        Path file = DeadLetterFiles.replays(directory, query);
        Map<String, Marker> markers = new HashMap<>();
        if (!Files.isRegularFile(file)) {
            return markers;
        }
        try (BufferedReader lines = reader(file)) {
            String line;
            while ((line = lines.readLine()) != null) {
                String id = DeadLetterJson.string(line, "id");
                String outcome = DeadLetterJson.string(line, "outcome");
                if (id == null || outcome == null) {
                    continue;
                }
                try {
                    // Last writer wins: an entry replayed twice reads as however the second went.
                    markers.put(
                            id,
                            new Marker(DeadLetterEntry.Replay.valueOf(outcome), DeadLetterJson.number(line, "wall")));
                } catch (IllegalArgumentException unknownOutcome) {
                    // A marker written by a newer node than this one. Ignored rather than guessed.
                }
            }
        } catch (IOException unreadable) {
            return markers;
        }
        return markers;
    }

    private static DeadLetterEntry withReplay(DeadLetterEntry entry, Map<String, Marker> replays) {
        Marker marker = replays.get(entry.id());
        if (marker == null) {
            return entry;
        }
        return new DeadLetterEntry(
                entry.sequence(),
                entry.letter(),
                marker.outcome(),
                marker.wallMillis() > 0 ? Instant.ofEpochMilli(marker.wallMillis()) : null);
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException unreadable) {
            return 0;
        }
    }

    private static BufferedReader reader(Path file) throws IOException {
        return Files.newBufferedReader(file, StandardCharsets.UTF_8);
    }

    /** What retention took, in entries and bytes. */
    record Eviction(long entries, long bytes) {}

    private record Marker(DeadLetterEntry.Replay outcome, long wallMillis) {}
}
