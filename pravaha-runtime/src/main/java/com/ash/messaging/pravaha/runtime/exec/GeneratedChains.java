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
package com.ash.messaging.pravaha.runtime.exec;

import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.plan.FilterOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.ProjectOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;

/**
 * Where a registered query's filters and projection are replaced by generated code (finding C-7).
 *
 * <p>When a lane's pipeline is built, each chain of filters and projections sitting directly on a
 * scan is offered to the installed {@link StageGenerator}. A chain it compiles runs as one generated
 * stage; a chain it refuses is built interpreted, exactly as it always was. Either way the pipeline
 * records one line per chain saying which path it is on and why, and the query's description
 * carries those lines, so an operator can see it.
 *
 * <p><strong>The generated stage is held to the interpreter's answer, byte for byte.</strong> It
 * allocates its output row only for a row that passed, with the reservation and trim the
 * interpreted projection uses, writes the same header, and pushes the same kind of row downstream: a
 * filter-only chain passes the very row it was given, as the interpreted filter does. A row that is
 * not a {@link BinaryRowView} -- which a lane never produces -- goes through the interpreted chain
 * built beside it, so there is no input the generated path handles differently.
 *
 * <p>Compiled when the lane's pipeline is built rather than swapped in later. A stage generated
 * before the first row has no interpreted state to hand over and no moment at which two
 * implementations are both live, which is what the earlier background-upgrade design
 * ({@code AdaptiveStage}, {@code StageUpgradeService}) had to reason about and why it is gone.
 */
public final class GeneratedChains {

    private static volatile StageGenerator installed;

    private GeneratedChains() {}

    /**
     * Installs the generator for every lane pipeline compiled from now on; null removes it.
     *
     * <p>Process-wide and read at compile time, like {@link InterpretedPipeline#measureOperators}: a
     * query already running keeps the path it was built on.
     */
    public static void install(StageGenerator generator) {
        installed = generator;
    }

    /** The installed generator, or null when every query runs interpreted. */
    public static StageGenerator installed() {
        return installed;
    }

    /** The scan a chain of filters and projections stands on, or null if something else intervenes. */
    static ScanOperator scanUnder(PhysicalOperator operator) {
        PhysicalOperator current = operator;
        while (current instanceof FilterOperator || current instanceof ProjectOperator) {
            current = current.inputs().get(0);
        }
        return current instanceof ScanOperator scan ? scan : null;
    }

    /** The chain as the description prints it, root first. */
    static String describe(PhysicalOperator root) {
        List<String> labels = new ArrayList<>();
        for (PhysicalOperator current = root; ; current = current.inputs().get(0)) {
            labels.add(current.label());
            if (current instanceof ScanOperator) {
                return String.join(" <- ", labels);
            }
        }
    }

    /**
     * The generated replacement for the chain rooted at {@code root}, or null to build it interpreted.
     *
     * @param paths where the outcome is recorded, generated or not
     */
    static RowProcessor generated(PhysicalOperator root, RowArena arena, RowProcessor downstream, List<String> paths) {
        StageGenerator generator = installed;
        if (generator == null || scanUnder(root) == null) {
            return null;
        }
        StageGenerator.Outcome outcome;
        try {
            outcome = generator.generate(root);
        } catch (RuntimeException e) {
            // A generator defect must cost the query its speed and never its answer.
            outcome = StageGenerator.Outcome.refused("the generator failed, which is a defect in it: " + e);
        }
        if (!outcome.generated()) {
            paths.add("interpreted: " + describe(root) + " -- " + outcome.reason());
            return null;
        }
        paths.add("generated: " + describe(root) + " -- " + outcome.reason());
        GeneratedRowStage stage = outcome.stage();
        RowProcessor fallback = interpreted(root, arena, downstream);
        return stage.projects()
                ? projecting(stage, root, arena, downstream, fallback)
                : filtering(stage, downstream, fallback);
    }

    private static RowProcessor filtering(GeneratedRowStage stage, RowProcessor downstream, RowProcessor fallback) {
        return row -> {
            if (!(row instanceof BinaryRowView binary)) {
                fallback.process(row);
                return;
            }
            if (stage.test(binary.region(), binary.offset())) {
                downstream.process(row);
            }
        };
    }

    private static RowProcessor projecting(
            GeneratedRowStage stage,
            PhysicalOperator root,
            RowArena arena,
            RowProcessor downstream,
            RowProcessor fallback) {
        RowLayout layout = RowLayout.of(root.outputSchema());
        BinaryRowView view = new BinaryRowView(layout);
        // The interpreted projection's reservation and trim, so the arena fills identically.
        int reserve = layout.rowSize(1024);
        int size = layout.fixedEnd();
        return row -> {
            if (!(row instanceof BinaryRowView binary)) {
                fallback.process(row);
                return;
            }
            MemoryRegion region = binary.region();
            int at = binary.offset();
            if (!stage.test(region, at)) {
                return;
            }
            long handle = arena.allocate(reserve);
            if (handle == ArenaHandle.NULL) {
                throw new PravahaException(
                        RuntimeErrors.ARENA_EXHAUSTED,
                        "the projection's arena is full; raise pravaha.lane.arena.slab-bytes or reduce pravaha.lane.batch-size");
            }
            MemoryRegion out = arena.regionOf(handle);
            int outAt = arena.offsetOf(handle);
            stage.project(region, at, out, outAt);
            arena.trimTo(handle, size);
            downstream.process(view.wrap(out, outAt));
        };
    }

    /** The chain built interpreted, without registering its scan: the fallback for a foreign row. */
    static RowProcessor interpreted(PhysicalOperator root, RowArena arena, RowProcessor downstream) {
        RowProcessor head = downstream;
        for (PhysicalOperator current = root;
                !(current instanceof ScanOperator);
                current = current.inputs().get(0)) {
            RowProcessor next = head;
            if (current instanceof FilterOperator filter) {
                head = row -> {
                    if (filter.predicate().test(row)) {
                        next.process(row);
                    }
                };
            } else {
                head = RowStages.projector((ProjectOperator) current, arena, next);
            }
        }
        return head;
    }
}
