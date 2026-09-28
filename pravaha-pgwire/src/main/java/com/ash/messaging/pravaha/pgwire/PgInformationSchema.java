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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.Field;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;

/**
 * The {@code information_schema} questions Power BI and Npgsql ask, answered from the view catalogue.
 *
 * <p>The same kind of thing {@link PgCatalogShim} is, and written from its rule: a recognizer of the
 * specific query texts real clients send, not an evaluator of SQL over a virtual schema. Two clients
 * matter here, and every template below is one of theirs, verbatim in its distinguishing part:
 *
 * <ul>
 *   <li><strong>Power BI's PostgreSQL connector</strong> (Power BI Desktop, the on-premises data
 *       gateway, Fabric dataflows): its navigator asks for the character sets, the tables, a table's
 *       columns, its foreign keys in both directions and its primary and unique keys, each as one
 *       statement against {@code INFORMATION_SCHEMA}. The texts are the ones a real Power BI Desktop
 *       sent a real PostgreSQL, from that server's statement log (datafusion-contrib/datafusion-postgres
 *       issue 218).
 *   <li><strong>Npgsql's {@code GetSchema("Tables")} and {@code GetSchema("Columns")}</strong>, from
 *       Npgsql 4.0.17's own {@code NpgsqlSchema.cs} -- the version Power BI bundles -- with their
 *       restrictions sent as {@code column = $n} parameters.
 * </ul>
 *
 * <p>What the answers say: every view the principal may see is one {@code BASE TABLE} in schema
 * {@code public} of catalogue {@code pravaha} -- the same relation {@code pg_class} calls relkind
 * {@code 'r'} in {@link PgCatalogShim}, so the two catalogues never disagree about what a view is.
 * There are no foreign keys, and no primary or unique constraints are declared: a view's key is
 * enforced by the engine, but reporting it as a primary key makes Power BI order its navigator
 * preview by it ({@code ORDER BY "$Ordered"."key"}), which a read refuses. An empty answer to "what
 * constraints are declared" costs Power BI nothing but auto-detected relationships.
 *
 * <p>Visibility is the caller's: {@link #tryAnswer} is handed the names this principal may see,
 * computed by {@link PgCatalogShim}'s own SX-5/SX-11 filter, and never looks at the catalogue for a
 * name outside that list.
 */
final class PgInformationSchema {

    private final ViewCatalog catalog;

