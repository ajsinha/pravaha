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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.embedded.ContinuousQuery;
import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewQuery;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every case study, run: its continuous queries over a small sample, and its answers checked.
 *
 * <p>{@link CaseStudySqlTest} proves each study's SQL plans. That is not the same as proving it
 * says what its README says: a window keyed by the wrong column, a filter one comparison off, or a
 * read that sums the wrong thing all plan. So each study ships {@code data/sample/}: one CSV per
 * stream and per dimension table, and {@code answers.txt}, the answers to its read queries worked
 * out by hand from those rows. This test starts an embedded engine -- no server, no store, no
 * Docker -- declares the study's streams as {@code schema/streams.properties} does, registers
 * every continuous query its {@code schema/views.properties} names, pushes the sample rows in, and
 * checks each answer.
 *
 * <p>Rows are pushed, not read by the study's source plugin, because most of those need a store
 * (Aerospike, PostgreSQL, MySQL, Kafka). What is proved is that the SQL gives the right answers to
 * the rows the source would deliver. Dimension tables are served by the {@code jdbc} lookup plugin
 * over an in-memory H2 database, so a lookup join runs as it does in a deployment.
 *
 * <p>The studies are found by listing {@code examples/case-studies/}, so a new study without a
 * sample and its answers fails here rather than going unrun.
 *
 * <h2>{@code data/sample/}</h2>
 *
 * <ul>
 *   <li>{@code <stream>.csv}: a header naming the stream's columns in order, then its rows, in the
 *       order they arrive. Streams with more than one file are merged by event time, each file's own
 *       order kept -- so a late row stays late. A row whose first column is {@code -} is a
 *       retraction of the row that follows the marker, as a change-data-capture source delivers the
 *       old half of an update.
 *   <li>{@code <lookup>.csv}: the dimension table's rows.
 *   <li>{@code answers.txt}: blocks of {@code == <read file or SQL> [:: param, param]} followed by
 *       the expected rows, columns separated by {@code |}, in any order. A parameter that is all digits
 *       is bound as a number; quote it ({@code '447700900666'}) to bind it as text; {@code (none)} for no rows.
 *       Every {@code *-read-*.sql} of the study must be checked at least once.
 * </ul>
 */
class CaseStudyRunTest {

    private static final String STUDIES = "examples/case-studies";

    @TestFactory
    Stream<DynamicTest> everyCaseStudyGivesTheAnswersWorkedOutByHand() throws IOException {
        return studyDirectories().stream()
                .map(dir -> DynamicTest.dynamicTest(dir.getFileName().toString(), () -> run(dir)));
    }

