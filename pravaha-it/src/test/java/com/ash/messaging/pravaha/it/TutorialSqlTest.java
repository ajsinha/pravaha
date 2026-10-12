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
package com.ash.messaging.pravaha.it;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.plugin.filesystem.FilesystemSourcePlugin;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.PravahaSchema;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every SQL statement in the hands-on tutorials ({@code docs/tutorials/}) is planned against the real
 * engine, as {@link HelpExamplesSqlTest} plans the help's.
 *
 * <p>Each tutorial's streams are the ones its own node declares -- {@code docs/tutorials/data/tNN/application.yaml},
 * or, for a tutorial whose node is a scaffold ({@code pravaha init} in tutorial 1), the {@code pravaha.streams}
 * the tutorial shows in a {@code yaml} block -- and its lookups take their columns from the {@code CREATE TABLE}
 * of the tutorial's own {@code .sql} script. Then every {@code ```sql} block in the tutorial and every
 * {@code data/tNN/*.sql} file it registers from is planned through the planner and plan builder that
 * {@code POST /api/v1/queries/validate} uses. A block marked {@code <!-- sql: refused PRV-2020 -->} must be
 * refused with that code. The scenarios that capture the tutorials' output ({@code
 * sdk/python/tools/tutorial_captures.py}) run the same SQL on a real node; this test is the cheap gate that
 * runs on every build.
 */
class TutorialSqlTest {

    private static final Pattern BLOCK = Pattern.compile(
            "(?:<!--\\s*sql:\\s*([a-z-]+)(?:\\s+(PRV-\\d{4}))?\\s*-->\\s*\\n)?```sql[^\\n]*\\n(.*?)\\n```",
            Pattern.DOTALL);
    private static final Pattern YAML_BLOCK = Pattern.compile("```yaml[^\\n]*\\n(.*?)\\n```", Pattern.DOTALL);
    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE TABLE\\s+(\\w+)\\s*\\((.*?)\\);", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(".git"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("cannot locate the repository root");
        }
        return p;
    }

    private static Path tutorials() {
        return repoRoot().resolve("docs/tutorials");
    }

    @Test
    void everySqlStatementInTheTutorialsPlansOrIsRefusedAsItSays() throws IOException {
        List<Path> docs;
        try (Stream<Path> listed = Files.list(tutorials())) {
            docs = listed.filter(p -> p.getFileName().toString().matches("\\d\\d-.*\\.md"))
                    .sorted()
                    .toList();
        }
        assertThat(docs).as("the hands-on tutorials").hasSizeGreaterThanOrEqualTo(6);
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (Path doc : docs) {
            String number = doc.getFileName().toString().substring(0, 2);
            Path data = tutorials().resolve("data/t" + number);
            String text = Files.readString(doc, StandardCharsets.UTF_8);
            PravahaSchema schema = schemaOf(doc, data, text);
            List<String[]> statements = new ArrayList<>();
            Matcher block = BLOCK.matcher(text);
            while (block.find()) {
                String kind = block.group(1) == null ? "continuous" : block.group(1);
                statements.add(new String[] {
                    doc.getFileName() + ":" + lineOf(text, block.start(3)), kind, block.group(2), strip(block.group(3))
                });
            }
            if (Files.isDirectory(data)) {
                try (Stream<Path> files = Files.list(data)) {
                    for (Path sql : files.filter(p -> p.toString().endsWith(".sql"))
                            .sorted()
                            .toList()) {
                        String body = Files.readString(sql, StandardCharsets.UTF_8);
                        if (CREATE_TABLE.matcher(body).find()) {
                            continue; // a database script, not a continuous query
                        }
                        statements.add(new String[] {
                            "data/t" + number + "/" + sql.getFileName(), "continuous", null, strip(body)
                        });
                    }
                }
            }
            for (String[] s : statements) {
                checked++;
                RuntimeException thrown = null;
                try {
                    PhysicalOperator plan = new PhysicalPlanBuilder().build(new SqlPlanner(schema).plan(s[3]));
                    assertThat(plan.outputSchema().fieldCount()).isPositive();
                } catch (RuntimeException e) {
                    thrown = e;
                }
                if (s[1].equals("continuous") && thrown != null) {
                    failures.add(s[0] + " does not plan: " + firstLine(thrown));
                } else if (s[1].equals("refused") && thrown == null) {
                    failures.add(s[0] + " is shown as refused and the engine accepts it");
                } else if (s[1].equals("refused")
                        && s[2] != null
                        && !codeOf(thrown).equals(s[2])) {
                    failures.add(s[0] + " is shown as refused with " + s[2] + " and is refused with " + codeOf(thrown));
                } else if (!s[1].equals("continuous") && !s[1].equals("refused")) {
                    failures.add(s[0] + " has an unknown marker 'sql: " + s[1] + "'");
                }
            }
        }
        assertThat(failures)
                .as("SQL in the tutorials must be SQL the engine plans, or refuses exactly as the tutorial says")
                .isEmpty();
        assertThat(checked).as("the tutorials hold SQL to check").isGreaterThanOrEqualTo(10);
        System.out.printf(
                "TutorialSqlTest: %d statements in %d tutorials planned or refused as marked%n", checked, docs.size());
    }

    // ------------------------------------------------------------------------------ the schema

    /** The streams the tutorial's node declares, and its lookups, as the planner sees them. */
    @SuppressWarnings("unchecked")
    private static PravahaSchema schemaOf(Path doc, Path data, String text) throws IOException {
        Map<String, Object> streams = new LinkedHashMap<>();
        Map<String, Object> lookups = new LinkedHashMap<>();
        List<String> sources = new ArrayList<>();
        Path config = data.resolve("application.yaml");
        if (Files.exists(config)) {
            sources.add(Files.readString(config, StandardCharsets.UTF_8));
        } else {
            Matcher yaml = YAML_BLOCK.matcher(text);
            while (yaml.find()) {
                sources.add(yaml.group(1));
            }
        }
        for (String source : sources) {
            Object loaded = new Yaml().load(source);
            if (loaded instanceof Map<?, ?> root && root.get("pravaha") instanceof Map<?, ?> pravaha) {
                if (pravaha.get("streams") instanceof Map<?, ?> s) {
                    streams.putAll((Map<String, Object>) s);
                }
                if (pravaha.get("lookups") instanceof Map<?, ?> l) {
                    lookups.putAll((Map<String, Object>) l);
                }
            }
        }
        assertThat(streams)
                .as("%s: the streams its node declares", doc.getFileName())
                .isNotEmpty();
        PravahaSchema schema = new PravahaSchema();
        for (Map.Entry<String, Object> stream : streams.entrySet()) {
            Map<String, Object> spec = (Map<String, Object>) stream.getValue();
            StreamSchema parsed =
                    FilesystemSourcePlugin.parseSchema(stream.getKey(), String.valueOf(spec.get("schema")));
            StreamSchema.Builder builder = StreamSchema.builder(stream.getKey());
            parsed.fields().forEach(field -> builder.field(field.name(), field.type()));
            if (spec.get("event-time") != null) {
                builder.eventTime(String.valueOf(spec.get("event-time")));
            }
            if (spec.get("out-of-orderness") != null) {
                builder.outOfOrderness(duration(String.valueOf(spec.get("out-of-orderness"))));
            }
            schema.register(builder.build());
        }
        for (Map.Entry<String, Object> lookup : lookups.entrySet()) {
            Map<String, Object> options =
                    (Map<String, Object>) ((Map<String, Object>) lookup.getValue()).get("options");
            String table = String.valueOf(options.get("table"));
            schema.registerLookup(tableOf(data, table));
        }
        return schema;
    }

    /** A lookup table's columns, from the CREATE TABLE in the tutorial's own database script. */
    private static StreamSchema tableOf(Path data, String table) throws IOException {
        try (Stream<Path> files = Files.list(data)) {
            for (Path sql : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                Matcher create = CREATE_TABLE.matcher(Files.readString(sql, StandardCharsets.UTF_8));
                while (create.find()) {
                    if (!create.group(1).equalsIgnoreCase(table)) {
                        continue;
                    }
                    StreamSchema.Builder builder = StreamSchema.builder(table);
                    for (String column : create.group(2).split(",\\s*\\n")) {
                        String[] parts = column.strip().split("\\s+");
                        String type = parts[1].toUpperCase(java.util.Locale.ROOT);
                        builder.field(
                                parts[0],
                                type.startsWith("VARCHAR") || type.startsWith("CHAR")
                                        ? Types.string()
                                        : type.startsWith("INT") || type.startsWith("BIGINT")
                                                ? Types.int64()
                                                : Types.string());
                    }
                    return builder.build();
                }
            }
        }
        throw new AssertionError("no CREATE TABLE " + table + " in " + data);
    }

    private static Duration duration(String text) {
        String t = text.strip();
        if (t.matches("\\d+")) {
            return Duration.ofSeconds(Long.parseLong(t));
        }
        if (t.matches("\\d+s")) {
            return Duration.ofSeconds(Long.parseLong(t.substring(0, t.length() - 1)));
        }
        if (t.matches("\\d+m")) {
            return Duration.ofMinutes(Long.parseLong(t.substring(0, t.length() - 1)));
        }
        return Duration.parse(t);
    }

    // ------------------------------------------------------------------------------ helpers

    /** The statement without its comment lines and trailing semicolon. */
    private static String strip(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.strip().startsWith("--")) {
                out.append(line).append('\n');
            }
        }
        String body = out.toString().strip();
        return body.endsWith(";") ? body.substring(0, body.length() - 1) : body;
    }

    private static int lineOf(String text, int offset) {
        return 1
                + (int) text.substring(0, offset).chars().filter(c -> c == '\n').count();
    }

    private static String codeOf(RuntimeException e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof PravahaException pe) {
                return pe.errorCode().code();
            }
            t = t.getCause();
        }
        Matcher code = Pattern.compile("PRV-\\d{4}").matcher(String.valueOf(e.getMessage()));
        return code.find() ? code.group() : "(no code: " + e.getClass().getSimpleName() + ")";
    }

    private static String firstLine(RuntimeException e) {
        String message = String.valueOf(e.getMessage());
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
