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
package com.ash.messaging.pravaha.api.wire;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;

/**
 * The framing for Pravaha's own Flight actions and tickets.
 *
 * <p>Flight SQL has no vocabulary for "register a continuous query" or "subscribe to one" -- it was
 * designed for asking questions, not for standing up computations. Those arrive as Flight
 * <em>actions</em> and a Flight <em>ticket</em>, which is the extension point the protocol provides
 * for exactly this, so the transport stays one protocol rather than two.
 *
 * <p>The payload is a list of UTF-8 strings behind a magic number and a version, and it is
 * hand-framed rather than protobuf on purpose. The Python SDK carries no protobuf runtime -- that is
 * a deliberate promise about what installing this client costs somebody -- and a format that is
 * four lines to write in either language keeps that promise without a code generator in the build.
 *
 * <p>The magic matters for a second reason: {@code getStream} has to tell a Pravaha subscription
 * ticket from a Flight SQL one, and guessing by trying to parse it as protobuf and seeing what
 * happens is not telling.
 *
 * <p>It lives in the API module because both ends of the wire need it and neither owns it. The
 * server cannot depend on the client and the client must not depend on the server, so a format they
 * both speak belongs with the contract rather than in either implementation.
 */
public final class ControlWire {

    /** A request this server cannot read. Its own code, because both ends of the wire need it. */
    public static final ErrorCode BAD_REQUEST = new ErrorCode(6102, "FLIGHT_BAD_HANDLE");

    /** "PRVH" -- lets getStream recognise our tickets without parsing them as something else. */
    public static final int MAGIC = 0x50525648;

    static final byte VERSION = 1;

    /** Actions this server answers beyond Flight SQL's own. */
    public static final String REGISTER = "pravaha.register";

    public static final String DROP = "pravaha.drop";

    public static final String LIST = "pravaha.list";

    public static final String PAUSE = "pravaha.pause";

    public static final String RESUME = "pravaha.resume";

    /**
     * Blue/green replacement (ADR-046). {@code replace} takes the name, the new SQL, the key
     * ordinals and the options as {@code key=value;key=value}; the others take the name, and
     * {@code backfill} takes a verb ({@code pause}, {@code resume}, {@code throttle}) and a value.
     */
    public static final String REPLACE = "pravaha.replace";

    /** The state of one replacement, or of every one this node knows when the name is empty. */
    public static final String REPLACEMENT = "pravaha.replacement";

    public static final String CUTOVER = "pravaha.cutover";

    public static final String ROLLBACK = "pravaha.rollback";

    /** Ends a replacement that has not cut over, releasing the candidate. */
    public static final String ABANDON = "pravaha.abandon";

    /** Confirms a cutover: the replaced version is released and there is no rollback after it. */
    public static final String FINISH = "pravaha.finish";

    /** Controls a backfill while it runs: {@code pause}, {@code resume} or {@code throttle}. */
    public static final String BACKFILL = "pravaha.backfill";

    /**
     * The fields a replacement's status carries, in order, for every surface that renders one.
     *
     * <p>Positional and append-only, like the listing's: a client that reads the first twelve
     * fields goes on reading exactly what it always did when a thirteenth is added.
     */
    public static final java.util.List<String> REPLACEMENT_FIELDS = java.util.List.of(
            "name",
            "state",
            "sql",
            "candidate",
            "replacing",
            "sink",
            "options",
            "owner",
            "started_at",
            "cut_over_at",
            "rollback_until",
            "rollback_available",
            "history_rows",
            "live_rows",
            "rows_per_second",
            "partitions",
            "partitions_live",
            "history_complete",
            "rate_limit",
            "paused",
            "lag_nanos",
            "failure_code",
            "failure");

    /**
     * A page of a query's dead letters, newest first (B5).
     *
     * <p>Request: the query's name, the offset and the page size. Reply: one result per entry,
     * then one final result whose first field is {@code #} and which carries the queue's totals --
     * a trailer rather than a header, because a Flight action's results are streamed and the
     * totals are read from the same file the page was.
     */
    public static final String DLQ_LIST = "pravaha.dlq.list";

    /** One dead letter whole, by its id. Request: the query's name and the id. */
    public static final String DLQ_SHOW = "pravaha.dlq.show";

