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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * The {@code pgoutput} logical replication protocol, version 1, decoded by hand (ADR-041).
 *
 * <p>This is the cost the ADR accepted in exchange for needing no dependency: the messages are
 * documented ("Logical Replication Message Formats" in the PostgreSQL manual) and stable, and this
 * class is all of it that the source uses. Pure -- a buffer in, a record out -- so every message
 * shape is testable without a server.
 *
 * <p>Protocol version 1 on purpose. Version 2 adds streaming of in-progress transactions, which
 * would hand the reader changes that may still roll back; version 1 sends a transaction only once
 * it has committed, which is the boundary this source delivers on anyway.
 *
 * <p>Tuple values arrive in PostgreSQL's text output format ({@code binary} is not requested), so a
 * value's meaning is its type's text representation, converted by {@link PgValues}. Three column
 * kinds exist: {@code n} (null), {@code t} (a text value) and {@code u} -- "unchanged TOASTed value,
 * not sent". The last is a placeholder, not data, and {@link Tuple#value} refuses to pretend
 * otherwise.
 */
final class PgOutput {

    private PgOutput() {}

    /** One decoded message. */
    sealed interface Message
            permits Begin, Commit, Relation, Insert, Update, Delete, Truncate, LogicalMessage, Ignored {}

    /** @param finalLsn the LSN of the transaction's commit record */
    record Begin(long finalLsn, long commitMicros, int xid) implements Message {}

    /** @param endLsn the end of the commit record: the position after this transaction */
    record Commit(long commitLsn, long endLsn, long commitMicros) implements Message {}

    /** A column as a Relation message describes it. */
    record Column(String name, int typeOid, boolean key) {}

    /**
     * Sent before the first change to a relation in a session, and again after its definition
     * changes.
     *
     * @param replicaIdentity {@code f} full, {@code d} default, {@code n} nothing, {@code i} index
     */
    record Relation(int relid, String namespace, String name, char replicaIdentity, List<Column> columns)
            implements Message {}

    record Insert(int relid, Tuple after) implements Message {}

    /**
     * @param oldKind {@code O} for a whole old row, {@code K} for the key only, or {@code 0} when
     *     the update carried no before-image at all
     */
    record Update(int relid, char oldKind, Tuple before, Tuple after) implements Message {}

    record Delete(int relid, char oldKind, Tuple before) implements Message {}

    record Truncate(List<Integer> relids, int options) implements Message {}

    record LogicalMessage(boolean transactional, long lsn, String prefix, byte[] content) implements Message {}

    /** Origin and Type messages, which carry nothing this source needs. */
    record Ignored(char type) implements Message {}

    /** A row image: one kind and, for {@code t}, one text value per column. */
    record Tuple(char[] kinds, String[] values) {

        int size() {
            return kinds.length;
        }

        boolean isNull(int column) {
            return kinds[column] == 'n';
        }

        boolean isUnchangedToast(int column) {
            return kinds[column] == 'u';
        }

        /** The column's text value, or null; never the placeholder for an unchanged TOAST value. */
        String value(int column) {
            if (kinds[column] == 'u') {
                throw new IllegalStateException("column " + column + " is an unchanged TOAST placeholder, not a "
                        + "value; it has to be carried forward from the before-image");
            }
            return values[column];
        }
    }

