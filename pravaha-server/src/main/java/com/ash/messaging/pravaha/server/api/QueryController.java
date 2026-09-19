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
package com.ash.messaging.pravaha.server.api;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.codegen.FilterProjectGenerator;
import com.ash.messaging.pravaha.registry.FeedStatus;
import com.ash.messaging.pravaha.registry.QueryListing;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.RegistryErrors;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.sql.SourcePosition;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

/**
 * Query planning and validation.
 *
 * <p>{@code /validate} is the endpoint the console's editor calls on every keystroke burst, so its
 * shape matters: an invalid query is a <strong>200 with {@code valid: false}</strong>, not an error
 * response. A syntax error while someone is mid-word is a normal state of an editor, and returning
 * 400 for it would make every keystroke look like a failure in the client's logs and metrics.
 */
@RestController
@RequestMapping("/api/v1/queries")
@Tag(name = "Queries", description = "Validate, explain and plan continuous queries")
public class QueryController {

    private final StreamCatalog catalog;
    private final DtoMapper mapper;
    private final com.ash.messaging.pravaha.server.security.HttpAuthorizer authorizer;
    private final RegistryAccess registry;

    /** Planning only, with no registered queries to describe. */
    public QueryController(
            StreamCatalog catalog,
            DtoMapper mapper,
            com.ash.messaging.pravaha.server.security.HttpAuthorizer authorizer) {
        this(catalog, mapper, authorizer, new RegistryAccess(null, null, null));
    }

    @Autowired
    public QueryController(
            StreamCatalog catalog,
            DtoMapper mapper,
            com.ash.messaging.pravaha.server.security.HttpAuthorizer authorizer,
            RegistryAccess registry) {
        this.catalog = catalog;
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.registry = registry;
    }

    /**
     * Refuses unless this caller may read every stream the SQL names.
     *
     * <p>A plan describes the shape of a view and the columns it carries, which is most of what the
     * data is. These two endpoints returned full, unfiltered plans for payroll queries to a
     * principal denied every payroll view on Flight, because this surface consulted no policy at
     * all. Checked against the text rather than the plan, so a refusal costs no planning and a
     * query naming an unreadable stream cannot be planned to find out what is in it.
     */
    private void requireReadable(jakarta.servlet.http.HttpServletRequest request, String sql) {
        if (sql == null) {
            return;
        }
        String lowered = sql.toLowerCase(java.util.Locale.ROOT);
        for (StreamSchema schema : catalog.all()) {
            if (lowered.contains(schema.name().toLowerCase(java.util.Locale.ROOT))) {
                authorizer.requireRead(request, schema.name());
            }
        }
    }

    /**
     * The generated source for a plan, or why there is none.
     *
     * <p>A plan the generator does not cover is not an error: the interpreted path runs it
     * correctly, which is the design's guarantee (section 12.4). What the caller needs to know is
     * that this query takes the slower path and why, which is an answer rather than a failure.
     */
    private String generatedSource(PhysicalOperator plan) {
        try {
            return new FilterProjectGenerator().generate(plan, "ExplainStage").source();
        } catch (PravahaException e) {
            return "-- no generated form: " + e.getMessage()
                    + System.lineSeparator()
                    + "-- this query runs on the interpreted path, which is correct and slower";
        }
    }

    /**
     * Refuses a body that carried no {@code sql} field at all.
     *
     * <p>API-F9. A missing field is not an invalid query, and answering it as one produced the worst
     * possible reply: {@code /validate} returned {@code 200 valid:false} with
     * {@code PRV-2010  Cannot invoke "String.length()" because "s" is null} -- the planner's own
     * {@link NullPointerException} message, forwarded verbatim by whoever wrapped it, under a
     * planning code and a help URL for a planning failure that never happened. Dressed as a normal
     * editor diagnostic, an internal-exception leak was indistinguishable from "your SQL is wrong".
     *
     * <p>Thrown <em>before</em> the try that turns refusals into diagnostics, so it leaves as a
     * {@code 400} rather than a {@code 200}: the caller sent a malformed request, which is a
     * different thing from a query that does not validate, and only one of them is a normal state of
     * an editor.
     *
     * <p>Null only, deliberately. An empty or blank {@code sql} is what an editor sends while the
     * pane is empty and the lexer already refuses it precisely ({@code PRV-2001}); turning that into
     * a {@code 400} would make the console's own idle state an error in its logs, which is the exact
     * failure this endpoint's {@code 200 valid:false} shape exists to avoid.
     */
    private static String requireSql(ValidateRequest request) {
        if (request == null || request.sql() == null) {
            throw new PravahaException(
                    ApiErrors.MISSING_FIELD,
                    "this request has no 'sql': send a JSON body of the form {\"sql\": \"SELECT ...\"}");
        }
        return request.sql();
    }

