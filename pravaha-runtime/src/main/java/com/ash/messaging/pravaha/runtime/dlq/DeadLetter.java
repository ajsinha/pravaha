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

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One record the engine could not process, and everything needed to work out why.
 *
 * <p>The fields are chosen by what a person actually needs at the moment they look. The
 * <strong>raw bytes</strong>, because a record that failed to decode cannot be described any other
 * way and paraphrasing it loses the thing that broke. The <strong>source offset</strong>, because
 * the first question is always "can I replay it?" and the second is "what else came from there?".
 * The <strong>correlation id</strong>, so one bad record can be traced across the engine's logs, the
 * DLQ file and whatever the source system calls it -- and, since B5, so that it can be <em>named</em>
 * by an API that fetches or replays exactly this one. And the <strong>reason</strong> as a sentence
 * rather than a class name, since a stack trace tells you where the engine gave up and not what was
 * wrong with the data.
 *
 * <p>Four fields were added when the queue became something to read rather than only to write
 * (B5), and each pays for itself at a surface. The <strong>code</strong>, because every other
 * failure in this system is addressable by a {@code PRV-} number and a dead letter's was not, so it
 * could not be grouped, filtered or linked to its help page. The <strong>stream</strong>, because a
 * query reading two streams gave no way to tell which one the record came from. The
 * <strong>schema</strong> the stream had when the record was rejected, because that is what makes
 * "the schema has changed since" a refusal a replay can actually check rather than a caveat in the
 * documentation. And a <strong>wall-clock</strong> reading beside the monotonic one, because
 * {@code timestampNanos} orders entries and cannot be shown to a person: it is a JVM's uptime, and
 * rendering it as a date produces 1970.
 *
 * @param queryId which query rejected it; one source can feed many, and only some may object
 * @param reason what was wrong, in a sentence
 * @param code the {@code PRV-} code of the decode failure, or empty when the source named none
 * @param stream which of the query's streams it arrived on, or empty when the source did not say
 * @param schema the stream's schema as it was at the moment of rejection, as a signature
 * @param sourceOffset where it came from, in the source's own terms
 * @param raw the bytes as received, never re-encoded
 * @param correlationId ties this entry to log lines and metrics, and names it to an API
 * @param timestampNanos when the engine rejected it, not when it was produced; monotonic, for order
 * @param wallMillis the same moment on the wall clock, or zero when it was not recorded
 */
@SuppressWarnings("ArrayRecordComponent") // carries the array; nothing compares or hashes one
public record DeadLetter(
        String queryId,
        String reason,
        String code,
        String stream,
        String schema,
        String sourceOffset,
        byte[] raw,
        String correlationId,
        long timestampNanos,
        long wallMillis) {

    public DeadLetter {
        Objects.requireNonNull(queryId, "queryId");
        Objects.requireNonNull(reason, "reason");
        code = code == null ? "" : code;
        stream = stream == null ? "" : stream;
        schema = schema == null ? "" : schema;
        sourceOffset = sourceOffset == null ? "" : sourceOffset;
        raw = raw == null ? new byte[0] : raw.clone();
        correlationId = correlationId == null ? "" : correlationId;
    }

    /**
     * The shape this record had before it was something to read: no code, no stream, no schema and
     * no wall clock.
     *
     * <p>Kept because a caller that only writes -- a test, an embedder's own queue -- should not
     * have to supply four fields it has nothing to say about. What it writes is a dead letter with
     * those fields empty, which every surface renders as "not recorded" rather than inventing one.
     */
    public DeadLetter(
            String queryId, String reason, String sourceOffset, byte[] raw, String correlationId, long timestampNanos) {
        this(queryId, reason, "", "", "", sourceOffset, raw, correlationId, timestampNanos, 0L);
    }

    @Override
    public byte[] raw() {
        // Defensive copy on the way out as well as in. A DLQ entry is evidence, and evidence that
        // the caller can edit after the fact is not evidence.
        return raw.clone();
    }

    /** How many bytes the rejected record was, without copying them to find out. */
    public int size() {
        return raw.length;
    }

    /**
     * What an API, a CLI and a console address this entry by: its correlation id.
     *
     * <p>Not a second identifier. The correlation id was already a fresh UUID per rejection, quoted
     * in log lines and in tickets, and minting an id beside it would have given one record two
     * names -- so the person searching the log for a correlation id and the person calling
     * {@code /dead-letters/{id}} are holding the same string.
     */
    public String id() {
        return correlationId;
    }

    /** When it was rejected, on the wall clock, or empty for an entry written before that was recorded. */
    public Optional<Instant> at() {
        return wallMillis <= 0 ? Optional.empty() : Optional.of(Instant.ofEpochMilli(wallMillis));
    }

    @Override
    public String toString() {
        return "DeadLetter[" + queryId + " at " + sourceOffset + ": " + reason + "]";
    }
}
