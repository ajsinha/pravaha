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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asking a question about a maintained view.
 *
 * <p>The behaviour worth proving is not that SQL works -- it is the same planner and the same
 * operators a continuous query uses, and they are tested elsewhere. It is that a request/response
 * query <em>reuses</em> them, so a WHERE clause means exactly what it means in a continuous query
 * rather than nearly what it means. Two implementations of the same clause agree until the day they
 * do not, and that day is a support call about a number.
 */
class ViewQueryTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("tier", Types.string().withNullable(true))
            .field("total", Types.int64())
            .build();

    private ViewCatalog catalog;
    private ServedView view;

    @BeforeEach
    void setUp() {
        view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        catalog = new ViewCatalog().register(view);
        put("u1", "gold", 300);
        put("u2", "silver", 50);
        put("u3", null, 7);
        put("u4", "gold", 1200);
        view.commit(100);
    }

    private void put(String user, String tier, long total) {
        view.applyValues(new Object[] {user, tier, total}, 1, 100);
    }

    @Test
    void aWholeViewCanBeRead() {
        ViewQuery.Result result = new ViewQuery(catalog).execute("SELECT user_id, total FROM user_volume");

        assertThat(result.size()).isEqualTo(4);
        assertThat(result.schema().fields().stream().map(f -> f.name()).toList())
                .containsExactly("user_id", "total");
    }

    @Test
    void aWhereClauseIsTheSameWhereClauseAContinuousQueryUses() {
        ViewQuery.Result result = new ViewQuery(catalog).execute("SELECT user_id FROM user_volume WHERE total > 100");

        assertThat(result.rows().stream().map(row -> (String) row[0]).sorted().toList())
                .containsExactly("u1", "u4");
    }

    @Test
    void nullBehavesAsSqlSaysRatherThanAsAnAbsence() {
        // u3 has no tier. `tier = 'gold'` must not match it, and `tier IS NULL` must -- the same
        // three-valued logic the streaming path uses, because it is literally the same predicate.
        ViewQuery query = new ViewQuery(catalog);

        assertThat(query.execute("SELECT user_id FROM user_volume WHERE tier = 'gold'")
                        .size())
                .isEqualTo(2);
        assertThat(query.execute("SELECT user_id FROM user_volume WHERE tier IS NULL")
                        .rows()
                        .get(0)[0])
                .isEqualTo("u3");
    }

    @Test
    void aPointLookupIsJustAQueryWithAKeyPredicate() {
        // Tier 1 and tier 2 are the same thing at the SQL surface; the difference is only how much
        // of the view a predicate touches.
        ViewQuery.Result result =
                new ViewQuery(catalog).execute("SELECT tier, total FROM user_volume WHERE user_id = 'u1'");

        assertThat(result.size()).isEqualTo(1);
        assertThat(result.rows().get(0)[0]).isEqualTo("gold");
        assertThat(result.rows().get(0)[1]).isEqualTo(300L);
    }

    @Test
    void aComputedColumnWorksBecauseItIsTheSameExpressionTree() {
        ViewQuery.Result result =
                new ViewQuery(catalog).execute("SELECT user_id, total * 2 FROM user_volume WHERE user_id = 'u2'");

        assertThat(result.rows().get(0)[1]).isEqualTo(100L);
    }

    @Test
    void anAggregateOverTheWholeViewIsAnswered() {
        ViewQuery.Result result = new ViewQuery(catalog).execute("SELECT SUM(total) FROM user_volume");

        assertThat(result.rows().get(0)[0]).isEqualTo(1557L);
    }

    @Test
    void onlyCommittedRowsAreVisible() {
        // A request/response read sees the same committed state a consistent subscription sees.
        // Reading uncommitted work would make a one-shot query disagree with the continuous query
        // that maintains the view, which is the one inconsistency this whole design exists to avoid.
        put("u5", "bronze", 9);

        assertThat(new ViewQuery(catalog)
                        .execute("SELECT user_id FROM user_volume")
                        .size())
                .isEqualTo(4);

        view.commit(200);

        assertThat(new ViewQuery(catalog)
                        .execute("SELECT user_id FROM user_volume")
                        .size())
                .isEqualTo(5);
    }

    @Test
    void aQueryNamingAnUnknownViewSaysWhatIsServed() {
        assertThatThrownBy(() -> new ViewQuery(catalog).execute("SELECT * FROM nowhere"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("nowhere");
    }

    @Test
    void queryingWithNoViewsRegisteredExplainsWhereViewsComeFrom() {
        assertThatThrownBy(() -> new ViewQuery(new ViewCatalog()).execute("SELECT * FROM anything"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4023")
                .hasMessageContaining("registering a continuous query");
    }

    @Test
    void joiningTwoViewsInOneRequestIsRefusedWithTheReason() {
        // Deliberately excluded by ADR-030: the views are maintained separately, and joining them
        // in a request would compute rather than look up -- which is the analytics engine this
        // product is not.
        StreamSchema other = StreamSchema.builder("other")
                .field("user_id", Types.string())
                .field("note", Types.string())
                .build();
        catalog.register(new ServedView("other", other, List.of(0), 10));

        assertThatThrownBy(() -> new ViewQuery(catalog)
                        .execute("SELECT a.user_id FROM user_volume a JOIN other b ON a.user_id = b.user_id"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("reads exactly one view");
    }
}