    @PostMapping("/validate")
    @Operation(summary = "Validate and plan a query without running it")
    public ApiDtos.ValidationResult validate(
            @RequestBody ValidateRequest request, jakarta.servlet.http.HttpServletRequest http) {
        requireSql(request);
        requireReadable(http, request.sql());
        long start = System.nanoTime();
        try {
            PhysicalOperator plan = planFor(request.sql());
            return ApiDtos.ValidationResult.ok(
                    mapper.toFields(plan.outputSchema()), (System.nanoTime() - start) / 1_000L);
        } catch (PravahaException e) {
            // Not an error response: the caller asked whether the query is valid and the answer is
            // "no, and here is why", which is a successful answer to that question.
            //
            // The position is read from the parser's and validator's own fields, never from the text of
            // the message: an editor that underlined by matching "line N, column M" in English was
            // depending on the wording of a third-party library, which is not a contract.
            ApiDtos.SourceRange range = SourcePosition.of(e)
                    .map(at -> new ApiDtos.SourceRange(at.startLine(), at.startColumn(), at.endLine(), at.endColumn()))
                    .orElse(null);
            return new ApiDtos.ValidationResult(
                    false,
                    List.of(new ApiDtos.Diagnostic(e.errorCode().code(), e.getMessage(), e.helpUrl(), "error", range)),
                    List.of(),
                    (System.nanoTime() - start) / 1_000L);
        }
    }

    /** {@link #explain(ValidateRequest, String, String, HttpServletRequest)} as text only. */
    public ApiDtos.ExplainResult explain(ValidateRequest request, String level, HttpServletRequest http) {
        return explain(request, level, "text", http);
    }

    /**
     * The plan for some SQL.
     *
     * <p>{@code format=graph} adds the physical plan as nodes and edges, beside the text. A parameter
     * rather than a new path, for the reason {@code level=codegen} is one: it is another answer to the
     * question this endpoint already asks.
     */
    @PostMapping("/explain")
    @Operation(summary = "Show the plan for a query; format=graph adds it as nodes and edges")
    public ApiDtos.ExplainResult explain(
            @RequestBody ValidateRequest request,
            @RequestParam(defaultValue = "physical") String level,
            @RequestParam(defaultValue = "text") String format,
            HttpServletRequest http) {

        requireSql(request);
        requireReadable(http, request.sql());
        if (!"text".equals(format) && !"graph".equals(format)) {
            throw new IllegalArgumentException("format must be 'text' or 'graph', got '" + format + "'");
        }
        ApiDtos.ExplainResult text = explainText(request, level);
        if ("text".equals(format)) {
            return text;
        }
        return new ApiDtos.ExplainResult(
                text.level(), text.plan(), text.outputFields(), mapper.toPlanGraph(planFor(request.sql()), null));
    }

    private ApiDtos.ExplainResult explainText(ValidateRequest request, String level) {
        SqlPlanner planner = plannerFor();
        return switch (level) {
            case "codegen" -> {
                // The Java the engine will actually run. Not a new endpoint: it is another answer to
                // the question this one already asks, and adding a path for it would grow the locked
                // API surface for something an existing parameter expresses.
                PhysicalOperator plan = planFor(request.sql());
                yield new ApiDtos.ExplainResult("codegen", generatedSource(plan), mapper.toFields(plan.outputSchema()));
            }
            case "logical" -> new ApiDtos.ExplainResult("logical", planner.explain(request.sql()), List.of());
            case "physical" -> {
                PhysicalOperator plan = new PhysicalPlanBuilder().build(planner.plan(request.sql()));
                yield new ApiDtos.ExplainResult(
                        "physical", PhysicalPlanBuilder.explain(plan), mapper.toFields(plan.outputSchema()));
            }
            default -> throw new IllegalArgumentException("level must be 'logical' or 'physical', got '" + level + "'");
        };
    }

