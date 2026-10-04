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
package com.ash.messaging.pravaha.runtime.plan;

import java.math.BigInteger;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code SUBSTRING}'s window is the one the standard describes, at every start and every length.
 *
 * <p>Finding TY-22. {@code Expression.Substring} computed the end of the window as {@code from +
 * length} in {@code long}, with a comment claiming that made it overflow-safe. It did not:
 * {@code SUBSTRING(s FROM 1 FOR 9223372036854775807)} -- which is how a generated query spells "the
 * rest of the string" -- wrapped to {@code Long.MIN_VALUE}, the range came out empty, and the whole
 * value was silently replaced by the empty string. Nothing failed and nothing was logged; the row
 * simply had the wrong text in it.
 *
 * <p>Written as a property against an on-heap model rather than as a handful of examples, because
 * the defect lived at exactly the arguments nobody picks by hand. The model computes the same set
 * the standard defines -- the characters at 1-based positions {@code p} with {@code start <= p <
 * start + length} that the string actually has -- in {@link BigInteger}, where the arithmetic
 * cannot wrap at all, so the two implementations differ in their arithmetic and agree on their
 * answer or one of them is wrong.
 */
class SubstringRangeTest {

    /** Includes a surrogate pair, so a code-point count and a char count disagree. */
    private static final List<String> SUBJECTS = List.of("", "a", "hello", "ünïcødé", "🙂ab", "0123456789", "a🙂b🙂c");

    /** The values a wrong answer hides at: the extremes, the fences, and zero. */
    private static final List<Long> EDGES = List.of(
            Long.MIN_VALUE,
            Long.MIN_VALUE + 1,
            -9_223_372_036_854_775_000L,
            -5L,
            -1L,
            0L,
            1L,
            2L,
            5L,
            10L,
            9_223_372_036_854_775_000L,
            Long.MAX_VALUE - 1,
            Long.MAX_VALUE);

    @Test
    void theWindowIsTheStandardsAtEveryStartAndLength() {
        for (String subject : SUBJECTS) {
            for (long start : EDGES) {
                for (long length : EDGES) {
                    assertThat(substring(subject, start, length))
                            .as("SUBSTRING('%s' FROM %d FOR %d)", subject, start, length)
                            .isEqualTo(model(subject, start, length));
                }
            }
        }
    }

    @Test
    void theWindowIsTheStandardsAtRandomStartsAndLengths() {
        // Seeded, so a failure is reproducible and a passing run is not luck that changes daily.
        Random random = new Random(20260919L);
        for (int i = 0; i < 20_000; i++) {
            String subject = SUBJECTS.get(random.nextInt(SUBJECTS.size()));
            long start = random.nextBoolean() ? random.nextLong() : random.nextInt(21) - 10;
            long length = random.nextBoolean() ? random.nextLong() : random.nextInt(21) - 10;
            assertThat(substring(subject, start, length))
                    .as("SUBSTRING('%s' FROM %d FOR %d)", subject, start, length)
                    .isEqualTo(model(subject, start, length));
        }
    }

    @Test
    @SuppressWarnings("NullAway") // nulls on purpose: what a caller outside NullAway may pass
    void ty22_aLengthAtTheTopOfTheRangeReturnsTheWholeValueRatherThanNothing() {
        // The finding's own reproduction. `1 + Long.MAX_VALUE` wrapped to Long.MIN_VALUE, so the
        // window was empty and a non-empty column came back as "".
        assertThat(substring("hello", 1, Long.MAX_VALUE)).isEqualTo("hello");
        assertThat(substring("hello", 3, Long.MAX_VALUE)).isEqualTo("llo");
        assertThat(substring("ünïcødé", 1, Long.MAX_VALUE)).isEqualTo("ünïcødé");
        // And the FROM-only form, which means the same thing, agrees with it.
        assertThat(Expression.Substring.toEnd(text("hello"), number(1)).evaluateString(null))
                .isEqualTo(substring("hello", 1, Long.MAX_VALUE));
    }

    @Test
    void ty22_aStartAtTheBottomOfTheRangeIsEmptyRatherThanTheWholeValue() {
        // The mirror of the same arithmetic: an enormously negative start plus a finite length is
        // still far short of position 1, so nothing is in the window.
        assertThat(substring("hello", Long.MIN_VALUE, 5)).isEmpty();
        assertThat(substring("hello", Long.MIN_VALUE, Long.MAX_VALUE))
                .as("start + length is about -1 here, so the window still ends before the string starts")
                .isEmpty();
    }

    @SuppressWarnings("NullAway") // nulls on purpose: what a caller outside NullAway may pass
    private static String substring(String subject, long start, long length) {
        return new Expression.Substring(text(subject), number(start), number(length)).evaluateString(null);
    }

    /**
     * The characters at 1-based positions in {@code [start, start + length)} that exist.
     *
     * <p>In {@link BigInteger} on purpose: the implementation's whole difficulty is that the same
     * sum overflows in {@code long}, so a model that could overflow too would agree with the bug.
     */
    private static String model(String subject, long start, long length) {
        BigInteger from = BigInteger.valueOf(start);
        BigInteger until = from.add(BigInteger.valueOf(Math.max(0L, length)));
        StringBuilder kept = new StringBuilder();
        int[] codePoints = subject.codePoints().toArray();
        for (int i = 0; i < codePoints.length; i++) {
            BigInteger position = BigInteger.valueOf(i + 1L);
            if (position.compareTo(from) >= 0 && position.compareTo(until) < 0) {
                kept.appendCodePoint(codePoints[i]);
            }
        }
        return kept.toString();
    }

    private static Expression text(String value) {
        return Expression.Literal.ofText(value);
    }

    private static Expression number(long value) {
        return Expression.Literal.ofLong(value);
    }
}
