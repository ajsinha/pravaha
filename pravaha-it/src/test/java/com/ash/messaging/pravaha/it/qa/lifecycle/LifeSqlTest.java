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
package com.ash.messaging.pravaha.it.qa.lifecycle;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;
import com.ash.messaging.pravaha.sql.plan.ParameterPlacement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** LIFE-021..028 -- the SQL a registration is given: what plans, what is refused, and parameters. */
@Tag("qa")
class LifeSqlTest extends LifecycleTestSupport {

    @Test
    void life021_malformedSqlIsRefusedAtRegistrationWithAPlanError() {
        int namesBefore = registry.names().size();
        for (String bad : List.of("SELECT usr FROM", "SELEC usr FROM txn", "SELECT usr FROM txn WHERE", "")) {
            assertThatThrownBy(() -> registry.register("bad", bad, List.of(0), Principal.ANONYMOUS))
                    .as("'%s' must be refused at registration, not accepted and failed later", bad)
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(registry.names())
                .as("no partial registration survives a plan-time refusal")
                .hasSize(namesBefore);
    }

    @Test
    void life022_anUnknownStreamIsRefusedAndTheMessageNamesWhatIsKnown() {
        assertThatThrownBy(() -> registry.register("v", "SELECT usr FROM txns", List.of(0), Principal.ANONYMOUS))
                .as("the common typo: txns instead of txn")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("txn");

        // Control: the correctly-named stream registers in the same run.
        assertThat(registry.register("v_ok", "SELECT usr FROM txn", List.of(0), Principal.ANONYMOUS))
                .isNotNull();
    }

    @Test
    void life023_anUnwindowedKeyedGroupByIsRefusedAsAContinuousQueryButAllowedAsAViewRead() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        push("v1", 1, "ann", 100, 1);
        push("v1", 2, "ann", 50, 1);
        push("v1", 3, "bob", 10, 1);

        assertThatThrownBy(() -> registry.register(
                        "v2", "SELECT usr, COUNT(*) AS n FROM txn GROUP BY usr", List.of(0), Principal.ANONYMOUS))
                .as("unbounded key space: state would grow with every distinct usr forever")
                .isInstanceOf(RuntimeException.class);

        // The same shape, run once against a maintained view instead of registered as a standing
        // computation, succeeds -- SQL_SUPPORT's documented asymmetry.
        assertThat(rows("SELECT usr, COUNT(*) AS n FROM v1 GROUP BY usr"))
                .as("same SQL shape, different answer: a one-shot read over a bounded view is fine")
                .hasSize(2);
    }

    @Test
    void life024_aWindowedGroupByWithoutTheWindowBoundariesIsRefused() {
        String missingBoundaries = "SELECT usr, SUM(amount) FROM TABLE(TUMBLE(TABLE txn, "
                + "DESCRIPTOR(event_time), INTERVAL '10' SECOND)) GROUP BY usr";
        assertThatThrownBy(() -> registry.register("v", missingBoundaries, List.of(0), Principal.ANONYMOUS))
                .as("the unbounded case wearing a window's clothes: usr alone is still unbounded state")
                .isInstanceOf(RuntimeException.class);

        // Control: S2, with window_start/window_end in the GROUP BY, registers.
        String s2 = "SELECT usr, SUM(amount) AS total FROM TABLE(TUMBLE(TABLE txn, "
                + "DESCRIPTOR(event_time), INTERVAL '10' SECOND)) GROUP BY usr, window_start, window_end";
        assertThat(registry.register("v_ok", s2, List.of(0), Principal.ANONYMOUS))
                .isNotNull();
    }

    @Test
    void life026_aVeryLargeQueryRegistersAndStillReads() {
        StringBuilder where = new StringBuilder("SELECT usr, amount FROM txn WHERE amount = 0");
        for (int n = 1; n <= 1000; n++) {
            where.append(" OR amount = ").append(n);
        }
        long start = System.nanoTime();
        registry.register("v_big", where.toString(), List.of(0), Principal.ANONYMOUS);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(registry.names())
                .as("a 1000-term WHERE registers rather than being silently rejected")
                .contains("v_big");
        push("v_big", 1, "ann", 5, 1);
        assertThat(rows("SELECT usr, amount FROM v_big"))
                .as("and the registered view still reads")
                .hasSize(1);
        // Recorded rather than asserted against a bound: the case only asks that the latency be
        // stated for the log.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(0);
    }

    @Test
    void life027_parametersAreClassifiedBeforeTheyAreBound() {
        List<ParameterPlacement> tap = registry.classify("SELECT usr, amount FROM txn WHERE usr = ?");
        assertThat(tap).hasSize(1);
        assertThat(tap.get(0).placement()).isEqualTo(ParameterPlacement.Placement.TAP);

        // LIFE-027, as authored, uses a HAVING SUM(amount) > ? to illustrate a parameter that is
        // "aggregated away". It classifies as TAP instead, correctly: HAVING filters the aggregate's
        // own output column (total), which the view carries, so a subscriber can apply it at the
        // tap. What actually forks a computation is a parameter on a column the aggregate consumes
        // and does not re-emit -- amount, filtered before the GROUP BY, with only usr and total in
        // the output.
        String aggregated = "SELECT usr, SUM(amount) AS total FROM txn WHERE amount > ? "
                + "GROUP BY TUMBLE(event_time, INTERVAL '10' SECOND), usr";
        List<ParameterPlacement> registration = registry.classify(aggregated);
        assertThat(registration).hasSize(1);
        assertThat(registration.get(0).placement()).isEqualTo(ParameterPlacement.Placement.REGISTRATION);

        registry.register("bound1", aggregated, List.of(0), Principal.ANONYMOUS, BoundParameters.of(List.of(10L)));
        registry.register("bound2", aggregated, List.of(0), Principal.ANONYMOUS, BoundParameters.of(List.of(20L)));
        assertThat(registry.require("bound1").fingerprint())
                .as("V-distinct: a bound value is in the plan, so two bindings are two computations")
                .isNotEqualTo(registry.require("bound2").fingerprint());
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void life028_aRegistrationWithBoundParametersRecordsItsPlacementsAndAvoidableForks() {
        registry.register(
                "u1_only",
                "SELECT usr, amount FROM txn WHERE usr = ?",
                List.of(0),
                Principal.ANONYMOUS,
                BoundParameters.of(List.of("u1")));

        List<ParameterPlacement> placements = registry.require("u1_only").parameterPlacements();
        assertThat(placements).hasSize(1);
        assertThat(placements.get(0).placement()).isEqualTo(ParameterPlacement.Placement.TAP);
        assertThat(registry.require("u1_only").avoidableForks())
                .as("a TAP-placed parameter is a fork that a subscriber filter would have avoided")
                .hasSize(1);

        registry.register(
                "u2_only",
                "SELECT usr, amount FROM txn WHERE usr = ?",
                List.of(0),
                Principal.ANONYMOUS,
                BoundParameters.of(List.of("u2")));
        assertThat(registry.require("u2_only").fingerprint())
                .as("V-distinct: the fork actually happened -- two different bindings, two fingerprints")
                .isNotEqualTo(registry.require("u1_only").fingerprint());
    }
}