    static Message decode(ByteBuffer buffer) {
        char type = (char) buffer.get();
        try {
            return switch (type) {
                case 'B' -> new Begin(buffer.getLong(), buffer.getLong(), buffer.getInt());
                case 'C' -> {
                    buffer.get(); // flags, unused and zero
                    yield new Commit(buffer.getLong(), buffer.getLong(), buffer.getLong());
                }
                case 'R' -> relation(buffer);
                case 'I' -> {
                    int relid = buffer.getInt();
                    expect(buffer, 'N', "Insert");
                    yield new Insert(relid, tuple(buffer));
                }
                case 'U' -> update(buffer);
                case 'D' -> {
                    int relid = buffer.getInt();
                    char kind = (char) buffer.get();
                    if (kind != 'K' && kind != 'O') {
                        throw malformed("Delete carries old-tuple kind '" + kind + "'");
                    }
                    yield new Delete(relid, kind, tuple(buffer));
                }
                case 'T' -> {
                    int count = buffer.getInt();
                    int options = buffer.get();
                    List<Integer> relids = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        relids.add(buffer.getInt());
                    }
                    yield new Truncate(relids, options);
                }
                case 'M' -> {
                    boolean transactional = (buffer.get() & 1) != 0;
                    long lsn = buffer.getLong();
                    String prefix = string(buffer);
                    byte[] content = new byte[buffer.getInt()];
                    buffer.get(content);
                    yield new LogicalMessage(transactional, lsn, prefix, content);
                }
                case 'O', 'Y' -> new Ignored(type);
                default ->
                    throw malformed("message type '" + type + "' is not part of pgoutput protocol version 1 "
                            + "as this source requests it");
            };
        } catch (java.nio.BufferUnderflowException | IndexOutOfBoundsException e) {
            throw malformed("message '" + type + "' ended early");
        }
    }

    private static Relation relation(ByteBuffer buffer) {
        int relid = buffer.getInt();
        String namespace = string(buffer);
        String name = string(buffer);
        char identity = (char) buffer.get();
        int count = buffer.getShort();
        List<Column> columns = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            boolean key = (buffer.get() & 1) != 0;
            String column = string(buffer);
            int typeOid = buffer.getInt();
            buffer.getInt(); // atttypmod: the type check is done by OID against the table at open
            columns.add(new Column(column, typeOid, key));
        }
        return new Relation(relid, namespace, name, identity, List.copyOf(columns));
    }

    private static Update update(ByteBuffer buffer) {
        int relid = buffer.getInt();
        char marker = (char) buffer.get();
        char oldKind = 0;
        Tuple before = null;
        if (marker == 'K' || marker == 'O') {
            oldKind = marker;
            before = tuple(buffer);
            marker = (char) buffer.get();
        }
        if (marker != 'N') {
            throw malformed("Update has '" + marker + "' where the new tuple's 'N' belongs");
        }
        return new Update(relid, oldKind, before, tuple(buffer));
    }

    private static Tuple tuple(ByteBuffer buffer) {
        int count = buffer.getShort();
        char[] kinds = new char[count];
        String[] values = new String[count];
        for (int i = 0; i < count; i++) {
            char kind = (char) buffer.get();
            kinds[i] = kind;
            switch (kind) {
                case 'n', 'u' -> values[i] = null;
                case 't' -> {
                    byte[] bytes = new byte[buffer.getInt()];
                    buffer.get(bytes);
                    values[i] = new String(bytes, StandardCharsets.UTF_8);
                }
                default ->
                    throw malformed("tuple column " + i + " has kind '" + kind + "'; this source asks "
                            + "for text values, so only 'n', 'u' and 't' are expected");
            }
        }
        return new Tuple(kinds, values);
    }

    private static void expect(ByteBuffer buffer, char wanted, String message) {
        char found = (char) buffer.get();
        if (found != wanted) {
            throw malformed(message + " has '" + found + "' where '" + wanted + "' belongs");
        }
    }

    /** A NUL-terminated UTF-8 string. */
    private static String string(ByteBuffer buffer) {
        int start = buffer.position();
        int end = start;
        while (buffer.get(end) != 0) {
            end++;
        }
        byte[] bytes = new byte[end - start];
        buffer.get(bytes);
        buffer.get(); // the terminator
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static PravahaException malformed(String what) {
        return new PravahaException(
                CdcErrors.UNREPRESENTABLE_CHANGE,
                "the replication stream carried a message this decoder cannot read: " + what + ". Nothing was "
                        + "delivered from it; the stream stops here rather than guess at the rest.");
    }
}
