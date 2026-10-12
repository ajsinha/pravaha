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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.Field;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.registry.ContinuousQueryStatements;
import com.ash.messaging.pravaha.registry.DraftFingerprint;
import com.ash.messaging.pravaha.registry.QueryListing;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.alert.AlertDefinition;
import com.ash.messaging.pravaha.registry.alert.AlertErrors;
import com.ash.messaging.pravaha.registry.alert.AlertService;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.ViewNames;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.sql.AlertStatement;
import com.ash.messaging.pravaha.sql.AlertStatements;
import com.ash.messaging.pravaha.sql.ContinuousStatement;
import com.ash.messaging.pravaha.sql.ContinuousStatements;

/**
 * Drafts from words: a continuous query (feature {@code draft}, ADR-058's drafting moved to the server) and
 * an alert (feature {@code alert}). The model writes the statement's parts; the engine judges the whole
 * statement as registering or creating it would -- every refusal {@code /validate} would give, the policy's
 * answers for what it reads and writes, the alert's view, columns, channels and masks -- with one repair turn.
 * A draft comes back with its dry run: the plan, what it reads, the lane it would take and the registered
 * queries whose computation it would share. Nothing is registered or created here.
 */
final class AiDrafts {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern ALERT_NAME =
            Pattern.compile("(?is)^(\\s*CREATE\\s+ALERT\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?)(\"(?:[^\"]|\"\")*\"|\\S+)");

    private final AiFeatures f;

    AiDrafts(AiFeatures f) {
        this.f = f;
    }

    /** What a draft's answer became once the engine accepted it. */
    private record Accepted(
            String name,
            String sql,
            List<String> keys,
            Map<String, String> options,
            String statement,
            String explanation,
            ContinuousStatement.Create create) {}

    AiAnswers.DraftAnswer draft(Principal p, @Nullable String description, @Nullable String wanted) {
        f.on();
        String text = f.text(description, "describe the continuous query you want");
        QueryRegistry registry = f.registry();
        SecurityPolicy policy = registry.policy();
        AccessDecision may = policy.mayRegisterQuery(p);
        if (!may.allowed()) {
            f.record(p, AiFeatures.DRAFT, "-", false, "refused " + SecurityErrors.FORBIDDEN.code(), "may not register");
            throw new PravahaException(
                    SecurityErrors.FORBIDDEN,
                    "you may not register a continuous query, so there is nothing to draft for you: " + may.reason());
        }
        List<AiGrounding.Relation> inputs = inputs(p, registry, policy);
        if (inputs.isEmpty()) {
            f.record(p, AiFeatures.DRAFT, "-", false, "refused " + SecurityErrors.FORBIDDEN.code(), "inputs=0");
            throw new PravahaException(
                    SecurityErrors.FORBIDDEN,
                    "there is no stream or view you may build on, so there is nothing to draft over");
        }
        String id = AiGrounding.id();
        StringBuilder schema = new StringBuilder(AiGrounding.schema(inputs));
        List<String> sinks = sinks(p, policy);
        if (!sinks.isEmpty()) {
            schema.append("SINKS you may write to: ")
                    .append(String.join(", ", sinks))
                    .append('\n');
        }
        String first = "TASK: draft\n" + AiGrounding.block("schema", id, schema.toString())
                + AiGrounding.block("description", id, text)
                + (wanted == null || wanted.isBlank() ? "" : AiGrounding.block("name", id, wanted.strip()));
        ContinuousQueryStatements statements = new ContinuousQueryStatements(registry, policy, f.audit);
        AiFeatures.Judged<Accepted> judged =
                f.judged(p, AiFeatures.DRAFT, "draft", AiPrompts.draft(id), first, text, "sql", "draft", id, n -> {
                    Accepted a = proposal(p, registry, n, wanted);
                    List<PravahaException> refusals = statements.validate(a.create(), p);
                    if (!refusals.isEmpty()) {
                        throw combined(refusals);
                    }
                    return a;
                });
        Accepted a = judged.value();
        AiAnswers.DraftDryRun dry = dryRun(p, registry, a, inputs);
        f.record(
                p,
                AiFeatures.DRAFT,
                a.name(),
                true,
                "ok",
                "descriptionChars=" + text.length() + " turns=" + judged.turns() + " sharesWith="
                        + dry.sharesWith().size());
        return new AiAnswers.DraftAnswer(
                text,
                a.name(),
                a.sql(),
                a.keys(),
                a.options(),
                a.statement(),
                a.explanation(),
                judged.turns(),
                dry,
                false);
    }

