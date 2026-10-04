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
 * <p>Those two rules pull against each other exactly once: when the DLQ itself fails. The first
 * wins (DLQFULL-1). Counting the entry and carrying on was tried, and on a full disk it dropped
 * records with nothing said while the source read past them; so an entry that cannot be written is
 * counted in {@link #failures()} and refused with {@code PRV-4090}, and what was feeding it stops at
 * that record -- the behaviour of a node with no queue configured, which fails loudly.
 */
public interface DeadLetterQueue extends AutoCloseable {

    /**
     * Records one rejected record.
     *
     * @throws com.ash.messaging.pravaha.api.PravahaException {@code PRV-4090} when the entry could not
     *     be written; the caller lets it propagate, so the record stops what fed it rather than vanish
     */
    void accept(DeadLetter letter);

    /** Entries accepted. */
    long count();

    /** Entries that could not be written, each one refused. Non-zero means the DLQ needs attention. */
    long failures();

    /**
     * How many entries are waiting to be dealt with -- the queue's depth, not the running total.
     *
     * <p>{@link #count()} only ever rises and answers "how many has this process rejected". The
     * question an operator asks of a dashboard is the other one: how much is sitting there now,
     * after whatever retention has taken and whatever has been replayed. A rising count with a flat
     * depth is a queue somebody is keeping on top of; a rising depth is the finding.
     *
     * <p>Defaults to {@link #count()}, which is exactly right for an implementation that never
     * removes anything.
     */
    default long depth() {
        return count();
    }

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
