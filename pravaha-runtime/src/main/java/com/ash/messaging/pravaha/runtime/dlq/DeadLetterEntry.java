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
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * A dead letter as a reader sees it: the record, where it sits in the file, and what has been done
 * with it.
 *
 * <p>The writer's {@link DeadLetter} is the evidence and nothing else. This is the same evidence
 * with the two things only the store knows: its position, so a listing can be paged without the
 * page shifting under the caller, and whether somebody has already replayed it -- which is the
 * first question asked of an entry during a clean-up, and the one that stops two operators
 * replaying the same thousand records at each other.
 *
 * @param sequence the entry's one-based position in its file, oldest first; stable unless retention
 *     evicts, which shifts it and is why {@link DeadLetter#id()} and not this addresses an entry
 * @param letter the record itself
 * @param replay what has happened to it since
 * @param replayedAt when that was, or null for an entry nobody has touched
 */
public record DeadLetterEntry(
        long sequence,
        DeadLetter letter,
        Replay replay,
        @Nullable Instant replayedAt) {

    /** What has been done with an entry. */
    public enum Replay {
        /** Nobody has replayed it. */
        NEW,
        /** Replayed, and the record decoded: it is a row in the view, at the frontier it was replayed at. */
        REPLAYED,
        /**
         * Replayed, and it failed to decode again -- so it went back on the queue as a new entry.
         *
         * <p>The new entry is what a second attempt would address. This one is marked rather than
         * removed so that the history of an entry somebody has already tried twice is visible;
         * without it, a queue of records that cannot ever decode looks exactly like a queue nobody
         * has looked at.
         */
        FAILED_AGAIN
    }

    public DeadLetterEntry {
        replay = replay == null ? Replay.NEW : replay;
    }

    /** An entry nobody has replayed. */
    public static DeadLetterEntry of(long sequence, DeadLetter letter) {
        return new DeadLetterEntry(sequence, letter, Replay.NEW, null);
    }

    /** What addresses this entry on every surface. */
    public String id() {
        return letter.id();
    }

    public Optional<Instant> replayedAtInstant() {
        return Optional.ofNullable(replayedAt);
    }
}