    /** The streams, lookup tables and views this person may build on, as the model is shown them. */
    private List<AiGrounding.Relation> inputs(Principal p, QueryRegistry registry, SecurityPolicy policy) {
        List<AiGrounding.Relation> out = new ArrayList<>();
        for (StreamSchema s : registry.streams()) {
            List<String> keys = s.primaryKey();
            AiGrounding.relation(p, policy, "stream", s.name(), s.name(), s, keys, true)
                    .ifPresent(out::add);
        }
        f.views()
                .shownTo(p)
                .forEach((name, view) -> AiGrounding.relation(
                                p,
                                policy,
                                "view",
                                name,
                                ViewNames.resolve(p, name),
                                view.schema(),
                                AiGrounding.keysOf(view),
                                true)
                        .ifPresent(out::add));
        return out;
    }

    private List<String> sinks(Principal p, SecurityPolicy policy) {
        List<String> out = new ArrayList<>();
        f.node.sinks()
                .ifPresent(s -> s.bindings().keySet().forEach(name -> {
                    if (policy.mayWriteTo(p, name).allowed()) {
                        out.add(name);
                    }
                }));
        return out;
    }

    /** The model's parts as one {@code CREATE CONTINUOUS QUERY}, under a name free in this person's tenant. */
    private static Accepted proposal(Principal p, QueryRegistry registry, JsonNode n, @Nullable String wanted) {
        String sql = n.path("sql").asText("").strip();
        if (sql.isEmpty()) {
            throw AiFeatures.unusable("the model could not draft this as a continuous query over what you may read"
                    + AiFeatures.because(n));
        }
        String select = AiFeatures.requireSelect(sql);
        List<String> keys = new ArrayList<>();
        n.path("keys").forEach(k -> {
            if (!k.asText("").isBlank()) {
                keys.add(k.asText().strip());
            }
        });
        if (keys.isEmpty()) {
            throw new AiFeatures.Repairable(
                    AiFeatureErrors.ANSWER_UNUSABLE,
                    "the draft has no key: name the output columns that make a row distinct");
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (String o : List.of("retention", "sink", "index", "lane")) {
            String v = n.path("options").path(o).asText("").strip();
            if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) {
                options.put(o, v);
            }
        }
        String name = free(
                p,
                registry,
                slug(wanted == null || wanted.isBlank() ? n.path("name").asText("") : wanted));
        String statement = statement(name, keys, options, select);
        Optional<ContinuousStatement> recognized = ContinuousStatements.recognize(statement);
        if (!(recognized.orElse(null) instanceof ContinuousStatement.Create create)) {
            throw new AiFeatures.Repairable(
                    AiFeatureErrors.ANSWER_UNUSABLE, "the draft is not one CREATE CONTINUOUS QUERY");
        }
        String explanation = AiGrounding.clean(n.path("explanation").asText(""), 1200);
        return new Accepted(name, select, List.copyOf(keys), options, statement, explanation, create);
    }

