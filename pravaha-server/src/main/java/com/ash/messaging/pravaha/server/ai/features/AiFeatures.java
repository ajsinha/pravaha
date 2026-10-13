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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.ai.AiErrors;
import com.ash.messaging.pravaha.ai.CallContext;
import com.ash.messaging.pravaha.ai.ChatMessage;
import com.ash.messaging.pravaha.ai.ChatRequest;
import com.ash.messaging.pravaha.ai.LlmException;
import com.ash.messaging.pravaha.ai.LlmGateway;
import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.Field;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.ai.AiProperties;
import com.ash.messaging.pravaha.serving.ServingErrors;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;

/**
 * Pravaha's plain-English features, on Drishti's design (its {@code BiAiService}: docs/guides/BI_AI.md, here
 * docs/guides/AI.md). <b>The model writes names and SQL, never answers.</b> Every feature calls the model
 * through the one {@link LlmGateway} under its own feature name -- {@code ask}, {@code draft}, {@code alert},
 * {@code explain}, {@code narrative}, {@code page} -- so the AI policy decides per feature and role, masks,
 * limits and audits; each adds an audit event of its own ({@code ai.<feature>}: the target, the outcome and
 * sizes, never a prompt or an answer). What the model writes is judged by the engine as the person asking --
 * a query is planned and run through the same {@link ViewQuery} Flight and the PostgreSQL gateway use (their
 * grants, row filters, masks), a draft is judged as registering it would be, an alert as creating it would
 * be -- and a refusal gets one repair turn with the engine's words, then {@code PRV-1088}. Nothing is
 * registered, created or saved: that is always the caller's own, explicit, next call.
 *
 * <p>Two gates, as in Drishti: the engine's capability first (a person who may read no view, or may not
 * register, never reaches the model), then the AI policy for the feature.
 */
public final class AiFeatures implements AutoCloseable {

    public static final String ASK = "ask";
    public static final String DRAFT = "draft";
    public static final String ALERT = "alert";
    public static final String EXPLAIN = "explain";
    public static final String NARRATIVE = "narrative";
    public static final String PAGE = "page";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern FENCE = Pattern.compile("(?s)```[a-zA-Z]*\\s*\\n?(.*?)```");
    private static final Pattern LEADING_COMMENTS =
            Pattern.compile("^(\\s*(--[^\\n]*\\n|/\\*.*?\\*/))*\\s*", Pattern.DOTALL);

    /** The earlier turns of a conversation a follow-up is sent with, at most. */
    static final int CONVERSATION = 3;

    final LlmGateway gateway;
    final AiProperties props;
    final PravahaNode node;
    final AuditSink audit;
    final Duration readDeadline;
    final ChangeWindow changes;
    private final AiDrafts drafts;
    private final AiExplains explains;

    /**
     * @param readDeadline how long one read may run ({@code pravaha.serving.read.deadline}); zero for none
     */
    public AiFeatures(
            LlmGateway gateway, AiProperties props, PravahaNode node, AuditSink audit, Duration readDeadline) {
        this.gateway = gateway;
        this.props = props;
        this.node = node;
        this.audit = audit;
        this.readDeadline = readDeadline;
        this.changes = new ChangeWindow(
                props.getFeatures().getChangeViews(), props.getFeatures().getChangeCommits(), Clock.systemUTC());
        this.drafts = new AiDrafts(this);
        this.explains = new AiExplains(this);
    }

    // ------------------------------------------------------------------ the features

