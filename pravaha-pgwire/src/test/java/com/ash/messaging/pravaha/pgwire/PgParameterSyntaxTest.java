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

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code $1}, {@code $2}, ... to {@code ?}: the one syntax seam between PostgreSQL SQL and ADR-032's. */
class PgParameterSyntaxTest {

    @Test
    void rewritesSequentialPlaceholdersInOrder() {
        assertThat(PgParameterSyntax.toQuestionMarks("SELECT * FROM t WHERE a = $1 AND b = $2"))
                .isEqualTo("SELECT * FROM t WHERE a = ? AND b = ?");
    }

    @Test
    void aStatementWithNoPlaceholdersIsUnchanged() {
        assertThat(PgParameterSyntax.toQuestionMarks("SELECT * FROM t")).isEqualTo("SELECT * FROM t");
    }

    @Test
    void aDollarSignInsideAStringLiteralIsNotAPlaceholder() {
        assertThat(PgParameterSyntax.toQuestionMarks("SELECT * FROM t WHERE note = 'costs $1 today'"))
                .isEqualTo("SELECT * FROM t WHERE note = 'costs $1 today'");
    }

    @Test
    void aDollarSignInsideAQuotedIdentifierIsNotAPlaceholder() {
        assertThat(PgParameterSyntax.toQuestionMarks("SELECT \"col$1\" FROM t WHERE a = $1"))
                .isEqualTo("SELECT \"col$1\" FROM t WHERE a = ?");
    }

    @Test
    void aDollarSignInsideACommentIsNotAPlaceholder() {
        assertThat(PgParameterSyntax.toQuestionMarks("SELECT * FROM t WHERE a = $1 -- not $2, a comment\n"))
                .isEqualTo("SELECT * FROM t WHERE a = ? -- not $2, a comment\n");
    }

    @Test
    void placeholdersOutOfOrderAreRefusedRatherThanRewrittenWrong() {
        assertThatThrownBy(() -> PgParameterSyntax.toQuestionMarks("SELECT * FROM t WHERE a = $2 AND b = $1"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-6210");
    }

    @Test
    void aReusedPlaceholderIsRefusedRatherThanCountedTwice() {
        // $1 = $1 is legal PostgreSQL and means "one bound value, compared to itself" -- Calcite's
        // positional '?' cannot say that, and no real driver generates this shape in the first
        // place (JDBC's own '?' API has no way to ask for the same bound value twice).
        assertThatThrownBy(() -> PgParameterSyntax.toQuestionMarks("SELECT * FROM t WHERE a = $1 AND b = $1"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-6210");
    }

    @Test
    void aGapInNumberingIsRefused() {
        assertThatThrownBy(() -> PgParameterSyntax.toQuestionMarks("SELECT * FROM t WHERE a = $1 AND b = $3"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-6210");
    }
}
