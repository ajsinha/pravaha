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
package com.ash.messaging.pravaha.registry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.serving.Retention;

/**
 * What was registered, written down, so that a restart does not lose it.
 *
 * <p>Before this, a registry held everything in memory: restart a server and every continuous query
 * a client had registered was simply gone, with no error and nothing to look at. The client's next
 * subscription failed with "no such view" and the only fix was for every client to re-register,
 * which each of them would have to know to do.
 *
 * <p>The journal records <em>registrations</em>, not state. That distinction is the whole design:
 *
 * <p>A registration -- name, SQL, key columns, owner, retention, bound parameters -- is small,
 * changes rarely, and is the thing that cannot be recomputed, because it came from a client that may
 * never speak again. State is large, changes constantly, and <em>can</em> be recomputed by reading
 * the stream. So the journal is an append-only list of small facts, and a recovered query starts
 * with empty state and fills again as data arrives.
 *
 * <p>That means a restart costs a warm-up, not an outage: the views exist immediately and answer
 * with what has arrived since. For a windowed query the first window or two are partial. Restoring
 * state as well needs the source positions the state corresponds to, and those belong to whatever is
 * feeding the registry rather than to the registry itself, so it is not something this can do alone
 * and is not pretended here.
 *
 * <p><strong>The owner is recorded and re-checked on replay.</strong> A registration is not a
 * standing permission. If the principal who registered a query has since lost access to what it
 * reads, the query does not quietly come back at restart -- the replay refuses it and says so. A
 * journal that replayed blindly would be a way to keep an entitlement after it was revoked, simply
 * by having registered before it was.
 *
 * <p><strong>The file holds query text and bound parameter values</strong>, and those values are
 * whatever the client filtered on -- account numbers, customer ids. It should be permissioned like
 * data, not like configuration.
 */
public final class RegistryJournal {

    private static final System.Logger LOG = System.getLogger(RegistryJournal.class.getName());

    private static final String REGISTER = "R";
    private static final String DROP = "D";

    private final Path file;

    public RegistryJournal(Path file) {
        this.file = file;
    }

    /** One surviving registration, as replayed. */
    public record Entry(
            String name,
            String sql,
            List<Integer> keyColumns,
            String owner,
            Retention retention,
            List<String> parameters) {

        public Entry {
            keyColumns = List.copyOf(keyColumns);
            parameters = List.copyOf(parameters);
        }
    }

    /**
     * Appends a registration.
     *
     * <p>Each record is flushed before this returns. A registration acknowledged to a client and then
     * lost in a page cache is worse than one that failed outright: the client believes it is
     * registered and nothing will tell it otherwise.
     */
    public void recordRegistration(
            String name,
            String sql,
            List<Integer> keyColumns,
            String owner,
            Retention retention,
            List<String> parameters) {
        List<String> fields = new ArrayList<>();
        fields.add(REGISTER);
        fields.add(name);
        fields.add(sql);
        fields.add(joinInts(keyColumns));
        fields.add(owner == null ? "" : owner);
        fields.add(encodeRetention(retention));
        fields.addAll(parameters);
        append(fields);
    }

    /** Appends a drop, so a query dropped before a restart stays dropped after it. */
    public void recordDrop(String name) {
        append(List.of(DROP, name));
    }

    /**
     * Replays the journal.
     *
     * @return the registrations that are still live, in the order they were first registered. A name
     *     registered, dropped and registered again appears once, with its latest definition
     */
    public List<Entry> replay() {
        if (!Files.exists(file)) {
            return List.of();
        }
        // Insertion-ordered so recovery re-registers in the order the queries were created, which
        // keeps a shared computation's first registrant stable across restarts.
        Map<String, Entry> live = new LinkedHashMap<>();
        byte[] all;
        try {
            all = Files.readAllBytes(file);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read the registry journal at " + file, failure);
        }
        ByteBuffer buffer = ByteBuffer.wrap(all);
        int record = 0;
        while (buffer.remaining() > 4) {
            record++;
            int length = buffer.getInt();
            if (length < 0 || length > buffer.remaining()) {
                // A half-written final record is the expected result of a crash during an append,
                // not a corrupt journal. Everything before it is intact and is what we keep.
                break;
            }
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            List<String> fields;
            try {
                fields = ControlWire.decode(bytes);
            } catch (RuntimeException unreadable) {
                throw new PravahaException(
                        RegistryErrors.JOURNAL_UNREADABLE,
                        "record " + record + " of the registry journal at " + file + " cannot be decoded. "
                                + "Earlier records are fine; this one is not, and replaying past it would "
                                + "silently drop whatever it said",
                        unreadable);
            }
            apply(live, fields, record);
        }
        return List.copyOf(live.values());
    }

