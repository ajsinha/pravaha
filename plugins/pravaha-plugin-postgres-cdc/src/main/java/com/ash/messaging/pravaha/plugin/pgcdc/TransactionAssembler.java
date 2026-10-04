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
package com.ash.messaging.pravaha.plugin.pgcdc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * Turns decoded {@code pgoutput} messages into whole transactions of weighted rows.
 *
 * <p>The translation is the one {@code docs/guides/CONNECTORS.md} section 5 works through, and it is exact:
 *
 * <ul>
 *   <li>an {@code Insert} is the new row at {@code +1};
 *   <li>a {@code Delete} is the old row at {@code -1};
 *   <li>an {@code Update} is the old row at {@code -1} and then the new row at {@code +1}.
 * </ul>
 *
 * <p>Every one of those needs the <em>whole</em> old row, which PostgreSQL sends only under {@code
 * REPLICA IDENTITY FULL}. Open refuses a table without it; this refuses a before-image that arrives
 * key-only anyway (the identity was changed while streaming), because retracting {@code (42)} from a
 * view holding {@code (42, "silver")} retracts nothing and leaves silver at 900 for ever.
 *
 * <p>An unchanged TOAST value arrives in the new row as a placeholder. It is filled from the same
 * column of the old row -- which under {@code FULL} holds it in full -- and never written as a value.
 *
 * <p>Nothing leaves before {@code Commit}: a rolled-back transaction is never sent by PostgreSQL, and
 * a committed one is handed on whole. Pure, so every path is testable without a server.
 */
final class TransactionAssembler {

    /** Microseconds from the Unix epoch to PostgreSQL's, 2000-01-01. */
    private static final long POSTGRES_EPOCH_MICROS = 946_684_800_000_000L;

    private final CdcOptions options;
    private final CdcSchema.Mapping mapping;
    private final int tableOid;
    private final Consumer<CdcTransaction> out;
    private final Consumer<String> onMessage;

    /** Stream field to column index in the current Relation message, or null before one arrived. */
    private int @Nullable [] columnOf;

    private boolean inTransaction;
    private long commitMicros;
    private List<CdcTransaction.Change> changes = new ArrayList<>();
    private @Nullable PravahaException failure;

    /** The end of the last transaction handed on: anything ending at or before it is a repeat. */
    private long delivered;

    private long partialEnd;
    private long partialDelivered;

    /** How many unchanged TOAST placeholders were filled from a before-image. Read by tests. */
    private final java.util.concurrent.atomic.AtomicLong carriedForward = new java.util.concurrent.atomic.AtomicLong();

    /** While an initial snapshot is unfinished: which changes before its point the engine keeps. */
    private final @Nullable CatchUp catchUp;

    /** Key column to column index in the current Relation message, when {@link #catchUp} needs keys. */
    private int @Nullable [] keyOf;

    /**
     * The log between a resumed position and an initial snapshot's consistent point, seen through the
     * snapshot's key frontier (see {@link InitialSnapshot}).
     *
     * <p>What the engine holds is the table's rows keyed at or below the frontier. A change in that
     * stretch of log is delivered when its row's key is at or below the frontier, and dropped when it
     * is above: the snapshot, read at the consistent point, will deliver the row as that change left
     * it. Transactions ending after the consistent point are not filtered at all.
     */
    interface CatchUp {

        /** The snapshot's consistent point. */
        long until();

        /** The key columns, by name, whose text {@link #atOrBelow} is given. */
        List<String> keyColumns();

        /** Which of these keys are at or below the frontier; all false when nothing is snapshotted yet. */
        boolean[] atOrBelow(List<List<String>> keys);
    }

    /**
     * @param resume where the engine's state stands; transactions it already holds are dropped
     * @param onMessage told the content of each of this source's own logical messages
     */
    TransactionAssembler(
            CdcOptions options,
            CdcSchema.Mapping mapping,
            int tableOid,
            CdcOffset resume,
            Consumer<CdcTransaction> out,
            Consumer<String> onMessage) {
        this(options, mapping, tableOid, resume, out, onMessage, null);
    }

