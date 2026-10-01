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
import org.apache.calcite.sql.SqlDynamicParam;
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

    /**
     * The refusals that have to be decided <em>before</em> validation, because Calcite's own
     * validator answers first and answers worse.
     *
     * <p>Two of them, both singleton findings.
     *
     * <ul>
     *   <li><strong>W-6.</strong> {@code CUMULATE} is a SQL:2016 windowing function this engine does
     *       not implement. Calcite parses it and then refuses it as an unresolved function
     *       signature -- or, in the {@code TABLE(CUMULATE(...))} spelling, as a {@code $SCALAR_QUERY}
     *       whose record type has too many fields, which is a sentence about nothing the person
     *       wrote. Named here so the refusal says what it is and what to write instead.
     *   <li><strong>X-9.</strong> A {@code ?} outside a {@code WHERE} or {@code HAVING} clause was
     *       refused with three different codes depending on where it stood: {@code PRV-2002} from
     *       Calcite's validator for a bare {@code SELECT ?} it cannot type, {@code PRV-2021} from
     *       the expression compiler for {@code amount * ?}, and {@code PRV-2063} from
     *       {@code ParameterMetadata} only when that class was separately invoked. ADR-032 states
     *       one rule, so there is one code: {@code PRV-2063}, decided on the parse tree where the
     *       clause a placeholder stands in is still visible.
     * </ul>
     *
     * <p>{@code ORDER BY}, {@code LIMIT} and {@code OFFSET} subtrees are skipped by the placeholder
     * walk: those clauses are refused whole, below, and "this engine has no sort operator" is a
     * better answer to {@code ORDER BY ?} than "a placeholder is not a value here".
     */
    static void checkBeforeValidation(SqlNode node) {
        refuseUnbuiltWindowFunctions(node);
        refusePlaceholdersOutsideAFilter(node, false);
    }

    /** Walks the statement and refuses the shapes the optimiser would otherwise erase. */
    static void check(SqlNode node) {
        if (node == null) {
            return;
        }
        if (node instanceof SqlOrderBy orderBy) {
            if (orderBy.orderList != null && !orderBy.orderList.isEmpty()) {
                throw orderByRefusal(orderBy.orderList);
            }
            // X-6's neighbour, found while reproducing it. `SELECT ... LIMIT 5` and
            // `... OFFSET 5 ROWS` parse into this same node with an EMPTY order list, and the
            // refusal above printed "'ORDER BY '" -- naming a clause the statement does not
            // contain, with nothing between the quotes. The row limit is refused for a reason of
            // its own and now says so.
            throw rowLimitRefusal(orderBy.fetch != null, orderBy.offset != null);
        }
        if (node instanceof SqlSelect select) {
            if (select.getOrderList() != null && !select.getOrderList().isEmpty()) {
                throw orderByRefusal(select.getOrderList());
            }
            if (select.getFetch() != null || select.getOffset() != null) {
                throw rowLimitRefusal(select.getFetch() != null, select.getOffset() != null);
            }
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
                        + "clause had never been written. See docs/guides/CONTINUOUS_QUERIES.md for what this engine "
                        + "executes and what it refuses.");
    }

    /**
     * {@code LIMIT} and {@code OFFSET} over a continuous view, named as themselves.
     *
     * <p>Refused for a reason of their own rather than as a sort. A view is maintained, not
     * returned: rows arrive, change and retract, so "the first five" is whichever five the engine
     * happens to hold at the instant of the read and a different five a moment later. Without a
     * sort there is not even a rule saying which five. Refusing is the no-leniency answer -- the
     * alternative is an answer that is arbitrary and looks deliberate.
     */
    private static PravahaException rowLimitRefusal(boolean hasFetch, boolean hasOffset) {
        String clause = hasFetch && hasOffset ? "LIMIT and OFFSET" : hasFetch ? "LIMIT" : "OFFSET";
        return new PravahaException(
                SqlErrors.UNSUPPORTED_OPERATOR,
                clause + " is not executed by this engine, and is refused rather than ignored. A continuous "
                        + "view is maintained rather than returned: rows arrive, update and retract, so "
                        + "'the first five' is whichever five happened to be held at the instant of the read "
                        + "and a different five a moment later. There is no sort operator either, so nothing "
                        + "even decides which five. Take the rows you want in whatever reads the view. "
                        + "See docs/guides/CONTINUOUS_QUERIES.md for what this engine executes and what it refuses.");
    }

    /**
     * Windowing functions SQL knows and this engine does not execute, refused by name (W-6).
     *
     * <p>Before validation, because Calcite gets there first and its answer is about its own
     * internals: {@code CUMULATE(...)} in a {@code GROUP BY} is "No match found for function
     * signature", and {@code TABLE(CUMULATE(...))} is a complaint about {@code $SCALAR_QUERY}
     * receiving a record with more than one field. Neither names {@code CUMULATE}'s absence, and
     * neither reaches the plan builder's own {@code default} arm, which does.
     */
    private static void refuseUnbuiltWindowFunctions(SqlNode node) {
        if (node == null) {
            return;
        }
        if (node instanceof SqlCall call) {
            String name = call.getOperator().getName().toUpperCase(java.util.Locale.ROOT);
            if (name.equals("CUMULATE")) {
                throw new PravahaException(
                        SqlErrors.UNSUPPORTED_OPERATOR,
                        "CUMULATE is a SQL:2016 windowing function this engine does not implement: its windows "
                                + "share a start and grow to a maximum, so a row belongs to every step from its "
                                + "own to the last and the slice grid that makes TUMBLE and HOP O(1) per row does "
                                + "not describe it. Use TUMBLE or HOP. See docs/guides/CONTINUOUS_QUERIES.md for what "
                                + "this engine executes and what it refuses.");
            }
            call.getOperandList().forEach(SqlShapeRefusals::refuseUnbuiltWindowFunctions);
            return;
        }
        if (node instanceof SqlNodeList list) {
            list.forEach(SqlShapeRefusals::refuseUnbuiltWindowFunctions);
        }
    }

    /**
     * ADR-032's one rule, enforced in one place with one code (X-9).
     *
     * <p>A {@code ?} belongs in a {@code WHERE} or {@code HAVING} clause. Decided on the parse tree
     * because that is the last copy of the statement in which the clause a placeholder stands in is
     * still a clause -- by the time a plan exists a group key and a projection are both a {@code
     * RexNode} under a {@code Project}, and Calcite's validator has already refused the ones it
     * cannot type.
     *
     * <p>A subquery's own clauses are judged on their own: a {@code ?} in the select list of a
     * subquery written inside a {@code WHERE} is still in a select list.
     */
    private static void refusePlaceholdersOutsideAFilter(SqlNode node, boolean insideAFilter) {
        if (node == null) {
            return;
        }
        if (node instanceof SqlDynamicParam param) {
            if (!insideAFilter) {
                throw placeholderRefusal(param.getIndex());
            }
            return;
        }
        if (node instanceof SqlOrderBy orderBy) {
            // ORDER BY, LIMIT and OFFSET are refused whole, after validation. Naming the clause is
            // a better answer than naming the placeholder inside it.
            refusePlaceholdersOutsideAFilter(orderBy.query, insideAFilter);
            return;
        }
        if (node instanceof SqlSelect select) {
            refusePlaceholdersOutsideAFilter(select.getSelectList(), false);
            refusePlaceholdersOutsideAFilter(select.getFrom(), false);
            refusePlaceholdersOutsideAFilter(select.getWhere(), true);
            refusePlaceholdersOutsideAFilter(select.getGroup(), false);
            refusePlaceholdersOutsideAFilter(select.getHaving(), true);
            refusePlaceholdersOutsideAFilter(select.getWindowList(), false);
            return;
        }
        if (node instanceof SqlCall call) {
            call.getOperandList().forEach(operand -> refusePlaceholdersOutsideAFilter(operand, insideAFilter));
            return;
        }
        if (node instanceof SqlNodeList list) {
            list.forEach(operand -> refusePlaceholdersOutsideAFilter(operand, insideAFilter));
        }
    }

    private static PravahaException placeholderRefusal(int index) {
        return new PravahaException(
                SqlErrors.PARAMETER_NOT_A_VALUE,
                "?" + (index + 1) + " is not in a WHERE or HAVING clause. A placeholder stands for a value "
                        + "that selects rows, and nothing else: a projection, a window size, a group key, an "
                        + "aggregate argument or a table name decides what the query *is* rather than which "
                        + "rows it returns. Two window sizes have no rows in common, so they cannot share a "
                        + "computation or its state -- binding one would create a separate query per size, "
                        + "and the first anyone would know of it is a memory alarm. See ADR-032.");
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