    private void apply(Map<String, Entry> live, List<String> fields, int record) {
        if (fields.isEmpty()) {
            return;
        }
        String kind = fields.get(0);
        if (DROP.equals(kind) && fields.size() >= 2) {
            live.remove(fields.get(1));
            return;
        }
        if (!REGISTER.equals(kind) || fields.size() < 6) {
            throw new PravahaException(
                    RegistryErrors.JOURNAL_UNREADABLE,
                    "record " + record + " of the registry journal at " + file + " is a '" + kind + "' with "
                            + fields.size() + " fields, which this version does not understand. Refusing rather "
                            + "than skipping it: a skipped registration is a view a client expects and will not find");
        }
        String name = fields.get(1);
        Entry entry = new Entry(
                name,
                fields.get(2),
                parseInts(fields.get(3)),
                fields.get(4),
                decodeRetention(fields.get(5)),
                fields.subList(6, fields.size()));
        // Re-registering a live name replaces it, which is what the registry itself does.
        live.remove(name);
        live.put(name, entry);
    }

    private void append(List<String> fields) {
        byte[] payload = ControlWire.encode(fields);
        ByteBuffer buffer = ByteBuffer.allocate(4 + payload.length);
        buffer.putInt(payload.length);
        buffer.put(payload);
        buffer.flip();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Owner-only, before the first write. The javadoc above has always said this file holds
            // account numbers and customer ids and should be permissioned like data -- and then the
            // code created it at whatever the umask happened to be, which on most systems is
            // world-readable. An instruction to the operator is not a control; this is.
            restrictToOwner(file);
            try (FileChannel channel = FileChannel.open(
                    file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                // Acknowledging a registration that is only in a page cache is worse than failing it.
                channel.force(true);
            }
        } catch (IOException failure) {
            throw new PravahaException(
                    RegistryErrors.JOURNAL_UNWRITABLE,
                    "cannot append to the registry journal at " + file
                            + ". The registration would be lost at the next restart, so it is refused now "
                            + "rather than acknowledged and forgotten",
                    failure);
        }
    }

    /**
     * Narrows a file to its owner, where the filesystem supports it.
     *
     * <p>Best effort on purpose. A POSIX permission cannot be set on every filesystem -- Windows,
     * and some network mounts -- and refusing to journal at all on those would trade a
     * confidentiality gap for an availability one. Where it cannot be applied it is reported, so the
     * gap is visible rather than assumed closed.
     */
    private static void restrictToOwner(Path target) {
        try {
            if (!Files.exists(target)) {
                Files.createFile(target);
            }
            if (target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException | UnsupportedOperationException cannot) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "could not restrict permissions on " + target + " (" + cannot
                            + "); it holds query text and bound parameter values, so check them by hand");
        }
    }

    /** Rewrites the journal with only what is live, discarding the history of drops. */
    public synchronized void compact(List<Entry> live) {
        Path temporary = file.resolveSibling(file.getFileName() + ".compacting");
        try {
            Files.deleteIfExists(temporary);
            RegistryJournal rewritten = new RegistryJournal(temporary);
            for (Entry entry : live) {
                rewritten.recordRegistration(
                        entry.name(),
                        entry.sql(),
                        entry.keyColumns(),
                        entry.owner(),
                        entry.retention(),
                        entry.parameters());
            }
            // Atomic: a crash here leaves either the old journal or the new one, never a partial
            // rewrite, and both are complete descriptions of what is registered.
            Files.move(
                    temporary,
                    file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            throw new PravahaException(
                    RegistryErrors.JOURNAL_UNWRITABLE, "cannot compact the registry journal at " + file, failure);
        }
    }

    public Path file() {
        return file;
    }

    private static String joinInts(List<Integer> values) {
        StringBuilder joined = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                joined.append(',');
            }
            joined.append(values.get(index));
        }
        return joined.toString();
    }

    private static List<Integer> parseInts(String encoded) {
        List<Integer> values = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return values;
        }
        for (String part : encoded.split(",")) {
            values.add(Integer.parseInt(part.trim()));
        }
        return values;
    }

    private static String encodeRetention(Retention retention) {
        if (retention == null) {
            return "";
        }
        return retention.maxAge() == null
                ? "forever"
                : Long.toString(retention.maxAge().toMillis());
    }

    private static Retention decodeRetention(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return Retention.DEFAULT;
        }
        if ("forever".equals(encoded)) {
            return Retention.forever();
        }
        return Retention.ofAge(Duration.ofMillis(Long.parseLong(encoded)));
    }

    /** Encodes a bound parameter value as text, so a journal is readable without the engine. */
    public static String encodeParameter(Object value) {
        if (value == null) {
            return "n:";
        }
        if (value instanceof byte[] bytes) {
            return "b:" + Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short) {
            return "i:" + value;
        }
        if (value instanceof Double || value instanceof Float) {
            return "d:" + value;
        }
        if (value instanceof Boolean) {
            return "z:" + value;
        }
        return "s:" + value;
    }

    /** Reverses {@link #encodeParameter}. */
    public static Object decodeParameter(String encoded) {
        if (encoded == null || encoded.length() < 2) {
            return null;
        }
        String body = encoded.substring(2);
        return switch (encoded.charAt(0)) {
            case 'n' -> null;
            case 'i' -> Long.parseLong(body);
            case 'd' -> Double.parseDouble(body);
            case 'z' -> Boolean.parseBoolean(body);
            case 'b' -> Base64.getDecoder().decode(body);
            default -> body;
        };
    }

    private static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
