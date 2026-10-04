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
package com.ash.messaging.pravaha.plugin.mysqlcdc;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.shyiko.mysql.binlog.GtidSet;
import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.GtidEventData;
import com.github.shyiko.mysql.binlog.event.QueryEventData;
import com.github.shyiko.mysql.binlog.event.RotateEventData;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Turns binlog events into committed transactions of weighted rows. Pure: no I/O, one thread.
 *
 * <p>An insert is {@code +1}; a delete is the whole old row at {@code -1}; an update is its before
 * image at {@code -1} and its after image at {@code +1}. {@code binlog_row_image = FULL}, which open
 * insists on, is what makes the before image the whole row. A transaction is released only at its
 * commit ({@code XID}, or {@code COMMIT} for a non-transactional engine), so a rolled-back one never
 * appears. Transactions that do not touch the captured table become position markers.
 *
 * <p>Refused, whole, with {@code PRV-5156}: a {@code TRUNCATE} of the table (it names no rows to
 * retract), an {@code ALTER}, {@code DROP} or {@code RENAME} of it (a statement's leading comments are
 * skipped before it is read), a table map whose columns disagree with the stream -- in count, in type,
 * in a decimal's precision or scale, in nullability or in signedness ({@link
 * MySqlSchema#binlogMismatch}, MYC-4) -- and a compressed transaction payload.
 *
 * <p><strong>Positions between transactions</strong> (MYC-1). Outside a transaction, a heartbeat (the
 * server's "you have everything up to here") and a rotation to a new binlog file are position markers
 * too: nothing before them can concern the table unless it was already assembled, so an idle table's
 * position follows the log and a purged file does not refuse a restart that missed nothing. Never
 * while a restored checkpoint's partial transaction is still to come, whose position must hold.
 *
 * <p><strong>GTID mode</strong> (MYC-2): each transaction's GTID is added to the executed set it
 * started from, and every transaction and marker carries the set as it stands after it.
 */
final class TransactionAssembler {

    private static final Pattern DDL = Pattern.compile(
            "^\\s*(truncate|alter|drop|rename)\\s+(?:table\\s+)?(?:if\\s+exists\\s+)?(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Comments before a statement: C-style blocks, and {@code --} and {@code #} lines. */
    private static final Pattern LEADING_COMMENTS = Pattern.compile(
            "^(?:\\s+|/\\*(?!!).*?\\*/|--(?:[ \\t][^\\n]*)?(?:\\n|$)|#[^\\n]*(?:\\n|$))+", Pattern.DOTALL);

    private final MySqlCdcOptions options;
    private final MySqlSchema.Mapping mapping;
    private final Map<Long, Boolean> tables = new HashMap<>();
    private String file;
    private int skip;
    private boolean inTransaction;
    private List<BinlogTransaction.Change> changes = new ArrayList<>();
    private @Nullable PravahaException failure;
    /** GTID mode: what has executed so far, and the GTID of the transaction under way. */
    private final @Nullable GtidSet executed;

    private @Nullable String currentGtid;
    /** GTID mode: the transaction {@code skip} counts into; null means the first one. */
    private final @Nullable String skipGtid;

    /**
     * @param file the binlog file reading starts in
     * @param skip changes of the first transaction a restored checkpoint already holds
     */
    TransactionAssembler(MySqlCdcOptions options, MySqlSchema.Mapping mapping, String file, int skip) {
        this(options, mapping, file, skip, null, null);
    }

    /**
     * @param gtidSet the executed GTID set reading starts after, or null in file mode
     * @param skipGtid in GTID mode, the transaction {@code skip} counts into, or null
     */
    TransactionAssembler(
            MySqlCdcOptions options,
            MySqlSchema.Mapping mapping,
            String file,
            int skip,
            @Nullable String gtidSet,
            @Nullable String skipGtid) {
        this.options = options;
        this.mapping = mapping;
        this.file = file;
        this.skip = skip;
        this.executed = gtidSet == null ? null : new GtidSet(gtidSet);
        this.skipGtid = skipGtid;
    }

    /** Takes one event; returns a transaction when this event completes one, otherwise null. */
    @Nullable
    BinlogTransaction accept(Event event) {
        EventHeaderV4 header = event.getHeader();
        EventType type = header.getEventType();
        if (type == EventType.ROTATE) {
            // The rotate the server sends on connecting has no position of its own (0) and repeats
            // where reading starts; a real one closes the old file, and the new file's start is a
            // position a restart may resume from.
            RotateEventData rotate = event.getData();
            file = rotate.getBinlogFilename();
            return header.getNextPosition() > 0 ? between(rotate.getBinlogPosition()) : null;
        }
        if (type == EventType.HEARTBEAT) {
            return header.getNextPosition() > 0 ? between(header.getNextPosition()) : null;
        }
        if (type == EventType.GTID) {
            if (executed != null) {
                currentGtid = ((GtidEventData) event.getData()).getMySqlGtid().toString();
            }
            return null;
        }
        if (type == EventType.QUERY) {
            return query(header, event.getData());
        }
        if (type == EventType.XID) {
            return complete(header);
        }
        if (type == EventType.TABLE_MAP) {
            tableMap(event.getData());
            return null;
        }
        if (type == EventType.TRANSACTION_PAYLOAD) {
            fail(new PravahaException(
                    MySqlCdcErrors.UNREPRESENTABLE_CHANGE,
                    "a compressed transaction payload arrived at " + file + ":" + header.getPosition()
                            + "; mysql-cdc does not decompress binlog transactions. SET GLOBAL "
                            + "binlog_transaction_compression = OFF;"));
            return null;
        }
        if (EventType.isRowMutation(type)) {
            rows(type, event.getData());
        }
        return null;
    }

    /**
     * A marker at {@code position} of the current file when no transaction is open and no restored
     * partial transaction is still to come; otherwise nothing.
     */
    private @Nullable BinlogTransaction between(long position) {
        if (inTransaction || skip > 0 || position < 4) {
            return null;
        }
        return BinlogTransaction.marker(file, position, executedText());
    }

    private @Nullable String executedText() {
        return executed == null ? null : executed.toString();
    }

    /** GTID mode: the transaction under way is done, delivered or not. */
    private @Nullable String commitGtid() {
        String gtid = currentGtid;
        if (executed != null && gtid != null) {
            executed.add(gtid);
        }
        currentGtid = null;
        return gtid;
    }

    private @Nullable BinlogTransaction query(EventHeaderV4 header, QueryEventData data) {
        String sql = LEADING_COMMENTS.matcher(data.getSql()).replaceFirst("").strip();
        if (sql.equalsIgnoreCase("BEGIN")) {
            begin();
            return null;
        }
        if (sql.equalsIgnoreCase("COMMIT")) {
            return complete(header);
        }
        PravahaException refusal = refuse(sql, data.getDatabase(), header);
        if (inTransaction) {
            fail(refusal);
            return null;
        }
        // DDL outside a transaction is a transaction of its own.
        String gtid = commitGtid();
        return refusal == null
                ? BinlogTransaction.marker(file, header.getNextPosition(), executedText())
                : new BinlogTransaction(
                        file, header.getNextPosition(), 0, List.of(), 0L, refusal, gtid, executedText());
    }

    /** A refusal when {@code sql} truncates, alters, drops or renames the captured table. */
    private @Nullable PravahaException refuse(String sql, String defaultDatabase, EventHeaderV4 header) {
        Matcher matcher = DDL.matcher(sql);
        if (!matcher.matches()) {
            return null;
        }
        String verb = matcher.group(1).toUpperCase(Locale.ROOT);
        boolean everyName = verb.equals("DROP") || verb.equals("RENAME");
        for (String name : matcher.group(2).replace("`", "").split("[\\s,;()]+", -1)) {
            if (name.isEmpty()) {
                continue;
            }
            if (names(name, defaultDatabase)) {
                String why = verb.equals("TRUNCATE")
                        ? "a TRUNCATE names no rows, so there is nothing to retract and the view would keep them"
                        : "the table changed shape or went away, and its rows can no longer be read as this stream";
                return new PravahaException(
                        MySqlCdcErrors.UNREPRESENTABLE_CHANGE,
                        verb + " of " + options.qualifiedTable() + " at " + file + ":" + header.getPosition() + ": "
                                + why + ". Everything before it was delivered; drop the registration and its "
                                + "checkpoint and register again.");
            }
            if (!everyName) {
                break;
            }
        }
        return null;
    }

    private boolean names(String name, String defaultDatabase) {
        int dot = name.indexOf('.');
        if (dot >= 0) {
            return name.substring(0, dot).equals(options.database())
                    && name.substring(dot + 1).equals(options.table());
        }
        return name.equals(options.table()) && options.database().equals(defaultDatabase);
    }

    private void tableMap(TableMapEventData data) {
        boolean ours =
                options.database().equals(data.getDatabase()) && options.table().equals(data.getTable());
        tables.put(data.getTableId(), ours);
        if (!ours) {
            return;
        }
        String mismatch = data.getColumnTypes().length != mapping.columnCount()
                ? "has " + data.getColumnTypes().length + " columns in the binlog and " + mapping.columnCount()
                        + " in the stream"
                : MySqlSchema.binlogMismatch(mapping, data);
        if (mismatch != null) {
            fail(new PravahaException(
                    MySqlCdcErrors.UNREPRESENTABLE_CHANGE,
                    options.qualifiedTable() + " " + mismatch + " at " + file + ": the table was altered, and its "
                            + "rows can no longer be read as this stream. Everything before it was delivered; drop "
                            + "the registration and its checkpoint and register again."));
        }
    }

    private void rows(EventType type, Object data) {
        if (EventType.isWrite(type)) {
            WriteRowsEventData write = (WriteRowsEventData) data;
            if (ours(write.getTableId())) {
                for (Serializable[] row : write.getRows()) {
                    add(row, 1);
                }
            }
        } else if (EventType.isUpdate(type)) {
            UpdateRowsEventData update = (UpdateRowsEventData) data;
            if (ours(update.getTableId())) {
                for (Map.Entry<Serializable[], Serializable[]> row : update.getRows()) {
                    add(row.getKey(), -1);
                    add(row.getValue(), 1);
                }
            }
        } else if (EventType.isDelete(type)) {
            DeleteRowsEventData delete = (DeleteRowsEventData) data;
            if (ours(delete.getTableId())) {
                for (Serializable[] row : delete.getRows()) {
                    add(row, -1);
                }
            }
        }
    }

    private boolean ours(long tableId) {
        return Boolean.TRUE.equals(tables.get(tableId));
    }

    private void add(Serializable[] image, long weight) {
        if (!inTransaction) {
            begin();
        }
        if (failure == null) {
            changes.add(BinlogTransaction.Change.of(mapping, image, weight));
        }
    }

    private void begin() {
        inTransaction = true;
        changes = new ArrayList<>();
        failure = null;
    }

    private void fail(@Nullable PravahaException refusal) {
        if (refusal == null) {
            return;
        }
        if (!inTransaction) {
            begin();
        }
        if (failure == null) {
            failure = refusal;
        }
    }

    private BinlogTransaction complete(EventHeaderV4 header) {
        long end = header.getNextPosition();
        String gtid = commitGtid();
        if (!inTransaction) {
            return BinlogTransaction.marker(file, end, executedText());
        }
        inTransaction = false;
        List<BinlogTransaction.Change> done = changes;
        changes = new ArrayList<>();
        long commitNanos = header.getTimestamp() * 1_000_000L;
        if (failure != null) {
            PravahaException refused = failure;
            failure = null;
            return new BinlogTransaction(file, end, 0, List.of(), commitNanos, refused, gtid, executedText());
        }
        int already = 0;
        if (skip > 0 && (skipGtid == null || skipGtid.equals(gtid))) {
            already = Math.min(skip, done.size());
            done = done.subList(already, done.size());
            skip = 0;
        }
        return new BinlogTransaction(file, end, already, List.copyOf(done), commitNanos, null, gtid, executedText());
    }
}