    /**
     * Ask your data: the question becomes one snapshot {@code SELECT} over a view this person may read, run as
     * them; the answer is the rows the engine gave them, the chart Pravaha chose from the result's shape and
     * the SQL the model chose.
     *
     * @param conversation earlier turns, oldest first, for a follow-up; the last {@value #CONVERSATION} are sent
     */
    public AiAnswers.AskAnswer ask(
            Principal p, @Nullable String question, @Nullable List<AiAnswers.AskTurn> conversation) {
        on();
        String q = text(question, "ask a question about your data");
        ViewCatalog served = views();
        SecurityPolicy policy = registry().policy();
        List<AiGrounding.Relation> readable = AiGrounding.views(p, served.shownTo(p), policy);
        if (readable.isEmpty()) {
            record(p, ASK, "-", false, "refused " + SecurityErrors.FORBIDDEN.code(), "views=0");
            throw new PravahaException(
                    SecurityErrors.FORBIDDEN,
                    "there is no view you may read, so there is nothing to ask the data about");
        }
        String id = AiGrounding.id();
        StringBuilder user =
                new StringBuilder("TASK: ask\n").append(AiGrounding.block("schema", id, AiGrounding.schema(readable)));
        String history = history(conversation);
        if (!history.isEmpty()) {
            user.append(AiGrounding.block("conversation", id, history));
        }
        user.append(AiGrounding.block("question", id, q));
        ViewQuery reader = new ViewQuery(served, registry().policy(), audit, node.readAdmission(), readDeadline);
        Judged<Ran> judged = judged(p, ASK, "ask", AiPrompts.ask(id), user.toString(), q, "sql", "SQL", id, n -> {
            String sql = n.path("sql").asText("").strip();
            if (sql.isEmpty()) {
                throw unusable(
                        "the model could not turn the question into a query over the views you may read" + because(n));
            }
            String select = requireSelect(sql);
            return new Ran(
                    select, AiGrounding.clean(n.path("explanation").asText(""), 1200), reader.execute(select, p));
        });
        Ran ran = judged.value();
        List<String> names = new ArrayList<>();
        List<AiAnswers.ResultColumn> columns = new ArrayList<>();
        for (Field f : ran.result().schema().fields()) {
            names.add(f.name());
            columns.add(new AiAnswers.ResultColumn(f.name(), AiGrounding.typeOf(f)));
        }
        int cap = props.getFeatures().getAskRows();
        List<List<@Nullable Object>> rows = new ArrayList<>();
        for (Object[] row : ran.result().rows()) {
            if (rows.size() >= cap) {
                break;
            }
            rows.add(AiGrounding.row(row));
        }
        int count = ran.result().size();
        AiAnswers.ChartChoice chart = chart(ran.result().schema().fields(), count);
        List<String> reads = AiGrounding.mentioned(ran.sql(), readable);
        String summary = null;
        // AIQA-3: every column masked for this reader in any relation they may read is hidden, and a query
        // that names one anywhere -- an alias, an expression, a sub-query -- is not summarised at all, since
        // its output columns may carry the masked values under other names.
        Set<String> hidden = new LinkedHashSet<>();
        readable.forEach(r -> hidden.addAll(r.masked()));
        boolean derivesFromMasked = hidden.stream().anyMatch(c -> AiGrounding.mentionsWord(ran.sql(), c));
        if (props.aggregates() && !rows.isEmpty() && !derivesFromMasked) {
            String sid = AiGrounding.id();
            summary = words(
                            p,
                            ASK,
                            AiPrompts.summarise(sid),
                            List.of(ChatMessage.user("TASK: summarise\n"
                                    + AiGrounding.block("question", sid, q)
                                    + AiGrounding.block("result", sid, AiGrounding.table(names, rows, hidden, cap)))))
                    .strip();
        }
        record(
                p,
                ASK,
                String.join(",", reads),
                true,
                "ok " + count + " rows",
                "questionChars=" + q.length() + " turns=" + judged.turns() + " rows=" + count + " chart="
                        + chart.kind());
        return new AiAnswers.AskAnswer(
                q,
                ran.sql(),
                ran.explanation(),
                columns,
                rows,
                count,
                count > rows.size(),
                chart,
                props.getValues(),
                summary,
                judged.turns());
    }

    /** {@link AiDrafts#draft}. */
    public AiAnswers.DraftAnswer draft(Principal p, @Nullable String description, @Nullable String name) {
        return drafts.draft(p, description, name);
    }

    /** {@link AiDrafts#alert}. */
    public AiAnswers.AlertDraftAnswer alertDraft(Principal p, @Nullable String description, @Nullable String name) {
        return drafts.alert(p, description, name);
    }