    private PhysicalOperator planFor(String sql) {
        return new PhysicalPlanBuilder().build(plannerFor().plan(sql));
    }

    private SqlPlanner plannerFor() {
        if (catalog.lookups().isEmpty()) {
            return SqlPlanner.withStreams(catalog.all().toArray(new StreamSchema[0]));
        }
        // Streams and dimension tables are registered differently because the planner treats them
        // differently. Validating a lookup query against a catalog that knew only streams reported
        // the table as not found -- for a query a registration would have accepted.
        com.ash.messaging.pravaha.sql.PravahaSchema schema = new com.ash.messaging.pravaha.sql.PravahaSchema();
        catalog.all().forEach(schema::register);
        catalog.lookups().forEach(schema::registerLookup);
        return new SqlPlanner(schema);
    }

    // ------------------------------------------------------------------ registered queries

    /**
     * Every registered query this caller may see.
     *
     * <p>The same rules as Flight's {@code pravaha.list}, because it is the same code: {@link
     * QueryListing}. A name the policy denies is absent, a query reading a stream the caller may not
     * read is absent (SX-11), and a caller entitled only to a row-filtered slice sees the query with
     * its counts withheld (SX-18).
     */
    @GetMapping
    @Operation(summary = "List the registered continuous queries this caller may see")
    public List<ApiDtos.QueryDetail> list(HttpServletRequest http) {
        Principal principal = authorizer.principalOf(http);
        return registry.listing()
                .map(listing -> listing.list(principal, "http.list").stream()
                        .map(entry -> detail(listing, principal, entry, "http.list"))
                        .toList())
                .orElse(List.of());
    }

    /**
     * One registered query.
     *
     * <p>A name the caller's policy denies is refused ({@code 403}) whether or not it exists. A name
     * it allows answers {@code 404} identically when nothing is registered under it and when the query
     * reads a stream the caller may not read -- telling those apart would tell a principal denied
     * {@code payroll} that a view over it exists.
     */
    @GetMapping("/{name}")
    @Operation(summary = "Describe one registered continuous query")
    public ApiDtos.QueryDetail get(@PathVariable String name, HttpServletRequest http) {
        Principal principal = authorizer.principalOf(http);
        QueryListing listing = requireListing(http, name);
        QueryListing.Entry entry =
                listing.find(principal, name, "http.describe").orElseThrow(() -> noSuchQuery(name));
        return detail(listing, principal, entry, "http.describe");
    }

    /**
     * A registered query's physical plan as a graph, with what the engine measures for the query.
     *
     * <p>The plan the query is <em>running</em>, taken from its execution rather than re-planned from
     * its text: a query registered with bound parameters has values in its plan that its SQL does not.
     */
    @GetMapping("/{name}/plan")
    @Operation(summary = "A registered query's plan as nodes and edges")
    public ApiDtos.PlanGraph plan(@PathVariable String name, HttpServletRequest http) {
        Principal principal = authorizer.principalOf(http);
        QueryListing listing = requireListing(http, name);
        QueryListing.Entry entry = listing.find(principal, name, "http.plan").orElseThrow(() -> noSuchQuery(name));
        return mapper.toPlanGraph(entry.query().plan(), telemetry(entry));
    }

    /**
     * The listing rules, or -- before the node has a registry -- the same answers they would give.
     *
     * <p>A denied name is refused exactly as it is with a registry; an allowed one is not registered,
     * because nothing is.
     */
    private QueryListing requireListing(HttpServletRequest http, String name) {
        return registry.listing().orElseGet(() -> {
            authorizer.requireRead(http, name);
            throw noSuchQuery(name);
        });
    }

