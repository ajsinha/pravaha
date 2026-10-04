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
package com.ash.messaging.pravaha.sql.plan;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.metadata.RelColumnOrigin;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.util.ImmutableBitSet;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.SecurityErrors;

/**
 * Refuses a plan that would use a column masked for its reader where the column's value is compared
 * rather than shown (ADR-059 §4): as a filter operand, a group key, a join key, a sort or ranking key,
 * an aggregate's argument, or a registered view's key column. {@code PRV-7006}, naming the column and
 * the use.
 *
 * <p>Comparing a masked column would tell the reader its equality classes -- which rows share a card
 * number -- or its order, which is what the mask exists to hide. The mask is applied to the rows before
 * any operator (see {@link NarrowingPlan}), so an operator above could only ever compare masked values;
 * this refusal names the misuse at plan time rather than answer it with groups and sorts of {@code
 * 'XXXX'} that mean nothing.
 *
 * <p>Where a column comes from is Calcite's column-origin metadata, which follows a column through
 * projections, filters, joins and aggregates to the table column it derives from -- so {@code UPPER(card)}
 * compared is refused as {@code card} compared. An origin the metadata cannot establish is not refused:
 * the value there is already masked, so nothing can leak through it.
 */
public final class MaskedColumnUse {

    private MaskedColumnUse() {}

    /**
     * Refuses a plan that compares a masked column, or keys its view by one.
     *
     * @param masked the masked columns of each object the plan reads, by the object's name
     * @param keyColumns the output ordinals a registration keys its view by; empty for a read
     */
    public static void refuse(RelNode plan, Map<String, Set<String>> masked, List<Integer> keyColumns) {
        if (masked.values().stream().allMatch(Set::isEmpty)) {
            return;
        }
        walk(plan, masked);
        RelMetadataQuery metadata = plan.getCluster().getMetadataQuery();
        for (Integer key : keyColumns == null ? List.<Integer>of() : keyColumns) {
            if (key != null && key >= 0 && key < plan.getRowType().getFieldCount()) {
                maskedOrigin(metadata, plan, key, masked)
                        .ifPresent(column -> refuse(column, "a key column of the view being registered"));
            }
        }
    }

    private static void walk(RelNode node, Map<String, Set<String>> masked) {
        RelMetadataQuery metadata = node.getCluster().getMetadataQuery();
        switch (node) {
            case Filter filter ->
                check(
                        metadata,
                        filter.getInput(),
                        RelOptUtil.InputFinder.bits(filter.getCondition()),
                        masked,
                        "a filter operand");
            case Join join ->
                check(metadata, join, RelOptUtil.InputFinder.bits(join.getCondition()), masked, "a join key");
            case Aggregate aggregate -> {
                check(metadata, aggregate.getInput(), aggregate.getGroupSet(), masked, "a group key");
                for (AggregateCall call : aggregate.getAggCallList()) {
                    check(
                            metadata,
                            aggregate.getInput(),
                            ImmutableBitSet.of(call.getArgList()),
                            masked,
                            "the argument of " + call.getAggregation().getName());
                }
            }
            case Sort sort -> {
                for (RelFieldCollation field : sort.getCollation().getFieldCollations()) {
                    check(metadata, sort.getInput(), ImmutableBitSet.of(field.getFieldIndex()), masked, "a sort key");
                }
            }
            case Project project -> {
                for (RexNode expression : project.getProjects()) {
                    expression.accept(new RexShuttle() {
                        @Override
                        public RexNode visitOver(RexOver over) {
                            ImmutableBitSet.Builder keys = ImmutableBitSet.builder();
                            over.getWindow().partitionKeys.forEach(k -> keys.addAll(RelOptUtil.InputFinder.bits(k)));
                            over.getWindow().orderKeys.forEach(k -> keys.addAll(RelOptUtil.InputFinder.bits(k.left)));
                            check(metadata, project.getInput(), keys.build(), masked, "a ranking or window key");
                            return over;
                        }
                    });
                }
            }
            default -> {
                // scans, values, table functions: nothing compared here
            }
        }
        node.getInputs().forEach(input -> walk(input, masked));
    }

    private static void check(
            RelMetadataQuery metadata,
            RelNode input,
            ImmutableBitSet used,
            Map<String, Set<String>> masked,
            String use) {
        for (int ordinal : used) {
            if (ordinal < input.getRowType().getFieldCount()) {
                maskedOrigin(metadata, input, ordinal, masked).ifPresent(column -> refuse(column, use));
            }
        }
    }

    private static Optional<String> maskedOrigin(
            RelMetadataQuery metadata, RelNode node, int ordinal, Map<String, Set<String>> masked) {
        Set<RelColumnOrigin> origins = metadata.getColumnOrigins(node, ordinal);
        if (origins == null) {
            return Optional.empty();
        }
        for (RelColumnOrigin origin : origins) {
            List<String> qualified = origin.getOriginTable().getQualifiedName();
            String table = qualified.get(qualified.size() - 1);
            Set<String> columns = masked.getOrDefault(table, Set.of());
            String column = origin.getOriginTable()
                    .getRowType()
                    .getFieldList()
                    .get(origin.getOriginColumnOrdinal())
                    .getName();
            if (columns.contains(column)) {
                return Optional.of(table + "." + column);
            }
        }
        return Optional.empty();
    }

    private static void refuse(String column, String use) {
        throw new PravahaException(
                SecurityErrors.MASKED_COLUMN_USE,
                column + " is masked for you, and this query uses it as " + use + ". Comparing a masked column "
                        + "would tell you which rows share its value, or their order -- what the mask hides. Select "
                        + "it to see its masked value; compare, group, join or sort on another column");
    }
}
