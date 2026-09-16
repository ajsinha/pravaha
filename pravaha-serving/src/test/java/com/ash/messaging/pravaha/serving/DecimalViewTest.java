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

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.Decimals;
import com.ash.messaging.pravaha.common.row.RowLayout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A view whose schema contains a DECIMAL column, which used to be a view nobody could query.
 *
 * <p>Finding TY-19. {@code SELECT id FROM n} failed with {@code field 1 ('amt') is fixed-width; use
 * the typed setter} -- on a column the query never named. The cause is that a request/response query
 * materialises the <em>whole</em> view row before the plan runs, because the plan reads its input by
 * ordinal; the projection is above that and cannot be consulted. DECIMAL had no case in the
 * materialiser and fell through to the string branch, so a view was unqueryable the moment its
 * schema contained a decimal, regardless of what any query asked for.
 *
 * <p>The tests below are deliberately about the columns that are <em>not</em> decimal. That is what
 * the finding is: the decimal column is collateral, and a test that only asserted on {@code amt}
 * would pass against a fix that special-cased the projection and left every other query broken.
 *
 * <p>Both ways a value reaches a view are covered, because they are different code and only one of
 * them was found first. {@code applyValues} takes an {@code Object[]}; {@code apply} takes a row off
 * the lane and decodes it. Each had its own DECIMAL hole.
 *
 * <p>This is not decimal <em>arithmetic</em>, which remains unbuilt and refused at plan time
 * (PRV-2021). It is a decimal value being carried through a scan without being computed on.
 */
class DecimalViewTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("n")
            .field("id", Types.int64())
            .field("amt", Types.decimal(10, 2))
            .field("note", Types.string())
            .build();

    private static ServedView populated() {
        ServedView view = new ServedView("n", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {1L, new BigDecimal("12.34"), "a"}, 1, 100);
        view.applyValues(new Object[] {2L, new BigDecimal("-5.00"), "b"}, 1, 100);
        view.commit(100);
        return view;
    }

    @Test
    void aQueryThatNeverNamesTheDecimalColumnIsAnswered() {
        ViewCatalog catalog = new ViewCatalog().register(populated());

        assertThat(new ViewQuery(catalog).execute("SELECT id FROM n").rows())
                .extracting(row -> row[0])
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(new ViewQuery(catalog).execute("SELECT id, note FROM n").rows())
                .extracting(row -> row[1])
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void aPredicateOverANonDecimalColumnStillSelectsRows() {
        // The filter runs over the materialised row, so this exercises the same scan the projection
        // does and would fail identically -- but it is the shape an application actually writes.
        ViewQuery.Result result =
                new ViewQuery(new ViewCatalog().register(populated())).execute("SELECT note FROM n WHERE id = 1");

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0)[0]).isEqualTo("a");
    }

    @Test
    void theDecimalColumnItselfComesBackAsABigDecimalRatherThanItsTwoLimbs() {
        // Once the scan stopped failing, this became reachable for the first time -- and the result
        // writer handed back the storage form, `new long[]{high, low}`, which reaches a caller as
        // "[J@301434fb". The scale lives in the schema and the limbs do not carry it, so the pair
        // is unreadable anywhere the schema is not also to hand.
        ViewQuery.Result result =
                new ViewQuery(new ViewCatalog().register(populated())).execute("SELECT id, amt FROM n WHERE id = 1");

        assertThat(result.rows().get(0)[1]).isEqualTo(new BigDecimal("12.34"));
    }

    @Test
    void aRowAppliedFromTheLaneIsQueryableOnTheSameTerms() {
        // The other way in. ServedView materialises every ordinal when a row is applied, so a
        // DECIMAL column was refused there too -- before any query existed to be poisoned.
        ServedView view = new ServedView("n", SCHEMA, List.of(0), 10_000);
        RowLayout layout = RowLayout.of(SCHEMA);
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 4)) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            long handle = arena.allocate(layout.rowSize(128));
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            BigDecimal amount = new BigDecimal("99.99");
            writer.setLong(0, 7L)
                    .setDecimal(1, Decimals.high(amount, 2), Decimals.low(amount, 2))
                    .setString(2, "z");
            writer.weight(1L).sequence(1L).eventTimestampNanos(100).commit();
            arena.trimTo(handle, writer.sizeSoFar());
            view.apply(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)), 100);
        }
        view.commit(100);

        ViewQuery.Result result = new ViewQuery(new ViewCatalog().register(view)).execute("SELECT id, amt FROM n");

        assertThat(result.rows().get(0)[0]).isEqualTo(7L);
        assertThat(result.rows().get(0)[1]).isEqualTo(new BigDecimal("99.99"));
    }

    @Test
    void aViewFedThroughTheSinkHoldsTheSameClassAsOneFedDirectly() {
        // The third door into a view, and the one that would have made a partial fix look complete:
        // the sink staged the two limbs as a long[], so a view filled through it held a different
        // class in the same column than one filled through applyValues -- and the query path, now
        // expecting a BigDecimal, would have failed only for views fed by a running query.
        ServedView view = new ServedView("n", SCHEMA, List.of(0), 10_000);
        ViewSink sink = new ViewSink(view, SCHEMA);
        BigDecimal amount = new BigDecimal("3.50");
        sink.begin()
                .setLong(0, 9L)
                .setDecimal(1, Decimals.high(amount, 2), Decimals.low(amount, 2))
                .setString(2, "s")
                .weight(1)
                .sequence(1)
                .commit();
        sink.commit(sink.appliedFrontier());

        ViewQuery.Result result = new ViewQuery(new ViewCatalog().register(view)).execute("SELECT note, amt FROM n");

        assertThat(result.rows().get(0)[0]).isEqualTo("s");
        assertThat(result.rows().get(0)[1]).isEqualTo(amount);
    }
}
