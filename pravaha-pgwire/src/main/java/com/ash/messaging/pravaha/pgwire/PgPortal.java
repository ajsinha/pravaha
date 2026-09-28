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
package com.ash.messaging.pravaha.pgwire;

import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;

/**
 * A {@code Bind}: one statement, one set of parameter values, and -- once {@code Execute} has run at
 * least once -- how far a row-limited fetch has read.
 *
 * <p><strong>Why a cursor over an already-materialised list, and not a real one.</strong> {@code
 * ViewQuery} has no cursor to hand this class; {@code queries.execute} runs the whole query and
 * returns every row (design section 17, {@code ViewQuery.MAX_RESULT_ROWS}). {@code Execute}'s row
 * limit is real to the client -- a driver that asks for 50 rows at a time gets exactly 50 back, and
 * {@code PortalSuspended} rather than {@code CommandComplete} when more remain -- and is paging over
 * an answer this server already has in memory, not streaming one out of the engine. That is an
 * honest difference from a real cursor's laziness, not a claim of one.
 */
final class PgPortal {

    private final PgStatement statement;
    private final BoundParameters parameters;

    /** {@code Bind}'s result format codes, as sent: none (all text), one for all, or one per column. */
    private final short[] resultFormats;

    /** Set on the first {@code Execute}; {@code null} means this portal has not run yet. */
    private ViewQuery.Result result;

    private int cursor;

    PgPortal(PgStatement statement, BoundParameters parameters, short[] resultFormats) {
        this.statement = statement;
        this.parameters = parameters;
        this.resultFormats = resultFormats.clone();
    }

    PgStatement statement() {
        return statement;
    }

    short[] resultFormats() {
        return resultFormats.clone();
    }

    BoundParameters parameters() {
        return parameters;
    }

    boolean hasRun() {
        return result != null;
    }

    ViewQuery.Result result() {
        return result;
    }

    /** Records this portal's rows, the first time {@code Execute} runs it. */
    void runOnce(ViewQuery.Result result) {
        if (this.result == null) {
            this.result = result;
        }
    }

    int cursor() {
        return cursor;
    }

    void advanceTo(int position) {
        cursor = position;
    }
}
