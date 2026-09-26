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

    /**
     * The fields one {@link #LIST} row carries, in order (WIRE-1).
     *
     * <p>Positional and append-only: the first five were the original contract, and every field
     * since has been added at the end, so a client that reads the first {@code n} goes on reading
     * exactly what it always did. The order used to be defined by a comment beside the producer and
     * read by index literals in three parsers; it is defined here, the producer refuses to send a row
     * of any other width, and the parsers look fields up by these names. Add a field only at the end.
     */
    public static final java.util.List<String> LIST_FIELDS = java.util.List.of(
            "name",
            "state",
            "sql",
            "fingerprint",
            "rows_in",
            "key_ordinals",
            "sink",
            "retention",
            "feed_state",
            "feed_code",
            "feed_message",
            "feed_where",
            "feed_at",
            "sink_state",
            "sink_code",
            "sink_message");

    /** Where a named field sits in a {@link #LIST} row; refused for a name {@link #LIST_FIELDS} does not have. */
    public static int listField(String name) {
        int index = LIST_FIELDS.indexOf(name);
        if (index < 0) {
            throw new IllegalArgumentException("a pravaha.list row has no field '" + name + "'; it has " + LIST_FIELDS);
        }
        return index;
    }

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
        return ticket(SUBSCRIBE, view, filterPairs, null);
    }

    /** {@link #subscribeTicket}, carrying what this subscriber asked its buffer to do (STRM-16). */
    public static byte[] subscribeTicket(String view, List<String> filterPairs, SubscriberPreference preference) {
        return ticket(SUBSCRIBE, view, filterPairs, preference);
    }

    /** {@link #subscribeTicket}, for a subscription that starts from the view's snapshot. */
    public static byte[] subscribeFromSnapshotTicket(String view, List<String> filterPairs) {
        return ticket(SUBSCRIBE_FROM_SNAPSHOT, view, filterPairs, null);
    }

    private static byte[] ticket(String verb, String view, List<String> filterPairs, SubscriberPreference preference) {
        List<String> fields = new ArrayList<>();
        fields.add(verb);
        fields.add(view);
        fields.addAll(filterPairs);
        if (preference != null) {
            fields.add(preference.encode());
        }
        return encode(fields);
    }

    /**
     * What a subscriber asked its server-side buffer to do when it cannot keep up (STRM-16).
     *
     * <p>Until this existed the ticket was {@code ["subscribe", view, pairs…]} and nothing else, so
     * {@code streamSubscription} passed {@code SubscriptionOptions.DEFAULT} and every remote
     * subscriber was {@code (10 000, CONFLATE)} whatever it asked for -- while
     * {@code ClientOptions.subscriberBufferRows} and {@code conflateOnOverflow} had no reader
     * anywhere and {@code OPERATIONS.md} presented the overflow policy as "per the subscriber's
     * choice". `CONFLATE` is the wrong default for anything maintaining its own total from the
     * weights, by its own javadoc, and it was the only policy reachable.
     *
     * <p><strong>It rides last, and that is what makes it safe to add.</strong> Everything from
     * field 2 on is alternating filter column and value, so their count is even; one more field
     * makes it odd, which is unambiguous in both directions. A server that does not know about this
     * sees an unpaired trailing field and ignores it, exactly as it did before; a server that does
     * reads an even tail as a ticket with no preference and uses its defaults. No version bump, and
     * no filter silently read as a policy.
     *
     * @param bufferRows rows this subscriber may fall behind by, at least 1
     * @param overflow what happens past it: {@code CONFLATE}, {@code DROP_OLDEST} or {@code FAIL},
     *     spelled as {@code SubscriptionOptions.Overflow} spells them
     */
    public record SubscriberPreference(int bufferRows, String overflow) {

        public SubscriberPreference {
            if (bufferRows < 1) {
                throw new PravahaException(
                        BAD_REQUEST, "a subscriber's buffer must hold at least one row, not " + bufferRows);
            }
            overflow = overflow == null ? "" : overflow.strip().toUpperCase(java.util.Locale.ROOT);
        }

        /** {@code rows=10000;overflow=CONFLATE}. */
        public String encode() {
            return "rows=" + bufferRows + ";overflow=" + overflow;
        }

        /**
         * Reads one back, or null when {@code text} is not one.
         *
         * <p>Null rather than a refusal, because the only thing that reaches here that is not one of
         * these is an odd trailing field from a client that meant something else, and reading it as
         * a policy would be the guess this format exists to avoid.
         */
        public static SubscriberPreference decode(String text) {
            if (text == null || !text.startsWith("rows=")) {
                return null;
            }
            int rows = -1;
            String overflow = "";
            for (String part : text.split(";")) {
                int split = part.indexOf('=');
                if (split < 0) {
                    continue;
                }
                String key = part.substring(0, split).strip();
                String value = part.substring(split + 1).strip();
                if (key.equals("rows")) {
                    try {
                        rows = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                } else if (key.equals("overflow")) {
                    overflow = value;
                }
            }
            return rows < 1 ? null : new SubscriberPreference(rows, overflow);
        }
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
     * @param dropped how many whole commits this subscriber has lost before this batch, cumulative
     *     (STRM-10). Zero for a subscriber that has kept up, and for a snapshot subscription, which
     *     is ended rather than skipped past a commit
     */
    public record BatchMark(String kind, long frontier, long dropped) {

        /** A part of the snapshot with more to follow. */
        public static final String SNAPSHOT = "snapshot";

        /** The snapshot's last part; the client's copy is complete once it is applied. */
        public static final String SNAPSHOT_END = "snapshot-end";

        /** One commit after the snapshot. */
        public static final String COMMIT = "commit";

        private static final String PREFIX = "pravaha:";

        /** A mark for a subscriber that has lost nothing, which is every mark but STRM-10's. */
        public BatchMark(String kind, long frontier) {
            this(kind, frontier, 0L);
        }

        /**
         * {@code pravaha:<kind>:<frontier>}, with {@code :<dropped>} appended only when something
         * has been dropped.
         *
         * <p>Appended rather than always present so the common mark is byte-for-byte what it has
         * always been. The parser splits on every colon instead of the last one, because a fourth
         * component moved where "the last colon" is -- which is the kind of thing that decodes to
         * a plausible wrong answer rather than to a failure.
         */
        public byte[] encode() {
            String text = PREFIX + kind + ":" + frontier + (dropped > 0 ? ":" + dropped : "");
            return text.getBytes(StandardCharsets.UTF_8);
        }

        /** The mark in {@code metadata}, or null when there is none or it is not one of ours. */
        public static BatchMark decode(byte[] metadata) {
            if (metadata == null || metadata.length == 0) {
                return null;
            }
            String text = new String(metadata, StandardCharsets.UTF_8);
            if (!text.startsWith(PREFIX)) {
                return null;
            }
            String[] parts = text.substring(PREFIX.length()).split(":");
            if (parts.length < 2 || parts[0].isEmpty()) {
                return null;
            }
            try {
                return new BatchMark(
                        parts[0], Long.parseLong(parts[1]), parts.length > 2 ? Long.parseLong(parts[2]) : 0L);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        public boolean isSnapshot() {
            return SNAPSHOT.equals(kind) || SNAPSHOT_END.equals(kind);
        }
    }
}
