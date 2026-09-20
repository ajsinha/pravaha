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
package com.ash.messaging.pravaha.sdk.flight;

import java.util.ArrayList;
import java.util.List;

/**
 * What one step of a debug session did (ADR-047, design section 16.4).
 *
 * <p>One answer rather than four calls, and that is the shape design section 23.9's screen needs:
 * the input rows, the operator flows, the view's changes and the watermark are four panels of one
 * moment, and a screen that asked for them separately would show four.
 *
 * @param session the session's id
 * @param sequence which step this was, counting from one
 * @param kind {@code ROW}, {@code ROWS}, {@code COMMIT}, {@code WATERMARK} or {@code UNTIL}
 * @param rowsIn the input rows this step consumed, in order
 * @param operators what each operator of the plan did with them
 * @param viewChanges what changed in the fork's view, weights included
 * @param watermarkNanos where event time stands, or null if nothing has moved it
 * @param rowsConsumed how many rows the session has consumed in total
 * @param viewSize how many rows the fork's view holds
 * @param exhausted whether the sources had no more rows to give
 * @param stopped why the step stopped, in words
 */
public record DebugStepReport(
        String session,
        long sequence,
        String kind,
        List<InputRow> rowsIn,
        List<OperatorFlow> operators,
        List<ViewDelta> viewChanges,
        Long watermarkNanos,
        long rowsConsumed,
        int viewSize,
        boolean exhausted,
        String stopped) {

    /** One row that entered the query. */
    public record InputRow(
            String stream, int partition, String offset, long weight, long eventTimeNanos, List<String> values) {}

    /** What one operator saw and produced during the step. */
    public record OperatorFlow(String id, String kind, String label, long rowsIn, long rowsOut) {}

    /** One change to the view: a negative weight withdraws a row. */
    public record ViewDelta(long weight, List<String> values) {}

    /**
     * Reads one off the control wire.
     *
     * <p>The layout is the fixed fields, then each list as a count followed by that many groups.
     * A cursor rather than fixed indexes, because the lists are variable length and the next one
     * starts wherever the last ended -- which is the price of a flat wire and is paid here, once,
     * rather than by every caller.
     */
    static DebugStepReport of(List<String> fields) {
        int[] at = {8};
        List<InputRow> rows = new ArrayList<>();
        int rowCount = (int) Wire.number(fields, at[0]++);
        for (int i = 0; i < rowCount; i++) {
            String stream = Wire.text(fields, at[0]++);
            int partition = (int) Wire.number(fields, at[0]++);
            String offset = Wire.text(fields, at[0]++);
            long weight = Wire.number(fields, at[0]++);
            long eventTime = Wire.number(fields, at[0]++);
            rows.add(new InputRow(stream, partition, offset, weight, eventTime, values(fields, at)));
        }

        List<OperatorFlow> operators = new ArrayList<>();
        int operatorCount = (int) Wire.number(fields, at[0]++);
        for (int i = 0; i < operatorCount; i++) {
            operators.add(new OperatorFlow(
                    Wire.text(fields, at[0]++),
                    Wire.text(fields, at[0]++),
                    Wire.text(fields, at[0]++),
                    Wire.number(fields, at[0]++),
                    Wire.number(fields, at[0]++)));
        }

        List<ViewDelta> changes = new ArrayList<>();
        int changeCount = (int) Wire.number(fields, at[0]++);
        for (int i = 0; i < changeCount; i++) {
            long weight = Wire.number(fields, at[0]++);
            changes.add(new ViewDelta(weight, values(fields, at)));
        }

        return new DebugStepReport(
                Wire.text(fields, 0),
                Wire.number(fields, 1),
                Wire.text(fields, 2),
                rows,
                operators,
                changes,
                Wire.optionalNumber(fields, 3),
                Wire.number(fields, 4),
                (int) Wire.number(fields, 5),
                Boolean.parseBoolean(Wire.text(fields, 6)),
                Wire.text(fields, 7));
    }

    private static List<String> values(List<String> fields, int[] at) {
        int count = (int) Wire.number(fields, at[0]++);
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(Wire.text(fields, at[0]++));
        }
        return values;
    }
}
