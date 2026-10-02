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

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;

/**
 * A row wider than the inbox cell of the query it was handed to: refused for that row, before
 * anything is copied, and not a failure of the query (CELLBYTES-1).
 *
 * <p>The inbox refused such a row with an {@code IllegalArgumentException}, which the registry
 * turned into a failure of the query -- and every query on the stream was handed the same row, so
 * one wide value stopped all of them for good. A row that does not fit says nothing about the query:
 * the query keeps running, and the caller is told, with the sizes and the setting that decides them,
 * which row it could not deliver. {@code PRV-3002}, which the guides already name for a row that
 * does not fit an inbox cell.
 */
public final class RowTooWideException extends PravahaException {

    private final int rowBytes;
    private final int cellBytes;

    public RowTooWideException(int rowBytes, int cellBytes) {
        super(
                RuntimeErrors.BACKPRESSURED,
                "a row of " + rowBytes + " bytes does not fit this query's inbox cell of " + cellBytes
                        + " bytes, so it is refused; the query keeps running. Raise pravaha.lane.inbox.cell-bytes "
                        + "to the widest row the stream carries.");
        this.rowBytes = rowBytes;
        this.cellBytes = cellBytes;
    }

    /** How wide the refused row was. */
    public int rowBytes() {
        return rowBytes;
    }

    /** The widest row the query's inbox takes. */
    public int cellBytes() {
        return cellBytes;
    }
}