    /** All the refusals as one: the first one's code; a security refusal is the engine's at once, not repaired. */
    private static PravahaException combined(List<PravahaException> refusals) {
        for (PravahaException e : refusals) {
            if (e.errorCode().category() == ErrorCode.Category.SECURITY) {
                return e;
            }
        }
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < refusals.size() && i < 3; i++) {
            words.append(i == 0 ? "" : "; ")
                    .append(
                            i == 0
                                    ? AiGrounding.bare(refusals.get(i))
                                    : refusals.get(i).getMessage());
        }
        return new AiFeatures.Repairable(refusals.get(0).errorCode(), words.toString());
    }

    /** What registering it would do, from reads alone: the fingerprint, the plan, the lane, the sharing. */
    private AiAnswers.DraftDryRun dryRun(
            Principal p, QueryRegistry registry, Accepted a, List<AiGrounding.Relation> inputs) {
        StreamSchema out = registry.outputSchemaOf(a.sql(), p);
        List<AiAnswers.ResultColumn> fields = new ArrayList<>();
        for (Field field : out.fields()) {
            fields.add(new AiAnswers.ResultColumn(field.name(), AiGrounding.typeOf(field)));
        }
        ContinuousStatement.Create create = a.create();
        Retention retention = create.retain()
                .map(r -> r.age().map(Retention::ofAge).orElseGet(Retention::forever))
                .orElse(null);
        String fingerprint = DraftFingerprint.of(
                        registry,
                        a.name(),
                        a.sql(),
                        create.keyOrdinals(out),
                        p,
                        retention,
                        create.sink().orElse(null))
                .shortForm();
        List<String> shares = new ArrayList<>();
        new QueryListing(registry, registry.policy(), f.audit)
                .list(p, "ai.draft")
                .forEach(entry -> {
                    if (entry.query().fingerprint().shortForm().equals(fingerprint)) {
                        shares.add(entry.name());
                    }
                });
        String lane;
        if ("dedicated".equalsIgnoreCase(a.options().getOrDefault("lane", ""))) {
            lane = "dedicated: a lane of its own, as WITH (lane = 'dedicated') asks";
        } else if (!shares.isEmpty()) {
            lane = "the lane of " + shares.get(0) + "'s computation, which it would share: no new computation";
        } else if (registry.maxQueriesPerSharedLane() <= 0) {
            lane = "own: a lane of its own (pravaha.lane.multiplex.enabled is false)";
        } else if (registry.sharingFrom() > 0 && registry.queries().size() < registry.sharingFrom()) {
            lane = "own: a lane of its own until " + registry.sharingFrom() + " computations run (auto)";
        } else {
            lane = "shared: a shared lane, up to " + registry.maxQueriesPerSharedLane() + " computations each";
        }
        return new AiAnswers.DraftDryRun(
                fingerprint,
                registry.explainAs(a.sql(), p),
                fields,
                AiGrounding.mentioned(a.sql(), inputs),
                lane,
                shares);
    }

    // ------------------------------------------------------------------ alerts

    AiAnswers.AlertDraftAnswer alert(Principal p, @Nullable String description, @Nullable String wanted) {
        f.on();
        String text = f.text(description, "describe the alert you want");
        AlertService alerts = f.node.alerts()
                .orElseThrow(() -> new PravahaException(
                        AlertErrors.NOT_SERVED, "this node runs no alert service (pravaha.alerts.enabled)"));
        List<AiGrounding.Relation> views =
                AiGrounding.views(p, f.views().shownTo(p), f.registry().policy());
        if (views.isEmpty()) {
            f.record(p, AiFeatures.ALERT, "-", false, "refused " + SecurityErrors.FORBIDDEN.code(), "views=0");
            throw new PravahaException(
                    SecurityErrors.FORBIDDEN,
                    "there is no view you may read, so there is nothing an alert could watch");
        }
        String id = AiGrounding.id();
        String schema = AiGrounding.schema(views) + "CHANNELS: "
                + String.join(", ", alerts.channels().keySet()) + "\n";
        String first = "TASK: alert\n" + AiGrounding.block("schema", id, schema)
                + AiGrounding.block("description", id, text)
                + (wanted == null || wanted.isBlank() ? "" : AiGrounding.block("name", id, wanted.strip()));
        AiFeatures.Judged<Checked> judged = f.judged(
                p, AiFeatures.ALERT, "alert", AiPrompts.alert(id), first, text, "statement", "alert", id, n -> {
                    String statement = n.path("statement").asText("").strip();
                    if (statement.isEmpty()) {
                        throw AiFeatures.unusable("the model could not write this as an alert on a view you may read"
                                + AiFeatures.because(n));
                    }
                    String named = wanted == null || wanted.isBlank() ? statement : renamed(statement, slug(wanted));
                    return new Checked(
                            checked(p, alerts, named, 0),
                            AiGrounding.clean(n.path("explanation").asText(""), 1200));
                });
        AlertDefinition d = judged.value().definition();
        String condition = String.join(
                " AND ",
                d.where().stream().map(AlertStatement.Condition::toString).toList());
        f.record(
                p,
                AiFeatures.ALERT,
                d.name(),
                true,
                "ok",
                "descriptionChars=" + text.length() + " turns=" + judged.turns());
        return new AiAnswers.AlertDraftAnswer(
                text,
                d.name(),
                ViewNames.shown(p, d.view()),
                condition,
                d.channels(),
                statementOf(p, d),
                judged.value().explanation(),
                judged.turns(),
                false);
    }

    /** What {@code CREATE ALERT} would make of the model's statement, and what the model said it does. */
    private record Checked(AlertDefinition definition, String explanation) {}

    /** The definition {@code CREATE ALERT} would make, under a free name: a taken one gets {@code _2}, {@code _3}. */
    private static AlertDefinition checked(Principal p, AlertService alerts, String statement, int renames) {
        Optional<AlertStatement> parsed = AlertStatements.recognize(statement);
        if (!(parsed.orElse(null) instanceof AlertStatement.Create create)) {
            throw new AiFeatures.Repairable(
                    AiFeatureErrors.ANSWER_UNUSABLE, "the answer is not one CREATE ALERT statement");
        }
        try {
            return alerts.validate(p, create);
        } catch (PravahaException e) {
            if (e.errorCode().equals(AlertErrors.ALERT_EXISTS) && renames < 8) {
                return checked(p, alerts, renamed(statement, base(create.name()) + "_" + (renames + 2)), renames + 1);
            }
            throw e;
        }
    }

    /** The statement as it was judged: the model's, with the name it was judged under. */
    private static String statementOf(Principal p, AlertDefinition d) {
        StringBuilder b = new StringBuilder("CREATE ALERT ")
                .append(quote(d.name()))
                .append(" ON ")
                .append(quote(ViewNames.shown(p, d.view())));
        if (!d.where().isEmpty()) {
            b.append("\n  WHERE ")
                    .append(String.join(
                            " AND ",
                            d.where().stream()
                                    .map(AlertStatement.Condition::toString)
                                    .toList()));
        }
        b.append("\n  NOTIFY ")
                .append(String.join(
                        ", ", d.channels().stream().map(AiDrafts::quote).toList()));
        Map<String, String> with = new LinkedHashMap<>();
        if (!"warning".equalsIgnoreCase(d.options().severity())) {
            with.put("severity", "'" + d.options().severity().toLowerCase(Locale.ROOT) + "'");
        }
        if (!d.options().include().isEmpty()) {
            with.put(
                    "include",
                    "("
                            + String.join(
                                    ", ",
                                    d.options().include().stream()
                                            .map(AiDrafts::quote)
                                            .toList()) + ")");
        }
        duration(with, "fire_after", d.options().fireAfter());
        duration(with, "clear_after", d.options().clearAfter());
        duration(with, "dedupe", d.options().dedupe());
        duration(with, "resend_every", d.options().resendEvery());
        if (!with.isEmpty()) {
            List<String> parts = new ArrayList<>();
            with.forEach((k, v) -> parts.add(k + " = " + v));
            b.append("\n  WITH (").append(String.join(", ", parts)).append(')');
        }
        return b.toString();
    }

    private static void duration(Map<String, String> with, String option, java.time.Duration d) {
        if (!d.isZero()) {
            with.put(option, "'" + d + "'");
        }
    }

    private static String renamed(String statement, String name) {
        Matcher m = ALERT_NAME.matcher(statement);
        return m.find() ? m.replaceFirst(Matcher.quoteReplacement(m.group(1) + quote(name))) : statement;
    }

    // ------------------------------------------------------------------ names and statements

    /** A name a {@code FROM} clause holds: lower case, letters, digits and underscores, a letter first. */
    static String slug(@Nullable String s) {
        String t = (s == null ? "" : s.strip())
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("_+", "_");
        t = t.replaceAll("^_+|_+$", "");
        if (t.isEmpty() || !Character.isLetter(t.charAt(0))) {
            t = "draft" + (t.isEmpty() ? "" : "_" + t);
        }
        return t.length() > 60 ? t.substring(0, 60) : t;
    }

    private static String base(String name) {
        return name.replaceAll("_\\d+$", "");
    }

    /** {@code name}, or the first of {@code name_2}, {@code name_3}... no query of this tenant holds. */
    private static String free(Principal p, QueryRegistry registry, String name) {
        String candidate = name;
        for (int i = 2; i < 100 && taken(p, registry, candidate); i++) {
            candidate = name + "_" + i;
        }
        return candidate;
    }

    private static boolean taken(Principal p, QueryRegistry registry, String name) {
        try {
            return registry.find(QueryRegistry.engineName(p, name)).isPresent();
        } catch (PravahaException e) {
            return false; // a name registration refuses: the statement's validation says so
        }
    }

    /** One {@code CREATE CONTINUOUS QUERY}, as {@code docs/guides/CONTINUOUS_QUERIES.md} §10.1 writes it. */
    static String statement(String name, List<String> keys, Map<String, String> options, String select) {
        StringBuilder b = new StringBuilder("CREATE CONTINUOUS QUERY ")
                .append(quote(name))
                .append("\n  KEYED BY (")
                .append(String.join(", ", keys.stream().map(AiDrafts::quote).toList()))
                .append(')');
        if (options.containsKey("index")) {
            b.append("\n  INDEX (").append(quote(options.get("index"))).append(')');
        }
        if (options.containsKey("sink")) {
            b.append("\n  WRITING TO ").append(quote(options.get("sink")));
        }
        String retention = options.get("retention");
        if (retention != null) {
            b.append("forever".equalsIgnoreCase(retention) ? "\n  RETAIN FOREVER" : "\n  RETAIN FOR " + retention);
        }
        if (options.containsKey("lane")) {
            b.append("\n  WITH (lane = '")
                    .append(options.get("lane").replace("'", "''"))
                    .append("')");
        }
        return b.append("\nAS\n").append(select).toString();
    }

    static String quote(String name) {
        return NAME.matcher(name).matches() ? name : "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
