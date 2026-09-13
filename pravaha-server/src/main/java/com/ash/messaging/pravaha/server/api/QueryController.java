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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.codegen.FilterProjectGenerator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
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

    public QueryController(StreamCatalog catalog, DtoMapper mapper) {
        this.catalog = catalog;
        this.mapper = mapper;
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

    @PostMapping("/validate")
    @Operation(summary = "Validate and plan a query without running it")
    public ApiDtos.ValidationResult validate(@RequestBody ValidateRequest request) {
        long start = System.nanoTime();
        try {
            PhysicalOperator plan = planFor(request.sql());
            return ApiDtos.ValidationResult.ok(
                    mapper.toFields(plan.outputSchema()), (System.nanoTime() - start) / 1_000L);
        } catch (PravahaException e) {
            // Not an error response: the caller asked whether the query is valid and the answer is
            // "no, and here is why", which is a successful answer to that question.
            return new ApiDtos.ValidationResult(
                    false,
                    List.of(new ApiDtos.Diagnostic(e.errorCode().code(), e.getMessage(), e.helpUrl(), "error")),
                    List.of(),
                    (System.nanoTime() - start) / 1_000L);
        }
    }

    @PostMapping("/explain")
    @Operation(summary = "Show the plan for a query")
    public ApiDtos.ExplainResult explain(
            @RequestBody ValidateRequest request, @RequestParam(defaultValue = "physical") String level) {

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

    public record ValidateRequest(String sql) {}
}
