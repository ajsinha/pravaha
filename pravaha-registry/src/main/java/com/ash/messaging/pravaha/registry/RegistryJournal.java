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

    /**
     * A live name whose computation keeps a lane of its own ({@code lane = 'dedicated'}): the name,
     * then {@code dedicated}.
     *
     * <p>Follows its {@code R}, {@code W} or {@code C} record as {@code X} does, in the same append
     * when written with a registration, and like {@code X} applies to the name's current
     * registration only. A kind of its own, so a build that predates it refuses it by name rather
     * than replaying the query onto a shared lane its registrant asked it never to share.
     */
    private static final String LANE = "L";

    /**
     * A live name the registry now keys by another engine name (ADR-060): the old name, then the new.
     *
     * <p>Written once, at the first recovery after per-tenant names, for a registration another tenant
     * than the default made under its bare name: from here it is its tenant's {@code tenant.default.name}.
     * The registration keeps the checkpoint directory its old name implied -- nothing is moved -- and its
     * indexes, lane and pending replacement travel with it. A kind of its own, so a build that predates
     * per-tenant names refuses it by name rather than replaying the query under a name it no longer has.
     */
    private static final String RENAMED = "N";

    /**
     * A live name whose computation checkpoints into a directory other than the one its name implies,
     * from here on (SHAREDLOSS-1): the name, then the directory.
     *
     * <p>Two names that ask the same question share one computation, which checkpoints into the
     * directory of the name that started it. Each name's record implies a directory of its own, so
     * when the first name was dropped the survivor came back from a restart reading its own, empty,
     * directory, and the computation's state was lost. The drop writes this beside its {@code D}, in
     * the same append, for every surviving name. Applied in place, so the name keeps its position, its
     * indexes and its lane. A kind of its own, so a build that predates it refuses it by name rather
     * than restoring the survivor from nothing.
     */
    private static final String MOVED = "M";

    private final Path file;

    /**
     * Whether this instance has made sure the file ends on a record boundary before appending to it
     * (JOURNALMID-1). An append after a torn final record used to land behind the torn bytes, where
     * the next start read it as part of them and lost it -- and every registration after it.
     */
    private boolean tailChecked;

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
            List<Integer> indexed,
            boolean dedicatedLane) {

        public Entry {
            keyColumns = List.copyOf(keyColumns);
            parameters = List.copyOf(parameters);
            indexed = indexed == null ? List.of() : List.copyOf(indexed);
            sink = sink == null || sink.isEmpty() ? null : sink;
            checkpointDirectory =
                    checkpointDirectory == null || checkpointDirectory.isEmpty() ? null : checkpointDirectory;
        }

        /** A registration on whatever lane the node's lane-sharing mode gives it. */
        public Entry(
                String name,
                String sql,
                List<Integer> keyColumns,
                String owner,
                Retention retention,
                List<String> parameters,
                String sink,
                String checkpointDirectory,
                List<Integer> indexed) {
            this(name, sql, keyColumns, owner, retention, parameters, sink, checkpointDirectory, indexed, false);
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
            return new Entry(
                    name,
                    sql,
                    keyColumns,
                    owner,
                    retention,
                    parameters,
                    sink,
                    checkpointDirectory,
                    columns,
                    dedicatedLane);
        }

        /** This registration under another name, checkpointing into {@code directory} (ADR-060). */
        public Entry renamedTo(String newName, String directory) {
            return new Entry(
                    newName, sql, keyColumns, owner, retention, parameters, sink, directory, indexed, dedicatedLane);
        }

        /** This registration, on a lane of its own whatever the node's lane-sharing mode. */
        public Entry withDedicatedLane() {
            return new Entry(
                    name, sql, keyColumns, owner, retention, parameters, sink, checkpointDirectory, indexed, true);
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
        recordRegistration(name, sql, keyColumns, owner, retention, parameters, sink, indexed, false);
    }

    /**
     * Appends a registration, the equality indexes its view keeps and whether it keeps a lane of its
     * own, in one write.
     */
    public void recordRegistration(
            String name,
            String sql,
            List<Integer> keyColumns,
            String owner,
            Retention retention,
            BoundParameters parameters,
            String sink,
            List<Integer> indexed,
            boolean dedicatedLane) {
        List<String> encoded = new ArrayList<>();
        for (int index = 0; index < parameters.size(); index++) {
            encoded.add(encodeParameter(parameters.at(index)));
        }
        append(
                registration(name, sql, keyColumns, owner, retention, encoded, sink),
                indexRecord(name, indexed),
                dedicatedLane ? laneRecord(name) : null);
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

    /** As the overload above, with what the registration declares beyond its SQL. */
    void recordRegistration(
            String name,
            String sql,
            List<Integer> keyColumns,
            String owner,
            Retention retention,
            BoundParameters parameters,
            String sink,
            Declaring declared) {
        recordRegistration(
                name,
                sql,
                keyColumns,
                owner,
                retention,
                parameters,
                sink,
                declared.indexes(),
                declared.dedicatedLane());
    }

    /** Appends that {@code name}'s current registration keeps a lane of its own. */
    public void recordDedicatedLane(String name) {
        append(laneRecord(name));
    }

    private static List<String> laneRecord(String name) {
        return List.of(LANE, name, "dedicated");
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

    /**
     * Appends a drop of {@code name} whose computation lives on under {@code survivors}, which from here
     * checkpoint into {@code directory} (SHAREDLOSS-1); one write, so a crash keeps both or neither.
     */
    public void recordDrop(String name, java.util.Collection<String> survivors, String directory) {
        List<List<String>> records = new ArrayList<>();
        records.add(List.of(DROP, name));
        if (directory != null) {
            for (String survivor : survivors) {
                records.add(List.of(MOVED, survivor, directory));
            }
        }
        @SuppressWarnings({"unchecked", "rawtypes"}) // Java makes no generic array: a raw one, cast
        List<String>[] all = records.toArray(new List[0]);
        append(all);
    }

    /** Appends that the live name {@code from} is {@code to} from now on (ADR-060); see {@link #RENAMED}. */
    public void recordRenamed(String from, String to) {
        append(List.of(RENAMED, from, to));
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

        /** This replacement, of the name its serving version is keyed by now (ADR-060). */
        public Pending renamedTo(String newName) {
            return new Pending(newName, sql, keyColumns, owner, retention, sink, options, checkpointDirectory);
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
        append(cutover(entry));
    }

    private static List<String> cutover(Entry entry) {
        List<String> fields = new ArrayList<>(List.of(
                CUTOVER,
                entry.name(),
                entry.sql(),
                joinInts(entry.keyColumns()),
                entry.owner() == null ? "" : entry.owner(),
                encodeRetention(entry.retention()),
                entry.sink() == null ? "" : entry.sink(),
                entry.checkpointDirectory() == null ? "" : entry.checkpointDirectory()));
        // Bound values trail the eight fields (ADR-060: a registration with a directory of its own is a
        // C, and keeps them); a build that reads eight reads exactly what it always did.
        fields.addAll(entry.parameters());
        return fields;
    }

    /**
     * Appends a registration that checkpoints into {@code directory} rather than the one its name
     * implies, with what it declares, in one write (ADR-060).
     *
     * <p>A default-tenant name whose directory another tenant's registration from before per-tenant
     * names still occupies: written as a {@code C} record, the one that carries a directory, so a restart
     * restores each from its own.
     */
    void recordRegistrationIn(
            String directory,
            String name,
            String sql,
            List<Integer> keyColumns,
            String owner,
            Retention retention,
            BoundParameters parameters,
            String sink,
            Declaring declared) {
        List<String> encoded = new ArrayList<>();
        for (int index = 0; index < parameters.size(); index++) {
            encoded.add(encodeParameter(parameters.at(index)));
        }
        append(
                cutover(new Entry(name, sql, keyColumns, owner, retention, encoded, sink, directory)),
                indexRecord(name, declared.indexes()),
                declared.dedicatedLane() ? laneRecord(name) : null);
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
        Scan scan = scan(all);
        for (int i = 0; i < scan.records().size(); i++) {
            apply(live, pending, scan.records().get(i), i + 1);
        }
        if (scan.tornAt() >= 0) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "the registry journal at " + file + " ends in a half-written record ("
                            + (all.length - scan.tornAt())
                            + " bytes from offset " + scan.tornAt() + "), the trace of a crash during an append; the "
                            + scan.records().size() + " complete records before it are replayed, and the next append "
                            + "cuts the torn bytes off first");
        }
        return new Replayed(List.copyOf(live.values()), List.copyOf(pending.values()));
    }

    /**
     * The journal's complete records, and where a torn final record begins ({@code -1} for none).
     *
     * @param tornAt the offset of a final record a crash left half-written, which is everything from
     *     there to the end of the file
     */
    private record Scan(List<List<String>> records, long tornAt) {}

    /**
     * Reads every record, telling a torn tail from damage in the middle (JOURNALMID-1).
     *
     * <p>A record is a four-byte length and that many bytes of {@link ControlWire}. A crash during an
     * append leaves a prefix of the last write: a length with too few bytes behind it, or a few bytes
     * of a length. That is expected and harmless -- everything before it is intact. But a length
     * damaged in the middle of the file reads exactly the same way, as one that runs past the end,
     * and treating it as a torn tail dropped every record after it at every start, silently, along
     * with every registration appended after it since. So a bad length is a torn tail only when no
     * complete record follows it: the bytes after it are searched for one, recognised by a plausible
     * length followed by {@link ControlWire}'s four-byte magic and a payload that decodes. Finding
     * one means valid records follow the damage, and the start is refused, naming both offsets,
     * rather than replaying a journal with a hole in it.
     */
    private Scan scan(byte[] all) {
        ByteBuffer buffer = ByteBuffer.wrap(all);
        List<List<String>> records = new ArrayList<>();
        int record = 0;
        while (buffer.hasRemaining()) {
            record++;
            int start = buffer.position();
            boolean partialHeader = buffer.remaining() < 4;
            int length = partialHeader ? -1 : buffer.getInt();
            if (length < 0 || length > buffer.remaining()) {
                int resumes = nextRecordAfter(all, start + 1);
                if (resumes < 0) {
                    return new Scan(records, start);
                }
                throw new PravahaException(
                        RegistryErrors.JOURNAL_UNREADABLE,
                        "record " + record + " of the registry journal at " + file + ", at byte offset " + start
                                + ", has a damaged length (" + (partialHeader ? "cut short" : String.valueOf(length))
                                + ", with " + Math.max(0, all.length - start - 4)
                                + " bytes after it), and a complete record "
                                + "follows at byte offset " + resumes + ". This is damage in the middle of the "
                                + "journal, not a write a crash cut short, and replaying up to it would silently "
                                + "drop every registration after it. Nothing has been changed. To recover, restore "
                                + "the journal from a backup, or move it aside and re-register the queries -- the "
                                + "records before offset " + start + " are intact and readable");
            }
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            try {
                records.add(ControlWire.decode(bytes));
            } catch (RuntimeException unreadable) {
                throw new PravahaException(
                        RegistryErrors.JOURNAL_UNREADABLE,
                        "record " + record + " of the registry journal at " + file + " (byte offset " + start
                                + ") cannot be decoded. Earlier records are fine; this one is not, and replaying "
                                + "past it would silently drop whatever it said",
                        unreadable);
            }
        }
        return new Scan(records, -1);
    }

    /**
     * The offset of the first complete record starting at or after {@code from}, or {@code -1}.
     *
     * <p>A candidate is a non-negative length that fits the file, followed by {@link ControlWire}'s
     * magic and a payload that decodes. A torn tail is a prefix of one write, so the only record
     * boundaries inside it are its own -- none of which can be complete, or the tail would not be
     * torn.
     */
    private static int nextRecordAfter(byte[] all, int from) {
        ByteBuffer buffer = ByteBuffer.wrap(all);
        for (int at = from; at + 4 + 9 <= all.length; at++) {
            int length = buffer.getInt(at);
            if (length < 9 || length > all.length - at - 4) {
                continue;
            }
            byte[] payload = java.util.Arrays.copyOfRange(all, at + 4, at + 4 + length);
            if (!ControlWire.isOurs(payload)) {
                continue;
            }
            try {
                if (!ControlWire.decode(payload).isEmpty()) {
                    return at;
                }
            } catch (RuntimeException notARecord) {
                // The magic by chance; keep looking.
            }
        }
        return -1;
    }

    /**
     * Cuts a torn final record off before the first append (JOURNALMID-1), so what is appended
     * starts on a record boundary; refuses to append to a journal damaged in the middle.
     */
    private void cutTornTail() throws IOException {
        if (tailChecked) {
            return;
        }
        if (Files.exists(file)) {
            Scan scan = scan(Files.readAllBytes(file));
            if (scan.tornAt() >= 0) {
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                    channel.truncate(scan.tornAt());
                    channel.force(true);
                }
                LOG.log(
                        System.Logger.Level.WARNING,
                        "cut the half-written final record off the registry journal at " + file + " (from byte offset "
                                + scan.tornAt() + ") before appending to it");
            }
        }
        tailChecked = true;
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
        if (LANE.equals(kind) && fields.size() >= 3) {
            Entry laneEntry = live.get(fields.get(1));
            if (laneEntry != null && "dedicated".equals(fields.get(2))) {
                live.put(laneEntry.name(), laneEntry.withDedicatedLane());
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
                            fields.subList(8, fields.size()),
                            fields.get(6),
                            fields.get(7)));
            pending.remove(cutName);
            return;
        }
        if (RENAMED.equals(kind) && fields.size() >= 3) {
            renamed(live, pending, fields.get(1), fields.get(2));
            return;
        }
        if (MOVED.equals(kind) && fields.size() >= 3) {
            // In place: a LinkedHashMap keeps a key's position when its value is replaced.
            Entry moved = live.get(fields.get(1));
            if (moved != null) {
                live.put(moved.name(), moved.renamedTo(moved.name(), fields.get(2)));
            }
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

    /**
     * Moves a live name and its pending replacement to {@code to}, in place, so the registration keeps
     * its position in the order recovery replays in -- which is the order a chain's upstream comes back
     * before its dependants. It keeps the directory its old name implied, where its state is.
     */
    private static void renamed(Map<String, Entry> live, Map<String, Pending> pending, String from, String to) {
        Entry moved = live.get(from);
        if (moved != null) {
            String directory = moved.directory().orElse(QueryCheckpoints.directoryFor(from));
            Map<String, Entry> reordered = new LinkedHashMap<>();
            live.forEach((name, entry) -> {
                if (name.equals(from)) {
                    reordered.put(to, moved.renamedTo(to, directory));
                } else if (!name.equals(to)) {
                    reordered.put(name, entry);
                }
            });
            live.clear();
            live.putAll(reordered);
        }
        Pending replacing = pending.remove(from);
        if (replacing != null) {
            pending.put(to, replacing.renamedTo(to));
        }
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
            cutTornTail();
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
                if (entry.dedicatedLane()) {
                    rewritten.recordDedicatedLane(entry.name());
                }
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
        for (String part : encoded.split(",", -1)) {
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
