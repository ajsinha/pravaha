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
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.server.ai.features.AiAnswers;
import com.ash.messaging.pravaha.server.ai.features.AiFeatures;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

/**
 * The plain-English features' REST face (docs/guides/AI.md; Drishti's {@code BiAiController}). Each is one
 * {@code POST} as the signed-in caller, through the AI policy under its feature's name; each answer says what
 * the model chose beside what the engine made of it, and none saves anything ({@code Cache-Control:
 * no-store}). Off with the AI layer ({@code PRV-1080}); a model answer the engine refused twice, or that is no
 * answer, is {@code PRV-1088}; the engine's own refusal of what the model chose keeps its own code.
 */
@RestController
@RequestMapping("/api/v1/ai")
@Tag(name = "AI features", description = "Plain English over the engine, which judges and runs everything as you")
public class AiFeaturesController {

    /**
     * Ask your data.
     *
     * @param question the question
     * @param conversation earlier questions and the SQL chosen for each, oldest first, for a follow-up
     */
    public record AskBody(
            @Nullable String question, @Nullable List<AiAnswers.AskTurn> conversation) {}

    /**
     * A draft from words.
     *
     * @param description what it should do
     * @param name the name to give it, else the model's suggestion
     */
    public record DraftBody(
            @Nullable String description, @Nullable String name) {}

    /**
     * What to explain.
     *
     * @param kind {@code sql}, {@code refusal} or {@code change}
     * @param sql the SQL ({@code sql}), or the statement a refusal was given for ({@code refusal}, optional)
     * @param query a registered query's name, instead of {@code sql} ({@code sql})
     * @param code the refusal's code, {@code PRV-nnnn} ({@code refusal})
     * @param message the refusal's message ({@code refusal}, optional)
     * @param view the view ({@code change})
     */
    public record ExplainBody(
            @Nullable String kind,
            @Nullable String sql,
            @Nullable String query,
            @Nullable String code,
            @Nullable String message,
            @Nullable String view) {}

    /**
     * A batch of a view's changes, as a subscriber received them.
     *
     * @param view the view
     * @param changes its changes
     */
    public record NarrateBody(
            @Nullable String view, @Nullable List<AiAnswers.ChangeRow> changes) {}

    /**
     * A question about a console page.
     *
     * @param screen the page's screen key
     * @param about the page's "About this page" text
     * @param topics help topics that go with it
     * @param question the question
     */
    public record PageBody(
            @Nullable String screen,
            @Nullable String about,
            @Nullable List<AiAnswers.HelpTopic> topics,
            @Nullable String question) {}

    private final AiFeatures features;
    private final HttpAuthorizer authorizer;

    public AiFeaturesController(AiFeatures features, HttpAuthorizer authorizer) {
        this.features = features;
        this.authorizer = authorizer;
    }

    @PostMapping("/ask")
    @Operation(summary = "Ask your data: the SQL the model chose, run as you, and the chart the result's shape suits")
    public AiAnswers.AskAnswer ask(@RequestBody AskBody body, HttpServletRequest http, HttpServletResponse response) {
        return noStore(response, features.ask(authorizer.principalOf(http), body.question(), body.conversation()));
    }

    @PostMapping("/draft")
    @Operation(summary = "Draft a continuous query from words, judged and dry-run by the engine; nothing is registered")
    public AiAnswers.DraftAnswer draft(
            @RequestBody DraftBody body, HttpServletRequest http, HttpServletResponse response) {
        return noStore(response, features.draft(authorizer.principalOf(http), body.description(), body.name()));
    }

    @PostMapping("/alert-draft")
    @Operation(summary = "Draft a CREATE ALERT from words, judged as creating it would be; nothing is created")
    public AiAnswers.AlertDraftAnswer alertDraft(
            @RequestBody DraftBody body, HttpServletRequest http, HttpServletResponse response) {
        return noStore(response, features.alertDraft(authorizer.principalOf(http), body.description(), body.name()));
    }

    @PostMapping("/explain")
    @Operation(summary = "Explain a query's SQL (grounded in its plan), a refusal, or why a view changed")
    public AiAnswers.ExplainAnswer explain(
            @RequestBody ExplainBody body, HttpServletRequest http, HttpServletResponse response) {
        return noStore(
                response,
                features.explain(
                        authorizer.principalOf(http),
                        body.kind(),
                        body.sql(),
                        body.query(),
                        body.code(),
                        body.message(),
                        body.view()));
    }

    @PostMapping("/narrate")
    @Operation(summary = "A batch of a view's changes in plain English (keys and counts under labels-only)")
    public AiAnswers.NarrateAnswer narrate(
            @RequestBody NarrateBody body, HttpServletRequest http, HttpServletResponse response) {
        return noStore(response, features.narrate(authorizer.principalOf(http), body.view(), body.changes()));
    }

    @PostMapping("/page")
    @Operation(summary = "Ask about a console page, grounded in its About text and help topics only")
    public AiAnswers.PageAnswer page(
            @RequestBody PageBody body, HttpServletRequest http, HttpServletResponse response) {
        return noStore(
                response,
                features.page(
                        authorizer.principalOf(http), body.screen(), body.about(), body.topics(), body.question()));
    }

    private static <T> T noStore(HttpServletResponse response, T answer) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return answer;
    }
}
