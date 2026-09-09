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
import java.util.Properties;

import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.RelConversionException;
import org.apache.calcite.tools.ValidationException;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * Turns SQL into a validated, optimised relational tree.
 *
 * <p>Everything expensive happens here, once, at query registration: parse, validate, optimise. The
 * steady state does none of it (design section 6.3). That two-phase split is what lets the runtime be
 * allocation-free -- there is no planner object anywhere near a record.
 *
 * <p>A planner instance is single-use, which is Calcite's own constraint rather than ours: its
 * {@link Planner} accumulates state across the parse/validate/convert sequence and cannot be
 * rewound. Each call here creates a fresh one, so the class itself is safe to reuse and to share.
 *
 * <p>Identifiers are case-sensitive and unquoted names are <em>not</em> upper-cased. Calcite
 * defaults to Oracle's behaviour, which would silently turn {@code user_id} into {@code USER_ID}
 * and fail to resolve it against a schema discovered from a store that uses lower case -- which is
 * to say, nearly every store.
 */
public final class SqlPlanner {

    private final PravahaSchema schema;

    public SqlPlanner(PravahaSchema schema) {
        this.schema = schema;
    }

    /** A planner over a fresh, empty catalog. */
    public static SqlPlanner withStreams(StreamSchema... streams) {
        PravahaSchema catalog = new PravahaSchema();
        for (StreamSchema stream : streams) {
            catalog.register(stream);
        }
        return new SqlPlanner(catalog);
    }

    public PravahaSchema schema() {
        return schema;
    }

    /**
     * Parses, validates and optimises.
     *
     * @throws PravahaException with a {@code PRV-2xxx} code and the original message. Calcite's
     *     diagnostics carry line and column, which is most of what makes a syntax error fixable, so
     *     they are preserved rather than replaced.
     */
    public RelNode plan(String sql) {
        FrameworkConfig config = frameworkConfig();
        try (Planner planner = Frameworks.getPlanner(config)) {
            SqlNode parsed;
            try {
                parsed = planner.parse(sql);
            } catch (SqlParseException e) {
                throw new PravahaException(SqlErrors.PARSE_FAILED, e.getMessage(), e);
            }

            SqlNode validated;
            try {
                validated = planner.validate(parsed);
            } catch (ValidationException e) {
                throw new PravahaException(
                        SqlErrors.VALIDATION_FAILED, rootMessage(e) + ". Known streams: " + schema.streamNames(), e);
            }

            try {
                RelRoot root = planner.rel(validated);
                return root.project();
            } catch (RelConversionException e) {
                throw new PravahaException(SqlErrors.PLANNING_FAILED, rootMessage(e), e);
            }
        } catch (PravahaException e) {
            throw e;
        } catch (Exception e) {
            throw new PravahaException(SqlErrors.PLANNING_FAILED, rootMessage(e), e);
        }
    }

    /** The plan as text, for {@code EXPLAIN} and for golden-plan tests. */
    public String explain(String sql) {
        return RelOptUtil.toString(plan(sql));
    }

    private FrameworkConfig frameworkConfig() {
        SchemaPlus root = Frameworks.createRootSchema(true);
        SchemaPlus pravaha = root.add("pravaha", schema);

        Properties properties = new Properties();
        // Store column names are overwhelmingly lower case. Calcite's Oracle-style default would
        // upper-case unquoted identifiers and then fail to resolve them, which produces a very
        // confusing "column not found" for a column that plainly exists.
        properties.setProperty(CalciteConnectionProperty.CASE_SENSITIVE.camelName(), "true");
        properties.setProperty(CalciteConnectionProperty.UNQUOTED_CASING.camelName(), "UNCHANGED");
        properties.setProperty(CalciteConnectionProperty.QUOTED_CASING.camelName(), "UNCHANGED");

        return Frameworks.newConfigBuilder()
                .defaultSchema(pravaha)
                .parserConfig(SqlParser.config()
                        .withCaseSensitive(true)
                        .withUnquotedCasing(org.apache.calcite.avatica.util.Casing.UNCHANGED)
                        .withQuotedCasing(org.apache.calcite.avatica.util.Casing.UNCHANGED)
                        .withConformance(SqlConformanceEnum.LENIENT))
                .context(org.apache.calcite.plan.Contexts.of(new CalciteConnectionConfigImpl(properties)))
                .typeSystem(PravahaTypeSystem.INSTANCE)
                .build();
    }

    /** The innermost message, which is nearly always the one that says what is actually wrong. */
    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? t.toString() : message;
    }

    /** Stream names currently resolvable. */
    public List<String> streamNames() {
        return List.copyOf(schema.streamNames());
    }
}