    /** {@link AiExplains#explain}. */
    public AiAnswers.ExplainAnswer explain(
            Principal p,
            @Nullable String kind,
            @Nullable String sql,
            @Nullable String query,
            @Nullable String code,
            @Nullable String message,
            @Nullable String view) {
        return explains.explain(p, kind, sql, query, code, message, view);
    }

    /** {@link AiExplains#narrate}. */
    public AiAnswers.NarrateAnswer narrate(
            Principal p, @Nullable String view, @Nullable List<AiAnswers.ChangeRow> changes) {
        return explains.narrate(p, view, changes);
    }

    /** {@link AiExplains#page}. */
    public AiAnswers.PageAnswer page(
            Principal p,
            @Nullable String screen,
            @Nullable String about,
            @Nullable List<AiAnswers.HelpTopic> topics,
            @Nullable String question) {
        return explains.page(p, screen, about, topics, question);
    }

    /**
     * Whether the engine's own gate of {@code feature} opens to {@code p} (AIQA-6), so {@code /ai/features}
     * never offers what the engine refuses before the model is asked: a draft to a person who may not
     * register, an alert on a node that runs no alert service. Reads only; no model is called.
     */
    public boolean capable(Principal p, String feature) {
        return switch (feature) {
            case DRAFT ->
                node.registry()
                        .map(r -> r.policy().mayRegisterQuery(p).allowed())
                        .orElse(false);
            case ALERT -> node.alerts().isPresent();
            default -> true;
        };
    }

    @Override
    public void close() {
        changes.close();
    }

    // ------------------------------------------------------------------ the chart

    /**
     * The chart that suits the result, from its shape alone (Drishti's {@code suggest}): one value is a stat;
     * a time column with a measure is a line (split by a text column when there is one); one category and one
     * measure is a bar while there are at most 30 rows; everything else a table.
     */
    static AiAnswers.ChartChoice chart(List<Field> fields, int rows) {
        if (rows == 1 && fields.size() == 1) {
            return new AiAnswers.ChartChoice("stat", null, fields.get(0).name(), null);
        }
        String time = null;
        String measure = null;
        String category = null;
        for (Field f : fields) {
            TypeName t = f.type().typeName();
            if (time == null && (t == TypeName.TIMESTAMP_LTZ || t == TypeName.DATE)) {
                time = f.name();
            } else if (measure == null && numeric(t)) {
                measure = f.name();
            } else if (category == null && t == TypeName.STRING) {
                category = f.name();
            }
        }
        if (time != null && measure != null && fields.size() <= 3) {
            return new AiAnswers.ChartChoice("line", time, measure, category);
        }
        if (time == null && category != null && measure != null && fields.size() == 2 && rows <= 30) {
            return new AiAnswers.ChartChoice("bar", category, measure, null);
        }
        return new AiAnswers.ChartChoice("table", null, null, null);
    }

