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
package com.ash.messaging.pravaha.sql.plan;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator;
import com.ash.messaging.pravaha.sql.SqlErrors;

/**
 * {@code MIN} and {@code MAX} over an input that can retract, found before a row moves (MINRETRACT-1).
 *
 * <p>A MIN or MAX accumulator keeps the extreme, not the values under it, so a retraction of the
 * extreme cannot be answered: the previous one is gone. The aggregate refuses the retraction rather
 * than serve a stale extreme, and that refusal stopped the query -- windowed or not -- on the first
 * delete from a change feed or the first {@code op} column's retraction, at run time, after the
 * query had been accepted and had run for as long as the source happened not to delete.
 *
 * <p>Where it is knowable, the refusal moves to registration: a MIN or MAX whose input reads a
 * stream bound to a source that emits deletes (a CDC source, a file with an operation column) is
 * refused with {@code PRV-2076}, naming the aggregate and the stream. A stream nothing is bound to
 * is pushed by hand; the embedded engine asks {@link #extremeOver} before it delivers a retraction,
 * and refuses that call instead, with every query left running.
 */
public final class RetractedExtremes {

    private RetractedExtremes() {}

    /**
     * Refuses a plan with a {@code MIN} or {@code MAX} over an input that can carry a retraction.
     *
     * @param retracts whether rows read from the named stream can carry a negative weight; the
     *     registry answers it from the source bound to the stream
     */
    public static void check(PhysicalOperator plan, Predicate<String> retracts) {
        Optional<Found> found = find(plan, retracts);
        if (found.isPresent()) {
            throw new PravahaException(
                    SqlErrors.EXTREME_OVER_RETRACTIONS,
                    found.get().aggregate() + " cannot be maintained over an input that retracts: "
                            + found.get().reason() + ". A MIN or MAX keeps the extreme and not the values "
                            + "under it, so the first retraction of the extreme has no answer and would stop "
                            + "the query. Compute it over a stream whose source only appends, or use COUNT, SUM "
                            + "or AVG, which retract exactly.");
        }
    }

    /**
     * The first {@code MIN} or {@code MAX} in {@code plan} that a retraction on {@code stream} would
     * reach, named as {@code MIN(v)}; empty when none would.
     */
    public static Optional<String> extremeOver(PhysicalOperator plan, String stream) {
        return find(plan, stream::equals).map(Found::aggregate);
    }

    private record Found(String aggregate, String reason) {}

    private static Optional<Found> find(PhysicalOperator plan, Predicate<String> retracts) {
        List<AggregateOperator.AggregateCall> calls =
                switch (plan) {
                    case AggregateOperator aggregate -> aggregate.aggregates();
                    case WindowedAggregateOperator windowed -> windowed.aggregates();
                    default -> List.of();
                };
        for (AggregateOperator.AggregateCall call : calls) {
            if (call.kind() != AggregateOperator.AggregateCall.Kind.MIN
                    && call.kind() != AggregateOperator.AggregateCall.Kind.MAX) {
                continue;
            }
            PhysicalOperator input = plan.inputs().get(0);
            ChangelogAnalysis.Result below = ChangelogAnalysis.analyse(input, retracts);
            if (below.producesUpdates()) {
                String argument = call.argumentOrdinal() < 0
                        ? "*"
                        : input.outputSchema().field(call.argumentOrdinal()).name();
                return Optional.of(new Found(call.kind() + "(" + argument + ")", below.reason()));
            }
        }
        for (PhysicalOperator input : plan.inputs()) {
            Optional<Found> below = find(input, retracts);
            if (below.isPresent()) {
                return below;
            }
        }
        return Optional.empty();
    }
}
