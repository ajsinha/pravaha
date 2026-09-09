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

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.impl.AbstractTable;

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * A registered stream, as Calcite sees it.
 *
 * <p>Deliberately <strong>not</strong> {@code ScannableTable} or {@code StreamableTable}. The 1.0
 * draft made this a {@code ScannableTable} whose {@code scan()} returned a blocking enumerator, and
 * that is the single decision the design had to reverse: Calcite's Enumerable convention is
 * pull-based and row-at-a-time, which defeats batching, defeats the JIT, and cannot approach the
 * throughput targets (design section 3, gap G1).
 *
 * <p>So this table exists only for the validator and the optimiser to bind against. Nothing ever
 * calls it at run time -- the planner's output is translated into Pravaha's own operator DAG, and
 * that is what executes. Calcite is a compiler here, not a runtime (ADR-002).
 */
public final class PravahaTable extends AbstractTable {

    private final StreamSchema schema;

    public PravahaTable(StreamSchema schema) {
        this.schema = schema;
    }

    public StreamSchema streamSchema() {
        return schema;
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        return TypeMapping.toRowType(typeFactory, schema);
    }

    @Override
    public String toString() {
        return "PravahaTable[" + schema.name() + " v" + schema.version() + "]";
    }
}
