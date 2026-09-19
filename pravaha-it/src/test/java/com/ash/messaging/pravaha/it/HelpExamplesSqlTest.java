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
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.plugin.filesystem.FilesystemSourcePlugin;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.ContinuousStatement;
import com.ash.messaging.pravaha.sql.ContinuousStatements;
import com.ash.messaging.pravaha.sql.PravahaSchema;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.ParameterMetadata;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every SQL example in the console's help pages is planned against the real engine.
 *
 * <p>The same mechanism as {@link CaseStudySqlTest}, extended to the help: a declared set of
 * streams ({@code console/content/examples/streams.properties}, one schema grammar with the node's
 * own), the views a deployment would have ({@code views.properties}), and every {@code ```sql} block
 * under {@code console/content/topics/} planned through the planner and plan builder that
 * {@code POST /api/v1/queries/validate} uses. A help page is the first thing somebody copies from, so
 * an example that reads plausibly and is refused is the worst thing it can hold.
 *
 * <p>A block is a continuous query unless the HTML comment on the line before it says otherwise:
 *
 * <ul>
 *   <li>{@code <!-- sql: read -->} -- a read of a maintained view, prepared through {@link ViewQuery}
 *       against the declared views and any view the same page creates;
 *   <li>{@code <!-- sql: refused PRV-2050 -->} -- a continuous query the engine must refuse, with that
 *       code (the code may be left out when the refusal carries none);
 *   <li>{@code <!-- sql: read-refused PRV-2020 -->} -- the same, for a read;
 *   <li>{@code <!-- sql: parameterised -->} -- a registration carrying bound values, planned and its
 *       placeholders counted.
 * </ul>
 *
 * <p>{@code CREATE}, {@code DROP}, {@code PAUSE}, {@code RESUME CONTINUOUS QUERY} and {@code SHOW
 * CONTINUOUS QUERIES} are recognised as the engine recognises them; a {@code CREATE}'s {@code SELECT}
 * is planned and its {@code KEYED BY} resolved against the plan's output, and the view it names
 * becomes readable by the page's read examples. A block may hold several statements, each ended by a
 * semicolon at the end of a line.
 */
class HelpExamplesSqlTest {

    private static final Pattern BLOCK = Pattern.compile(
            "(?:<!--\\s*sql:\\s*([a-z-]+)(?:\\s+(PRV-\\d{4}))?\\s*-->\\s*\\n)?```sql[^\\n]*\\n(.*?)\\n```",
            Pattern.DOTALL);

    private record Example(String page, int line, String kind, String code, String sql) {

        String where() {
            return page + ":" + line;
        }
    }

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

    private static Path topics() {
        return repoRoot().resolve("console/content/topics");
    }

    private static Path fixture(String name) {
        return repoRoot().resolve("console/content/examples").resolve(name);
    }

    // ------------------------------------------------------------------------------------ tests

    @Test
    void theHelpDeclaresItsStreamsAndViews() {
        assertThat(fixture("streams.properties")).exists();
        assertThat(fixture("views.properties")).exists();
        assertThat(topics()).isDirectory();
    }

    @Test
    void everySqlExampleInTheHelpPlansOrIsRefusedAsItSays() throws IOException {
        PravahaSchema schema = schema();
        Map<String, ServedView> declared = declaredViews(schema);

        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (Path page : pages()) {
            List<Example> examples = examplesIn(page);
            // The views this page's own CREATE statements maintain, readable by its read examples.
            Map<String, ServedView> created = new LinkedHashMap<>(declared);
            for (Example example : examples) {
                if (!example.kind().equals("continuous")) {
                    continue;
                }
                try {
                    Optional<ServedView> view = continuous(schema, example.sql());
                    view.ifPresent(v -> created.put(v.name(), v));
                } catch (RuntimeException e) {
                    failures.add(example.where() + " does not plan: " + firstLine(e));
                }
                checked++;
            }
            ViewCatalog catalog = new ViewCatalog();
            created.values().forEach(catalog::register);
            ViewQuery reads = new ViewQuery(catalog);
            for (Example example : examples) {
                switch (example.kind()) {
                    case "continuous" -> {
                        // planned above
                    }
                    case "parameterised" -> {
                        // A registration that carries bound values (an embedded QueryRegistry.register(...,
                        // BoundParameters)): the
                        // values arrive with the registration, so here the plan is checked and its
                        // placeholders counted, as CaseStudySqlTest does for its parameterised reads.
                        try {
                            int count = ParameterMetadata.of(new SqlPlanner(schema).plan(example.sql()))
                                    .count();
                            if (count == 0) {
                                failures.add(example.where() + " is marked parameterised and binds nothing");
                            }
                        } catch (RuntimeException e) {
                            failures.add(example.where() + " does not plan: " + firstLine(e));
                        }
                        checked++;
                    }
                    case "read" -> {
                        try {
                            reads.prepare(example.sql(), Principal.ANONYMOUS);
                        } catch (RuntimeException e) {
                            failures.add(example.where() + " is a read that does not plan: " + firstLine(e));
                        }
                        checked++;
                    }
                    case "refused", "read-refused" -> {
                        RuntimeException thrown = null;
                        try {
                            if (example.kind().equals("refused")) {
                                continuous(schema, example.sql());
                            } else {
                                reads.prepare(example.sql(), Principal.ANONYMOUS);
                            }
                        } catch (RuntimeException e) {
                            thrown = e;
                        }
                        if (thrown == null) {
                            failures.add(example.where() + " is shown as refused and the engine accepts it");
                        } else if (example.code() != null && !codeOf(thrown).equals(example.code())) {
                            failures.add(example.where() + " is shown as refused with " + example.code()
                                    + " and is refused with " + codeOf(thrown) + ": " + firstLine(thrown));
                        }
                        checked++;
                    }
                    default -> failures.add(example.where() + " has an unknown marker 'sql: " + example.kind() + "'");
                }
            }
        }
        assertThat(failures)
                .as("SQL in the console's help must be SQL the engine plans, or refuses exactly as the page says")
                .isEmpty();
        System.out.printf(
                "HelpExamplesSqlTest: %d SQL statements in %d help pages planned or refused as marked%n",
                checked, pages().size());
        assertThat(checked)
                .as("the help should hold enough SQL for this check to mean something")
                .isGreaterThan(100);
    }

    // ------------------------------------------------------------------------------ planning

    /**
     * One statement, planned as a registration would plan it. For a {@code CREATE}, the view it
     * would maintain; for a bare {@code SELECT} or a management statement, nothing.
     */
    private static Optional<ServedView> continuous(PravahaSchema schema, String sql) {
        Optional<ContinuousStatement> statement = ContinuousStatements.recognize(sql);
        if (statement.isEmpty()) {
            PhysicalOperator plan = new PhysicalPlanBuilder().build(new SqlPlanner(schema).plan(sql));
            assertThat(plan.outputSchema().fieldCount()).isPositive();
            return Optional.empty();
        }
        if (statement.get() instanceof ContinuousStatement.Create create) {
            PhysicalOperator plan = new PhysicalPlanBuilder().build(new SqlPlanner(schema).plan(create.select()));
            List<Integer> keys = create.keyOrdinals(plan.outputSchema());
            return Optional.of(
                    new ServedView(create.name(), plan.outputSchema().renamedTo(create.name()), keys, 1_000));
        }
        return Optional.empty();
    }

    private static PravahaSchema schema() throws IOException {
        Properties properties = load(fixture("streams.properties"));
        Map<String, Map<String, String>> streams = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            String[] parts = key.split("\\.", 3);
            if (parts.length == 3 && parts[0].equals("stream")) {
                streams.computeIfAbsent(parts[1], k -> new LinkedHashMap<>())
                        .put(parts[2], properties.getProperty(key).strip());
            }
        }
        PravahaSchema schema = new PravahaSchema();
        for (Map.Entry<String, Map<String, String>> stream : streams.entrySet()) {
            Map<String, String> spec = stream.getValue();
            StreamSchema parsed = FilesystemSourcePlugin.parseSchema(stream.getKey(), spec.get("fields"));
            StreamSchema.Builder builder = StreamSchema.builder(stream.getKey());
            parsed.fields().forEach(field -> builder.field(field.name(), field.type()));
            if (spec.containsKey("event-time")) {
                builder.eventTime(spec.get("event-time"));
            }
            if (spec.containsKey("out-of-orderness")) {
                builder.outOfOrderness(Duration.parse(spec.get("out-of-orderness")));
            }
            if ("lookup".equals(spec.get("role"))) {
                schema.registerLookup(builder.build());
            } else {
                schema.register(builder.build());
            }
        }
        return schema;
    }

    private static Map<String, ServedView> declaredViews(PravahaSchema schema) throws IOException {
        Properties properties = load(fixture("views.properties"));
        Map<String, ServedView> views = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames().stream().sorted().toList()) {
            if (!key.startsWith("view.") || key.endsWith(".keys")) {
                continue;
            }
            String name = key.substring(5);
            String keys = properties.getProperty(key + ".keys");
            assertThat(keys).as("view %s declares no keys", name).isNotBlank();
            String create =
                    "CREATE CONTINUOUS QUERY " + name + " KEYED BY (" + keys + ") AS " + properties.getProperty(key);
            try {
                views.put(name, continuous(schema, create).orElseThrow());
            } catch (RuntimeException e) {
                throw new AssertionError("views.properties: " + name + " does not plan: " + firstLine(e), e);
            }
        }
        return views;
    }

    // ------------------------------------------------------------------------------ extraction

    private static List<Path> pages() throws IOException {
        try (Stream<Path> files = Files.list(topics())) {
            return files.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
    }

    static List<Example> examplesIn(Path page) throws IOException {
        String text = Files.readString(page, StandardCharsets.UTF_8);
        List<Example> examples = new ArrayList<>();
        Matcher block = BLOCK.matcher(text);
        while (block.find()) {
            String kind = block.group(1) == null ? "continuous" : block.group(1);
            int line = 1
                    + (int) text.substring(0, block.start(3))
                            .chars()
                            .filter(c -> c == '\n')
                            .count();
            for (String statement : statements(block.group(3))) {
                examples.add(new Example(page.getFileName().toString(), line, kind, block.group(2), statement));
            }
        }
        return examples;
    }

    /** A block's statements: split where a semicolon ends a line, the semicolon dropped. */
    private static List<String> statements(String block) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : block.split("\n", -1)) {
            current.append(line).append('\n');
            if (line.stripTrailing().endsWith(";")) {
                add(out, current.toString().strip());
                current.setLength(0);
            }
        }
        add(out, current.toString().strip());
        return out;
    }

    private static void add(List<String> out, String statement) {
        String body = statement.endsWith(";")
                ? statement.substring(0, statement.length() - 1).strip()
                : statement;
        // A block may end in comments after its last statement; they are not a statement.
        boolean onlyComments =
                body.lines().allMatch(l -> l.isBlank() || l.strip().startsWith("--"));
        if (!onlyComments) {
            out.add(body);
        }
    }

    // ------------------------------------------------------------------------------ helpers

    private static Properties load(Path path) throws IOException {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(path)) {
            properties.load(in);
        }
        return properties;
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
