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
package com.ash.messaging.pravaha.it.debug;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.registry.DebugSessions;
import com.ash.messaging.pravaha.registry.DebugStep;

import static com.ash.messaging.pravaha.it.debug.DebugTestSupport.OWNER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim the whole debugger rests on: the same fork and the same steps give the same answers
 * (ADR-047, design section 16.4).
 *
 * <p>Two sessions are forked from one checkpoint and given an identical script -- single rows, a
 * batch, a watermark, a predicate -- and every report is compared field for field: the rows that
 * entered, each operator's rows in and out, the view's changes with their weights, the watermark,
 * and the sentence saying why the step stopped.
 *
 * <p>That is not a property this engine has for free. A live query's partitions are read by
 * separate pumps and its event time moves on a wall-clock tick, so two runs of it would not agree
 * on which row arrived when or on which window had fired. A fork has neither: the replay
 * interleaves its partitions in a fixed order, event time moves only when a step says so, and
 * every step waits for the lane to drain before it reports. Remove any of the three and this test
 * is what notices.
 */
@Timeout(240)
final class DebugDeterminismTest {

    private static final List<String> FIRST = List.of(
            DebugTestSupport.row("ann", 100, DebugTestSupport.SECOND),
            DebugTestSupport.row("bob", 250, DebugTestSupport.SECOND));

    private static final List<String> BEYOND = List.of(
            DebugTestSupport.row("ann", 7, 2 * DebugTestSupport.SECOND),
            DebugTestSupport.row("bob", 11, 2 * DebugTestSupport.SECOND),
            DebugTestSupport.row("dan", 13, 3 * DebugTestSupport.SECOND),
            DebugTestSupport.row("ann", 17, 3 * DebugTestSupport.SECOND),
            DebugTestSupport.row("eve", 19, 4 * DebugTestSupport.SECOND),
            DebugTestSupport.row("dan", 23, 4 * DebugTestSupport.SECOND),
            DebugTestSupport.row("bob", 29, 5 * DebugTestSupport.SECOND),
            DebugTestSupport.row("ann", 31, 5 * DebugTestSupport.SECOND));

    @Test
    void twoSessionsOverOneCheckpointReportTheSameThing(@TempDir Path dir) throws Exception {
        try (DebugTestSupport engine = new DebugTestSupport(dir, DebugTestSupport.TOTAL, List.of(0), FIRST, BEYOND)) {
            DebugSessions sessions = engine.registry().debugSessions();
            List<DebugStep.Request> script = List.of(
                    DebugStep.Request.row(),
                    DebugStep.Request.row(),
                    DebugStep.Request.rows(3),
                    DebugStep.Request.toWatermark(10 * DebugTestSupport.SECOND),
                    DebugStep.Request.toCommit(),
                    DebugStep.Request.until("total", ">", "360"),
                    DebugStep.Request.rows(20));

            List<DebugStep> first = run(sessions, engine.checkpoint(), script);
            List<DebugStep> second = run(sessions, engine.checkpoint(), script);

            assertThat(first).hasSameSizeAs(script);
            for (int index = 0; index < first.size(); index++) {
                DebugStep a = first.get(index);
                DebugStep b = second.get(index);
                assertThat(b.kind()).isEqualTo(a.kind());
                assertThat(b.sequence()).isEqualTo(a.sequence());
                assertThat(b.rowsIn())
                        .as("step %d (%s): the same rows entered, in the same order", index + 1, a.kind())
                        .isEqualTo(a.rowsIn());
                assertThat(b.operators())
                        .as("step %d: every operator saw and produced the same number of rows", index + 1)
                        .isEqualTo(a.operators());
                assertThat(b.viewChanges())
                        .as("step %d: the same changes, with the same weights", index + 1)
                        .isEqualTo(a.viewChanges());
                assertThat(b.watermarkNanos()).isEqualTo(a.watermarkNanos());
                assertThat(b.rowsConsumed()).isEqualTo(a.rowsConsumed());
                assertThat(b.viewSize()).isEqualTo(a.viewSize());
                assertThat(b.exhausted()).isEqualTo(a.exhausted());
                assertThat(b.stopped())
                        .as("step %d: and the same reason for stopping", index + 1)
                        .isEqualTo(a.stopped());
            }

            // Not vacuous: the script has to have actually done something.
            assertThat(first.stream().mapToLong(step -> step.rowsIn().size()).sum())
                    .as("the script consumed every row past the checkpoint")
                    .isEqualTo(BEYOND.size());
            assertThat(first.stream().anyMatch(step -> !step.viewChanges().isEmpty()))
                    .as("and the view moved while it did")
                    .isTrue();
        }
    }

    private static List<DebugStep> run(DebugSessions sessions, long checkpoint, List<DebugStep.Request> script) {
        String id = sessions.fork("spend", checkpoint, OWNER).id();
        List<DebugStep> steps = new ArrayList<>();
        try {
            for (DebugStep.Request request : script) {
                DebugStep step = sessions.step(id, request, OWNER);
                // The session id differs between the two runs and nothing else may, so it is
                // normalised away here rather than excluded from every comparison above.
                steps.add(new DebugStep(
                        "session",
                        step.sequence(),
                        step.kind(),
                        step.rowsIn(),
                        step.operators(),
                        step.viewChanges(),
                        step.watermarkNanos(),
                        step.rowsConsumed(),
                        step.viewSize(),
                        step.exhausted(),
                        step.stopped()));
            }
        } finally {
            sessions.end(id, OWNER);
        }
        return steps;
    }
}
