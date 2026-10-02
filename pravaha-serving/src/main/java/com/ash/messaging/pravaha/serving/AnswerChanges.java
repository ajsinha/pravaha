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
package com.ash.messaging.pravaha.serving;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One commit's change to a view's answer, netted before it is handed to followers (ADR-056).
 *
 * <p>Within a commit the shown row of a key can change more than once -- a retraction that takes a
 * key's newest row away shows the one behind it (VIEWW-1), and a second retraction may then take that
 * too. A follower is promised how the answer changed across the commit, so rows that left and came
 * back, or came and left, cancel here, and each distinct row appears at most once, on one side.
 */
final class AnswerChanges {

    private AnswerChanges() {}

    /**
     * One commit's netted change: the rows that left the answer and the rows that entered it.
     *
     * <p>Also what a plain subscription is handed since KEYEDWT-1, as {@code -1} and {@code +1}
     * changes, so a subscriber summing weights holds exactly the view.
     */
    record Netted(List<Object[]> leaving, List<Object[]> entering) {}

    /**
     * Nets {@code left} against {@code entered} and hands what remains to every listener.
     *
     * @return what was handed over, or null when the commit changed nothing in the answer
     */
    static Netted handOver(List<AnswerListener> listeners, List<Object[]> left, List<Object[]> entered, long frontier) {
        Netted netted = net(left, entered);
        if (netted != null) {
            for (AnswerListener listener : listeners) {
                listener.onAnswer(netted.leaving(), netted.entering(), frontier);
            }
        }
        return netted;
    }

    /**
     * Nets {@code left} against {@code entered}, handing it to nobody.
     *
     * @return the netted change, or null when it changes nothing in the answer
     */
    static Netted net(List<Object[]> left, List<Object[]> entered) {
        if (left == null || (left.isEmpty() && entered.isEmpty())) {
            return null;
        }
        Map<Row, Integer> net = new LinkedHashMap<>();
        for (Object[] row : left) {
            net.merge(new Row(row), -1, Integer::sum);
        }
        for (Object[] row : entered) {
            net.merge(new Row(row), 1, Integer::sum);
        }
        List<Object[]> leaving = new ArrayList<>();
        List<Object[]> entering = new ArrayList<>();
        net.forEach((row, count) -> {
            for (int i = 0; i < Math.abs(count); i++) {
                (count < 0 ? leaving : entering).add(row.values());
            }
        });
        if (leaving.isEmpty() && entering.isEmpty()) {
            return null;
        }
        return new Netted(Collections.unmodifiableList(leaving), Collections.unmodifiableList(entering));
    }

    /** A row compared by its values, arrays included. */
    @SuppressWarnings("ArrayRecordComponent") // equals and hashCode compare the array's contents
    private record Row(Object[] values) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Row row && Arrays.deepEquals(values, row.values);
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(values);
        }
    }
}
