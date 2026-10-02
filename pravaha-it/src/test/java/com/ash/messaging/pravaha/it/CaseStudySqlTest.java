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
import com.ash.messaging.pravaha.sql.ContinuousStatement;
import com.ash.messaging.pravaha.sql.ContinuousStatements;
import com.ash.messaging.pravaha.sql.PravahaSchema;
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

    /**
     * The studies. Which views each maintains is declared in its {@code schema/views.properties},
     * not listed here -- a study with two continuous queries (and two of them have) would otherwise
     * have one of its views silently unchecked, which is how the trading study's cancel_rate went
     * unverified until a study needed the same thing.
     */
    private static final List<String> STUDIES = List.of(
            "banking-card-velocity",
            "finance-counterparty-exposure",
            "trading-order-flow",
            "biology-sequencing-qc",
            "trade-processing",
            "manufacturing-sensor-anomalies",
            "ecommerce-checkout-funnel",
            "adtech-click-attribution",
            "telecom-cdr-fraud",
            "logistics-delivery-sla",
            "retail-inventory-mysql",
            "lakehouse-orders-iceberg",
            "payments-shared-kafka");

    private static Path studies() {
        return repoRoot().resolve("examples/case-studies");
    }

    private static Path repoRoot() {
        Path path = Path.of("").toAbsolutePath();
        while ((path != null && !Files.exists(path.resolve("pom.xml").toAbsolutePath()))
                || (path != null && !Files.exists(path.resolve("examples")))) {
            path = path.getParent();
        }
        return path;
    }

    /**
     * Every directory under examples/case-studies is in {@link #STUDIES}, and nothing else is. A study
     * added without being listed here would have its SQL go unplanned.
     */
    @Test
    void everyCaseStudyDirectoryIsListed() throws IOException {
        try (Stream<Path> entries = Files.list(studies())) {
            List<String> directories = entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
            assertThat(STUDIES).containsExactlyInAnyOrderElementsOf(directories);
        }
    }

    /**
     * The {@code SELECT} of a continuous query file. A file may be a {@code CREATE CONTINUOUS QUERY}
     * statement -- a study that declares an index has to be, since {@code INDEX (column)} is a clause
     * of the statement -- and its {@code SELECT} is what is planned, exactly as the registry plans it.
     */
    private static String selectOf(String sql) {
        return ContinuousStatements.recognize(sql)
                .map(statement -> ((ContinuousStatement.Create) statement).select())
                .orElse(sql);
    }

    @Test
    void everyCaseStudyDeclaresItsStreamsAndViews() throws IOException {
        for (String study : STUDIES) {
            assertThat(studies().resolve(study).resolve("schema/streams.properties"))
                    .as("%s must declare its data model in a form the build can check", study)
                    .exists();
            assertThat(studies().resolve(study).resolve("schema/views.properties"))
                    .as("%s must say which continuous query maintains which view", study)
                    .exists();
            assertThat(viewsOf(study)).as("%s declares no views", study).isNotEmpty();
        }
    }

    /** view name to the continuous SQL file that maintains it. */
    private static Map<String, String> viewsOf(String study) throws IOException {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(studies().resolve(study).resolve("schema/views.properties"))) {
            properties.load(in);
        }
        Map<String, String> views = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("view.") && key.indexOf('.', 5) < 0) {
                views.put(key.substring(5), properties.getProperty(key).strip());
            }
        }
        return views;
    }

    /** A catalog holding every view a study declares, as a deployment would have. */
    private static ViewCatalog catalogOf(String study) throws IOException {
        Schemas schemas = schemasOf(study);
        ViewCatalog catalog = new ViewCatalog();
        for (Map.Entry<String, String> view : viewsOf(study).entrySet()) {
            PhysicalOperator plan = new PhysicalPlanBuilder()
                    .build(schemas.planner()
                            .plan(selectOf(
                                    read(studies().resolve(study).resolve("sql").resolve(view.getValue())))));
            catalog.register(
                    new ServedView(view.getKey(), rename(plan.outputSchema(), view.getKey()), List.of(0), 1_000));
        }
        return catalog;
    }

    @Test
    void everyContinuousQueryPlans() throws IOException {
        List<String> failures = new ArrayList<>();
        for (String study : STUDIES) {
            Schemas schemas = schemasOf(study);
            for (Path sql : sqlFiles(study, "continuous")) {
                try {
                    PhysicalOperator plan =
                            new PhysicalPlanBuilder().build(schemas.planner().plan(selectOf(read(sql))));
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
    void everyReadQueryRunsAgainstTheViewsItsStudyProduces() throws IOException {
        List<String> failures = new ArrayList<>();
        for (String study : STUDIES) {
            // Every view the study declares, registered together -- so a read query may name any of
            // them, exactly as it could in a deployment where all of them are running.
            ViewQuery queries = new ViewQuery(catalogOf(study));
            for (Path sql : sqlFiles(study, "read")) {
                try {
                    queries.prepare(read(sql), com.ash.messaging.pravaha.security.Principal.ANONYMOUS);
                } catch (RuntimeException e) {
                    failures.add(study + "/" + sql.getFileName() + ": " + firstLine(e));
                }
            }
        }
        assertThat(failures)
                .as("read queries must plan against the views their study produces")
                .isEmpty();
    }

    @Test
    void everyReadmeContainsTheSqlItDocuments() throws IOException {
        List<String> failures = new ArrayList<>();
        for (String study : STUDIES) {
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
        for (String study : STUDIES) {
            Path dir = studies().resolve(study);
            // A README that references a generator or an example nobody shipped wastes the reader's
            // time at exactly the point they had decided to try it.
            for (String required : List.of(
                    "README.md",
                    "schema/streams.properties",
                    "conf/application.yaml",
                    "sql",
                    "java",
                    "python",
                    "data")) {
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
                        "\\]\\((sql/[^)]+|schema/[^)]+|java/[^)]+|python/[^)]+|data/[^)]+|conf/[^)]+)\\)")
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

    /**
     * The event time the test plans against is the event time the study tells a reader to configure.
     *
     * <p>Two files say which column a stream's time lives in: {@code schema/streams.properties},
     * which this test plans from, and the {@code conf/application.yaml} a reader actually starts a
     * node with. A study whose fixture declares an event time and whose shipped configuration does
     * not is CASE-1 again with one more place to hide it -- green here, empty view there.
     */
    @Test
    void everyStudyConfiguresTheEventTimeItIsPlannedWith() throws IOException {
        List<String> wrong = new ArrayList<>();
        for (String study : STUDIES) {
            String configured = Files.readString(studies().resolve(study).resolve("conf/application.yaml"));
            for (Map.Entry<String, String> declared : eventTimesOf(study).entrySet()) {
                for (String timing : List.of("out-of-orderness", "allowed-lateness")) {
                    String value = streamsOf(study)
                            .getProperty("stream." + declared.getKey() + "." + timing, "")
                            .strip();
                    if (!value.isEmpty() && !configured.contains(timing + ": " + value)) {
                        wrong.add(study + ": conf/application.yaml does not declare '" + timing + ": " + value
                                + "' for stream '" + declared.getKey() + "', which its schema/streams.properties does");
                    }
                }
                if (!configured.contains("event-time: " + declared.getValue())) {
                    wrong.add(study + ": conf/application.yaml does not declare 'event-time: " + declared.getValue()
                            + "' for stream '" + declared.getKey() + "', which its schema/streams.properties does");
                }
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void everyParameterisedReadDeclaresItsParameters() throws IOException {
        for (String study : STUDIES) {
            ViewCatalog catalog = catalogOf(study);
            StreamSchema[] viewSchemas = catalog.schemas().values().toArray(new StreamSchema[0]);
            for (Path sql : sqlFiles(study, "read")) {
                String body = read(sql);
                if (!body.contains("?")) {
                    continue;
                }
                ParameterMetadata parameters =
                        ParameterMetadata.of(SqlPlanner.withStreams(viewSchemas).plan(body));
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

    /**
     * What a study's node would have in its catalogue: the streams it consumes and the dimension
     * tables it asks. Two of the studies join one stream to another, so there may be more than one
     * source -- each registered as a stream, as a node with two {@code pravaha.sources} bindings
     * registers them. Planning the second as a lookup would refuse the join those studies run.
     */
    @SuppressWarnings("ArrayRecordComponent") // carries the array; nothing compares or hashes one
    private record Schemas(List<StreamSchema> sources, StreamSchema[] lookups) {
        SqlPlanner planner() {
            PravahaSchema catalog = new PravahaSchema();
            sources.forEach(catalog::register);
            for (StreamSchema lookup : lookups) {
                catalog.registerLookup(lookup);
            }
            return new SqlPlanner(catalog);
        }
    }

    private static Properties streamsOf(String study) throws IOException {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(studies().resolve(study).resolve("schema/streams.properties"))) {
            properties.load(in);
        }
        return properties;
    }

    /** stream name to the column the study declares as its event time, for the streams that have one. */
    private static Map<String, String> eventTimesOf(String study) throws IOException {
        Map<String, String> eventTimes = new LinkedHashMap<>();
        Properties properties = streamsOf(study);
        for (String key : properties.stringPropertyNames()) {
            String[] parts = key.split("\\.", -1);
            if (parts.length == 3 && parts[0].equals("stream") && parts[2].equals("event-time")) {
                eventTimes.put(parts[1], properties.getProperty(key).strip());
            }
        }
        return eventTimes;
    }

    private static Schemas schemasOf(String study) throws IOException {
        Properties properties = streamsOf(study);
        Map<String, String> roles = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();
        // Spelled as the server spells it -- pravaha.streams.<name>.event-time -- so the fixture and
        // the application.yaml the study ships cannot drift apart in wording.
        Map<String, String> eventTimes = eventTimesOf(study);
        for (String key : properties.stringPropertyNames()) {
            String[] parts = key.split("\\.", -1);
            if (parts.length == 3 && parts[0].equals("stream")) {
                if (parts[2].equals("role")) {
                    roles.put(parts[1], properties.getProperty(key).strip());
                } else if (parts[2].equals("fields")) {
                    fields.put(parts[1], properties.getProperty(key));
                }
            }
        }
        List<StreamSchema> sources = new ArrayList<>();
        List<StreamSchema> lookups = new ArrayList<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            StreamSchema schema = schemaOf(study, entry.getKey(), entry.getValue(), eventTimes.get(entry.getKey()));
            if ("source".equals(roles.get(entry.getKey()))) {
                sources.add(schema);
            } else {
                lookups.add(schema);
            }
        }
        if (sources.isEmpty()) {
            throw new IllegalStateException(study + " declares no source stream");
        }
        return new Schemas(sources, lookups.toArray(new StreamSchema[0]));
    }

    /**
     * A study's stream, built exactly as a node would build it -- <em>including</em> which column is
     * its event time.
     *
     * <p>This fixture used never to call {@link StreamSchema.Builder#eventTime}, so it had the same
     * gap the studies had (CASE-1): four of the five window over a stream, none of them declared an
     * event-time column, and the plan the test checked was therefore not the plan a node would run.
     * The test was green, and would have stayed green after a fix that only touched the studies.
     *
     * <p>So a stream carrying a {@code TIMESTAMP} column must say which one is its event time, and
     * the fixture fails by name when it does not. That is not a test-only rule: without the
     * declaration no watermark advances over the stream, so no window a query opens can ever close,
     * and the engine refuses such a plan at registration. A study whose windows could never close
     * now fails the build here rather than in a reader's empty view.
     */
    private static StreamSchema schemaOf(String study, String name, String fields, String eventTime) {
        if ((eventTime == null || eventTime.isBlank()) && fields.contains("TIMESTAMP")) {
            throw new IllegalStateException(study + "'s stream '" + name + "' carries a TIMESTAMP column and "
                    + "declares no event time. Add stream." + name + ".event-time to its "
                    + "schema/streams.properties (and pravaha.streams." + name + ".event-time to the "
                    + "application.yaml it ships): without it no watermark advances over the stream and no "
                    + "window over it can ever close.");
        }
        StreamSchema.Builder builder = StreamSchema.builder(name);
        for (String field : fields.split(",", -1)) {
            String[] parts = field.strip().split(":", -1);
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
        if (eventTime != null && !eventTime.isBlank()) {
            builder.eventTime(eventTime.strip());
        }
        return builder.build();
    }
}
