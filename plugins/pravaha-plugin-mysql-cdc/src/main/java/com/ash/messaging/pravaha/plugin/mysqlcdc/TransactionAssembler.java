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

import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.QueryEventData;
import com.github.shyiko.mysql.binlog.event.RotateEventData;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;

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
 * retract), an {@code ALTER}, {@code DROP} or {@code RENAME} of it, a table map whose column count
 * disagrees with the stream, and a compressed transaction payload.
 */
final class TransactionAssembler {

    private static final Pattern DDL = Pattern.compile(
            "^\\s*(truncate|alter|drop|rename)\\s+(?:table\\s+)?(?:if\\s+exists\\s+)?(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final MySqlCdcOptions options;
    private final MySqlSchema.Mapping mapping;
    private final Map<Long, Boolean> tables = new HashMap<>();
    private String file;
    private int skip;
    private boolean inTransaction;
    private List<BinlogTransaction.Change> changes = new ArrayList<>();
    private PravahaException failure;

    /**
     * @param file the binlog file reading starts in
     * @param skip changes of the first transaction a restored checkpoint already holds
     */
    TransactionAssembler(MySqlCdcOptions options, MySqlSchema.Mapping mapping, String file, int skip) {
        this.options = options;
        this.mapping = mapping;
        this.file = file;
        this.skip = skip;
    }

    /** Takes one event; returns a transaction when this event completes one, otherwise null. */
    BinlogTransaction accept(Event event) {
        EventHeaderV4 header = event.getHeader();
        EventType type = header.getEventType();
        if (type == EventType.ROTATE) {
            // Only the file: the next transaction's end is the next position worth recording. The
            // rotate the server sends on connecting repeats the requested file and is harmless.
            RotateEventData rotate = event.getData();
            file = rotate.getBinlogFilename();
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

    private BinlogTransaction query(EventHeaderV4 header, QueryEventData data) {
        String sql = data.getSql().strip();
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
        return refusal == null
                ? BinlogTransaction.marker(file, header.getNextPosition())
                : BinlogTransaction.refused(file, header.getNextPosition(), refusal);
    }

    /** A refusal when {@code sql} truncates, alters, drops or renames the captured table. */
    private PravahaException refuse(String sql, String defaultDatabase, EventHeaderV4 header) {
        Matcher matcher = DDL.matcher(sql);
        if (!matcher.matches()) {
            return null;
        }
        String verb = matcher.group(1).toUpperCase(Locale.ROOT);
        boolean everyName = verb.equals("DROP") || verb.equals("RENAME");
        for (String name : matcher.group(2).replace("`", "").split("[\\s,;()]+")) {
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
        if (ours && data.getColumnTypes().length != mapping.columnCount()) {
            fail(new PravahaException(
                    MySqlCdcErrors.UNREPRESENTABLE_CHANGE,
                    options.qualifiedTable() + " has " + data.getColumnTypes().length + " columns in the binlog and "
                            + mapping.columnCount() + " in the stream: the table was altered. Drop the registration "
                            + "and its checkpoint and register again."));
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

    private void fail(PravahaException refusal) {
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
        if (!inTransaction) {
            return BinlogTransaction.marker(file, end);
        }
        inTransaction = false;
        List<BinlogTransaction.Change> done = changes;
        changes = new ArrayList<>();
        long commitNanos = header.getTimestamp() * 1_000_000L;
        if (failure != null) {
            PravahaException refused = failure;
            failure = null;
            return new BinlogTransaction(file, end, 0, List.of(), commitNanos, refused);
        }
        int already = 0;
        if (skip > 0) {
            already = Math.min(skip, done.size());
            done = done.subList(already, done.size());
            skip = 0;
        }
        return new BinlogTransaction(file, end, already, List.copyOf(done), commitNanos, null);
    }
}