    /**
     * Feeds chosen dead letters back through the query. Request: the name, then one id per field.
     *
     * <p>One result per id: the id, the outcome ({@code REPLAYED} or {@code FAILED_AGAIN}), the
     * sentence, and the id of the entry a second failure wrote.
     */
    public static final String DLQ_REPLAY = "pravaha.dlq.replay";

    /**
     * The time-travel debugger (ADR-048). One action per verb, because a debugger is a
     * conversation -- fork, step, step, inspect, export -- and a single action carrying a verb
     * field would put the routing in the body where no client's types can see it.
     */
    public static final String DEBUG_FORK = "pravaha.debug.fork";

    /** The state of one session, or of every one the caller may administer when the id is empty. */
    public static final String DEBUG_SESSION = "pravaha.debug.session";

    /** Advances a session: {@code row}, {@code rows:N}, {@code commit}, {@code watermark:N}, {@code until:...}. */
    public static final String DEBUG_STEP = "pravaha.debug.step";

    /** What state a session's fork holds, and how much of each. */
    public static final String DEBUG_STATE = "pravaha.debug.state";

    /** One page of one operator's state. */
    public static final String DEBUG_INSPECT = "pravaha.debug.inspect";

    /** The fork's own view, which nothing else can read. */
    public static final String DEBUG_VIEW = "pravaha.debug.view";

    /** Writes the session out as a JUnit fixture. */
    public static final String DEBUG_EXPORT = "pravaha.debug.export";

    /** Ends a session and releases its fork. */
    public static final String DEBUG_END = "pravaha.debug.end";

    /** Which checkpoints of a query a session could be forked from. */
    public static final String DEBUG_CHECKPOINTS = "pravaha.debug.checkpoints";

    /**
     * The fields a debug session's status carries, in order. Positional and append-only, as the
     * replacement's are.
     */
    public static final java.util.List<String> DEBUG_SESSION_FIELDS = java.util.List.of(
            "id",
            "query",
            "sql",
            "checkpoint_id",
            "owner",
            "started_at",
            "last_used_at",
            "steps",
            "rows_consumed",
            "view_size",
            "watermark_nanos",
            "sinks_disabled",
            "streams");

    /**
     * The fields one step's report carries, in order.
     *
     * <p>The three lists -- the rows that entered, what each operator did, what changed in the
     * view -- are encoded as counts followed by their own fields, because the control wire is a
     * flat list of strings and a nested structure has to be laid out somehow. See
     * {@code DebugActions} for the layout and the reason it is not JSON.
     */
    public static final java.util.List<String> DEBUG_STEP_FIELDS = java.util.List.of(
            "session", "sequence", "kind", "watermark_nanos", "rows_consumed", "view_size", "exhausted", "stopped");

    private ControlWire() {}

