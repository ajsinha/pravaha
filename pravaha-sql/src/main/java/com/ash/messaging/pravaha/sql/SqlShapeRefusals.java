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

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.type.SqlTypeName;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * The refusals that have to be decided on the SQL the person wrote, not on the plan.
 *
 * <p>Every other refusal in this engine is made while the optimised tree is translated, which is
 * the right place for nearly all of them: the plan is what will execute, and refusing what cannot
 * execute is a guarantee about execution. It stops being the right place the moment Calcite's
 * optimiser <em>removes</em> the thing that should have been refused, because then the refusal is
 * not weakened -- it never fires, the query plans, and the person is told nothing at all.
 *
 * <p>Two findings, and both have the same shape.
 *
 * <ul>
 *   <li><strong>TY-20.</strong> {@code SELECT * FROM (SELECT id FROM t ORDER BY id) x} planned and
 *       ran while every other {@code ORDER BY} was refused, because a sort inside a derived table
 *       with no {@code FETCH} cannot change the answer and the optimiser drops it before a {@code
 *       Sort} node ever reaches the plan builder. The refusal was a property of the plan's shape
 *       rather than a promise to the person writing SQL.
 *   <li><strong>TY-23.</strong> {@code s || 5} and {@code s || CAST(5 AS VARCHAR)} both succeeded
 *       while {@code s || amount} was correctly refused -- a literal is coerced, or constant-folded
 *       away, before the compiler's text-only check ever sees it. So whether {@code ||} accepted a
 *       number depended on whether it was written as a literal, which is not a rule anybody can
 *       predict from the type system.
 * </ul>
 *
 * <p>Run against the parse tree, after validation has succeeded. After, so a query with a genuine
 * syntax or resolution error is told about that first, and so that SQL's own type coercion -- which
 * rewrites this tree in place -- has already inserted the casts it inserts on the person's behalf.
 * Against the parse tree rather than the plan, because the parse tree is the last copy of the
 * statement that the optimiser has not yet had a chance to simplify.
 */
final class SqlShapeRefusals {

    private SqlShapeRefusals() {}

    /** Walks the statement and refuses the shapes the optimiser would otherwise erase. */
    static void check(SqlNode node) {
        if (node == null) {
            return;
        }
        if (node instanceof SqlOrderBy orderBy) {
            throw orderByRefusal(orderBy.orderList);
        }
        if (node instanceof SqlSelect select
                && select.getOrderList() != null
                && !select.getOrderList().isEmpty()) {
            throw orderByRefusal(select.getOrderList());
        }
        if (node instanceof SqlCall call) {
            refuseCastOfALiteralToText(call);
            call.getOperandList().forEach(SqlShapeRefusals::check);
            return;
        }
        if (node instanceof SqlNodeList list) {
            list.forEach(SqlShapeRefusals::check);
        }
    }

    private static PravahaException orderByRefusal(SqlNode orderList) {
        return new PravahaException(
                SqlErrors.UNSUPPORTED_OPERATOR,
                "ORDER BY is not executed by this engine, and 'ORDER BY " + shortly(orderList) + "' is refused "
                        + "rather than ignored. A continuous view has no row order to maintain: rows arrive, "
                        + "update and retract, so an order fixed when the query was registered would be wrong "
                        + "before anybody read it. Sort the rows in whatever reads the view. Refused on the SQL "
                        + "rather than on the plan because the optimiser deletes a sort it can prove harmless -- "
                        + "one inside a derived table with no FETCH -- and the query then ran as though the "
                        + "clause had never been written. See docs/CONTINUOUS_QUERIES.md for what this engine "
                        + "executes and what it refuses.");
    }

    /**
     * {@code CAST(5 AS VARCHAR)}: a cast to text whose operand is a literal.
     *
     * <p>{@code CAST(amount AS VARCHAR)} is refused -- there is no number-to-text conversion in
     * this engine -- but the literal form is constant-folded into a character literal before
     * anything Pravaha owns runs, so the identical request succeeded.
     *
     * <p>One rule covers both halves of TY-23, which is worth saying because it is not obvious.
     * {@code s || 5} has no cast in the text; SQL's own type coercion inserts one during
     * validation, in place, in this very tree -- so by the time this walk runs, {@code s || 5} and
     * {@code s || CAST(5 AS VARCHAR)} are the same statement, which is exactly the claim the
     * finding makes about them and the reason they must answer alike.
     *
     * <p>{@code CAST(NULL AS VARCHAR)} is left alone: it converts nothing, and it is the rewrite
     * the bare-NULL refusal itself recommends.
     */
    private static void refuseCastOfALiteralToText(SqlCall call) {
        if (call.getKind() != SqlKind.CAST || call.getOperandList().size() != 2) {
            return;
        }
        if (!(call.getOperandList().get(0) instanceof SqlLiteral literal) || isTextOrNull(literal)) {
            return;
        }
        if (!(call.getOperandList().get(1) instanceof SqlDataTypeSpec target) || !isTextSpec(target)) {
            return;
        }
        throw new PravahaException(
                SqlErrors.UNSUPPORTED_EXPRESSION,
                "'" + shortly(call) + "' converts a " + literal.getTypeName()
                        + " literal to text, which this engine does not do. The same conversion over a "
                        + "column is refused; a literal is not a different question, and it only appeared to "
                        + "work because the cast -- whether it was written or SQL's own coercion inserted it -- "
                        + "was folded away before anything here could look at it. Write the value as text: '"
                        + literal + "'.");
    }

    private static boolean isTextOrNull(SqlLiteral literal) {
        SqlTypeName type = literal.getTypeName();
        return type == SqlTypeName.CHAR || type == SqlTypeName.VARCHAR || type == SqlTypeName.NULL;
    }

    private static boolean isTextSpec(SqlDataTypeSpec spec) {
        String name = spec.getTypeName().getSimple().toUpperCase(java.util.Locale.ROOT);
        return name.equals("VARCHAR") || name.equals("CHAR") || name.equals("CHARACTER") || name.equals("STRING");
    }

    /** One line of SQL, so a refusal about a clause does not print the whole statement. */
    private static String shortly(SqlNode node) {
        String text = node.toString().replace('\n', ' ').replaceAll(" +", " ").strip();
        return text.length() <= 120 ? text : text.substring(0, 117) + "...";
    }
}