    PgInformationSchema(ViewCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Answers {@code sql} if it is one of the {@code information_schema} templates this class knows.
     *
     * @param visible the views this principal may see, sorted -- the only names that can appear
     * @return empty if {@code sql} is not one of them; the caller then refuses it by name
     */
    Optional<ViewQuery.Result> tryAnswer(String sql, List<String> visible) {
        String shape = sql.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (!shape.contains("information_schema.")) {
            return Optional.empty();
        }
        if (shape.startsWith(CHARACTER_SETS)) {
            return Optional.of(result(schema("character_sets", "character_set_name"), rowsOf(new Object[] {"UTF8"})));
        }
        if (shape.contains("information_schema.referential_constraints")) {
            return foreignKeys(shape);
        }
        if (shape.contains("information_schema.table_constraints") && shape.contains(" as index_name")) {
            // Power BI's primary- and unique-key query. None are declared; see the class comment.
            return Optional.of(result(
                    StreamSchema.builder("key_constraints")
                            .field("index_name", Types.string())
                            .field("column_name", Types.string())
                            .field("ordinal_position", Types.int32())
                            .field("primary_key", Types.string())
                            .build(),
                    List.of()));
        }
        if (shape.startsWith(POWER_BI_TABLES) || shape.startsWith(NPGSQL_TABLES)) {
            return Optional.of(tables(sql, shape.startsWith(NPGSQL_TABLES), visible));
        }
        if (shape.startsWith(POWER_BI_COLUMNS)) {
            return Optional.of(columns(sql, false, visible));
        }
        if (shape.startsWith(NPGSQL_COLUMNS)) {
            return Optional.of(columns(sql, true, visible));
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------------------
    // The templates, whitespace-collapsed and lower-cased up to the point that tells them apart.

    private static final String CHARACTER_SETS = "select character_set_name from information_schema.character_sets";

    private static final String POWER_BI_TABLES =
            "select table_schema, table_name, table_type from information_schema.tables";

    private static final String NPGSQL_TABLES =
            "select table_catalog, table_schema, table_name, table_type from information_schema.tables";

    private static final String POWER_BI_COLUMNS = "select column_name, ordinal_position, is_nullable, case when "
            + "(data_type like '%unsigned%') then data_type || ' unsigned' else data_type end as data_type "
            + "from information_schema.columns";

    private static final String NPGSQL_COLUMNS = "select table_catalog, table_schema, table_name, column_name, "
            + "ordinal_position, column_default, is_nullable, udt_name as data_type, character_maximum_length, "
            + "character_octet_length, numeric_precision, numeric_precision_radix, numeric_scale, "
            + "datetime_precision, character_set_catalog, character_set_schema, character_set_name, "
            + "collation_catalog from information_schema.columns";

    // ------------------------------------------------------------------------------------------
    // Filters: equality on a name column, and IN / NOT IN lists on schema and type.

    private static final Pattern EQUALS = Pattern.compile(
            "(?<![\\w.])(?:\\w+\\.)?(table_catalog|table_schema|table_name|table_type|column_name)\\s*=\\s*'((?:[^']|'')*)'",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern IN_LIST = Pattern.compile(
            "(?<![\\w.])(?:\\w+\\.)?(table_schema|table_type)\\s+(not\\s+)?in\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LITERAL = Pattern.compile("'((?:[^']|'')*)'");

    /** One row's name columns, tested against every filter the statement states. */
    private record Filters(Map<String, String> equal, Map<String, Set<String>> in, Map<String, Set<String>> notIn) {

        static Filters of(String sql) {
            Map<String, String> equal = new HashMap<>();
            Matcher eq = EQUALS.matcher(sql);
            while (eq.find()) {
                equal.put(eq.group(1).toLowerCase(Locale.ROOT), eq.group(2).replace("''", "'"));
            }
            Map<String, Set<String>> in = new HashMap<>();
            Map<String, Set<String>> notIn = new HashMap<>();
            Matcher list = IN_LIST.matcher(sql);
            while (list.find()) {
                Set<String> values = new java.util.HashSet<>();
                Matcher literal = LITERAL.matcher(list.group(3));
                while (literal.find()) {
                    values.add(literal.group(1).replace("''", "'"));
                }
                (list.group(2) == null ? in : notIn).put(list.group(1).toLowerCase(Locale.ROOT), values);
            }
            return new Filters(equal, in, notIn);
        }

        boolean admits(String column, String value) {
            String wanted = equal.get(column);
            if (wanted != null && !wanted.equals(value)) {
                return false;
            }
            Set<String> allowed = in.get(column);
            if (allowed != null && !allowed.contains(value)) {
                return false;
            }
            Set<String> excluded = notIn.get(column);
            return excluded == null || !excluded.contains(value);
        }
    }

    private static final String CATALOG_NAME = "pravaha";
    private static final String SCHEMA_NAME = "public";
    private static final String TABLE_TYPE = "BASE TABLE";

    private static boolean admitsTable(Filters filters, String name) {
        return filters.admits("table_catalog", CATALOG_NAME)
                && filters.admits("table_schema", SCHEMA_NAME)
                && filters.admits("table_name", name)
                && filters.admits("table_type", TABLE_TYPE);
    }

    // ------------------------------------------------------------------------------------------
    // tables

    private ViewQuery.Result tables(String sql, boolean withCatalog, List<String> visible) {
        Filters filters = Filters.of(sql);
        StreamSchema schema = withCatalog
                ? schema("tables", "table_catalog", "table_schema", "table_name", "table_type")
                : schema("tables", "table_schema", "table_name", "table_type");
        List<Object[]> rows = new ArrayList<>();
        for (String name : visible) {
            if (!admitsTable(filters, name)) {
                continue;
            }
            rows.add(
                    withCatalog
                            ? new Object[] {CATALOG_NAME, SCHEMA_NAME, name, TABLE_TYPE}
                            : new Object[] {SCHEMA_NAME, name, TABLE_TYPE});
        }
        return result(schema, rows);
    }

    // ------------------------------------------------------------------------------------------
    // columns

    private ViewQuery.Result columns(String sql, boolean npgsqlShape, List<String> visible) {
        Filters filters = Filters.of(sql);
        StreamSchema schema = npgsqlShape
                ? StreamSchema.builder("columns")
                        .field("table_catalog", Types.string())
                        .field("table_schema", Types.string())
                        .field("table_name", Types.string())
                        .field("column_name", Types.string())
                        .field("ordinal_position", Types.int32())
                        .field("column_default", Types.string())
                        .field("is_nullable", Types.string())
                        .field("data_type", Types.string())
                        .field("character_maximum_length", Types.int32())
                        .field("character_octet_length", Types.int32())
                        .field("numeric_precision", Types.int32())
                        .field("numeric_precision_radix", Types.int32())
                        .field("numeric_scale", Types.int32())
                        .field("datetime_precision", Types.int32())
                        .field("character_set_catalog", Types.string())
                        .field("character_set_schema", Types.string())
                        .field("character_set_name", Types.string())
                        .field("collation_catalog", Types.string())
                        .build()
                : StreamSchema.builder("columns")
                        .field("column_name", Types.string())
                        .field("ordinal_position", Types.int32())
                        .field("is_nullable", Types.string())
                        .field("data_type", Types.string())
                        .build();
        List<Object[]> rows = new ArrayList<>();
        for (String name : visible) {
            if (!admitsTable(filters, name)) {
                continue;
            }
            ServedView view = catalog.find(name).orElse(null);
            if (view == null) {
                continue;
            }
            int ordinal = 0;
            for (Field field : view.schema().fields()) {
                ordinal++;
                if (!filters.admits("column_name", field.name())) {
                    continue;
                }
                String nullable = field.type().nullable() ? "YES" : "NO";
                if (!npgsqlShape) {
                    rows.add(new Object[] {field.name(), ordinal, nullable, dataType(field)});
                    continue;
                }
                Integer[] numeric = numericFacets(field);
                rows.add(new Object[] {
                    CATALOG_NAME,
                    SCHEMA_NAME,
                    name,
                    field.name(),
                    ordinal,
                    null, // column_default: no column has one
                    nullable,
                    udtName(field), // Npgsql selects udt_name AS data_type
                    null, // character_maximum_length: text is unbounded
                    null, // character_octet_length
                    numeric[0],
                    numeric[1],
                    numeric[2],
                    field.type().typeName() == com.ash.messaging.pravaha.api.data.TypeName.DATE ? 0 : null,
                    null,
                    null,
                    null,
                    null
                });
            }
        }
        return result(schema, rows);
    }

    /** {@code information_schema.columns.data_type}: PostgreSQL's SQL-standard spelling. */
    static String dataType(Field field) {
        return switch (field.type().typeName()) {
            case BOOLEAN -> "boolean";
            case INT8, INT16 -> "smallint";
            case INT32 -> "integer";
            case INT64 -> "bigint";
            case FLOAT32 -> "real";
            case FLOAT64 -> "double precision";
            case DECIMAL -> "numeric";
            case STRING -> "text";
            case DATE -> "date";
            case TIMESTAMP_LTZ -> "timestamp with time zone";
            case BYTES -> "bytea";
            case TIME -> "time without time zone";
            default -> field.type().sqlName();
        };
    }

    /** {@code information_schema.columns.udt_name}: the {@code pg_type.typname}. */
    static String udtName(Field field) {
        return switch (field.type().typeName()) {
            case BOOLEAN -> "bool";
            case INT8, INT16 -> "int2";
            case INT32 -> "int4";
            case INT64 -> "int8";
            case FLOAT32 -> "float4";
            case FLOAT64 -> "float8";
            case DECIMAL -> "numeric";
            case STRING -> "text";
            case DATE -> "date";
            case TIMESTAMP_LTZ -> "timestamptz";
            case BYTES -> "bytea";
            case TIME -> "time";
            default -> field.type().sqlName().toLowerCase(Locale.ROOT);
        };
    }

    /** {@code numeric_precision}, {@code numeric_precision_radix}, {@code numeric_scale}, as PostgreSQL reports them. */
    private static Integer[] numericFacets(Field field) {
        return switch (field.type().typeName()) {
            case INT8, INT16 -> new Integer[] {16, 2, 0};
            case INT32 -> new Integer[] {32, 2, 0};
            case INT64 -> new Integer[] {64, 2, 0};
            case FLOAT32 -> new Integer[] {24, 2, null};
            case FLOAT64 -> new Integer[] {53, 2, null};
            case DECIMAL -> {
                DecimalType decimal = (DecimalType) field.type();
                yield new Integer[] {decimal.precision(), 10, decimal.scale()};
            }
            default -> new Integer[] {null, null, null};
        };
    }

    // ------------------------------------------------------------------------------------------
    // Foreign keys: Power BI's two queries, one per direction. There are none, either way.

    private static Optional<ViewQuery.Result> foreignKeys(String shape) {
        if (shape.contains("as pk_table_schema")) {
            return Optional.of(result(
                    StreamSchema.builder("foreign_keys_in")
                            .field("pk_table_schema", Types.string())
                            .field("pk_table_name", Types.string())
                            .field("pk_column_name", Types.string())
                            .field("fk_column_name", Types.string())
                            .field("ordinal", Types.int32())
                            .field("fk_name", Types.string())
                            .build(),
                    List.of()));
        }
        if (shape.contains("as pk_column_name")) {
            return Optional.of(result(
                    StreamSchema.builder("foreign_keys_out")
                            .field("pk_column_name", Types.string())
                            .field("fk_table_schema", Types.string())
                            .field("fk_table_name", Types.string())
                            .field("fk_column_name", Types.string())
                            .field("ordinal", Types.int32())
                            .field("fk_name", Types.string())
                            .build(),
                    List.of()));
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------------------

    private static StreamSchema schema(String name, String... textColumns) {
        StreamSchema.Builder builder = StreamSchema.builder(name);
        for (String column : textColumns) {
            builder.field(column, Types.string());
        }
        return builder.build();
    }

    private static List<Object[]> rowsOf(Object[] row) {
        List<Object[]> rows = new ArrayList<>(1);
        rows.add(row);
        return rows;
    }

    private static ViewQuery.Result result(StreamSchema schema, List<Object[]> rows) {
        return new ViewQuery.Result(schema, rows);
    }
}