    static com.ash.messaging.pravaha.api.PravahaException noSuchQuery(String name) {
        return new com.ash.messaging.pravaha.api.PravahaException(
                RegistryErrors.NO_SUCH_QUERY,
                "no registered query named '" + name + "' that you may see. The registered queries are not "
                        + "listed here; GET /api/v1/queries lists the ones you may see.");
    }

    private ApiDtos.QueryDetail detail(
            QueryListing listing, Principal principal, QueryListing.Entry entry, String action) {
        RegisteredQuery query = entry.query();
        var view = query.view();
        ApiDtos.QuerySink sink = entry.sink()
                .map(sinkName -> new ApiDtos.QuerySink(
                        sinkName,
                        entry.sinkFailure().isEmpty(),
                        entry.sinkFailure()
                                .map(failure -> mapper.toProblem(failure, withheldOr(entry, failure)))
                                .orElse(null),
                        entry.rowsWrittenToSink()))
                .orElse(null);
        return new ApiDtos.QueryDetail(
                entry.name(),
                query.state().name(),
                query.sql(),
                query.fingerprint().shortForm(),
                listing.sharedNames(principal, entry, action),
                mapper.toKeyColumns(view.schema(), view.keyOrdinals()),
                view.retention().toString(),
                sink,
                entry.rowsIn(),
                entry.restricted(),
                query.registeredAt(),
                query.failure()
                        .map(failure -> mapper.toProblem(failure, withheldOr(entry, failure)))
                        .orElse(null),
                view.derivedFrom().stream().sorted().toList(),
                feed(entry));
    }

    /**
     * Whether rows still reach this query, source by source (FEED-1).
     *
     * <p>Each failure's message goes through {@link #withheldOr} like a sink's: it is a plugin's own
     * text, it can quote the row it could not read, and it can echo a binding's connection string.
     */
    private ApiDtos.QueryFeed feed(QueryListing.Entry entry) {
        FeedStatus status = entry.query().feedStatus();
        List<ApiDtos.FeedSource> sources = status.sources().stream()
                .map(source -> new ApiDtos.FeedSource(
                        source.stream(),
                        source.partition(),
                        source.state().name(),
                        source.shared(),
                        source.stop() != null && source.stop().origin(),
                        source.stop() == null
                                ? null
                                : mapper.toProblem(
                                        source.stop().failure(),
                                        withheldOr(entry, source.stop().failure())),
                        source.stop() == null ? null : source.stop().at()))
                .toList();
        ApiDtos.Problem first = status.firstStopped()
                .map(source -> mapper.toProblem(
                        source.stop().failure(), withheldOr(entry, source.stop().failure())))
                .orElse(null);
        return new ApiDtos.QueryFeed(
                status.state().name(),
                status.description(),
                sources,
                status.stoppedSources().size(),
                first);
    }

    /**
     * A failure's message, or a note that it is withheld.
     *
     * <p>A failure's text is the plugin's or the lane's own and can quote a row -- a constraint
     * violation names the key it tripped on -- so a caller entitled to a slice gets the code and not
     * the text. Everyone else gets the text with any configured sink credential struck out of it.
     */
    private String withheldOr(QueryListing.Entry entry, com.ash.messaging.pravaha.api.PravahaException failure) {
        if (entry.restricted()) {
            return "the message is withheld: your access to this view is row-filtered, and a failure's text "
                    + "can quote rows outside your entitlement. The code says what kind of failure it was.";
        }
        return registry.redact(failure.getMessage());
    }

    private static ApiDtos.QueryTelemetry telemetry(QueryListing.Entry entry) {
        RegisteredQuery query = entry.query();
        var usage = query.stateUsage();
        return new ApiDtos.QueryTelemetry(
                entry.rowsIn(),
                // How many groups a query holds is a count of the data too, so it is withheld on the
                // same evidence rows in is.
                entry.restricted() ? -1 : usage.held(),
                usage.ceiling(),
                entry.restricted() ? -1 : query.view().size(),
                query.watermarkNanos()
                        .map(nanos -> java.time.Instant.ofEpochSecond(0, nanos).toString())
                        .orElse(null),
                query.subscriberCount());
    }

    public record ValidateRequest(String sql) {}
}
