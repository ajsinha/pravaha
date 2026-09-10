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
package com.ash.messaging.pravaha.algebra;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The bilinear join lift: {@code Δ(A⋈B) = ΔA⋈I(B) + I(A)⋈ΔB + ΔA⋈ΔB}.
 *
 * <p>Design section 9.3's rule, and the one worth reading twice. It is the entire reason a
 * stream-to-stream join is correct under updates on <em>either</em> side without a line of
 * special-case code: an update is {@code -1} of the old row and {@code +1} of the new, the rule is
 * arithmetic over weights, and arithmetic does not care which side changed or whether the change was
 * an insert, an update or a delete.
 *
 * <p><strong>The third term is not optional and is the one people drop.</strong> Without
 * {@code ΔA⋈ΔB}, two rows that arrive in the same batch and match each other are never joined:
 * {@code ΔA} is matched against the integral of B <em>before</em> this batch, which does not contain
 * the new B row, and vice versa. The bug is invisible at low rates -- batches of one contain no
 * pairs to miss -- and appears as quietly missing output when traffic increases. That is precisely
 * backwards from how anybody debugs, so it is asserted directly.
 *
 * <p>The integrals are the state: every row of both sides that could still match. Bounding them is
 * the caller's business -- a window, a TTL, a key-range predicate -- and unbounded is how a join
 * eats a node.
 *
 * @param <L> left row type
 * @param <R> right row type
 * @param <K> join key type
 * @param <O> output row type
 */
public final class IncrementalJoin<L, R, K, O> {

    private final Function<L, K> leftKey;
    private final Function<R, K> rightKey;
    private final BiFunction<L, R, O> combine;

    /** Everything seen so far on each side: I(A) and I(B), indexed by key. */
    private final Map<K, Map<L, Long>> leftIndex = new HashMap<>();

    private final Map<K, Map<R, Long>> rightIndex = new HashMap<>();

    private long leftRows;
    private long rightRows;

    public IncrementalJoin(Function<L, K> leftKey, Function<R, K> rightKey, BiFunction<L, R, O> combine) {
        this.leftKey = leftKey;
        this.rightKey = rightKey;
        this.combine = combine;
    }

    /**
     * Applies one timestep's changes to both sides and returns the change to the join.
     *
     * <p>Both deltas at once, because the rule needs them together: computing the left's effect and
     * then the right's, with the indexes updated in between, double-counts the pairs that appear on
     * both sides in the same step -- which is the {@code ΔA⋈ΔB} term applied twice rather than once.
     */
    public ZSet<O> step(ZSet<L> leftDelta, ZSet<R> rightDelta) {
        ZSet.Builder<O> result = ZSet.builder();

        // ΔA ⋈ I(B): the new left rows against everything the right side already held.
        for (Map.Entry<L, Long> left : leftDelta.entries().entrySet()) {
            Map<R, Long> matches = rightIndex.get(leftKey.apply(left.getKey()));
            if (matches != null) {
                for (Map.Entry<R, Long> right : matches.entrySet()) {
                    result.add(combine.apply(left.getKey(), right.getKey()), left.getValue() * right.getValue());
                }
            }
        }

        // I(A) ⋈ ΔB: the new right rows against everything the left side already held.
        for (Map.Entry<R, Long> right : rightDelta.entries().entrySet()) {
            Map<L, Long> matches = leftIndex.get(rightKey.apply(right.getKey()));
            if (matches != null) {
                for (Map.Entry<L, Long> left : matches.entrySet()) {
                    result.add(combine.apply(left.getKey(), right.getKey()), left.getValue() * right.getValue());
                }
            }
        }

        // ΔA ⋈ ΔB: pairs where both sides changed in this same step. Dropping this term loses
        // exactly the matches that arrive together, which is a bug that hides at low rates.
        for (Map.Entry<L, Long> left : leftDelta.entries().entrySet()) {
            K key = leftKey.apply(left.getKey());
            for (Map.Entry<R, Long> right : rightDelta.entries().entrySet()) {
                if (key.equals(rightKey.apply(right.getKey()))) {
                    result.add(combine.apply(left.getKey(), right.getKey()), left.getValue() * right.getValue());
                }
            }
        }

        integrate(leftIndex, leftDelta.entries(), leftKey);
        integrate(rightIndex, rightDelta.entries(), rightKey);
        leftRows = countRows(leftIndex);
        rightRows = countRows(rightIndex);
        return result.build();
    }

    /** Folds a delta into an index, dropping rows whose weight reaches zero. */
    private static <T, K> void integrate(Map<K, Map<T, Long>> index, Map<T, Long> delta, Function<T, K> key) {
        delta.forEach((row, weight) -> {
            Map<T, Long> byRow = index.computeIfAbsent(key.apply(row), k -> new HashMap<>());
            long updated = byRow.merge(row, weight, Long::sum);
            if (updated == 0) {
                // A row retracted to zero is gone, not present with weight zero. Keeping it would
                // grow the index with every update on either side -- unbounded state produced by
                // bookkeeping rather than by data.
                byRow.remove(row);
                if (byRow.isEmpty()) {
                    index.remove(key.apply(row));
                }
            }
        });
    }

    private static <T, K> long countRows(Map<K, Map<T, Long>> index) {
        return index.values().stream().mapToLong(Map::size).sum();
    }

    /** Rows currently held on the left. The number a state bound is enforced against. */
    public long leftRows() {
        return leftRows;
    }

    public long rightRows() {
        return rightRows;
    }

    /** Distinct join keys held on either side. */
    public int keyCount() {
        List<K> keys = new ArrayList<>(leftIndex.keySet());
        rightIndex.keySet().stream().filter(key -> !leftIndex.containsKey(key)).forEach(keys::add);
        return keys.size();
    }

    /** Forgets a key entirely, which is how a window or a TTL bounds this. */
    public void forget(K key) {
        leftIndex.remove(key);
        rightIndex.remove(key);
        leftRows = countRows(leftIndex);
        rightRows = countRows(rightIndex);
    }
}
