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

/**
 * Where records go when they cannot be processed.
 *
 * <p>Two rules, from design section 15.6, and both are about what must <em>not</em> happen. A record
 * is <strong>never dropped silently</strong> -- a query producing slightly wrong answers because
 * some input was quietly discarded is far worse than one that fails, because nobody investigates
 * what nobody notices. And a bad record <strong>never stops the pipeline</strong> -- one malformed
 * message from one partner cannot be allowed to halt a query carrying nine others.
 *
 * <p>Those two rules pull against each other exactly once: when the DLQ itself fails. An
 * implementation that throws would break the second rule; one that swallows would break the first.
 * The resolution is {@link #failures()} -- writing is best-effort, and the count of entries that
 * could not be written is itself reported, so the gap is visible rather than invisible.
 */
public interface DeadLetterQueue extends AutoCloseable {

    /** Records one rejected record. Must not throw: the caller is already handling a failure. */
    void accept(DeadLetter letter);

    /** Entries accepted. */
    long count();

    /** Entries that could not be written. Non-zero means the DLQ itself needs attention. */
    long failures();

    @Override
    void close();

    /** A queue that counts and discards. For tests, and for a deployment that has deliberately opted out. */
    static DeadLetterQueue counting() {
        return new DeadLetterQueue() {
            private long count;

            @Override
            public void accept(DeadLetter letter) {
                count++;
            }

            @Override
            public long count() {
                return count;
            }

            @Override
            public long failures() {
                return 0;
            }

            @Override
            public void close() {}
        };
    }
}
