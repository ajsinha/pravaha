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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParserPos;

/**
 * Where in the SQL text a refusal belongs, when the parser or validator knows.
 *
 * <p>Lines and columns are 1-based, and the end is <strong>inclusive</strong>: it names the last
 * character of the offending text, which is how Calcite reports it. An editor whose ranges are
 * end-exclusive adds one to {@link #endColumn}.
 *
 * <p>Read from the exception, never from its message. Calcite's messages happen to carry "line N,
 * column M" in English, and a client that parsed that out of the text was depending on the wording of
 * a third-party library's diagnostics -- which is not a contract anybody made. The positions are
 * fields on {@link SqlParseException} and {@link CalciteContextException}; this is the one place that
 * knows those two types, so nothing outside {@code pravaha-sql} has to.
 */
public record SourcePosition(int startLine, int startColumn, int endLine, int endColumn) {

    public SourcePosition {
        if (startLine < 1 || startColumn < 1) {
            throw new IllegalArgumentException(
                    "a position is 1-based, got line " + startLine + ", column " + startColumn);
        }
        if (endLine < startLine || (endLine == startLine && endColumn < startColumn)) {
            // Calcite reports a zero-width end for some parse errors. A range that ends before it
            // starts is a point, and saying so is truer than inventing a width.
            endLine = startLine;
            endColumn = startColumn;
        }
    }

    /**
     * The position a planning failure carries, if Calcite recorded one anywhere in its causes.
     *
     * <p>Empty for every refusal Pravaha raises itself -- an unsupported shape, a type the engine will
     * not sum -- because those are decided about a plan, after the text has been left behind. Empty
     * is the honest answer there; a guessed position underlines the wrong thing with confidence.
     */
    public static Optional<SourcePosition> of(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof SqlParseException parse && parse.getPos() != null) {
                return fromParser(parse.getPos());
            }
            if (cause instanceof CalciteContextException context && context.getPosLine() > 0) {
                return Optional.of(new SourcePosition(
                        context.getPosLine(),
                        Math.max(1, context.getPosColumn()),
                        context.getEndPosLine(),
                        context.getEndPosColumn()));
            }
        }
        return Optional.empty();
    }

    private static Optional<SourcePosition> fromParser(SqlParserPos pos) {
        if (pos.getLineNum() < 1) {
            // SqlParserPos.ZERO: the parser failed somewhere it could not name.
            return Optional.empty();
        }
        return Optional.of(new SourcePosition(
                pos.getLineNum(), Math.max(1, pos.getColumnNum()), pos.getEndLineNum(), pos.getEndColumnNum()));
    }
}
