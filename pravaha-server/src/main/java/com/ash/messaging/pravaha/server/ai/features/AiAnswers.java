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
package com.ash.messaging.pravaha.server.ai.features;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * What the plain-English features answer (docs/guides/AI.md). Every answer says what the model chose --
 * the SQL, the statement -- beside what the engine made of it, and none is saved: registering a draft or
 * creating an alert is the caller's own, separate, explicit call.
 */
public final class AiAnswers {

    private AiAnswers() {}

    /**
     * A column of an answer.
     *
     * @param name its name
     * @param type its SQL type
     */
    public record ResultColumn(String name, String type) {}

    /**
     * The chart Pravaha chose from the shape of the result -- never the model.
     *
     * @param kind {@code stat} (one value), {@code line} (over a time column), {@code bar} (one category, one
     *     measure, at most 30 rows) or {@code table}
     * @param x the column along the axis, or null
     * @param y the measure, or null
     * @param series the column that splits a line into series, or null
     */
    public record ChartChoice(
            String kind,
            @Nullable String x,
            @Nullable String y,
            @Nullable String series) {}

    /**
     * An earlier turn of a conversation, so a follow-up ("and for last week?") has something to follow.
     *
     * @param question what was asked
     * @param sql the SQL the model chose for it
     */
    public record AskTurn(
            @Nullable String question, @Nullable String sql) {}

    /**
     * One change of a view, as a subscriber received it: what a narrative is written about.
     *
     * @param weight {@code 1} entered (or {@code +n}), {@code -1} left
     * @param values the row, in the view's column order
     */
    public record ChangeRow(@Nullable Long weight, @Nullable List<@Nullable Object> values) {}

    /**
     * A help topic a page sends with its question.
     *
     * @param title its title
     * @param text its text
     */
    public record HelpTopic(
            @Nullable String title, @Nullable String text) {}

    /**
     * Ask your data.
     *
     * @param question the question, as asked
     * @param sql the SQL the model chose, as run
     * @param explanation one sentence the model wrote about its SQL (not about the data)
     * @param columns the result's columns
     * @param rows the rows, as the engine answered this caller: their grants, row filters and masks applied
     * @param rowCount how many rows the engine answered
     * @param truncated whether more rows were answered than {@code pravaha.ai.features.ask-rows} returns
     * @param chart the chart chosen from the result's shape
     * @param values {@code labels-only} or {@code aggregates}: what a model may be sent
     * @param summary the model's wording of the result under {@code aggregates}; null under {@code labels-only}
     * @param turns model turns taken: 1, or 2 after a repair
     */
    public record AskAnswer(
            String question,
            String sql,
            String explanation,
            List<ResultColumn> columns,
            List<List<@Nullable Object>> rows,
            int rowCount,
            boolean truncated,
            ChartChoice chart,
            String values,
            @Nullable String summary,
            int turns) {}

    /**
     * What registering a draft would do, found without registering it.
     *
     * @param fingerprint the fingerprint the registration would have
     * @param plan the engine's physical plan
     * @param outputFields the view's columns
     * @param reads the streams and views it reads
     * @param lane where its computation would run, and why
     * @param sharesWith registered queries you may see with the same fingerprint: their computation is reused
     */
    public record DraftDryRun(
            String fingerprint,
            String plan,
            List<ResultColumn> outputFields,
            List<String> reads,
            String lane,
            List<String> sharesWith) {}

    /**
     * A drafted continuous query, judged by the engine and not registered.
     *
     * @param description what was asked for
     * @param name the suggested name (free in your tenant when it was drafted)
     * @param sql the {@code SELECT}
     * @param keys the view's key, by output column
     * @param options retention, sink, index and lane, where the draft has them
     * @param statement the whole {@code CREATE CONTINUOUS QUERY}, as {@code pravaha register} or {@code pravaha query} takes it
     * @param explanation what the model says the query does
     * @param turns model turns taken
     * @param dryRun what registering it would do
     * @param registered always false: registering is the caller's own call
     */
    public record DraftAnswer(
            String description,
            String name,
            String sql,
            List<String> keys,
            Map<String, String> options,
            String statement,
            String explanation,
            int turns,
            DraftDryRun dryRun,
            boolean registered) {}

    /**
     * A drafted alert, judged as {@code CREATE ALERT} would judge it and not created.
     *
     * @param description what was asked for
     * @param name the alert's name
     * @param view the view it would watch
     * @param condition its condition, or empty for every row
     * @param channels where it would notify
     * @param statement the {@code CREATE ALERT} statement
     * @param explanation what the model says it does
     * @param turns model turns taken
     * @param created always false: creating it is the caller's own call
     */
    public record AlertDraftAnswer(
            String description,
            String name,
            String view,
            String condition,
            List<String> channels,
            String statement,
            String explanation,
            int turns,
            boolean created) {}

    /**
     * A view's recent committed changes as this caller may see them, kept in memory from the first time
     * someone asked about the view.
     *
     * @param view the view
     * @param since when Pravaha started keeping its changes, or null when it has just started
     * @param commits the commits kept
     * @param entered rows that entered the answer over them
     * @param left rows that left it
     * @param keyColumns the view's key columns you may see
     * @param enteredKeys up to 20 keys that entered
     * @param leftKeys up to 20 keys that left
     */
    public record ChangeSummary(
            String view,
            @Nullable Instant since,
            int commits,
            int entered,
            int left,
            List<String> keyColumns,
            List<List<@Nullable Object>> enteredKeys,
            List<List<@Nullable Object>> leftKeys) {}

    /**
     * An explanation: of a query's SQL (grounded in its plan), of a refusal, or of why a view changed.
     *
     * @param kind {@code sql}, {@code refusal} or {@code change}
     * @param explanation the model's words
     * @param sql the SQL explained, or the SQL the refusal was checked against
     * @param plan the engine's plan the explanation is grounded in ({@code sql})
     * @param code the refusal's code ({@code refusal})
     * @param valid whether the engine accepts the SQL now ({@code refusal} with SQL), else null
     * @param engineCodes the codes the engine raised for that SQL
     * @param change the changes explained ({@code change})
     * @param values {@code labels-only} or {@code aggregates}
     */
    public record ExplainAnswer(
            String kind,
            String explanation,
            @Nullable String sql,
            @Nullable String plan,
            @Nullable String code,
            @Nullable Boolean valid,
            List<String> engineCodes,
            @Nullable ChangeSummary change,
            String values) {}

    /**
     * A batch of a view's changes in plain English.
     *
     * @param view the view
     * @param narrative the model's words; empty when there was nothing to say (no model call)
     * @param entered rows that entered
     * @param left rows that left
     * @param values {@code labels-only} or {@code aggregates}
     */
    public record NarrateAnswer(String view, String narrative, int entered, int left, String values) {}

    /**
     * Ask about this page.
     *
     * @param screen the page's screen key
     * @param answer the model's answer, grounded in the page's text and the help topics sent with it only
     * @param sources what the answer was drawn from
     */
    public record PageAnswer(String screen, String answer, List<String> sources) {}
}
