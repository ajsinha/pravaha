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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.ParameterMetadata;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every SQL statement in every case study is planned against the real engine.
 *
 * <p>A case study is a template somebody will copy into a production system, so the worst thing it
 * can contain is SQL that reads plausibly and the engine refuses. That is an easy document to write
 * by accident: `ORDER BY`, `LIKE`, a `CASE` expression and an unwindowed `GROUP BY` all look
 * unremarkable and are all rejected here.
 *
 * <p>So each study declares its streams in {@code schema/streams.properties}, and every {@code .sql}
 * file beside it is planned against those schemas. The continuous queries are planned as streaming
 * queries; the read queries are planned against the view their study produces, through the same
 * {@link ViewQuery} path a client uses. A study whose SQL stops working fails the build.
 */
class CaseStudySqlTest {

    /** Each study, and the view its continuous query maintains. */
    private static final Map<String, String> STUDY_VIEWS = Map.of(
            "banking-card-velocity", "card_velocity",
            "finance-counterparty-exposure", "counterparty_exposure",
            "trading-order-flow", "order_rate",
            "biology-sequencing-qc", "coverage_qc",
            "trade-processing", "trade_feed");

    private static Path studies() {
        return repoRoot().resolve("examples/case-studies");
    }

    private static Path repoRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("pom.xml").toAbsolutePath())
                || (path != null && !Files.exists(path.resolve("examples")))) {
            path = path.getParent();
        }
        return path;
    }

    @Test
    void everyCaseStudyDeclaresItsStreams() throws IOException {
        for (String study : STUDY_VIEWS.keySet()) {
            assertThat(studies().resolve(study).resolve("schema/streams.properties"))
                    .as("%s must declare its data model in a form the build can check", study)
                    .exists();
        }
    }

    @Test
    void everyContinuousQueryPlans() throws IOException {
        List<String> failures = new ArrayList<>();
        for (String study : STUDY_VIEWS.keySet()) {
            Schemas schemas = schemasOf(study);
            for (Path sql : sqlFiles(study, "continuous")) {
                try {
                    PhysicalOperator plan = new PhysicalPlanBuilder()
                            .build(SqlPlanner.withLookups(schemas.source(), schemas.lookups())
                                    .plan(read(sql)));
                    assertThat(plan.outputSchema().fieldCount()).isPositive();
                } catch (RuntimeException e) {
                    failures.add(study + "/" + sql.getFileName() + ": " + firstLine(e));
                }
            }
        }
        assertThat(failures)
                .as("continuous queries in the case studies must plan")
                .isEmpty();
    }

    @Test
    void everyReadQueryRunsAgainstTheViewItsStudyProduces() throws IOException {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> entry : STUDY_VIEWS.entrySet()) {
            String study = entry.getKey();
            Schemas schemas = schemasOf(study);

            // The view a read query sees is the output of that study's continuous query, presented
            // under the study's view name -- so these are planned against exactly what a deployment
            // would have, not against an approximation written for the test.
            PhysicalOperator continuous = new PhysicalPlanBuilder()
                    .build(SqlPlanner.withLookups(schemas.source(), schemas.lookups())
                            .plan(read(sqlFiles(study, "continuous").get(0))));
            StreamSchema viewSchema = rename(continuous.outputSchema(), entry.getValue());
            ViewCatalog catalog =
                    new ViewCatalog().register(new ServedView(entry.getValue(), viewSchema, List.of(1), 1_000));
            ViewQuery queries = new ViewQuery(catalog);

            for (Path sql : sqlFiles(study, "read")) {
                try {
                    queries.prepare(read(sql), com.ash.messaging.pravaha.security.Principal.ANONYMOUS);
                } catch (RuntimeException e) {
                    failures.add(study + "/" + sql.getFileName() + ": " + firstLine(e));
                }
            }
        }
        assertThat(failures)
                .as("read queries in the case studies must plan against their own view")
                .isEmpty();
    }

    @Test
    void everyReadmeContainsTheSqlItDocuments() throws IOException {
        List<String> failures = new ArrayList<>();
        for (String study : STUDY_VIEWS.keySet()) {
            Path readme = studies().resolve(study).resolve("README.md");
            if (!Files.exists(readme)) {
                failures.add(study + ": no README.md");
                continue;
            }
            String text = Files.readString(readme);
            for (Path sql : allSql(study)) {
                // The README must contain the *checked* SQL rather than a retyping of it. A document
                // that drifts from the file beside it is worse than no document, because the reader
                // has no way to tell which one is true.
                String body = read(sql).strip();
                if (!normalise(text).contains(normalise(body))) {
                    failures.add(study + "/" + sql.getFileName() + " is not quoted in the README");
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void everyCaseStudyShipsWhatItsReadmePromises() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String study : STUDY_VIEWS.keySet()) {
            Path dir = studies().resolve(study);
            // A README that references a generator or an example nobody shipped wastes the reader's
            // time at exactly the point they had decided to try it.
            for (String required : List.of("README.md", "schema/streams.properties", "sql", "java", "python", "data")) {
                if (!Files.exists(dir.resolve(required))) {
                    missing.add(study + "/" + required);
                }
            }
            if (!Files.exists(dir.resolve("README.md"))) {
                continue;
            }
            String readme = Files.readString(dir.resolve("README.md"));
            for (String referenced : referencedFiles(readme)) {
                if (!Files.exists(dir.resolve(referenced))) {
                    missing.add(study + " README points at " + referenced + ", which does not exist");
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    /** Every relative path a README links to or names in a command. */
    private static List<String> referencedFiles(String readme) {
        List<String> paths = new ArrayList<>();
        java.util.regex.Matcher links = java.util.regex.Pattern.compile(
                        "\\]\\((sql/[^)]+|schema/[^)]+|java/[^)]+|python/[^)]+|data/[^)]+)\\)")
                .matcher(readme);
        while (links.find()) {
            paths.add(links.group(1));
        }
        java.util.regex.Matcher commands = java.util.regex.Pattern.compile("python3 (data/[A-Za-z0-9_./-]+\\.py)")
                .matcher(readme);
        while (commands.find()) {
            paths.add(commands.group(1));
        }
        return paths;
    }

    @Test
    void everyParameterisedReadDeclaresItsParameters() throws IOException {
        for (Map.Entry<String, String> entry : STUDY_VIEWS.entrySet()) {
            Schemas schemas = schemasOf(entry.getKey());
            PhysicalOperator continuous = new PhysicalPlanBuilder()
                    .build(SqlPlanner.withLookups(schemas.source(), schemas.lookups())
                            .plan(read(sqlFiles(entry.getKey(), "continuous").get(0))));
            StreamSchema viewSchema = rename(continuous.outputSchema(), entry.getValue());

            for (Path sql : sqlFiles(entry.getKey(), "read")) {
                String body = read(sql);
                if (!body.contains("?")) {
                    continue;
                }
                ParameterMetadata parameters =
                        ParameterMetadata.of(SqlPlanner.withStreams(viewSchema).plan(body));
                // A placeholder the engine cannot bind would be found by a reader, at runtime, in
                // their own application.
                assertThat(parameters.count())
                        .as("%s uses ? but declares no bindable parameter", sql.getFileName())
                        .isPositive();
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    private static String normalise(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private static String firstLine(RuntimeException e) {
        String message = String.valueOf(e.getMessage());
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path).strip();
    }

    private static List<Path> allSql(String study) throws IOException {
        try (Stream<Path> files = Files.list(studies().resolve(study).resolve("sql"))) {
            return files.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
        }
    }

    private static List<Path> sqlFiles(String study, String kind) throws IOException {
        return allSql(study).stream()
                .filter(p -> p.getFileName().toString().contains(kind))
                .toList();
    }

    private static StreamSchema rename(StreamSchema schema, String name) {
        StreamSchema.Builder builder = StreamSchema.builder(name);
        schema.fields().forEach(field -> builder.field(field.name(), field.type()));
        return builder.build();
    }

    private record Schemas(StreamSchema source, StreamSchema[] lookups) {}

    private static Schemas schemasOf(String study) throws IOException {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(studies().resolve(study).resolve("schema/streams.properties"))) {
            properties.load(in);
        }
        Map<String, String> roles = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            String[] parts = key.split("\\.");
            if (parts.length == 3 && parts[0].equals("stream")) {
                if (parts[2].equals("role")) {
                    roles.put(parts[1], properties.getProperty(key).strip());
                } else if (parts[2].equals("fields")) {
                    fields.put(parts[1], properties.getProperty(key));
                }
            }
        }
        StreamSchema source = null;
        List<StreamSchema> lookups = new ArrayList<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            StreamSchema schema = schemaOf(entry.getKey(), entry.getValue());
            if ("source".equals(roles.get(entry.getKey()))) {
                source = schema;
            } else {
                lookups.add(schema);
            }
        }
        if (source == null) {
            throw new IllegalStateException(study + " declares no source stream");
        }
        return new Schemas(source, lookups.toArray(new StreamSchema[0]));
    }

    private static StreamSchema schemaOf(String name, String fields) {
        StreamSchema.Builder builder = StreamSchema.builder(name);
        for (String field : fields.split(",")) {
            String[] parts = field.strip().split(":");
            String column = parts[0].strip();
            String type = parts[1].strip();
            boolean nullable = type.endsWith("?");
            if (nullable) {
                type = type.substring(0, type.length() - 1);
            }
            PravahaType resolved =
                    switch (type) {
                        case "STRING" -> Types.string();
                        case "INT32" -> Types.int32();
                        case "INT64" -> Types.int64();
                        case "FLOAT64" -> Types.float64();
                        case "BOOLEAN" -> Types.bool();
                        case "TIMESTAMP" -> Types.timestamp();
                        default -> throw new IllegalArgumentException("unknown type '" + type + "' in " + name);
                    };
            builder.field(column, nullable ? resolved.withNullable(true) : resolved);
        }
        return builder.build();
    }
}