    @Test
    void everyCaseStudyDirectoryShipsASampleAndItsAnswers() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Path dir : studyDirectories()) {
            if (!Files.exists(dir.resolve("data/sample/answers.txt"))) {
                missing.add(dir.getFileName() + ": no data/sample/answers.txt");
            }
        }
        assertThat(studyDirectories()).as("case studies found").hasSizeGreaterThanOrEqualTo(13);
        assertThat(missing).isEmpty();
    }

    static List<Path> studyDirectories() throws IOException {
        try (Stream<Path> entries = Files.list(repoRoot().resolve(STUDIES))) {
            return entries.filter(Files::isDirectory).sorted().toList();
        }
    }

    // --------------------------------------------------------------------------------- one study

    private static void run(Path dir) throws Exception {
        Study study = Study.load(dir);
        String h2 = "jdbc:h2:mem:" + dir.getFileName().toString().replace('-', '_') + ";DATABASE_TO_LOWER=TRUE";
        try (Connection keepAlive = DriverManager.getConnection(h2)) {
            for (StreamSchema lookup : study.lookups) {
                loadLookup(keepAlive, lookup, dir.resolve("data/sample/" + lookup.name() + ".csv"));
            }
            try (PravahaEngine engine = PravahaEngine.createDefault()) {
                study.sources.forEach(engine::declareStream);
                for (StreamSchema lookup : study.lookups) {
                    engine.bindLookup(
                            lookup.name(),
                            "jdbc-lookup",
                            Map.of(
                                    "url",
                                    h2,
                                    "table",
                                    lookup.name(),
                                    "key.columns",
                                    lookup.field(0).name()));
                }
                engine.start();
                for (View view : study.views) {
                    String sql = Files.readString(dir.resolve("sql").resolve(view.file()))
                            .strip();
                    if (sql.regionMatches(true, 0, "CREATE", 0, 6)) {
                        engine.query(sql);
                    } else {
                        engine.register(ContinuousQuery.named(view.name())
                                .sql(sql)
                                .keyedBy(view.keys())
                                .build());
                    }
                }
                pushSample(engine, study, dir);
                checkAnswers(engine, study, dir);
            }
        }
    }

    /**
     * Every source stream's rows, merged by event time with each file's own order kept, one row at a
     * time -- and after each, event time moved on as a node's bounded-out-of-orderness watermark
     * would move it: the latest event time seen, less the stream's out-of-orderness, and for a study
     * with several streams the least of theirs, since a query over two streams moves only as fast as
     * the slower. Pushed rows carry no watermark of their own, so without this no window would close.
     */
    private static void pushSample(PravahaEngine engine, Study study, Path dir) throws IOException {
        List<List<Row>> queues = new ArrayList<>();
        for (StreamSchema source : study.sources) {
            queues.add(new ArrayList<>(readRows(source, dir.resolve("data/sample/" + source.name() + ".csv"))));
        }
        long[] latest = new long[queues.size()];
        Arrays.fill(latest, Long.MIN_VALUE);
        long watermark = Long.MIN_VALUE;
        while (queues.stream().anyMatch(q -> !q.isEmpty())) {
            // The next stream is the one whose head is earliest; ties go to the stream declared first.
            int next = -1;
            for (int i = 0; i < queues.size(); i++) {
                if (!queues.get(i).isEmpty()
                        && (next < 0
                                || queues.get(i).get(0).time()
                                        < queues.get(next).get(0).time())) {
                    next = i;
                }
            }
            Row row = queues.get(next).remove(0);
            StreamSchema stream = study.sources.get(next);
            if (row.retraction()) {
                engine.retract(stream.name(), new Object[][] {row.values()});
            } else {
                engine.push(stream.name(), List.<Object[]>of(row.values()));
            }
            if (stream.eventTimeOrdinal().isEmpty()) {
                continue;
            }
            latest[next] =
                    Math.max(latest[next], row.time() - stream.outOfOrderness().toMillis());
            long least = Long.MAX_VALUE;
            for (long each : latest) {
                if (each != Long.MIN_VALUE) {
                    least = Math.min(least, each);
                }
            }
            if (least > watermark) {
                watermark = least;
                for (StreamSchema source : study.sources) {
                    engine.advanceEventTime(source.name(), Instant.ofEpochMilli(watermark));
                }
            }
        }
    }

    private static void checkAnswers(PravahaEngine engine, Study study, Path dir) throws IOException {
        List<Answer> answers = Answer.parse(Files.readString(dir.resolve("data/sample/answers.txt")));
        List<String> failures = new ArrayList<>();
        for (Answer answer : answers) {
            String sql = answer.target().endsWith(".sql")
                    ? Files.readString(dir.resolve("sql").resolve(answer.target()))
                            .strip()
                    : answer.target();
            ViewQuery.Result result = engine.query(sql, answer.parameters().toArray());
            List<String> actual = result.rows().stream()
                    .map(row -> render(result.schema(), row))
                    .sorted()
                    .toList();
            List<String> expected = answer.rows().stream().sorted().toList();
            if (!actual.equals(expected)) {
                failures.add(answer.target() + " " + answer.parameters() + "\n    expected " + expected
                        + "\n    but was  " + actual);
            }
        }
        List<String> unchecked = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir.resolve("sql"))) {
            for (Path file : files.filter(f -> f.getFileName().toString().contains("-read-"))
                    .toList()) {
                String name = file.getFileName().toString();
                if (answers.stream().noneMatch(a -> a.target().equals(name))) {
                    unchecked.add(name);
                }
            }
        }
        // A view declared with INDEX (column) must have been read through it at least once, or the
        // study claims an access path its own answers never exercise.
        for (View view : study.views) {
            ServedView served = engine.find(view.name()).orElseThrow().view();
            if (!served.indexedColumns().isEmpty() && served.indexLookups() == 0) {
                failures.add(view.name() + " declares an index that no answer read through");
            }
        }
        assertThat(unchecked)
                .as("read queries of %s with no answer in answers.txt", dir.getFileName())
                .isEmpty();
        assertThat(failures).as("answers of %s", dir.getFileName()).isEmpty();
    }

    /** One result row as answers.txt spells it: columns joined by " | ", timestamps as instants. */
    static String render(StreamSchema schema, Object[] row) {
        List<String> cells = new ArrayList<>(row.length);
        for (int i = 0; i < row.length; i++) {
            Object value = row[i];
            String type = schema.field(i).type().typeName().name();
            if (value == null) {
                cells.add("NULL");
            } else if (type.startsWith("TIMESTAMP") && value instanceof Long nanos) {
                cells.add(Instant.ofEpochSecond(
                                Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L))
                        .toString());
            } else {
                cells.add(value.toString());
            }
        }
        return String.join(" | ", cells);
    }

    // --------------------------------------------------------------------------------- the files

    private record View(String name, String file, List<String> keys) {}

    private record Row(long time, boolean retraction, Object[] values) {}

    private record Answer(String target, List<Object> parameters, List<String> rows) {

        static List<Answer> parse(String text) {
            List<Answer> answers = new ArrayList<>();
            String target = null;
            List<Object> parameters = List.of();
            List<String> rows = new ArrayList<>();
            for (String raw : text.lines().toList()) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("==")) {
                    if (target != null) {
                        answers.add(new Answer(target, parameters, rows));
                    }
                    String head = line.substring(2).strip();
                    int split = head.indexOf("::");
                    target = (split < 0 ? head : head.substring(0, split)).strip();
                    parameters = split < 0
                            ? List.of()
                            : Arrays.stream(head.substring(split + 2).split(","))
                                    .map(String::strip)
                                    .map(Answer::parameter)
                                    .toList();
                    rows = new ArrayList<>();
                } else if (!line.equals("(none)")) {
                    rows.add(Arrays.stream(line.split("\\|")).map(String::strip).collect(Collectors.joining(" | ")));
                }
            }
            if (target != null) {
                answers.add(new Answer(target, parameters, rows));
            }
            return answers;
        }

        private static Object parameter(String text) {
            if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
                return text.substring(1, text.length() - 1);
            }
            return text.matches("-?\\d+") ? (Object) Long.parseLong(text) : text;
        }
    }

    /** A study's streams and views, as its schema/ files declare them. */
    private static final class Study {
        final List<StreamSchema> sources = new ArrayList<>();
        final List<StreamSchema> lookups = new ArrayList<>();
        final List<View> views = new ArrayList<>();

        static Study load(Path dir) throws IOException {
            Study study = new Study();
            Properties streams = properties(dir.resolve("schema/streams.properties"));
            Map<String, String> fields = new LinkedHashMap<>();
            for (String key : streams.stringPropertyNames().stream().sorted().toList()) {
                String[] parts = key.split("\\.", -1);
                if (parts.length == 3 && parts[0].equals("stream") && parts[2].equals("fields")) {
                    fields.put(parts[1], streams.getProperty(key));
                }
            }
            for (Map.Entry<String, String> entry : declarationOrder(dir, fields).entrySet()) {
                String name = entry.getKey();
                StreamSchema.Builder builder = StreamSchema.builder(name);
                for (String field : entry.getValue().split(",", -1)) {
                    String[] parts = field.strip().split(":", -1);
                    builder.field(parts[0].strip(), type(parts[1].strip()));
                }
                String eventTime = streams.getProperty("stream." + name + ".event-time", "")
                        .strip();
                if (!eventTime.isEmpty()) {
                    builder.eventTime(eventTime);
                }
                String outOfOrderness = streams.getProperty("stream." + name + ".out-of-orderness", "")
                        .strip();
                if (!outOfOrderness.isEmpty()) {
                    builder.outOfOrderness(duration(outOfOrderness));
                }
                String lateness = streams.getProperty("stream." + name + ".allowed-lateness", "")
                        .strip();
                if (!lateness.isEmpty()) {
                    builder.allowedLateness(duration(lateness));
                }
                boolean source = "source"
                        .equals(streams.getProperty("stream." + name + ".role", "")
                                .strip());
                (source ? study.sources : study.lookups).add(builder.build());
            }
            Properties views = properties(dir.resolve("schema/views.properties"));
            List<String> names = new ArrayList<>();
            for (String key : views.stringPropertyNames()) {
                if (key.startsWith("view.") && key.indexOf('.', 5) < 0) {
                    names.add(key.substring(5));
                }
            }
            names.sort(java.util.Comparator.comparing(n -> views.getProperty("view." + n)));
            for (String name : names) {
                String keys = views.getProperty("view." + name + ".keys", "");
                study.views.add(new View(
                        name,
                        views.getProperty("view." + name).strip(),
                        Arrays.stream(keys.split(","))
                                .map(String::strip)
                                .filter(k -> !k.isEmpty())
                                .toList()));
            }
            return study;
        }

        /** The streams in the order the properties file declares them, which is the merge's tie-break. */
        private static Map<String, String> declarationOrder(Path dir, Map<String, String> fields) throws IOException {
            Map<String, String> ordered = new LinkedHashMap<>();
            for (String line : Files.readAllLines(dir.resolve("schema/streams.properties"))) {
                String[] parts = line.split("=", 2)[0].strip().split("\\.", -1);
                if (parts.length == 3 && parts[2].equals("fields") && fields.containsKey(parts[1])) {
                    ordered.put(parts[1], fields.get(parts[1]));
                }
            }
            return ordered;
        }
    }

    // --------------------------------------------------------------------------------- helpers

    private static List<Row> readRows(StreamSchema schema, Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                .toList();
        checkHeader(schema, csv, lines.get(0));
        int eventTime = schema.eventTimeOrdinal().orElse(-1);
        List<Row> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            boolean retraction = line.startsWith("-,");
            Object[] values = parse(schema, retraction ? line.substring(2) : line, csv);
            long time = eventTime < 0 || values[eventTime] == null ? 0L : ((Instant) values[eventTime]).toEpochMilli();
            rows.add(new Row(time, retraction, values));
        }
        return rows;
    }

    private static void checkHeader(StreamSchema schema, Path csv, String header) {
        String expected = schema.fields().stream().map(f -> f.name()).collect(Collectors.joining(","));
        assertThat(header.strip())
                .as("%s must start with the stream's columns, in order", csv)
                .isEqualTo(expected);
    }

    private static Object[] parse(StreamSchema schema, String line, Path csv) {
        String[] cells = cells(line);
        assertThat(cells).as("%s: %s", csv, line).hasSize(schema.fieldCount());
        Object[] values = new Object[cells.length];
        for (int i = 0; i < cells.length; i++) {
            String cell = cells[i].strip();
            Function<String, Object> convert =
                    switch (schema.field(i).type().typeName().name()) {
                        case "INT32" -> Integer::valueOf;
                        case "INT64" -> Long::valueOf;
                        case "FLOAT64" -> Double::valueOf;
                        case "BOOLEAN" -> Boolean::valueOf;
                        case "STRING" -> s -> s;
                        default -> Instant::parse;
                    };
            values[i] =
                    cell.isEmpty() && !schema.field(i).type().typeName().name().equals("STRING")
                            ? null
                            : convert.apply(cell);
        }
        return values;
    }

    /** A CSV line's cells; a cell in double quotes may hold commas, and two quotes are one. */
    private static String[] cells(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted && c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                cell.append('"');
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells.toArray(new String[0]);
    }

    private static void loadLookup(Connection connection, StreamSchema table, Path csv)
            throws IOException, SQLException {
        String columns = table.fields().stream()
                .map(f -> f.name() + " " + sqlType(f.type()))
                .collect(Collectors.joining(", "));
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + table.name());
            statement.execute("CREATE TABLE " + table.name() + " (" + columns + ", PRIMARY KEY ("
                    + table.field(0).name() + "))");
        }
        List<String> lines = Files.readAllLines(csv).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                .toList();
        checkHeader(table, csv, lines.get(0));
        String marks = String.join(", ", java.util.Collections.nCopies(table.fieldCount(), "?"));
        try (PreparedStatement insert =
                connection.prepareStatement("INSERT INTO " + table.name() + " VALUES (" + marks + ")")) {
            for (String line : lines.subList(1, lines.size())) {
                Object[] values = parse(table, line, csv);
                for (int i = 0; i < values.length; i++) {
                    insert.setObject(i + 1, values[i]);
                }
                insert.executeUpdate();
            }
        }
    }

    private static String sqlType(PravahaType type) {
        return switch (type.typeName().name()) {
            case "INT32" -> "INTEGER";
            case "INT64" -> "BIGINT";
            case "FLOAT64" -> "DOUBLE PRECISION";
            case "BOOLEAN" -> "BOOLEAN";
            default -> "VARCHAR(200)";
        };
    }

    private static PravahaType type(String spelled) {
        boolean nullable = spelled.endsWith("?");
        String name = nullable ? spelled.substring(0, spelled.length() - 1) : spelled;
        PravahaType type =
                switch (name) {
                    case "STRING" -> Types.string();
                    case "INT32" -> Types.int32();
                    case "INT64" -> Types.int64();
                    case "FLOAT64" -> Types.float64();
                    case "BOOLEAN" -> Types.bool();
                    case "TIMESTAMP" -> Types.timestamp();
                    default -> throw new IllegalArgumentException("unknown type '" + spelled + "'");
                };
        return nullable ? type.withNullable(true) : type;
    }

    /** {@code 10s}, {@code 500ms}, {@code 2m}, {@code 1h}: the spellings the studies' conf files use. */
    static Duration duration(String text) {
        String t = text.strip();
        if (t.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(t.substring(0, t.length() - 2)));
        }
        long n = Long.parseLong(t.substring(0, t.length() - 1));
        return switch (t.charAt(t.length() - 1)) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            default -> throw new IllegalArgumentException("unreadable duration '" + text + "'");
        };
    }

    private static Properties properties(Path file) throws IOException {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }

    private static Path repoRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve(STUDIES))) {
            path = path.getParent();
        }
        return path;
    }
}
