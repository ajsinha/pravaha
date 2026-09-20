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
import java.util.Optional;

/**
 * One query's per-operator numbers, gathered from the pipeline each of its lanes runs.
 *
 * <p>A query on four lanes is four copies of the same plan, each with its own counters, and the
 * plan an operator is looking at is one picture. So the copies are added: rows in and out and
 * state bytes sum, the watermark takes the lowest -- a query has reached what its slowest lane has
 * reached -- and the sampled self time and its sample count sum, which keeps the ratio between
 * them meaningful.
 *
 * <p>Extracted from {@code QueryExecution} rather than added to it: that class is at the size
 * where this project extracts, and the merge rules above are worth a paragraph of their own rather
 * than a comment inside an accessor.
 */
public final class OperatorTelemetry {

    private OperatorTelemetry() {}

    /**
     * Every pipeline's operators added together, in plan-node order.
     *
     * @return empty when the pipelines were compiled with per-operator measurement off -- which is
     *     not the same as a plan whose every counter is zero, and a caller has to be able to tell
     *     those apart before it draws anything
     */
    public static List<OperatorMetrics.Snapshot> merge(List<InterpretedPipeline> pipelines) {
        List<OperatorMetrics.Snapshot> total = null;
        for (InterpretedPipeline pipeline : pipelines) {
            List<OperatorMetrics.Snapshot> each = pipeline.operatorMetrics();
            if (each.isEmpty()) {
                continue;
            }
            if (total == null) {
                total = new ArrayList<>(each);
                continue;
            }
            if (total.size() != each.size()) {
                // Every lane runs the same plan, so this cannot happen without a bug -- and adding
                // mismatched lists would silently attribute one lane's operator to another's box.
                throw new IllegalStateException("this query's lanes hold plans of different shapes: " + total.size()
                        + " operators and " + each.size() + "; per-operator numbers cannot be added across them");
            }
            for (int i = 0; i < total.size(); i++) {
                total.set(i, total.get(i).plus(each.get(i)));
            }
        }
        return total == null ? List.of() : List.copyOf(total);
    }

    /**
     * The operator most of a query's own time goes into, among those that were sampled.
     *
     * <p>The one honest answer to "which box is the bottleneck", and honest only because the time
     * is measured rather than inferred from row counts: an operator's rows in and rows out say
     * what it passed on, not what it cost. A filter that drops 99 % of its input is not the
     * bottleneck for dropping them.
     *
     * <p>Empty when nothing was sampled -- measurement off, or a query too quiet to have reached a
     * sample yet. Empty is the right answer there; naming the first operator would not be.
     */
    public static Optional<OperatorMetrics.Snapshot> bottleneck(List<OperatorMetrics.Snapshot> operators) {
        return operators.stream()
                .filter(each -> each.sampledRows() > 0 && each.selfNanos() > 0)
                .max(java.util.Comparator.comparingLong(OperatorMetrics.Snapshot::selfNanos));
    }

    /** Sampled self time across every operator, which is what a share is a share of. */
    public static long totalSelfNanos(List<OperatorMetrics.Snapshot> operators) {
        long total = 0;
        for (OperatorMetrics.Snapshot each : operators) {
            total += each.selfNanos();
        }
        return total;
    }
}