    /** Encodes a list of strings. Nulls are encoded as absent and decode as empty. */
    public static byte[] encode(List<String> fields) {
        int size = 4 + 1 + 4;
        List<byte[]> encoded = new ArrayList<>(fields.size());
        for (String field : fields) {
            byte[] bytes = (field == null ? "" : field).getBytes(StandardCharsets.UTF_8);
            encoded.add(bytes);
            size += 4 + bytes.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.putInt(MAGIC);
        buffer.put(VERSION);
        buffer.putInt(encoded.size());
        for (byte[] bytes : encoded) {
            buffer.putInt(bytes.length);
            buffer.put(bytes);
        }
        return buffer.array();
    }

    public static byte[] encode(String... fields) {
        return encode(List.of(fields));
    }

    /** True if these bytes are ours, without attempting to parse them as anything else. */
    public static boolean isOurs(byte[] bytes) {
        return bytes != null && bytes.length >= 5 && ByteBuffer.wrap(bytes).getInt() == MAGIC;
    }

    /**
     * Decodes, bounds-checking every read.
     *
     * <p>These bytes crossed a network and may have been truncated, cached wrong, or kept across an
     * upgrade. A malformed payload is a refusal with a code, never an exception with an array index
     * in it.
     */
    public static List<String> decode(byte[] bytes) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            if (buffer.remaining() < 9 || buffer.getInt() != MAGIC) {
                throw new PravahaException(BAD_REQUEST, "this is not a Pravaha request");
            }
            byte version = buffer.get();
            if (version != VERSION) {
                throw new PravahaException(
                        BAD_REQUEST,
                        "this request was built by a different version of the client; upgrade one of them");
            }
            int count = buffer.getInt();
            if (count < 0 || count > 1024) {
                throw new PravahaException(BAD_REQUEST, "this Pravaha request is malformed");
            }
            List<String> fields = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int length = buffer.getInt();
                if (length < 0 || length > buffer.remaining()) {
                    throw new PravahaException(BAD_REQUEST, "this Pravaha request is malformed");
                }
                byte[] field = new byte[length];
                buffer.get(field);
                fields.add(new String(field, StandardCharsets.UTF_8));
            }
            return fields;
        } catch (PravahaException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PravahaException(BAD_REQUEST, "this Pravaha request is malformed", e);
        }
    }

    /** The first field of a plain subscription ticket: changes from the next commit on, no state. */
    public static final String SUBSCRIBE = "subscribe";

    /**
     * The first field of a snapshot subscription ticket: the view as it stands, then every commit
     * after it (SUB-1).
     *
     * <p>A verb of its own rather than an extra field, so the two are told apart without guessing and
     * an older server refuses this ticket as one it does not know, instead of reading a flag as a
     * filter column. Every batch on such a stream carries a {@link BatchMark} as its application
     * metadata; a plain subscription's carry none, as they never have.
     */
    public static final String SUBSCRIBE_FROM_SNAPSHOT = "subscribe.snapshot";

    /** The ticket a subscriber returns with: a view name, then alternating filter column and value. */
    public static byte[] subscribeTicket(String view, List<String> filterPairs) {
        return ticket(SUBSCRIBE, view, filterPairs);
    }

    /** {@link #subscribeTicket}, for a subscription that starts from the view's snapshot. */
    public static byte[] subscribeFromSnapshotTicket(String view, List<String> filterPairs) {
        return ticket(SUBSCRIBE_FROM_SNAPSHOT, view, filterPairs);
    }

    private static byte[] ticket(String verb, String view, List<String> filterPairs) {
        List<String> fields = new ArrayList<>();
        fields.add(verb);
        fields.add(view);
        fields.addAll(filterPairs);
        return encode(fields);
    }

    /**
     * What one batch of a snapshot subscription is: part of the snapshot, its last part, or a commit.
     *
     * <p>Sent as the batch's Flight application metadata, as the UTF-8 text {@code
     * pravaha:<kind>:<frontier>}. A snapshot larger than one batch arrives as {@link #SNAPSHOT}
     * parts and ends with exactly one {@link #SNAPSHOT_END}, which is sent even for an empty view;
     * every batch after it is a {@link #COMMIT}, whole.
     *
     * @param frontier the committed frontier the snapshot is the view at, or the commit published
     */
    public record BatchMark(String kind, long frontier) {

        /** A part of the snapshot with more to follow. */
        public static final String SNAPSHOT = "snapshot";

        /** The snapshot's last part; the client's copy is complete once it is applied. */
        public static final String SNAPSHOT_END = "snapshot-end";

        /** One commit after the snapshot. */
        public static final String COMMIT = "commit";

        private static final String PREFIX = "pravaha:";

        public byte[] encode() {
            return (PREFIX + kind + ":" + frontier).getBytes(StandardCharsets.UTF_8);
        }

        /** The mark in {@code metadata}, or null when there is none or it is not one of ours. */
        public static BatchMark decode(byte[] metadata) {
            if (metadata == null || metadata.length == 0) {
                return null;
            }
            String text = new String(metadata, StandardCharsets.UTF_8);
            int split = text.lastIndexOf(':');
            if (!text.startsWith(PREFIX) || split <= PREFIX.length()) {
                return null;
            }
            try {
                return new BatchMark(text.substring(PREFIX.length(), split), Long.parseLong(text.substring(split + 1)));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        public boolean isSnapshot() {
            return SNAPSHOT.equals(kind) || SNAPSHOT_END.equals(kind);
        }
    }
}