    TransactionAssembler(
            CdcOptions options,
            CdcSchema.Mapping mapping,
            int tableOid,
            CdcOffset resume,
            Consumer<CdcTransaction> out,
            Consumer<String> onMessage,
            @Nullable CatchUp catchUp) {
        this.options = options;
        this.mapping = mapping;
        this.tableOid = tableOid;
        this.out = out;
        this.onMessage = onMessage;
        this.delivered = resume.lsn();
        this.partialEnd = resume.partialEnd();
        this.partialDelivered = resume.partialDelivered();
        this.catchUp = catchUp;
    }

    /** Where a new replication connection should start so that nothing handed on is sent again. */
    long resumeLsn() {
        return delivered;
    }

    long carriedForward() {
        return carriedForward.get();
    }

    /** Forgets a transaction in flight when its connection died; it will be sent again whole. */
    void connectionLost() {
        inTransaction = false;
        changes = new ArrayList<>();
        failure = null;
    }

    void accept(PgOutput.Message message) {
        switch (message) {
            case PgOutput.Begin begin -> {
                inTransaction = true;
                commitMicros = begin.commitMicros();
                changes = new ArrayList<>();
                failure = null;
            }
            case PgOutput.Commit commit -> commit(commit.endLsn());
            case PgOutput.Relation relation -> relation(relation);
            case PgOutput.Insert insert -> {
                if (ours(insert.relid())) {
                    changes.add(change(insert.after(), null, +1));
                }
            }
            case PgOutput.Update update -> {
                if (ours(update.relid())) {
                    if (update.oldKind() != 'O') {
                        refuse(keyOnly("an UPDATE"));
                        return;
                    }
                    changes.add(change(
                            Objects.requireNonNull(update.before(), "an 'O' update carries its old row"), null, -1));
                    changes.add(change(update.after(), update.before(), +1));
                }
            }
            case PgOutput.Delete delete -> {
                if (ours(delete.relid())) {
                    if (delete.oldKind() != 'O') {
                        refuse(keyOnly("a DELETE"));
                        return;
                    }
                    changes.add(change(delete.before(), null, -1));
                }
            }
            case PgOutput.Truncate truncate -> {
                if (truncate.relids().contains(tableOid)) {
                    refuse(new PravahaException(
                            CdcErrors.UNREPRESENTABLE_CHANGE,
                            "TRUNCATE " + options.qualifiedTable() + " arrived in the change stream. A truncate "
                                    + "carries no rows, so there is nothing to retract, and a view left holding the "
                                    + "old rows would be wrong without saying so. This source stops instead. To "
                                    + "recover: drop the slot (SELECT pg_drop_replication_slot('" + options.slot()
                                    + "')), drop the registration's checkpoints, and register the query again -- it "
                                    + "starts from the table as it now is. Use DELETE FROM rather than TRUNCATE on a "
                                    + "captured table to have the rows retracted one by one."));
                }
            }
            case PgOutput.LogicalMessage logical -> {
                if (!logical.transactional() && CdcOptions.MESSAGE_PREFIX.equals(logical.prefix())) {
                    String content = new String(logical.content(), StandardCharsets.UTF_8);
                    if (content.startsWith(options.slot() + ":")) {
                        // Sent outside any transaction, and only after every transaction committed
                        // before it: a position with nothing undelivered behind it.
                        if (!inTransaction && logical.lsn() > delivered && partialEnd == 0) {
                            delivered = logical.lsn();
                            out.accept(CdcTransaction.marker(logical.lsn()));
                        }
                        onMessage.accept(content);
                    }
                }
            }
            case PgOutput.Ignored ignored -> {
                // Origin and Type: nothing this source needs.
            }
        }
    }