    private static boolean numeric(TypeName t) {
        return switch (t) {
            case INT8, INT16, INT32, INT64, FLOAT32, FLOAT64, DECIMAL -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ the plumbing every feature shares

    /** The query the model chose, and what the engine answered it. */
    private record Ran(String sql, String explanation, ViewQuery.Result result) {}

    /**
     * What the engine accepted, and how many model turns it took.
     *
     * @param value what the judge returned
     * @param turns 1, or 2 after a repair
     */
    record Judged<T>(T value, int turns) {}

    /** The engine's judgement of one answer: what it made of it, or the refusal it would give. */
    @FunctionalInterface
    interface Judge<T> {
        T judge(JsonNode answer);
    }

    /** A refusal of a model's answer that the model may be told about and asked to mend. */
    static final class Repairable extends PravahaException {
        private static final long serialVersionUID = 1L;

        Repairable(ErrorCode code, String message) {
            super(code, message);
        }
    }

    /**
     * Asks the model, judges its answer, and on a refusal it may mend asks once more with the engine's words
     * (Drishti's repair-once-then-refuse, {@code DRS-4020}): the second refusal is {@code PRV-1088}. A refusal
     * the model cannot mend -- the person may not read or build on what it named ({@code PRV-7xxx}), the
     * engine is busy -- is the engine's, with its own code, at once. Nothing is retried for a reply that is not
     * an answer at all, or that says it cannot answer: that is {@code PRV-1088} too, never a guess.
     *
     * @param shown the field of the answer quoted back in a refusal, so the person sees what the model chose
     * @param what what the answer is, in a sentence: "SQL", "draft", "alert"
     */
    <T> Judged<T> judged(
            Principal p,
            String feature,
            String task,
            String system,
            String firstTurn,
            String question,
            String shown,
            String what,
            String id,
            Judge<T> judge) {
        List<ChatMessage> turns = new ArrayList<>();
        turns.add(ChatMessage.user(firstTurn));
        PravahaException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String reply = call(p, feature, system, turns);
            JsonNode answer;
            try {
                answer = object(reply, "the JSON object it was asked for");
                return new Judged<>(judge.judge(answer), attempt);
            } catch (PravahaException e) {
                if (!repairable(e)) {
                    record(p, feature, "-", false, "refused " + e.errorCode().code(), "turns=" + attempt);
                    if (e.errorCode().equals(AiFeatureErrors.ANSWER_UNUSABLE)) {
                        throw e;
                    }
                    throw new PravahaException(
                            e.errorCode(),
                            "the " + what + " the model chose was refused: " + AiGrounding.bare(e)
                                    + chosen(reply, shown));
                }
                last = e;
                turns.add(ChatMessage.assistant(reply));
                turns.add(ChatMessage.user(
                        AiPrompts.repair(id, task, e.errorCode().code() + "  " + AiGrounding.bare(e), question)));
            }
        }
        PravahaException refused = java.util.Objects.requireNonNull(last);
        record(p, feature, "-", false, "refused twice " + refused.errorCode().code(), "turns=2");
        throw unusable("the engine refused the " + what + " the model chose, and again after one repair, so it is not "
                + "shown. The last refusal: " + refused.errorCode().code() + "  " + AiGrounding.bare(refused));
    }

    private static boolean repairable(PravahaException e) {
        if (e instanceof LlmException) {
            return false;
        }
        if (e instanceof Repairable) {
            return true;
        }
        ErrorCode code = e.errorCode();
        return code.category() == ErrorCode.Category.PLANNING
                || code.category() == ErrorCode.Category.REGISTRY
                || code.equals(ServingErrors.NO_SUCH_VIEW);
    }

    /** {@code " -- the SQL it chose: ..."}, for a refusal, when the answer had one. */
    private static String chosen(String reply, String field) {
        try {
            String v = object(reply, "").path(field).asText("").strip();
            return v.isEmpty() ? "" : " (the " + field + " it chose: " + AiGrounding.clean(v, 400) + ")";
        } catch (PravahaException e) {
            return "";
        }
    }

    /** One model call as {@code p}, under {@code feature} (the policy's name for it). */
    String call(Principal p, String feature, String system, List<ChatMessage> turns) {
        AiProperties.Features f = props.getFeatures();
        ChatRequest request = new ChatRequest(
                f.getModel().isEmpty() ? null : f.getModel(),
                system,
                turns,
                List.of(),
                0.0,
                f.getMaxTokens(),
                false,
                Duration.ofSeconds(f.getTimeoutSeconds()));
        try {
            return gateway.chat(new CallContext(p.id(), Set.copyOf(p.roles()), feature), request)
                    .text();
        } catch (LlmException e) {
            record(p, feature, "-", false, e.kind().id(), e.errorCode().code());
            throw e;
        }
    }

    /** The most characters of a model's words an answer carries. */
    static final int WORDS = 4000;

    /**
     * One model call whose answer is words for a person (AIQA-4): control and format characters -- a
     * terminal escape sequence, a bidirectional override -- become spaces and the text is bounded, so model
     * text printed by the CLI or shown anywhere else cannot drive the terminal or disguise itself.
     */
    String words(Principal p, String feature, String system, List<ChatMessage> turns) {
        return AiGrounding.clean(call(p, feature, system, turns), WORDS);
    }

    /** The feature's own audit event: who, the target, the outcome and sizes; never a prompt or an answer. */
    void record(Principal p, String feature, String target, boolean allowed, String outcome, String detail) {
        audit.record(new AuditEvent(
                Instant.now(),
                p,
                "ai." + feature,
                target.isEmpty() ? "-" : target,
                allowed,
                outcome,
                Optional.of(detail)));
    }

    /** {@code PRV-1080} while the AI layer is off: no feature reads anything or calls anything. */
    void on() {
        gateway.requireEnabled();
    }

    /** The person's words, stripped and bounded by {@code pravaha.ai.features.max-question-chars}. */
    String text(@Nullable String s, String hint) {
        String t = s == null ? "" : s.strip();
        if (t.isEmpty()) {
            throw new IllegalArgumentException(hint);
        }
        int max = props.getFeatures().getMaxQuestionChars();
        if (t.length() > max) {
            throw new IllegalArgumentException(
                    "at most " + max + " characters (pravaha.ai.features.max-question-chars); this is " + t.length());
        }
        return t;
    }

    QueryRegistry registry() {
        return node.registry()
                .orElseThrow(() -> new PravahaException(
                        AiErrors.CONFIG, "the engine has not started: there is nothing to ground in"));
    }

    ViewCatalog views() {
        return node.views()
                .orElseThrow(() -> new PravahaException(
                        AiErrors.CONFIG, "the engine has not started: there is nothing to ground in"));
    }

    static PravahaException unusable(String message) {
        return new PravahaException(AiFeatureErrors.ANSWER_UNUSABLE, message);
    }

    /** {@code ": <the model's explanation>"} when it gave one. */
    static String because(JsonNode n) {
        String why = AiGrounding.clean(n.path("explanation").asText(""), AiGrounding.CUT_TEXT);
        return why.isEmpty() ? "" : ": " + why;
    }

    /** The reply's one JSON object, from inside a code fence or prose if the model added them. */
    static JsonNode object(String reply, String what) {
        String s = unfence(reply);
        int a = s.indexOf('{');
        int z = s.lastIndexOf('}');
        if (a < 0 || z < a) {
            throw unusable("the model did not answer with " + what);
        }
        try {
            JsonNode n = JSON.readTree(s.substring(a, z + 1));
            if (n == null || !n.isObject()) {
                throw unusable("the model did not answer with " + what);
            }
            return n;
        } catch (JsonProcessingException e) {
            throw unusable("the model's answer was not valid JSON");
        }
    }

    static String unfence(@Nullable String reply) {
        String s = reply == null ? "" : reply.strip();
        Matcher m = FENCE.matcher(s);
        return (m.find() ? m.group(1) : s).strip();
    }

    /**
     * The SQL as a plain {@code SELECT} (or {@code WITH}), its trailing semicolon taken off; anything else --
     * a statement that registers, grants or drops -- is refused before the engine sees it.
     */
    static String requireSelect(String sql) {
        String s = sql.strip();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        if (AiGrounding.hasControls(s)) {
            throw new Repairable(
                    AiFeatureErrors.ANSWER_UNUSABLE,
                    "the SQL carries control or format characters, which a query never needs; write it without them");
        }
        String head = LEADING_COMMENTS.matcher(s).replaceFirst("").toUpperCase(Locale.ROOT);
        if (!(head.startsWith("SELECT") || head.startsWith("WITH")) || s.contains(";")) {
            throw new Repairable(
                    AiFeatureErrors.ANSWER_UNUSABLE,
                    "only one read-only SELECT is answered here; the model wrote something else");
        }
        return s;
    }

    /** The last turns of a conversation as text: each question, then the SQL the model chose for it. */
    private static String history(@Nullable List<AiAnswers.AskTurn> conversation) {
        if (conversation == null || conversation.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        List<AiAnswers.AskTurn> last =
                conversation.subList(Math.max(0, conversation.size() - CONVERSATION), conversation.size());
        for (AiAnswers.AskTurn t : last) {
            if (t == null) {
                continue;
            }
            b.append("EARLIER QUESTION: ")
                    .append(AiGrounding.clean(t.question(), AiGrounding.CUT_TEXT))
                    .append('\n');
            b.append("THE SQL CHOSEN FOR IT: ")
                    .append(AiGrounding.clean(t.sql(), 2000))
                    .append('\n');
        }
        return b.toString();
    }
}
