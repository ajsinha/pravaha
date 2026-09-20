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

    static {
        // UTF-8 for string literals, because Calcite's default is ISO-8859-1 and nothing overrode
        // it. `WHERE name = '日本語'` -- or any literal containing a character above U+00FF, which
        // is most CJK, every emoji and a great many symbols -- was refused outright:
        //
        //     PRV-2010  Failed to encode '日本語' in character set 'ISO-8859-1'
        //
        // The same characters as column *data* have always worked: read from a file, compared,
        // uppercased, substringed byte-exact. Only writing one down in SQL was impossible.
        //
        // A system property rather than connection configuration, because that is where Calcite
        // reads it: CalciteSystemProperty resolves calcite.default.charset once, in a static
        // initialiser, and no per-planner setting reaches it.
        //
        // This block is the second line of defence, not the first. It only helps if this class
        // loads before Calcite does, and whether it does depends on what the process touched
        // first -- which made it pass in one test run and fail in another. The property is set
        // for real in src/main/resources/saffron.properties, which Calcite reads whatever the
        // order. This stays for an embedder that shades the jar and loses the resource.
        //
        // It survived a whole QA campaign because the standing "hostile unicode" fixture, ünïcødé,
        // is made entirely of Latin-1-representable accents -- a test string chosen to look
        // adversarial that happened to agree with the defect.
        if (System.getProperty("calcite.default.charset") == null) {
            System.setProperty("calcite.default.charset", "UTF-8");
            System.setProperty("calcite.default.nationalcharset", "UTF-8");
        }
    }

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

    /** A planner whose first stream is consumed and whose remaining ones are dimension tables. */
    public static SqlPlanner withLookups(StreamSchema stream, StreamSchema... lookups) {
        PravahaSchema catalog = new PravahaSchema();
        catalog.register(stream);
        for (StreamSchema each : lookups) {
            catalog.registerLookup(each);
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

            refuseDml(parsed);

            SqlNode validated;
            SqlNode written = dropStreamKeyword(parsed);

            // Findings W-6 and X-9. Before validation, because for both of them Calcite answers
            // first and answers about its own internals -- an unresolved function signature for
            // CUMULATE, "Illegal use of dynamic parameter" for a bare `SELECT ?`. See
            // SqlShapeRefusals.checkBeforeValidation.
            SqlShapeRefusals.checkBeforeValidation(written);

            try {
                validated = planner.validate(written);
            } catch (ValidationException e) {
                // SX-5/SX-1. This appended every known stream name, which is a catalogue dump
                // handed to whoever typed a name that does not exist -- including a caller
                // authorized for nothing, and before any authorization can run, because validation
                // happens during planning. The list was there to help with typos; the cost of that
                // help was the node's whole inventory, and a caller could map it by guessing.
                //
                // The count is kept because "0 known streams" is a genuinely different diagnosis
                // from "you misspelled one of 40" -- a node whose declarations never loaded is a
                // real and confusing failure (see StreamDeclarationProperties) -- and a count
                // discloses nothing about what the names are.
                throw new PravahaException(
                        SqlErrors.VALIDATION_FAILED,
                        rootMessage(e) + "." + guidanceFor(rootMessage(e)) + " This server has "
                                + schema.streamNames().size()
                                + " stream(s) declared; their names are not listed here because that would "
                                + "tell a caller who may not read them that they exist. Use the listing call, "
                                + "which is filtered by what you may read.",
                        e);
            }

            // Finding TY-20 and TY-23. Refused here, between validation and optimisation, because
            // the optimiser is about to delete the evidence: an unlimited ORDER BY inside a derived
            // table and a CAST of a literal to text are both gone by the time a plan exists.
            SqlShapeRefusals.check(written);

            try {
                RelRoot root = planner.rel(validated);
                return root.project();
            } catch (RelConversionException e) {
                throw planningFailure(e);
            }
        } catch (PravahaException e) {
            throw e;
        } catch (StackOverflowError e) {
            // An Error, not an Exception, so `catch (Exception)` below never saw it -- and Calcite's
            // validator walks a predicate by recursing on it. A long enough boolean chain therefore
            // exhausted the stack and the raw StackOverflowError went straight to the console of
            // whatever was running: `pravaha validate` printed a stack trace instead of a refusal.
            // About a second, on an ordinary query a client library can generate by rewriting a wide
            // IN list into ORs.
            //
            // Caught here because this is the request boundary: the stack has fully unwound by the
            // time we arrive, and the alternative is a process that dies on a query it could simply
            // have refused.
            throw new PravahaException(
                    SqlErrors.PLANNING_FAILED,
                    "this query nests too deeply for the planner to walk without exhausting the stack. "
                            + "It is almost always a long chain of AND or OR -- a client library rewriting a "
                            + "wide IN list is the usual source. Write it as IN (...), or split it into "
                            + "several queries. The limit is the JVM's stack rather than a number this engine "
                            + "chose, so it moves with -Xss.",
                    e);
        } catch (Exception e) {
            throw planningFailure(e);
        }
    }

    /**
     * Refuses {@code INSERT}, {@code UPDATE}, {@code DELETE} and {@code MERGE} by name, before
     * validation can call one of them an unknown table.
     *
     * <p><strong>{@code INSERT INTO sink SELECT ...} is not built, and B8 looked at building it.</strong>
     * It is not the same thing as {@code WRITING TO}, which is what it would have to be to be worth
     * a second spelling. A registration needs two things the {@code INSERT} form does not carry: a
     * <em>name</em>, which is what {@code DROP}, {@code PAUSE}, {@code RESUME} and every reader's
     * {@code FROM} clause use and which is not the sink's, and a <em>key</em>, without which the
     * view behind the query is a log and a point read against it has nothing to look up. Deriving
     * the name from the sink would give one object two identities the moment a second query writes
     * to the same sink; deriving the key from the {@code SELECT} list means guessing, and a view
     * keyed on a guess conflates rows that were never the same row. The alternative spelling is
     * three words longer and says both, so the refusal names it rather than inventing them
     * (ADR-043, ADR-049).
     *
     * <p>Before validation deliberately. Calcite validates the target table first, so {@code INSERT
     * INTO user_volume_agg SELECT ...} over a sink -- which is not in the catalogue, because a sink
     * is not a table you read -- was refused as an unknown identifier ({@code PRV-2002}), which
     * sends the reader looking for a typo.
     */
    private static void refuseDml(SqlNode parsed) {
        String verb =
                switch (parsed.getKind()) {
                    case INSERT -> "INSERT";
                    case UPDATE -> "UPDATE";
                    case DELETE -> "DELETE";
                    case MERGE -> "MERGE";
                    default -> null;
                };
        if (verb == null) {
            return;
        }
        String how = "INSERT".equals(verb)
                ? " A continuous query names where its output goes beside its own name and key, not instead "
                        + "of them: CREATE CONTINUOUS QUERY <name> KEYED BY (<column>, ...) WRITING TO <sink> AS "
                        + "<select>. The same thing is said by WITH (sink = '<sink>'), by `pravaha register "
                        + "--sink <sink>`, and by the fourth field of the pravaha.register Flight action."
                : "";
        throw new PravahaException(
                SqlErrors.UNSUPPORTED_OPERATOR,
                verb + " is not built: this engine answers questions and maintains views, and it has no DML "
                        + "surface -- there is nothing here whose rows a statement may edit in place." + how
                        + " See docs/CONTINUOUS_QUERIES.md for what this engine executes and what it refuses.");
    }

    /**
     * The one table a query names, taken from the parse tree and nothing else.
     *
     * <p>SX-5, and the channel its first fix left open. {@link #plan} resolves a name against the
     * catalogue while validating, so a query naming a view that does not exist fails there --
     * {@code PRV-2002}, raised before any caller has been authorized for anything. A view that
     * exists but is forbidden gets as far as the policy and is refused with {@code PRV-7002}. Two
     * codes, and the difference between them is an existence oracle: a caller entitled to nothing
     * can confirm a name by reading which refusal comes back.
     *
     * <p>Closing it means authorizing the name <em>before</em> the catalogue is consulted, which
     * means obtaining the name without consulting it. Parsing alone does that. The parser builds a
     * tree out of the text and never asks what exists, so it answers identically for a real name
     * and an invented one -- which is the whole point.
     *
     * <p>Only the single-table shape is recognised, and that is the shape a request/response query
     * is allowed to have: {@code ViewQuery} refuses anything reading more than one view. A join, a
     * subquery or a {@code VALUES} returns empty and its caller keeps the behaviour it had, which is
     * correct rather than merely convenient -- those queries are refused on other grounds and never
     * reach a view.
     *
     * @throws PravahaException {@code PRV-2001} when the text will not parse. A syntax error says
     *     nothing about what exists, so reporting it before authorizing discloses nothing.
     */
    public java.util.Optional<String> referencedTable(String sql) {
        try (Planner planner = Frameworks.getPlanner(frameworkConfig())) {
            SqlNode parsed;
            try {
                parsed = planner.parse(sql);
            } catch (SqlParseException e) {
                throw new PravahaException(SqlErrors.PARSE_FAILED, e.getMessage(), e);
            }
            return tableOf(dropStreamKeyword(parsed));
        }
    }

    /** The identifier in {@code FROM}, when there is exactly one and it is a plain name. */
    private static java.util.Optional<String> tableOf(SqlNode node) {
        SqlNode query = node instanceof org.apache.calcite.sql.SqlOrderBy ordered ? ordered.query : node;
        if (!(query instanceof org.apache.calcite.sql.SqlSelect select)) {
            return java.util.Optional.empty();
        }
        SqlNode from = select.getFrom();
        // "FROM v AS x" and "FROM v x" both arrive as an AS call wrapping the identifier.
        if (from instanceof org.apache.calcite.sql.SqlBasicCall call
                && call.getOperator().getKind() == org.apache.calcite.sql.SqlKind.AS
                && call.operandCount() > 0) {
            from = call.operand(0);
        }
        if (from instanceof org.apache.calcite.sql.SqlIdentifier identifier && !identifier.names.isEmpty()) {
            return java.util.Optional.of(identifier.names.get(identifier.names.size() - 1));
        }
        return java.util.Optional.empty();
    }

    /** The text Calcite puts in front of the expression it was converting when it failed. */
    private static final String CONVERTING_PREFIX = "while converting ";

    // Past this many characters a message is not a diagnosis any more, it is a dump: unreadable in a
    // terminal, a log line or a UI, and for a predicate that is exactly what rootMessage() below can
    // return verbatim (X-11 part B).
    private static final int MESSAGE_SUMMARY_THRESHOLD = 2_000;

    /**
     * Wraps a planning failure, replacing the message with a bounded summary when the original
     * threatens to interpolate an entire expression into the error.
     *
     * <p>Calcite's conversion failures carry the offending expression's full text in their message
     * ({@code "while converting <expr>: ..."}), and for a long {@code AND}/{@code OR} chain that
     * expression is the whole predicate. When the message is this large <em>and</em> has that shape,
     * it is summarised -- the operator and the number of terms, not the text -- and raised as
     * {@link SqlErrors#PREDICATE_TOO_LARGE} rather than the generic {@link SqlErrors#PLANNING_FAILED}.
     * Anything smaller, or shaped differently, keeps today's behaviour untouched.
     */
    private static PravahaException planningFailure(Throwable e) {
        String message = rootMessage(e);
        if (message.length() > MESSAGE_SUMMARY_THRESHOLD && message.contains(CONVERTING_PREFIX)) {
            return new PravahaException(SqlErrors.PREDICATE_TOO_LARGE, summarizeConversionFailure(message), e);
        }
        return new PravahaException(SqlErrors.PLANNING_FAILED, message, e);
    }

    /** Turns {@code "... while converting <thousands of characters> ..."} into a term count. */
    private static String summarizeConversionFailure(String message) {
        String expression = message.substring(message.indexOf(CONVERTING_PREFIX) + CONVERTING_PREFIX.length());
        int andCount = countOccurrences(expression, " AND ");
        int orCount = countOccurrences(expression, " OR ");
        String shape;
        if (andCount == 0 && orCount == 0) {
            // No AND/OR found -- still too large to include, but not identifiably a boolean chain, so
            // the summary says only what is true: its size.
            shape = "a single expression";
        } else {
            boolean isOr = orCount >= andCount;
            int terms = (isOr ? orCount : andCount) + 1;
            shape = terms + "-term " + (isOr ? "OR" : "AND") + " chain";
        }
        return "the query planner failed while converting a predicate, and the predicate itself is "
                + "omitted from this message because it is " + shape + " (" + expression.length()
                + " characters) -- too large to be useful here. This shape is usually a client library "
                + "rewriting a wide IN (...) list into AND/OR, or a generated query; write it as "
                + "IN (...) instead, or split it into several queries.";
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int from = haystack.indexOf(needle); from >= 0; from = haystack.indexOf(needle, from + needle.length())) {
            count++;
        }
        return count;
    }

    /**
     * Accepts {@code SELECT STREAM} and treats it as {@code SELECT}.
     *
     * <p>The keyword is redundant here, and saying why matters. Calcite's distinction is between a
     * table and the stream of changes to it, and a query must pick one; a table that declares itself
     * streamable can then <em>only</em> be read as a stream, and every ordinary {@code SELECT}
     * against it is rejected. In Pravaha there is nothing to pick between: every registered query is
     * continuous and every operator is incremental, so the table and the stream are the same object
     * and {@code STREAM} asks for what it would get anyway.
     *
     * <p>So the keyword is dropped from the tree rather than honoured. The alternative -- declaring
     * the tables streamable -- makes {@code SELECT STREAM} work and breaks every query that does not
     * say it, which is all of them in the quickstart and the examples. Dropping a keyword that
     * cannot change the answer is the smaller lie by a wide margin, and the design's own SQL (§11.2)
     * uses it, so refusing it would refuse the documented dialect.
     */
    private static SqlNode dropStreamKeyword(SqlNode node) {
        if (node instanceof org.apache.calcite.sql.SqlSelect select) {
            if (select.isKeywordPresent(org.apache.calcite.sql.SqlSelectKeyword.STREAM)) {
                select.setOperand(
                        0,
                        new org.apache.calcite.sql.SqlNodeList(
                                java.util.List.of(), org.apache.calcite.sql.parser.SqlParserPos.ZERO));
            }
        } else if (node instanceof org.apache.calcite.sql.SqlOrderBy orderBy) {
            dropStreamKeyword(orderBy.query);
        } else if (node instanceof org.apache.calcite.sql.SqlCall call) {
            call.getOperandList().stream().filter(java.util.Objects::nonNull).forEach(SqlPlanner::dropStreamKeyword);
        }
        return node;
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

    /**
     * Pravaha's own sentence, appended to a refusal Calcite's validator made first.
     *
     * <p>Finding TY-24. Five ordinary mistakes never reach this engine's own message: an unknown
     * function, a function given the wrong number of arguments, a cast between types that do not
     * convert. Calcite's validator refuses each of them first, correctly and with a less specific
     * message -- {@code No match found for function signature LTRIM(<CHARACTER>)} says nothing
     * about what this engine <em>does</em> evaluate, which is the only thing the reader needs.
     *
     * <p>Winning the race instead of joining it would mean replacing Calcite's operator table and
     * its cast checker with Pravaha's own, so that every unsupported function is declared here in
     * order to be refused here. That is a real piece of work and it is not this one. Appending the
     * supported set to the validator's own sentence costs nothing, is correct whichever refusal
     * won, and is pinned by a test -- so if a Calcite upgrade rewords one of these, the test says
     * so rather than the sentence quietly disappearing.
     */
    private static String guidanceFor(String message) {
        String functions = " Pravaha evaluates + - * / %, ABS, FLOOR, CEIL, ROUND, CASE WHEN, UPPER, LOWER, "
                + "TRIM, SUBSTRING and ||, and the aggregates COUNT, SUM, MIN, MAX and AVG; each of the "
                + "one-argument functions takes exactly one argument. See docs/CONTINUOUS_QUERIES.md.";
        if (message.contains("No match found for function signature")
                || message.contains("Invalid number of arguments to function")) {
            return functions;
        }
        if (message.contains("Cast function cannot convert value of type")) {
            return " Pravaha evaluates conversions between numbers; a boolean is not a number here and text "
                    + "is not either. Write CASE WHEN <condition> THEN 1 ELSE 0 END for a boolean read as a "
                    + "number, and assemble text where the text is assembled. See docs/CONTINUOUS_QUERIES.md.";
        }
        return "";
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
