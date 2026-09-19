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
package com.ash.messaging.pravaha.sql;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Positions read from the parser's and validator's own fields, not from their English.
 *
 * <p>The console underlined a diagnostic by matching "line N, column M" in the message text. That
 * worked until the message said something else, and for a validation error it was a guess from a
 * quoted identifier. These pin that the structured position survives the wrapping into a
 * {@code PravahaException} and names the right characters.
 */
class SourcePositionTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("txn_id", Types.int64())
            .field("amount", Types.int64())
            .build();

    private static PravahaException refusalOf(String sql) {
        return catchThrowableOfType(
                PravahaException.class, () -> SqlPlanner.withStreams(TXN).plan(sql));
    }

    @Test
    void anUnknownColumnIsPlacedOnTheColumnItself() {
        PravahaException refused = refusalOf("SELECT txn_id,\n       amont\nFROM txn");

        Optional<SourcePosition> where = SourcePosition.of(refused);

        assertThat(where).contains(new SourcePosition(2, 8, 2, 12));
    }

    @Test
    void anUnknownStreamIsPlacedOnItsName() {
        PravahaException refused = refusalOf("SELECT * FROM txns");

        assertThat(SourcePosition.of(refused)).contains(new SourcePosition(1, 15, 1, 18));
    }

    @Test
    void aSyntaxErrorCarriesWhereTheParserStopped() {
        PravahaException refused = refusalOf("SELECT txn_id FROM txn WHERE");

        assertThat(refused.errorCode()).isEqualTo(SqlErrors.PARSE_FAILED);
        assertThat(SourcePosition.of(refused))
                .as("a parse failure knows where it stopped")
                .isPresent()
                .get()
                .satisfies(position -> assertThat(position.startLine()).isEqualTo(1));
    }

    @Test
    void aRefusalPravahaMakesAboutThePlanHasNoPositionRatherThanAGuessedOne() {
        // Decided about the plan after the text has been left behind: there is no honest position.
        PravahaException invented = new PravahaException(SqlErrors.PLANNING_FAILED, "no position in here");

        assertThat(SourcePosition.of(invented)).isEmpty();
    }

    @Test
    void aRangeThatEndsBeforeItStartsIsAPoint() {
        assertThat(new SourcePosition(3, 9, 3, 2)).isEqualTo(new SourcePosition(3, 9, 3, 9));
    }
}
