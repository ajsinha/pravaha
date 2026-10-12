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

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TSEQ-1: a view read comparing a window bound to a timestamp literal by equality was refused with
 * PRV-2021 ("converts between INT64 and TIMESTAMP_LTZ") while {@code >=} planned, on a HOP view's
 * {@code window_end}. Equality, inequality and {@code IN} answer exactly what the range forms do.
 */
class TimestampEqualityReadTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("volume")
            .field("user_id", Types.string())
            .field("window_end", Types.timestamp())
            .field("total", Types.int64())
            .build();

    private ViewCatalog catalog;

    private static long nanos(String instant) {
        Instant at = Instant.parse(instant);
        return at.getEpochSecond() * 1_000_000_000L + at.getNano();
    }

    @BeforeEach
    void setUp() {
        ServedView view = new ServedView("volume", SCHEMA, List.of(0, 1), 100_000);
        catalog = new ViewCatalog().register(view);
        long at = 1;
        for (String end : List.of("2026-10-01T09:03:00Z", "2026-10-01T09:03:30Z", "2026-10-01T09:04:00Z")) {
            view.applyValues(new Object[] {"u1", nanos(end), 10L}, 1, at++);
            view.applyValues(new Object[] {"u2", nanos(end), 20L}, 1, at++);
        }
        view.commit(at);
    }

    private List<String> read(String where) {
        return new ViewQuery(catalog)
                .execute("SELECT user_id, window_end FROM volume WHERE " + where).rows().stream()
                        .map(row -> row[0] + "@" + row[1])
                        .sorted()
                        .toList();
    }

    @Test
    void equalityOnAWindowBoundMatchesExactlyThatInstant() {
        String at = "TIMESTAMP '2026-10-01 09:03:30'";
        List<String> equal = read("window_end = " + at);

        assertThat(equal).hasSize(2).allMatch(row -> row.endsWith("@" + nanos("2026-10-01T09:03:30Z")));
        assertThat(equal).isEqualTo(read("window_end >= " + at + " AND window_end <= " + at));
        assertThat(read("user_id = 'u1' AND window_end = " + at)).hasSize(1);
    }

    @Test
    void inequalityAndInOnAWindowBoundAreExact() {
        assertThat(read("window_end <> TIMESTAMP '2026-10-01 09:03:30'")).hasSize(4);
        assertThat(read("window_end IN (TIMESTAMP '2026-10-01 09:03:00', TIMESTAMP '2026-10-01 09:04:00')"))
                .hasSize(4)
                .noneMatch(row -> row.endsWith("@" + nanos("2026-10-01T09:03:30Z")));
        assertThat(read("window_end = TIMESTAMP '2026-10-01 09:03:31'")).isEmpty();
    }
}
