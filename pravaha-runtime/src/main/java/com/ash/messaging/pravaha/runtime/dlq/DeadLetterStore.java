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

import java.util.List;
import java.util.Optional;

/**
 * Reading a dead-letter queue back.
 *
 * <p>{@link DeadLetterQueue} is the write end and always was. This is the end that was missing: the
 * file was written by the node and read by nothing, so "inspect and act on a dead letter" -- the
 * console's fourth critical journey -- ended at a shell prompt and a {@code jq} recipe. An operator
 * who could not reach the node's filesystem could not reach its dead letters at all, which for a
 * containerised deployment is every operator.
 *
 * <p><strong>Newest first, everywhere.</strong> The file is append-only, so oldest-first is the
 * cheap order and it is the wrong one: a queue is looked at because something has just started
 * failing, and the entries that answer "what is happening now" are at the end. Every surface pages
 * from the newest backwards and none of them offers the other order, so that two surfaces cannot
 * disagree about what "the first page" means.
 *
 * <p><strong>No authorization here.</strong> A store knows nothing about principals; it reads
 * files. Who may see a count, who may see the bytes and who may replay is decided once, by the
 * registry's own listing rules, at the surfaces -- the same place the view's rows are decided. A
 * store that also policed would be that decision written twice.
 */
public interface DeadLetterStore {

    /** A store over nothing: what a node with no {@code pravaha.dlq.directory} answers with. */
    DeadLetterStore NONE = new DeadLetterStore() {

        @Override
        public List<String> queries() {
            return List.of();
        }

        @Override
        public DeadLetterCounts counts(String query) {
            return DeadLetterCounts.empty();
        }

        @Override
        public DeadLetterPage page(String query, int offset, int limit) {
            return DeadLetterPage.empty(offset, limit);
        }

        @Override
        public Optional<DeadLetterEntry> find(String query, String id) {
            return Optional.empty();
        }

        @Override
        public void recordReplay(String query, String id, DeadLetterEntry.Replay outcome) {}

        @Override
        public DeadLetterRetention retention() {
            return DeadLetterRetention.unbounded();
        }

        @Override
        public boolean configured() {
            return false;
        }
    };

    /** Every query this store holds entries for, in no particular order. */
    List<String> queries();

    /** How many entries, how many bytes, how many evicted, and the oldest and newest moments. */
    DeadLetterCounts counts(String query);

    /**
     * A page of entries, newest first.
     *
     * @param offset how many of the newest to skip
     * @param limit how many to return; the store clamps it, and the page says what it used
     */
    DeadLetterPage page(String query, int offset, int limit);

    /** One entry by its id, whole, wherever it sits in the file. */
    Optional<DeadLetterEntry> find(String query, String id);

    /**
     * Notes durably that an entry has been replayed, and how it went.
     *
     * <p>Beside the file rather than in it. The {@code .dlq} file is documented as one JSON object
     * per rejected record and is read with {@code jq} during incidents; interleaving markers would
     * break every recipe on that page, and rewriting the entry in place would make an append-only
     * file one that is edited -- with a torn write landing in the middle of somebody's evidence.
     */
    void recordReplay(String query, String id, DeadLetterEntry.Replay outcome);

    /** The bound this store enforces. */
    DeadLetterRetention retention();

    /** Whether a dead-letter directory is configured at all, as opposed to configured and empty. */
    default boolean configured() {
        return true;
    }
}
