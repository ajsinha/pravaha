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
package com.ash.messaging.pravaha.it.coverage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code DATE_FORMAT}, {@code REGEXP_EXTRACT} and {@code SPLIT_INDEX}, the three scalar functions
 * Nexmark q15-q17, q21 and q22 call.
 *
 * <p>Flink's semantics throughout, since Nexmark is written in Flink's dialect. The cases worth
 * reading are the edges where a plausible implementation differs from Flink's: an empty field kept
 * rather than skipped, a regex that does not match giving null rather than an empty string, and a
 * timestamp rendered in UTC whatever the machine's zone.
 */
class NexmarkScalarFunctionTest {

    private static final long SECOND = 1_000_000_000L;

    /** 2026-09-20T23:59:59.5Z, in nanoseconds: half a second before midnight UTC. */
    private static final long LATE_ON_THE_20TH = 1_789_948_799L * SECOND + SECOND / 2;

    /** Nexmark q21's CASE, exactly as the benchmark writes it. */
    private static final String Q21_CHANNEL_ID = "CASE WHEN lower(channel) = 'apple' THEN '0' "
            + "WHEN lower(channel) = 'google' THEN '1' ELSE REGEXP_EXTRACT(url, '(&|^)channel_id=([^&]*)', 2) END";

    private static final StreamSchema BID = StreamSchema.builder("bid")
            .field("auction", Types.int64())
            .field("url", Types.string().withNullable(true))
            .field("date_time", Types.timestamp().withNullable(true))
            .field("channel", Types.string())
            .build();

    @Test
    void dateFormatRendersTheInstantInUtc() {
        assertThat(one("SELECT DATE_FORMAT(date_time, 'yyyy-MM-dd') FROM bid", "x", LATE_ON_THE_20TH))
                .isEqualTo("2026-09-20");
        assertThat(one("SELECT DATE_FORMAT(date_time, 'yyyy-MM-dd HH:mm:ss.SSS') FROM bid", "x", LATE_ON_THE_20TH))
                .isEqualTo("2026-09-20 23:59:59.500");
    }

