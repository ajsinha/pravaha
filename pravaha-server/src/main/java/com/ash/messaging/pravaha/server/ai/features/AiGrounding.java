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

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.Field;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.catalog.CatalogObject;
import com.ash.messaging.pravaha.catalog.CatalogPolicy;
import com.ash.messaging.pravaha.catalog.ObjectKind;
import com.ash.messaging.pravaha.security.Narrowing;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.ViewNames;
import com.ash.messaging.pravaha.serving.ServedView;

/**
 * What a model is told, and nothing else (Drishti's {@code BiAiPrompts} and {@code AskPrompt}): the schema of
 * what <em>this</em> person may read -- names, columns, types, keys, descriptions -- with every column masked
 * for them left out, so the model cannot be told it exists, and nothing of a relation the policy refuses
 * them. Never a row or a value: values reach a prompt only through the {@code aggregates} setting, and then
 * only the reader's own, narrowed results ({@link #table}).
 *
 * <p>Everything a person or a document wrote -- a question, a description, a page's text -- goes between
 * markers carrying a per-request random id ({@link #block}), cleaned of control characters and of
 * marker-like runs ({@link #clean}), and the fixed system text says it is data. A description that says
 * "ignore previous instructions" is therefore one more string; and if a model were swayed by it anyway, the
 * SQL it chose is run as the person, whose grants the engine applies whatever the model wrote.
 */
final class AiGrounding {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A description or a page's text, at most. */
    static final int CUT_TEXT = 600;

    /** A name. */
    static final int CUT_NAME = 200;

    /** The most relations a schema block lists; the rest are counted, not named. */
    static final int MAX_RELATIONS = 60;

    private AiGrounding() {}

    /**
     * One relation a person may read, as the model is shown it.
     *
     * @param kind {@code view}, {@code stream} or {@code lookup}
     * @param name the name this person writes it by
     * @param engineName the engine's name for it
     * @param description its catalogue description, or empty
     * @param columns its columns, those masked for this person left out
     * @param keys its key columns that are not masked
     * @param masked the columns left out
     * @param eventTime the stream's event-time column, or null
     */
    record Relation(
            String kind,
            String name,
            String engineName,
            String description,
            List<AiAnswers.ResultColumn> columns,
            List<String> keys,
            Set<String> masked,
            @Nullable String eventTime) {}

