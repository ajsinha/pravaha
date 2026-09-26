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

import java.util.List;

import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeTransforms;
import org.apache.calcite.sql.util.SqlOperatorTables;

/**
 * The scalar functions Pravaha adds to SQL's standard set.
 *
 * <p>Three, each taken from Flink SQL with Flink's meaning, because they are the functions Nexmark's
 * published queries call (q15-q17, q21, q22) and a Nexmark query is written in Flink's dialect:
 *
 * <ul>
 *   <li>{@code DATE_FORMAT(timestamp, 'pattern')} -- text, null when the timestamp is null.
 *   <li>{@code REGEXP_EXTRACT(text, 'regex'[, group])} -- text, null when nothing matches.
 *   <li>{@code SPLIT_INDEX(text, 'delimiter', index)} -- text, null when there is no such field.
 * </ul>
 *
 * <p>Declared here only so the validator knows their names, arguments and result types. What they
 * compute lives in the runtime's expression tree, and which arguments must be literals is decided by
 * the expression compiler, which refuses anything else by name.
 */
public final class PravahaFunctions {

    /** {@code DATE_FORMAT(ts, 'yyyy-MM-dd')}. Nullable exactly when the timestamp is. */
    public static final SqlFunction DATE_FORMAT = new SqlFunction(
            "DATE_FORMAT",
            SqlKind.OTHER_FUNCTION,
            ReturnTypes.explicit(SqlTypeName.VARCHAR).andThen(SqlTypeTransforms.TO_NULLABLE),
            null,
            OperandTypes.family(SqlTypeFamily.TIMESTAMP, SqlTypeFamily.CHARACTER),
            SqlFunctionCategory.TIMEDATE);

    /** {@code REGEXP_EXTRACT(s, 'regex'[, group])}. Always nullable: a string may not match. */
    public static final SqlFunction REGEXP_EXTRACT = new SqlFunction(
            "REGEXP_EXTRACT",
            SqlKind.OTHER_FUNCTION,
            ReturnTypes.explicit(SqlTypeName.VARCHAR).andThen(SqlTypeTransforms.FORCE_NULLABLE),
            null,
            OperandTypes.or(
                    OperandTypes.family(SqlTypeFamily.CHARACTER, SqlTypeFamily.CHARACTER),
                    OperandTypes.family(SqlTypeFamily.CHARACTER, SqlTypeFamily.CHARACTER, SqlTypeFamily.INTEGER)),
            SqlFunctionCategory.STRING);

    /** {@code SPLIT_INDEX(s, 'delimiter', n)}. Always nullable: there may be no field {@code n}. */
    public static final SqlFunction SPLIT_INDEX = new SqlFunction(
            "SPLIT_INDEX",
            SqlKind.OTHER_FUNCTION,
            ReturnTypes.explicit(SqlTypeName.VARCHAR).andThen(SqlTypeTransforms.FORCE_NULLABLE),
            null,
            OperandTypes.family(SqlTypeFamily.CHARACTER, SqlTypeFamily.CHARACTER, SqlTypeFamily.INTEGER),
            SqlFunctionCategory.STRING);

    private PravahaFunctions() {}

    /** SQL's standard operators with these three added. */
    static SqlOperatorTable operatorTable() {
        return SqlOperatorTables.chain(
                SqlStdOperatorTable.instance(),
                SqlOperatorTables.of(List.of(DATE_FORMAT, REGEXP_EXTRACT, SPLIT_INDEX)));
    }
}
