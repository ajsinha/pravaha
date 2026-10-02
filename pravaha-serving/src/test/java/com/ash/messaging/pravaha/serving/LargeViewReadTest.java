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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BIGREAD-1: a read over a view larger than one arena holds. A read was one unbounded batch, so its
 * arenas were never reclaimed: {@code SELECT *} over a million-row view was refused {@code PRV-3001}
 * (the projection's arena) where {@code PRV-4024} is the documented answer, and {@code COUNT(*)} failed
 * with a bare {@code Index -1 out of bounds for length 64} -- the read's own arena exhausted.
 *
 * <p>Wide rows reach both arenas' 64 MiB in tens of thousands of rows, which is what the first two
 * tests use; the third crosses the million-row ceiling itself.
 */
@Timeout(300)
class LargeViewReadTest {

    private static final StreamSchema WIDE = StreamSchema.builder("wide")
            .field("id", Types.int64())
            .field("payload", Types.string())
            .build();

    private static final StreamSchema NARROW = StreamSchema.builder("narrow")
            .field("id", Types.int64())
            .field("n", Types.int64())
            .build();

    private static final int WIDE_ROWS = 90_000;

    private static ViewQuery wide() {
        ServedView view = new ServedView("wide", WIDE, List.of(0), WIDE_ROWS);
        String payload = "x".repeat(900);
        for (long i = 0; i < WIDE_ROWS; i++) {
            view.applyValues(new Object[] {i, payload}, 1, 100);
        }
        view.commit(100);
        return new ViewQuery(new ViewCatalog().register(view));
    }

    @Test
    void aReadOfMoreRowsThanOneArenaHoldsIsAnsweredInFull() {
        ViewQuery queries = wide();
        assertThat(queries.execute("SELECT * FROM wide").rows()).hasSize(WIDE_ROWS);
        assertThat(queries.execute("SELECT COUNT(*) AS n FROM wide").rows().get(0)[0])
                .isEqualTo((long) WIDE_ROWS);
        assertThat(queries.execute("SELECT SUM(id) AS s FROM wide").rows().get(0)[0])
                .isEqualTo((long) WIDE_ROWS * (WIDE_ROWS - 1) / 2);
    }

    @Test
    void aGroupedReadOverManyBatchesStillAgreesWithTheRows() {
        ViewQuery queries = wide();
        List<Object[]> grouped = queries.execute("SELECT payload, COUNT(*) AS n FROM wide GROUP BY payload")
                .rows();
        assertThat(grouped).hasSize(1);
        assertThat(grouped.get(0)[1]).isEqualTo((long) WIDE_ROWS);
    }

    @Test
    void pastTheResultCeilingTheReadIsRefusedWithItsOwnCodeAndACountStillAnswers() {
        int rows = ViewQuery.MAX_RESULT_ROWS + 10;
        ServedView view = new ServedView("narrow", NARROW, List.of(0), rows);
        for (long i = 0; i < rows; i++) {
            view.applyValues(new Object[] {i, 1L}, 1, 100);
        }
        view.commit(100);
        ViewQuery queries = new ViewQuery(new ViewCatalog().register(view));

        assertThatThrownBy(() -> queries.execute("SELECT * FROM narrow"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4024");
        assertThat(queries.execute("SELECT COUNT(*) AS c FROM narrow").rows().get(0)[0])
                .isEqualTo((long) rows);
    }
}
