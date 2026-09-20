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
package com.ash.messaging.pravaha.it.qa.perf;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate P3, measured on the machine that exists.
 *
 * <p>Gate P3 asks for Profile B -- a ten-second tumbling COUNT, SUM and AVG over 100 000 keys drawn
 * Zipf s = 1.1 -- at <strong>350 000 records per second per lane</strong>. The wave-4 gate pack
 * recorded it as NOT MEASURABLE HERE for the same three hardware confounds as P2, and it has stayed
 * unmeasured since. The owner's instruction on 2026-09-19 was to measure on this machine and name
 * it, so this measures it and names it.
 *
 * <p>The target below is design section 5.2's, unchanged, and a result under it prints as NOT
 * REACHED with the figure.
 *
 * <p><strong>The whole SQL path, on purpose.</strong> Profile B cannot be built by hand the way
 * Profile A can: its plan is a window assigner, a sliced aggregate and a keyed state map, and
 * assembling those directly would measure a pipeline the planner never produces. So this registers
 * the SQL and feeds the registry, which means the number includes planning's output faithfully and
 * includes the served view's apply -- and is therefore lower than a lane-only figure would be. It
 * is also the number a deployment would see.
 *
 * <p><strong>Not part of the default build.</strong> Named {@code *IT}, which surefire excludes.
 *
 * <pre>
 * ./mvnw -o -pl pravaha-it test -Dtest=ProfileBGateIT \
 *     -DfailIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@Timeout(3600)
final class ProfileBGateIT {

    /** Design section 5.2, NFR-2a, Profile B. */
    private static final double TARGET = 350_000d;

    private static final String TARGET_SOURCE = "design section 5.2 NFR-2a, Profile B, per lane-core";

    private static final int POOL = Integer.getInteger("pravaha.gate.p3.pool", 1 << 18);
    private static final long ROWS = Long.getLong("pravaha.gate.p3.rows", 2_000_000L);
    private static final int PASSES = Integer.getInteger("pravaha.gate.p3.passes", 5);
    private static final int WARMUPS = Integer.getInteger("pravaha.gate.p3.warmups", 2);

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private static final Duration DRAIN = Duration.ofMinutes(5);

    @Test
    void profileBWindowedAggregateOnOneLane() {
        MemoryAccess access = MemoryAccess.best();
        try (ProfileBRows rows = ProfileBRows.encode(access, POOL)) {
            System.out.printf(
                    "%n  Profile B pool: %,d rows, %,d distinct users of %,d ranks drawn Zipf s = %.1f, "
                            + "%,d distinct (user, window) groups over %d ten-second windows%n",
                    rows.rows(),
                    rows.distinctKeys(),
                    ProfileBRows.KEYS,
                    ProfileBRows.ZIPF_S,
                    rows.groups(),
                    ProfileBRows.WINDOWS);

            double[] samples = new double[PASSES];
            for (int pass = 0; pass < WARMUPS + PASSES; pass++) {
                double rate = onePass(rows);
                if (pass >= WARMUPS) {
                    samples[pass - WARMUPS] = rate;
                }
            }
            GateReading reading = new GateReading(
                    "Gate P3, Profile B end to end -- SQL planned, windowed aggregate over Zipf-skewed "
                            + "keys, one lane, results applied to a served view",
                    "rows/s",
                    TARGET,
                    TARGET_SOURCE,
                    samples);
            System.out.print(reading.report(MachineState.now()));
            assertThat(reading.best())
                    .as("a pass that aggregated nothing is not a fast pass")
                    .isGreaterThan(0);
        }
    }

    /** One timed pass of {@link #ROWS} rows through a registered Profile B query. */
    private static double onePass(ProfileBRows rows) {
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, ProfileBRows.schema())) {
            RegisteredQuery query = registry.register("p3_profile_b", ProfileBRows.sql(), List.of(0, 1), DANA);
            BinaryRowView view = new BinaryRowView(RowLayout.of(ProfileBRows.schema()));

            long began = System.nanoTime();
            for (long i = 0; i < ROWS; i++) {
                int index = (int) (i % rows.rows());
                view.wrap(rows.region(), (int) rows.offset(index));
                while (!query.accept(view)) {
                    Thread.onSpinWait();
                }
            }
            if (!query.awaitApplied(DRAIN)) {
                throw new AssertionError("the query did not drain within " + DRAIN);
            }
            long took = System.nanoTime() - began;

            // Outside the clock: close every window, which is what turns accumulated state into
            // answers. Without it a run could aggregate into a state map nobody ever read and the
            // rate would be a rate for work with no result.
            query.advanceWatermark(ProfileBRows.watermarkPastEverything());
            query.awaitApplied(DRAIN);
            requireWorkHappened(query, rows);
            return ROWS / (took / 1e9);
        }
    }

    /**
     * The assertion that stops this measuring an aggregate that aggregated nothing.
     *
     * <p>Every row fed must have been taken, and once the watermark has passed every window the
     * view must hold exactly the distinct (user, window) groups the pool contains -- a number
     * counted while the pool was encoded rather than guessed here. A windowed query that drops
     * rows, refuses keys past a ceiling or never closes a window fails this rather than reporting
     * a fine throughput for nothing.
     */
    private static void requireWorkHappened(RegisteredQuery query, ProfileBRows rows) {
        if (query.rowsIn() != ROWS) {
            throw new AssertionError(
                    "the registry took " + query.rowsIn() + " rows of " + ROWS + "; the pass fed less than it timed");
        }
        int held = query.view().size();
        if (held != rows.groups()) {
            throw new AssertionError("the view holds " + held + " groups and the pool contains " + rows.groups()
                    + " distinct (user, window) pairs; the aggregate did not produce the answer this pass was "
                    + "timed for");
        }
    }
}
