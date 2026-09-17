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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SX-5's third channel, re-measured (docs/qa/FINDINGS.md, docs/qa/logs/SECX.md SECX-091).
 *
 * <p>The finding's own numbers -- denied median 23.8ms, absent median 13.4ms, 100 iterations --
 * predate the fix that closed the refusal-code channel ({@link ViewQuery#execute(String,
 * Principal)}, and see {@code SqlPlanner.referencedTable}). That fix moved authorization ahead of
 * planning by obtaining the queried name from the parse tree alone, which the coordinator's own
 * words predicted would change this number: "authorizing before planning makes both paths do more
 * of the same work". This test re-measures under the current code rather than assuming that
 * prediction held.
 *
 * <p><strong>The threat model measured is the one the finding itself states</strong>: "an oracle
 * usable without ever being authorized for anything" (docs/qa/FINDINGS.md). That is a principal
 * denied every view, which is exactly what {@code
 * ViewQueryAuthorizationTest.sx5_aDeniedCallerCannotTellARealViewFromAnAbsentOneByTheCode} already
 * seed-proves the *codes* agree for. This test asks the remaining question: do the two refusals
 * also take indistinguishable *time*?
 *
 * <p><strong>Mechanism, read off {@code SqlPlanner} directly.</strong> {@code referencedTable}
 * parses only -- it never calls {@code Planner.validate}, so it cannot tell a real name from an
 * invented one, and it is the same cost for both. For a principal denied everything, {@code
 * ViewQuery.execute} calls it, gets a name either way, asks the policy, is refused, and throws --
 * for both a real and an absent view, {@code planFor} (the full parse+validate+optimize pipeline)
 * is <em>never reached</em>. Denied and absent now run the identical sequence of calls
 * (referencedTable, one policy.mayRead, one audit.record, one exception construction); the only
 * difference left is the view-name string itself. That is what "do more of the same work" meant,
 * and it is why the gap is expected to have narrowed sharply rather than merely a little.
 *
 * <p><strong>Method.</strong> Both cases run through the identical harness, on the same
 * single-threaded JVM, interleaved one-real-one-absent rather than run as two separate blocks --
 * a sequential run would let GC pauses or thermal throttling during one block bias only that
 * block's numbers, where interleaving spreads such noise evenly across both. 2,000 warm-up calls
 * of each are discarded before any timing starts, so the measured run is not paying for classes
 * Calcite has not loaded yet or a JIT compilation neither path has earned. 5,000 timed calls of
 * each follow (the finding used 100; more are taken here because the gap being measured is now
 * expected to be small, and a small gap needs more samples to distinguish from sampling noise than
 * a 10ms gap did). Median and p99 are reported, matching the finding's own statistic, from {@code
 * System.nanoTime()} around one {@code ViewQuery.execute} call each, including the exception it
 * throws.
 *
 * <p><strong>What this cannot control for</strong>: this machine's own load at the moment it runs,
 * which is why the number is reported rather than gated on -- see this class's own assertions,
 * which are structural only. A CI runner sharing a host with other work will see a noisier number
 * than a quiet workstation; that is a property of where this ran, not of the code being measured.
 */