    /** A fresh id for this request's markers. */
    static String id() {
        byte[] b = new byte[12];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** The views {@code principal} may read, under the names they write, each as they are shown it. */
    static List<Relation> views(Principal principal, Map<String, ServedView> shown, SecurityPolicy policy) {
        List<Relation> out = new ArrayList<>();
        shown.forEach((name, view) -> {
            String engine = ViewNames.resolve(principal, name);
            relation(principal, policy, "view", name, engine, view.schema(), keysOf(view), false)
                    .ifPresent(out::add);
        });
        return out;
    }

    /**
     * One relation, or empty when the policy refuses it to {@code principal} -- or narrows it in a way that
     * cannot be bound to them, which is a refusal too.
     *
     * @param build whether the question is building on it (a draft) rather than reading it
     */
    static Optional<Relation> relation(
            Principal principal,
            SecurityPolicy policy,
            String kind,
            String name,
            String engine,
            StreamSchema schema,
            List<String> keys,
            boolean build) {
        boolean allowed = build
                ? policy.mayBuildOn(principal, engine).allowed()
                : policy.mayRead(principal, engine).allowed();
        if (!allowed) {
            return Optional.empty();
        }
        Narrowing narrowing;
        try {
            narrowing = policy.narrowing(principal, engine);
        } catch (PravahaException e) {
            return Optional.empty();
        }
        Set<String> masked = new LinkedHashSet<>(narrowing.masks().keySet());
        List<AiAnswers.ResultColumn> columns = new ArrayList<>();
        for (Field f : schema.fields()) {
            if (!masked.contains(f.name())) {
                columns.add(new AiAnswers.ResultColumn(f.name(), typeOf(f)));
            }
        }
        List<String> shownKeys = keys.stream().filter(k -> !masked.contains(k)).toList();
        String eventTime = null;
        if (schema.eventTimeOrdinal().isPresent()) {
            String time = schema.field(schema.eventTimeOrdinal().getAsInt()).name();
            eventTime = masked.contains(time) ? null : time;
        }
        return Optional.of(new Relation(
                kind, name, engine, description(policy, kind, engine), columns, shownKeys, masked, eventTime));
    }

    /** A view's key columns, by name. */
    static List<String> keysOf(ServedView view) {
        List<String> keys = new ArrayList<>();
        for (int k : view.keyOrdinals()) {
            keys.add(view.schema().field(k).name());
        }
        return keys;
    }

    /** The catalogue's description of a relation, when the catalogue governs this node. */
    private static String description(SecurityPolicy policy, String kind, String engine) {
        if (!(policy instanceof CatalogPolicy catalog)) {
            return "";
        }
        ObjectKind objectKind =
                switch (kind) {
                    case "stream" -> ObjectKind.STREAM;
                    case "lookup" -> ObjectKind.LOOKUP;
                    default -> ObjectKind.VIEW;
                };
        try {
            return catalog.service()
                    .catalog()
                    .byEngineName(objectKind, engine)
                    .map(CatalogObject::description)
                    .orElse("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** A column's SQL type, without its nullability. */
    static String typeOf(Field f) {
        return f.type().sqlName().replace(" NOT NULL", "");
    }

    /** The relations as the model reads them: one line per relation, then its key and columns. */
    static String schema(List<Relation> relations) {
        StringBuilder b = new StringBuilder();
        int shown = 0;
        for (Relation r : relations) {
            if (shown++ >= MAX_RELATIONS) {
                b.append("(").append(relations.size() - MAX_RELATIONS).append(" more not listed)\n");
                break;
            }
            b.append(r.kind().toUpperCase(Locale.ROOT)).append(' ').append(clean(r.name(), CUT_NAME));
            String d = clean(r.description(), CUT_TEXT);
            b.append(d.isEmpty() ? "" : " -- " + d).append('\n');
            if (!r.keys().isEmpty()) {
                b.append("  key: ").append(String.join(", ", r.keys())).append('\n');
            }
            if (r.eventTime() != null) {
                b.append("  event time: ").append(r.eventTime()).append('\n');
            }
            b.append("  columns: ");
            for (int i = 0; i < r.columns().size(); i++) {
                AiAnswers.ResultColumn c = r.columns().get(i);
                b.append(i == 0 ? "" : ", ")
                        .append(clean(c.name(), CUT_NAME))
                        .append(' ')
                        .append(c.type());
            }
            b.append('\n');
        }
        return b.toString();
    }

    /** Material for the model, between markers only this request's id closes. */
    static String block(String name, String id, String body) {
        String open = "<<<BEGIN UNTRUSTED " + name + " id=" + id + ">>>\n";
        String close = "\n<<<END UNTRUSTED " + name + " id=" + id + ">>>\n";
        return open + clean(body, Integer.MAX_VALUE) + close;
    }

    /** Control characters to spaces, marker-like runs defused, cut to {@code max} characters. */
    static String clean(@Nullable String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("[\\p{Cc}\\p{Cf}&&[^\\n]]", " ")
                .replace("<<<", "‹‹‹")
                .replace(">>>", "›››")
                .strip();
        return t.length() <= max ? t : t.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** A value as JSON carries it: numbers and text as they are, an instant and bytes as text. */
    static @Nullable Object cell(@Nullable Object v) {
        if (v == null
                || v instanceof String
                || v instanceof Boolean
                || v instanceof Long
                || v instanceof Integer
                || v instanceof Short
                || v instanceof Byte
                || v instanceof Double
                || v instanceof Float
                || v instanceof BigDecimal) {
            return v;
        }
        if (v instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (v instanceof Instant i) {
            return i.toString();
        }
        return String.valueOf(v);
    }

    private static final Pattern CONTROLS = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\n\\r\\t]]");

    /** Whether {@code s} holds a control or format character other than a line break or a tab. */
    static boolean hasControls(String s) {
        return CONTROLS.matcher(s).find();
    }

    /**
     * Whether {@code sql} names {@code name} as a word, whatever is before it -- {@code pravaha.payments}
     * names {@code payments} too (AIQA-5) -- and in any case.
     */
    static boolean mentionsWord(String sql, String name) {
        return Pattern.compile(
                        "(?<![\\p{L}\\p{N}_])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE)
                .matcher(sql)
                .find();
    }

    /** A row of cells, JSON-ready. */
    static List<@Nullable Object> row(Object[] values) {
        List<@Nullable Object> out = new ArrayList<>(values.length);
        for (Object v : values) {
            out.add(cell(v));
        }
        return out;
    }

    /**
     * The reader's own rows as text for a model ({@code aggregates} only): the columns not in {@code hidden},
     * at most {@code max} rows.
     */
    static String table(
            List<String> columns, Collection<? extends List<@Nullable Object>> rows, Set<String> hidden, int max) {
        List<Integer> keep = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (!hidden.contains(columns.get(i))) {
                b.append(keep.isEmpty() ? "" : " | ").append(columns.get(i));
                keep.add(i);
            }
        }
        b.append('\n');
        int n = 0;
        for (List<@Nullable Object> row : rows) {
            if (n++ >= max) {
                b.append("(").append(rows.size() - max).append(" more rows not shown)\n");
                break;
            }
            for (int k = 0; k < keep.size(); k++) {
                Object v = keep.get(k) < row.size() ? row.get(keep.get(k)) : null;
                b.append(k == 0 ? "" : " | ").append(clean(String.valueOf(v), CUT_NAME));
            }
            b.append('\n');
        }
        return b.toString();
    }

    /** The names of {@code relations} that {@code sql} mentions as a word: what it reads. */
    static List<String> mentioned(String sql, List<Relation> relations) {
        List<String> out = new ArrayList<>();
        for (Relation r : relations) {
            if (mentionsWord(sql, r.name())) {
                out.add(r.name());
            }
        }
        return out;
    }

    /** The text after a refusal's code, so it can be quoted under another sentence without the code twice. */
    static String bare(PravahaException e) {
        String m = e.getMessage();
        String code = e.errorCode().code();
        return m.startsWith(code) ? m.substring(code.length()).strip() : m;
    }
}