    private void commit(long endLsn) {
        inTransaction = false;
        List<CdcTransaction.Change> committed = changes;
        PravahaException refused = failure;
        changes = new ArrayList<>();
        failure = null;
        if (endLsn <= delivered) {
            // Already handed on before a restart or a reconnect: PostgreSQL resends from where it
            // was asked, and this is the second line of defence against a repeat.
            return;
        }
        if (catchUp != null && endLsn <= catchUp.until() && !committed.isEmpty() && refused == null) {
            // Before the snapshot's point. Filtered before a partial is skipped: the engine's count of
            // this transaction's changes was taken of the filtered list, which is deterministic.
            committed = belowFrontier(committed);
        }
        int skip = 0;
        if (partialEnd != 0) {
            if (endLsn != partialEnd || partialDelivered > committed.size()) {
                refused = new PravahaException(
                        CdcErrors.RESUME_POINT_RELEASED,
                        "the checkpoint holds the first " + partialDelivered + " changes of the transaction ending at "
                                + CdcOffset.format(partialEnd)
                                + ", and the stream resumed with a transaction ending at "
                                + CdcOffset.format(endLsn) + " holding " + committed.size() + ". The slot no longer "
                                + "has that transaction, so the rest of it cannot be delivered.");
            } else {
                skip = (int) partialDelivered;
            }
            partialEnd = 0;
            partialDelivered = 0;
        }
        delivered = endLsn;
        List<CdcTransaction.Change> remaining = committed.subList(skip, committed.size());
        out.accept(new CdcTransaction(endLsn, skip, List.copyOf(remaining), refused));
    }

    private List<CdcTransaction.Change> belowFrontier(List<CdcTransaction.Change> changes) {
        boolean[] keep = Objects.requireNonNull(catchUp, "only a catch-up filters")
                .atOrBelow(changes.stream().map(CdcTransaction.Change::key).toList());
        List<CdcTransaction.Change> kept = new ArrayList<>();
        for (int i = 0; i < keep.length; i++) {
            if (keep[i]) {
                kept.add(changes.get(i));
            }
        }
        return kept;
    }

    private void relation(PgOutput.Relation relation) {
        boolean sameName = relation.namespace().equals(options.schemaName())
                && relation.name().equals(options.tableName());
        if (relation.relid() != tableOid) {
            if (sameName) {
                refuse(new PravahaException(
                        CdcErrors.SCHEMA_MISMATCH,
                        options.qualifiedTable() + " was dropped and created again while it was being captured (its "
                                + "OID changed). The rows of the old table cannot be retracted from the new one's "
                                + "stream; drop the slot and register the query again."));
            }
            return;
        }
        if (relation.replicaIdentity() != 'f') {
            refuse(new PravahaException(
                    CdcErrors.NOT_CAPTURABLE,
                    options.qualifiedTable() + " stopped being REPLICA IDENTITY FULL while it was being captured, so "
                            + "its before-images no longer carry the whole row. Run: ALTER TABLE "
                            + options.qualifiedTable() + " REPLICA IDENTITY FULL;"));
            return;
        }
        StreamSchema schema = mapping.schema();
        int[] map = new int[schema.fieldCount()];
        for (int field = 0; field < map.length; field++) {
            String name = mapping.columnNames().get(field);
            int found = -1;
            for (int column = 0; column < relation.columns().size(); column++) {
                if (relation.columns().get(column).name().equals(name)) {
                    found = column;
                }
            }
            if (found < 0 || relation.columns().get(found).typeOid() != mapping.typeOids()[field]) {
                refuse(new PravahaException(
                        CdcErrors.SCHEMA_MISMATCH,
                        "column '" + name + "' of " + options.qualifiedTable() + " was "
                                + (found < 0 ? "dropped" : "changed to another type")
                                + " while it was being captured. The stream's rows cannot be read into the "
                                + "schema the query was registered with; register it again."));
                return;
            }
            map[field] = found;
        }
        if (catchUp != null) {
            List<String> keys = catchUp.keyColumns();
            int[] keyMap = new int[keys.size()];
            for (int part = 0; part < keyMap.length; part++) {
                keyMap[part] = -1;
                for (int column = 0; column < relation.columns().size(); column++) {
                    if (relation.columns().get(column).name().equals(keys.get(part))) {
                        keyMap[part] = column;
                    }
                }
                if (keyMap[part] < 0) {
                    refuse(new PravahaException(
                            CdcErrors.SCHEMA_MISMATCH,
                            "primary key column '" + keys.get(part) + "' of " + options.qualifiedTable()
                                    + " was dropped while its initial snapshot was being read; the snapshot cannot "
                                    + "be resumed in an order that no longer exists. Register the query again."));
                    return;
                }
            }
            keyOf = keyMap;
        }
        columnOf = map;
    }

