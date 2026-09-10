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

import java.util.Objects;

/**
 * One record the engine could not process, and everything needed to work out why.
 *
 * <p>The fields are chosen by what a person actually needs at the moment they look. The
 * <strong>raw bytes</strong>, because a record that failed to decode cannot be described any other
 * way and paraphrasing it loses the thing that broke. The <strong>source offset</strong>, because
 * the first question is always "can I replay it?" and the second is "what else came from there?".
 * The <strong>correlation id</strong>, so one bad record can be traced across the engine's logs, the
 * DLQ file and whatever the source system calls it. And the <strong>reason</strong> as a sentence
 * rather than a class name, since a stack trace tells you where the engine gave up and not what was
 * wrong with the data.
 *
 * @param queryId which query rejected it; one source can feed many, and only some may object
 * @param reason what was wrong, in a sentence
 * @param sourceOffset where it came from, in the source's own terms
 * @param raw the bytes as received, never re-encoded
 * @param correlationId ties this entry to log lines and metrics
 * @param timestampNanos when the engine rejected it, not when it was produced
 */
public record DeadLetter(
        String queryId, String reason, String sourceOffset, byte[] raw, String correlationId, long timestampNanos) {

    public DeadLetter {
        Objects.requireNonNull(queryId, "queryId");
        Objects.requireNonNull(reason, "reason");
        sourceOffset = sourceOffset == null ? "" : sourceOffset;
        raw = raw == null ? new byte[0] : raw.clone();
        correlationId = correlationId == null ? "" : correlationId;
    }

    @Override
    public byte[] raw() {
        // Defensive copy on the way out as well as in. A DLQ entry is evidence, and evidence that
        // the caller can edit after the fact is not evidence.
        return raw.clone();
    }

    @Override
    public String toString() {
        return "DeadLetter[" + queryId + " at " + sourceOffset + ": " + reason + "]";
    }
}
