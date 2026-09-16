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

/**
 * Where a statement ends, which is the only thing this gateway knows about SQL.
 *
 * <p>Each case below is a semicolon that either does or does not end a statement. Getting one of
 * them wrong does not produce a syntax error the user can read: it produces a query torn in half
 * and refused as two, with the user's own data named as the cause.
 */
class SimpleQueryTextTest {

    @Test
    void theTrailingSemicolonPsqlSendsIsRemoved() {
        // ViewQuery takes a statement, not a statement and a semicolon. psql sends what was typed.
        assertThat(SimpleQueryText.singleStatement("SELECT 1;")).isEqualTo("SELECT 1");
        assertThat(SimpleQueryText.singleStatement("  SELECT 1 ;  \n ")).isEqualTo("SELECT 1");
    }

    @Test
    void aQueryWithNoSemicolonIsUntouched() {
        assertThat(SimpleQueryText.singleStatement("SELECT * FROM t")).isEqualTo("SELECT * FROM t");
    }

    @Test
    void aSemicolonInsideAStringLiteralIsJustACharacter() {
        assertThat(SimpleQueryText.singleStatement("SELECT * FROM t WHERE a = 'x;y'"))
                .isEqualTo("SELECT * FROM t WHERE a = 'x;y'");
    }

    @Test
    void anEscapedQuoteInsideALiteralDoesNotEndIt() {
        // 'it''s; fine' is one literal containing a semicolon. A scanner that treats the second
        // quote as the close ends the statement in the middle of the value.
        assertThat(SimpleQueryText.singleStatement("SELECT * FROM t WHERE a = 'it''s; fine'"))
                .isEqualTo("SELECT * FROM t WHERE a = 'it''s; fine'");
    }

    @Test
    void aSemicolonInsideAQuotedIdentifierIsJustACharacter() {
        assertThat(SimpleQueryText.singleStatement("SELECT \"odd;name\" FROM t"))
                .isEqualTo("SELECT \"odd;name\" FROM t");
    }

    @Test
    void commentsAreNotStatementBoundariesAndDoNotSurvive() {
        assertThat(SimpleQueryText.singleStatement("SELECT 1 -- a; comment\n")).isEqualTo("SELECT 1");
        assertThat(SimpleQueryText.singleStatement("SELECT /* a; comment */ 1")).isEqualTo("SELECT  1");
    }

    @Test
    void blockCommentsNestTheWayPostgresSaysTheyDo() {
        // A non-nesting scan ends the comment at the first inner close and hands the planner the
        // second half of something the user meant as one comment.
        assertThat(SimpleQueryText.singleStatement("SELECT /* outer /* inner */ still; comment */ 1"))
                .isEqualTo("SELECT  1");
    }

    @Test
    void aQueryThatIsOnlyWhitespaceOrCommentsIsEmptyRatherThanAnError() {
        // The protocol answers this with EmptyQueryResponse, which is neither a result nor a
        // failure. psql sends it whenever somebody presses enter on a blank line.
        assertThat(SimpleQueryText.singleStatement("")).isEmpty();
        assertThat(SimpleQueryText.singleStatement("  \n ")).isEmpty();
        assertThat(SimpleQueryText.singleStatement(";")).isEmpty();
        assertThat(SimpleQueryText.singleStatement("-- nothing here\n")).isEmpty();
    }

    @Test
    void severalStatementsAreRefusedByCountRatherThanSilentlyTruncated() {
        assertThatThrownBy(() -> SimpleQueryText.singleStatement("SELECT 1; SELECT 2"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-6201")
                .hasMessageContaining("2 statements");
    }

    @Test
    void anUnterminatedQuoteIsLeftForThePlannerToDiagnose() {
        // The planner knows the dialect and can point at the offset. A message from the transport
        // would be a second, worse syntax error about the same text.
        assertThat(SimpleQueryText.singleStatement("SELECT * FROM t WHERE a = 'open"))
                .isEqualTo("SELECT * FROM t WHERE a = 'open");
    }
}