class Sx5LatencyMeasurementTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("payroll")
            .field("employee_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal NOBODY = new Principal("outsider", "acme", Set.of(), Map.of());

    private static final String REAL_SQL = "SELECT employee_id FROM payroll";
    private static final String ABSENT_SQL = "SELECT employee_id FROM zzz_never_registered";

    private static final int WARMUP = 2_000;
    private static final int TIMED = 5_000;

    /**
     * Re-measures the finding's own comparison: a principal authorized for nothing, denied a real,
     * registered, populated view versus refused a name that was never registered at all.
     */
    @Test
    void deniedVersusAbsentLatencyUnderTheCurrentCode() {
        ViewCatalog catalog = new ViewCatalog();
        ServedView view = new ServedView("payroll", SCHEMA, List.of(0), 10_000);
        catalog.register(view);
        view.applyValues(new Object[] {"e1", 90_000L}, 1, 100);
        view.applyValues(new Object[] {"e2", 105_000L}, 1, 100);
        view.commit(100);

        ViewQuery queries = new ViewQuery(
                catalog, (principal, name) -> AccessDecision.deny("outsider"), new AuditSink.InMemory(20_000));

        // Sanity: both really do refuse, and with the code this finding is about -- not some other
        // failure this harness would otherwise be timing by accident.
        assertThat(codeOf(queries, REAL_SQL)).isEqualTo("PRV-7002");
        assertThat(codeOf(queries, ABSENT_SQL)).isEqualTo("PRV-7002");

        // Warm-up: both paths, interleaved, discarded.
        for (int i = 0; i < WARMUP; i++) {
            refuse(queries, REAL_SQL);
            refuse(queries, ABSENT_SQL);
        }

        long[] deniedNanos = new long[TIMED];
        long[] absentNanos = new long[TIMED];
        for (int i = 0; i < TIMED; i++) {
            deniedNanos[i] = timeOne(queries, REAL_SQL);
            absentNanos[i] = timeOne(queries, ABSENT_SQL);
        }

        double deniedMedianMs = medianMs(deniedNanos);
        double deniedP99Ms = p99Ms(deniedNanos);
        double absentMedianMs = medianMs(absentNanos);
        double absentP99Ms = p99Ms(absentNanos);
        double gapMs = deniedMedianMs - absentMedianMs;

        System.out.println("SX-5 latency channel -- re-measured (" + TIMED + " interleaved iterations each, "
                + WARMUP + " warm-up each, single-threaded, "
                + Runtime.getRuntime().availableProcessors()
                + " CPUs visible to this JVM):");
        System.out.printf("  denied (real, forbidden):  median %.3f ms, p99 %.3f ms%n", deniedMedianMs, deniedP99Ms);
        System.out.printf("  absent (never registered): median %.3f ms, p99 %.3f ms%n", absentMedianMs, absentP99Ms);
        System.out.printf("  gap: %.3f ms (median), was 10.4 ms in the finding's own figure (23.8 - 13.4)%n", gapMs);
        System.out.printf(
                "  finding's original ratio: %.2fx; this run's ratio: %.2fx%n",
                23.8 / 13.4, deniedMedianMs / absentMedianMs);

        assertThat(deniedMedianMs).isPositive();
        assertThat(absentMedianMs).isPositive();
    }

    /**
     * A second, exploratory check, beyond what the finding asks for: a policy that denies one
     * specific known name and defaults to allowing anything it has no rule for, rather than denying
     * the principal outright. This is not the finding's own threat model -- SX-5 is stated for "an
     * oracle usable without ever being authorized for anything" (a principal denied everything, the
     * case measured above) -- but it is worth a look, because it is the shape a real deployment's
     * policy more plausibly has (an ACL restricting specific sensitive names, not a blanket denial),
     * and {@code SqlPlanner.plan}'s validation step is only reached when the policy allows, which an
     * unmatched name does here. If this gap is large, it is a different, narrower finding: whether a
     * name is on a restriction list, not whether it is registered -- distinguishing those two things
     * is not what SX-5 is about, since a real, unrestricted view answers with data, not a refusal,
     * under this policy shape.
     */
    @Test
    void namedDenialVersusDefaultAllowedAbsentNameIsADifferentQuestion() {
        ViewCatalog catalog = new ViewCatalog();
        ServedView view = new ServedView("payroll", SCHEMA, List.of(0), 10_000);
        catalog.register(view);
        view.applyValues(new Object[] {"e1", 90_000L}, 1, 100);
        view.commit(100);

        ViewQuery queries = new ViewQuery(
                catalog,
                (principal, name) ->
                        "payroll".equals(name) ? AccessDecision.deny("restricted") : AccessDecision.allow(),
                new AuditSink.InMemory(20_000));

        assertThat(codeOf(queries, REAL_SQL)).isEqualTo("PRV-7002");
        assertThat(codeOf(queries, ABSENT_SQL)).isEqualTo("PRV-2002");

        for (int i = 0; i < WARMUP; i++) {
            refuse(queries, REAL_SQL);
            refuse(queries, ABSENT_SQL);
        }

        long[] deniedNanos = new long[TIMED];
        long[] absentNanos = new long[TIMED];
        for (int i = 0; i < TIMED; i++) {
            deniedNanos[i] = timeOne(queries, REAL_SQL);
            absentNanos[i] = timeOne(queries, ABSENT_SQL);
        }

        double deniedMedianMs = medianMs(deniedNanos);
        double absentMedianMs = medianMs(absentNanos);

        System.out.println("SX-5 exploratory check -- named denial (PRV-7002, short-circuits before "
                + "planning) versus a default-allowed absent name (PRV-2002, reaches full "
                + "parse+validate before failing), " + TIMED + " interleaved iterations each:");
        System.out.printf("  denied (payroll, restricted by name): median %.3f ms%n", deniedMedianMs);
        System.out.printf("  absent (default-allowed, then PRV-2002): median %.3f ms%n", absentMedianMs);
        System.out.printf("  gap: %.3f ms (median)%n", absentMedianMs - deniedMedianMs);

        assertThat(deniedMedianMs).isPositive();
        assertThat(absentMedianMs).isPositive();
    }

    private static void refuse(ViewQuery queries, String sql) {
        try {
            queries.execute(sql, NOBODY);
            throw new IllegalStateException("expected a refusal for " + sql);
        } catch (PravahaException expected) {
            // expected: NOBODY is denied everything.
        }
    }

    private static long timeOne(ViewQuery queries, String sql) {
        long start = System.nanoTime();
        try {
            queries.execute(sql, NOBODY);
            throw new IllegalStateException("expected a refusal for " + sql);
        } catch (PravahaException expected) {
            // timed on purpose: the refusal itself, exception construction included, is the answer
            // an unauthorized caller actually receives and can measure the timing of.
        }
        return System.nanoTime() - start;
    }

    private static String codeOf(ViewQuery queries, String sql) {
        try {
            queries.execute(sql, NOBODY);
            throw new IllegalStateException("expected a refusal for " + sql);
        } catch (PravahaException refused) {
            java.util.regex.Matcher found =
                    java.util.regex.Pattern.compile("PRV-\\d{4}").matcher(String.valueOf(refused.getMessage()));
            return found.find() ? found.group() : "no code";
        }
    }

    private static double medianMs(long[] nanos) {
        return percentileMs(nanos, 0.50);
    }

    private static double p99Ms(long[] nanos) {
        return percentileMs(nanos, 0.99);
    }

    private static double percentileMs(long[] nanos, double percentile) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        int index = Math.min(sorted.length - 1, (int) Math.ceil(percentile * sorted.length) - 1);
        return sorted[Math.max(0, index)] / 1_000_000.0;
    }
}