    @Test
    void dateFormatDoesNotDependOnTheMachinesZone() {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
            assertThat(one("SELECT DATE_FORMAT(date_time, 'yyyy-MM-dd') FROM bid", "x", LATE_ON_THE_20TH))
                    .isEqualTo("2026-09-20");
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    @Test
    void dateFormatBeforeTheEpochRoundsTowardsTheEarlierSecond() {
        // -0.5 s is 1969-12-31T23:59:59.5Z. Truncating division would make it 00:00:00 on the 1st.
        assertThat(one("SELECT DATE_FORMAT(date_time, 'yyyy-MM-dd HH:mm:ss') FROM bid", "x", -SECOND / 2))
                .isEqualTo("1969-12-31 23:59:59");
    }

    @SuppressWarnings("NullAway") // nulls passed on purpose
    @Test
    void dateFormatOfNullIsNull() {
        assertThat(one("SELECT DATE_FORMAT(date_time, 'yyyy-MM-dd') FROM bid", "x", null))
                .isNull();
    }

    @Test
    void regexpExtractTakesTheGroupAskedFor() {
        String sql = "SELECT REGEXP_EXTRACT(url, '(&|^)channel_id=([^&]*)', 2) FROM bid";
        assertThat(one(sql, "a=1&channel_id=7&b=2", 0L)).isEqualTo("7");
        assertThat(one(sql, "channel_id=9", 0L)).isEqualTo("9");
    }

    @Test
    void regexpExtractWithoutAGroupIsTheWholeMatch() {
        assertThat(one("SELECT REGEXP_EXTRACT(url, 'id=[0-9]+') FROM bid", "x?id=42&y", 0L))
                .isEqualTo("id=42");
    }

    @SuppressWarnings("NullAway") // nulls passed on purpose
    @Test
    void regexpExtractWithNoMatchIsNullRatherThanEmpty() {
        assertThat(one("SELECT REGEXP_EXTRACT(url, '(&|^)channel_id=([^&]*)', 2) FROM bid", "a=1", 0L))
                .isNull();
        assertThat(one("SELECT REGEXP_EXTRACT(url, 'a', 0) FROM bid", null, 0L)).isNull();
    }

    @Test
    void regexpExtractOfAGroupThatTookNoPartIsNull() {
        assertThat(one("SELECT REGEXP_EXTRACT(url, 'a(b)?c', 1) FROM bid", "ac", 0L))
                .isNull();
        assertThat(one("SELECT REGEXP_EXTRACT(url, 'a(b)?c', 1) FROM bid", "abc", 0L))
                .isEqualTo("b");
    }

    @Test
    void splitIndexKeepsEmptyFieldsAndCountsFromZero() {
        String url = "https://www.nexmark.com/xyz/abc/item.htm";
        assertThat(one("SELECT SPLIT_INDEX(url, '/', 0) FROM bid", url, 0L)).isEqualTo("https:");
        assertThat(one("SELECT SPLIT_INDEX(url, '/', 1) FROM bid", url, 0L)).isEqualTo("");
        assertThat(one("SELECT SPLIT_INDEX(url, '/', 3) FROM bid", url, 0L)).isEqualTo("xyz");
        assertThat(one("SELECT SPLIT_INDEX(url, '/', 5) FROM bid", url, 0L)).isEqualTo("item.htm");
    }

    @SuppressWarnings("NullAway") // nulls passed on purpose
    @Test
    void splitIndexPastTheLastFieldIsNull() {
        assertThat(one("SELECT SPLIT_INDEX(url, '/', 6) FROM bid", "a/b/c/d/e/f", 0L))
                .isNull();
        assertThat(one("SELECT SPLIT_INDEX(url, '/', 5) FROM bid", "a/b/c/d/e/f", 0L))
                .isEqualTo("f");
        assertThat(one("SELECT SPLIT_INDEX(url, '/', 0) FROM bid", "", 0L)).isNull();
        assertThat(one("SELECT SPLIT_INDEX(url, '/', 0) FROM bid", null, 0L)).isNull();
    }

    @Test
    void splitIndexSplitsOnTheWholeDelimiter() {
        assertThat(one("SELECT SPLIT_INDEX(url, '::', 1) FROM bid", "a::b:c::d", 0L))
                .isEqualTo("b:c");
    }

    @Test
    void nexmarkQ21ChoosesAChannelIdByComparingLoweredText() {
        String sql = "SELECT " + Q21_CHANNEL_ID + " FROM bid";
        assertThat(oneOnChannel(sql, "a&channel_id=5", "APPLE")).isEqualTo("0");
        assertThat(oneOnChannel(sql, "a&channel_id=5", "Google")).isEqualTo("1");
        assertThat(oneOnChannel(sql, "a&channel_id=5", "Baidu")).isEqualTo("5");
        assertThat(oneOnChannel(sql, "no id here", "Baidu")).isNull();
    }

    @Test
    void textInsideAnExpressionIsComparedForEqualityAndNeverOrdered() {
        assertThat(oneOnChannel("SELECT url FROM bid WHERE LOWER(channel) <> 'apple'", "kept", "Baidu"))
                .isEqualTo("kept");
        assertThatThrownBy(() -> plan("SELECT url FROM bid WHERE LOWER(channel) < 'b'"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("collation");
    }

    @Test
    void anArgumentThatMustBeALiteralIsRefusedWhenItIsNot() {
        assertThatThrownBy(() -> plan("SELECT REGEXP_EXTRACT(url, url, 1) FROM bid"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("text literal");
        assertThatThrownBy(() -> plan("SELECT SPLIT_INDEX(url, '/', CAST(auction AS INTEGER)) FROM bid"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("integer literal");
    }

    @Test
    void aMalformedLiteralIsRefusedAtRegistrationRatherThanOnEveryRow() {
        assertThatThrownBy(() -> plan("SELECT REGEXP_EXTRACT(url, '(unclosed', 1) FROM bid"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("does not compile");
        assertThatThrownBy(() -> plan("SELECT REGEXP_EXTRACT(url, '(a)', 2) FROM bid"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("group 2");
        assertThatThrownBy(() -> plan("SELECT SPLIT_INDEX(url, '', 1) FROM bid"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> plan("SELECT SPLIT_INDEX(url, '/', -1) FROM bid"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> plan("SELECT DATE_FORMAT(date_time, 'yyyy-MM-dd bbb') FROM bid"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("date-time pattern");
    }

    @Test
    void twoRegistrationsOfTheSameCallAreOneComputation() {
        assertThat(PhysicalPlanBuilder.explain(plan("SELECT REGEXP_EXTRACT(url, 'a(b)', 1) FROM bid")))
                .isEqualTo(PhysicalPlanBuilder.explain(plan("SELECT REGEXP_EXTRACT(url, 'a(b)', 1) FROM bid")));
        assertThat(plan("SELECT REGEXP_EXTRACT(url, 'a(b)', 1), DATE_FORMAT(date_time, 'yyyy') FROM bid"))
                .isEqualTo(plan("SELECT REGEXP_EXTRACT(url, 'a(b)', 1), DATE_FORMAT(date_time, 'yyyy') FROM bid"));
    }

    @Test
    void theIncrementalAnswerEqualsTheAnswerFromScratchIncludingRetractions() {
        String sql = "SELECT auction, DATE_FORMAT(date_time, 'yyyy-MM-dd') AS d, "
                + Q21_CHANNEL_ID + " AS channel_id, SPLIT_INDEX(url, '/', 1) AS dir "
                + "FROM bid";
        ZSetHarness.Change a = ZSetHarness.Change.insert(0, 1L, "x/channel_id=3", LATE_ON_THE_20TH, "Apple");
        ZSetHarness.Change b = ZSetHarness.Change.insert(0, 2L, "p/q&channel_id=4", LATE_ON_THE_20TH + SECOND, "Baidu");
        @SuppressWarnings("NullAway") // nulls passed on purpose
        ZSetHarness.Change c = ZSetHarness.Change.insert(0, 3L, null, null, "Google");
        List<ZSetHarness.Change> changes = List.of(a, b, c, a.retracted(), a, c.retracted(), b.retracted());

        Map<List<Object>, Long> maintained = ZSetHarness.maintained(BID, sql, changes, Long.MIN_VALUE);
        Map<List<Object>, Long> fromScratch = ZSetHarness.fromScratch(BID, sql, changes, Long.MIN_VALUE);
        assertThat(maintained).isEqualTo(fromScratch);
        assertThat(maintained)
                .as("the control: exactly one row survives, and its values are what the functions say")
                .containsExactly(Map.entry(Arrays.asList(1L, "2026-09-20", "0", "channel_id=3"), 1L));
    }

    // ---------------------------------------------------------------- helpers

    private static com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(BID).plan(sql));
    }

    /** One row with this url and channel through the query; the first output column, or null if dropped. */
    private static @Nullable Object oneOnChannel(String sql, String url, String channel) {
        Map<List<Object>, Long> out = ZSetHarness.maintained(
                BID, sql, List.of(ZSetHarness.Change.insert(0, 1L, url, 0L, channel)), Long.MIN_VALUE);
        return out.isEmpty() ? null : out.keySet().iterator().next().get(0);
    }

    /** One row through the query; the first output column. */
    private static Object one(String sql, String url, Long dateTime) {
        List<ZSetHarness.Change> input = new ArrayList<>();
        input.add(ZSetHarness.Change.insert(0, 1L, url, dateTime, "Baidu"));
        Map<List<Object>, Long> out = ZSetHarness.maintained(BID, sql, input, Long.MIN_VALUE);
        assertThat(out).hasSize(1);
        return out.keySet().iterator().next().get(0);
    }
}