    private boolean ours(int relid) {
        if (relid != tableOid) {
            return false;
        }
        if (columnOf == null && failure == null) {
            refuse(new PravahaException(
                    CdcErrors.UNREPRESENTABLE_CHANGE,
                    "a change to " + options.qualifiedTable() + " arrived before the Relation message describing it"));
        }
        return failure == null;
    }

    private PravahaException keyOnly(String what) {
        return new PravahaException(
                CdcErrors.NOT_CAPTURABLE,
                what + " of " + options.qualifiedTable() + " arrived with only the key in its before-image. "
                        + "Retracting the key alone retracts nothing from a view holding the whole row, so the "
                        + "old values would stay counted for ever. Run: ALTER TABLE " + options.qualifiedTable()
                        + " REPLICA IDENTITY FULL;");
    }

    private void refuse(PravahaException refusal) {
        // The first refusal wins; the transaction is refused whole at its Commit. pgoutput sends a
        // Relation message inside the transaction that first touches the table, so a refusal from
        // one belongs to that transaction too.
        if (failure == null) {
            failure = refusal;
        }
    }

    private CdcTransaction.Change change(PgOutput.Tuple tuple, PgOutput.@Nullable Tuple before, long weight) {
        int[] columnOf = Objects.requireNonNull(this.columnOf, "a Relation precedes the first change");
        String[] texts = new String[columnOf.length];
        for (int field = 0; field < texts.length; field++) {
            int column = columnOf[field];
            if (tuple.isUnchangedToast(column) && (before == null || before.isUnchangedToast(column))) {
                refuse(new PravahaException(
                        CdcErrors.UNREPRESENTABLE_CHANGE,
                        "column '" + mapping.columnNames().get(field) + "' of " + options.qualifiedTable()
                                + " arrived as an unchanged TOAST placeholder with no before-image holding its "
                                + "value. Writing the placeholder would corrupt that column in the view."));
                return new CdcTransaction.Change(new Object[texts.length], weight, 0L, null, new byte[0], null);
            }
            texts[field] = text(tuple, before, column);
        }
        List<String> key = null;
        if (keyOf != null) {
            String[] keyTexts = new String[keyOf.length];
            for (int part = 0; part < keyOf.length; part++) {
                // A key column is never an unchanged TOAST value without a before-image: a key is in
                // every before-image PostgreSQL writes.
                keyTexts[part] = text(tuple, before, keyOf[part]);
            }
            key = Arrays.asList(keyTexts);
        }
        return CdcTransaction.Change.fromText(
                mapping, texts, weight, (commitMicros + POSTGRES_EPOCH_MICROS) * 1_000L, key);
    }

    /** A column's text; an unchanged TOAST value is taken from the before-image, which holds it in full. */
    private String text(PgOutput.Tuple tuple, PgOutput.@Nullable Tuple before, int column) {
        if (tuple.isUnchangedToast(column)) {
            carriedForward.incrementAndGet();
            return Objects.requireNonNull(before, "change() refuses an unchanged TOAST value with no before-image")
                    .value(column);
        }
        return tuple.value(column);
    }
}
