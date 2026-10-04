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

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * Where a row goes when evaluating it fails before it reached any state (DLQPROJ-1): a division by
 * zero, a 64-bit or narrow-integer overflow, a cast with no answer of its type.
 *
 * <p>Attached by whatever opens the query's dead-letter queue ({@link
 * QueryExecution#deadLetterRowFailures}); with none attached, such a row stops the query as it always
 * did. Called on the lane thread, once per refused row. It throws only when the row could not be
 * kept ({@code PRV-4090}, DLQFULL-1), and then the row stops the query just as it would with no sink:
 * a row left out of the view and kept nowhere would be a row dropped. The row is handed over as text
 * because the flyweight it was read through is reused the moment this returns.
 */
@FunctionalInterface
public interface RowFailureSink {

    /**
     * Records one row that was not applied.
     *
     * @param stream the stream the row arrived on
     * @param schema that stream's schema, as the row was read
     * @param row the row's columns, rendered as a JSON object of strings
     * @param failure what evaluating it threw
     */
    void reject(String stream, StreamSchema schema, String row, ArithmeticException failure);
}
