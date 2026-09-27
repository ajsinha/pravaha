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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.common.io.SensitiveFiles;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;

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

    /**
     * A registration that also writes to a named sink (ADR-043): the {@code R} fields with the sink
     * name after the query name.
     *
     * <p>A kind of its own rather than a field appended to {@code R}, because {@code R} already
     * ends in a variable-length list of bound parameters and has no room after them. It also means a
     * build that predates sinks refuses this record by name instead of replaying the query without
     * its sink -- which would look like a successful recovery while the table it fed stopped moving.
     */
    private static final String REGISTER_WRITING = "W";

    /**
     * A replacement that has been started and has not yet cut over, rolled back or been abandoned
     * (ADR-046): the same fields as a registration, plus the options it was started with and the
     * directory its shadow checkpoints into.
     *
     * <p>Recorded because a backfill can run for hours and a node can restart during one. The
     * <em>name</em> still belongs to the version that is serving -- that is what makes this safe to
     * replay: a node that comes back up puts the running version back first and then starts the
     * replacement again, and a node of an older build refuses the record by name rather than
     * silently forgetting that a replacement was in flight.
     */
    private static final String REPLACEMENT = "P";

    /**
     * A cutover or a rollback: from here the name's registration is this one (ADR-046). It also
     * ends whatever replacement was pending for the name, in the same record -- two records could
     * leave a restart with the new version serving and a replacement of it still pending.
     */
    private static final String CUTOVER = "C";

    /** A pending replacement that ended without moving the name: abandoned, or failed. */
    private static final String REPLACEMENT_ENDED = "E";

    /**
     * The equality indexes a live name's view keeps (ADR-055): the name, then the output ordinals.
     *
     * <p>Follows the {@code R}, {@code W} or {@code C} record it belongs to, in the same append when
     * it is written with a registration, and applies to the name's current registration only -- a
     * later {@code R} or {@code C} for the name starts without indexes until another {@code X} says
     * otherwise. A kind of its own, so a build that predates indexes refuses it by name rather than
     * replaying the query without them, which would answer correctly and scan where it was told
     * it would probe.
     */
    private static final String INDEX = "X";

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
            List<String> parameters,
            String sink,
            String checkpointDirectory,
            List<Integer> indexed) {

        public Entry {
            keyColumns = List.copyOf(keyColumns);
            parameters = List.copyOf(parameters);
            indexed = indexed == null ? List.of() : List.copyOf(indexed);
            sink = sink == null || sink.isEmpty() ? null : sink;
            checkpointDirectory =
                    checkpointDirectory == null || checkpointDirectory.isEmpty() ? null : checkpointDirectory;
        }

        /** A registration keeping no equality index. */
        public Entry(
                String name,
                String sql,
                List<Integer> keyColumns,
                String owner,
                Retention retention,
                List<String> parameters,
                String sink,
                String checkpointDirectory) {
            this(name, sql, keyColumns, owner, retention, parameters, sink, checkpointDirectory, List.of());
        }

        /** This registration, keeping equality indexes over these output columns. */
        public Entry withIndexed(List<Integer> columns) {
            return new Entry(name, sql, keyColumns, owner, retention, parameters, sink, checkpointDirectory, columns);
        }

        /** A registration checkpointing into the directory its name implies. */
        public Entry(
                String name,
                String sql,
                List<Integer> keyColumns,
                String owner,
                Retention retention,
                List<String> parameters,
                String sink) {
            this(name, sql, keyColumns, owner, retention, parameters, sink, null);
        }

        /**
         * Where this registration checkpoints, when it is not the directory its name implies.
         *
         * <p>A version that took the name at a cutover keeps the directory it backfilled into.
         * Moving the files at the cutover would leave a window in which a crash has the name
         * pointing at a directory holding the <em>other</em> version's state, and restoring one
         * version's operator state into another's plan is the kind of wrong nothing reports.
         */
        public java.util.Optional<String> directory() {
            return java.util.Optional.ofNullable(checkpointDirectory);
        }

        /** A registration that writes only to its view. */
        public Entry(
                String name,
                String sql,
                List<Integer> keyColumns,
                String owner,
                Retention retention,
                List<String> parameters) {
            this(name, sql, keyColumns, owner, retention, parameters, null);
        }

        /** The sink this registration writes to, or empty when it writes only to its view. */
        public java.util.Optional<String> sinkName() {
            return java.util.Optional.ofNullable(sink);
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
        recordRegistration(name, sql, keyColumns, owner, retention, parameters, null);
    }

    /**
     * Appends a registration whose bound values are still values, encoding them on the way.
     *
     * <p>Here rather than at the call site so that a parameter is encoded by the class that decodes
     * it. The sink goes in the same record: two appends could leave a restart with the query and
     * without its sink, which recovers "successfully" while the table the query fed stops moving.
     */
    public void recordRegistration(
            String name,
            String sql,
            List<Integer> keyColumns,
            String owner,
            Retention retention,
            BoundParameters parameters,
            String sink) {
        recordRegistration(name, sql, keyColumns, owner, retention, parameters, sink, List.of());
    }

    /**
     * Appends a registration and the equality indexes its view keeps, in one write: two appends
     * could leave a restart with the query and without its indexes.
     */
    public void recordRegistration(
            String name,
            String sql,
            List<Integer> keyColumns,
            String owner,
            Retention retention,
            BoundParameters parameters,
            String sink,
            List<Integer> indexed) {
        List<String> encoded = new ArrayList<>();
        for (int index = 0; index < parameters.size(); index++) {
            encoded.add(encodeParameter(parameters.at(index)));
        }
        append(registration(name, sql, keyColumns, owner, retention, encoded, sink), indexRecord(name, indexed));
    }

    /**
     * Appends a registration that writes to {@code sink} as well as its view, or to its view alone
     * when {@code sink} is null.
     */
    public void recordRegistration(
            String name,
            String sql,
            List<Integer> keyColumns,
            String owner,
            Retention retention,
            List<String> parameters,
            String sink) {
        append(registration(name, sql, keyColumns, owner, retention, parameters, sink));
    }

    /** Appends the equality indexes {@code name}'s current registration keeps, replacing any before. */
    public void recordIndexes(String name, List<Integer> indexed) {
        append(indexRecord(name, indexed));
    }

    private static List<String> indexRecord(String name, List<Integer> indexed) {
        return indexed == null || indexed.isEmpty() ? null : List.of(INDEX, name, joinInts(indexed));
    }

    private static List<String> registration(
            String name,
            String sql,
            List<Integer> keyColumns,
            String owner,
            Retention retention,
            List<String> parameters,
            String sink) {
        List<String> fields = new ArrayList<>();
        fields.add(sink == null ? REGISTER : REGISTER_WRITING);
        fields.add(name);
        if (sink != null) {
            fields.add(sink);
        }
        fields.add(sql);
        fields.add(joinInts(keyColumns));
        fields.add(owner == null ? "" : owner);
        fields.add(encodeRetention(retention));
        fields.addAll(parameters);
        return fields;
    }

    /** Appends a drop, so a query dropped before a restart stays dropped after it. */
    public void recordDrop(String name) {
        append(List.of(DROP, name));
    }

    /** One replacement that had been started and had not finished when the journal was written. */
    public record Pending(
            String name,
            String sql,
            List<Integer> keyColumns,
            String owner,
            Retention retention,
            String sink,
            String options,
            String checkpointDirectory) {

        public Pending {
            keyColumns = List.copyOf(keyColumns);
            sink = sink == null || sink.isEmpty() ? null : sink;
        }

        public java.util.Optional<String> sinkName() {
            return java.util.Optional.ofNullable(sink);
        }
    }

    /** Appends a started replacement (ADR-046). The name still belongs to the version serving it. */
    public void recordReplacementStarted(Pending pending) {
        append(List.of(
                REPLACEMENT,
                pending.name(),
                pending.sql(),
                joinInts(pending.keyColumns()),
                pending.owner() == null ? "" : pending.owner(),
                encodeRetention(pending.retention()),
                pending.sink() == null ? "" : pending.sink(),
                pending.options() == null ? "" : pending.options(),
                pending.checkpointDirectory() == null ? "" : pending.checkpointDirectory()));
    }

    /**
     * Appends a cutover or a rollback: the name's registration is {@code entry} from now on, and
     * whatever replacement was pending for it is over.
     */
    public void recordCutover(Entry entry) {
        append(List.of(
                CUTOVER,
                entry.name(),
                entry.sql(),
                joinInts(entry.keyColumns()),
                entry.owner() == null ? "" : entry.owner(),
                encodeRetention(entry.retention()),
                entry.sink() == null ? "" : entry.sink(),
                entry.checkpointDirectory() == null ? "" : entry.checkpointDirectory()));
    }

    /** Appends the end of a replacement that never took the name: abandoned, or failed. */
    public void recordReplacementEnded(String name) {
        append(List.of(REPLACEMENT_ENDED, name));
    }

    /** What a replay found: the registrations that are live, and the replacements still pending. */
    public record Replayed(List<Entry> live, List<Pending> pending) {
        public Replayed {
            live = List.copyOf(live);
            pending = List.copyOf(pending);
        }
    }

    /**
     * Replays the journal.
     *
     * @return the registrations that are still live, in the order they were first registered. A name
     *     registered, dropped and registered again appears once, with its latest definition
     */
    public List<Entry> replay() {
        return replayAll().live();
    }

    /** Replays the journal, keeping both the live registrations and the replacements in flight. */
    public Replayed replayAll() {
        if (!Files.exists(file)) {
            return new Replayed(List.of(), List.of());
        }
        // Insertion-ordered so recovery re-registers in the order the queries were created, which
        // keeps a shared computation's first registrant stable across restarts.
        Map<String, Entry> live = new LinkedHashMap<>();
        Map<String, Pending> pending = new LinkedHashMap<>();
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
            apply(live, pending, fields, record);
        }
        return new Replayed(List.copyOf(live.values()), List.copyOf(pending.values()));
    }

    private void apply(Map<String, Entry> live, Map<String, Pending> pending, List<String> fields, int record) {
        if (fields.isEmpty()) {
            return;
        }
        String kind = fields.get(0);
        if (DROP.equals(kind) && fields.size() >= 2) {
            live.remove(fields.get(1));
            pending.remove(fields.get(1));
            return;
        }
        if (INDEX.equals(kind) && fields.size() >= 3) {
            Entry indexedEntry = live.get(fields.get(1));
            if (indexedEntry != null) {
                live.put(indexedEntry.name(), indexedEntry.withIndexed(parseInts(fields.get(2))));
            }
            return;
        }
        if (REPLACEMENT_ENDED.equals(kind) && fields.size() >= 2) {
            pending.remove(fields.get(1));
            return;
        }
        if (REPLACEMENT.equals(kind) && fields.size() >= 9) {
            pending.put(
                    fields.get(1),
                    new Pending(
                            fields.get(1),
                            fields.get(2),
                            parseInts(fields.get(3)),
                            fields.get(4),
                            decodeRetention(fields.get(5)),
                            fields.get(6),
                            fields.get(7),
                            fields.get(8)));
            return;
        }
        if (CUTOVER.equals(kind) && fields.size() >= 8) {
            String cutName = fields.get(1);
            live.remove(cutName);
            live.put(
                    cutName,
                    new Entry(
                            cutName,
                            fields.get(2),
                            parseInts(fields.get(3)),
                            fields.get(4),
                            decodeRetention(fields.get(5)),
                            List.of(),
                            fields.get(6),
                            fields.get(7)));
            pending.remove(cutName);
            return;
        }
        // W carries the sink name second; lifting it out leaves exactly R's fields.
        String sink = null;
        if (REGISTER_WRITING.equals(kind) && fields.size() >= 7) {
            sink = fields.get(2);
            fields = new ArrayList<>(fields);
            fields.remove(2);
            kind = REGISTER;
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
                fields.subList(6, fields.size()),
                sink);
        // Re-registering a live name replaces it, which is what the registry itself does.
        live.remove(name);
        live.put(name, entry);
    }

    /** Appends records in one write and one force; a null record is skipped. */
    @SafeVarargs
    private void append(List<String>... records) {
        List<byte[]> payloads = new ArrayList<>();
        int size = 0;
        for (List<String> fields : records) {
            if (fields != null) {
                byte[] payload = ControlWire.encode(fields);
                payloads.add(payload);
                size += 4 + payload.length;
            }
        }
        if (payloads.isEmpty()) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        for (byte[] payload : payloads) {
            buffer.putInt(payload.length);
            buffer.put(payload);
        }
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
            SensitiveFiles.createOwnerOnly(file);
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

    /** Rewrites the journal with only what is live, discarding the history of drops. */
    public synchronized void compact(List<Entry> live) {
        compact(live, replayAll().pending());
    }

    /** Rewrites the journal with what is live and what is still in flight, and nothing else. */
    public synchronized void compact(List<Entry> live, List<Pending> pending) {
        Path temporary = file.resolveSibling(file.getFileName() + ".compacting");
        try {
            Files.deleteIfExists(temporary);
            RegistryJournal rewritten = new RegistryJournal(temporary);
            for (Entry entry : live) {
                if (entry.checkpointDirectory() != null) {
                    // A version that took its name at a cutover: the directory travels with the
                    // registration, so a compaction that wrote it back as a plain R record would
                    // point the name at a directory holding nothing.
                    rewritten.recordCutover(entry);
                } else {
                    rewritten.recordRegistration(
                            entry.name(),
                            entry.sql(),
                            entry.keyColumns(),
                            entry.owner(),
                            entry.retention(),
                            entry.parameters(),
                            entry.sink());
                }
                // After the record it belongs to, since an R or a C clears what the name indexed.
                rewritten.recordIndexes(entry.name(), entry.indexed());
            }
            for (Pending each : pending) {
                rewritten.recordReplacementStarted(each);
            }
            // Atomic: a crash here leaves either the old journal or the new one, never a partial
            // rewrite, and both are complete descriptions of what is registered.
            Files.move(
                    temporary,
                    file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            // The rename is atomic for a reader and not durable until the directory entry is on
            // disk. Without this a compaction can survive as content with no name pointing at it.
            Path parent = file.getParent();
            if (parent != null) {
                SensitiveFiles.syncDirectory(parent);
            }
        } catch (IOException failure) {
            // A half-written rewrite left behind would be replayed as if it were the journal.
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "compaction failed and " + temporary + " could not be removed; delete it by hand "
                                + "before restarting, or it will be mistaken for the journal");
            }
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
}
