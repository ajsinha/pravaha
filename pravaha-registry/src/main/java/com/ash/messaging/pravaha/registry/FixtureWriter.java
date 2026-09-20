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
package com.ash.messaging.pravaha.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.Field;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.serving.ViewChange;

/**
 * Writes the JUnit source of a {@link FixtureExport} (ADR-048).
 *
 * <p>Separate from {@link FixtureExport}, which is the value, because generating Java is all
 * string building and it is the kind of code that grows: the export is what a caller holds and
 * this is how it was made.
 *
 * <p>The generated file registers the query the ordinary way, replays the session's script through
 * the ordinary {@code accept}/{@code commit} path, and asserts the view. It uses nothing a debug
 * session provides -- no fork, no checkpoint, no replay source -- so it keeps working when all of
 * that changes, which is the point of a regression test.
 */
final class FixtureWriter {

    /** Replay statements per generated method: a method body has a 64 KiB bytecode ceiling. */
    private static final int STATEMENTS_PER_METHOD = 100;

    /** The most script entries a fixture will carry. Past this, the file stops being readable. */
    static final int MAX_SCRIPT = 20_000;

    private FixtureWriter() {}

    static FixtureExport write(
            String className,
            String sessionId,
            String queryName,
            String sql,
            List<Integer> keyColumns,
            long checkpointId,
            Map<String, StreamSchema> inputSchemas,
            List<DebugSession.Action> script,
            List<ViewChange> expected) {

        if (script.size() > MAX_SCRIPT) {
            throw new PravahaException(
                    DebugErrors.BAD_STEP,
                    "this session has replayed " + script.size() + " rows, and a fixture carrying them would be "
                            + "a source file nobody can read or review. Export from a shorter session -- fork "
                            + "closer to the incident -- or step to the rows that matter.");
        }

        StringBuilder out = new StringBuilder(8192);
        licence(out);
        out.append("package ").append(FixtureExport.PACKAGE).append(";\n\n");
        imports(out);
        header(out, className, sessionId, queryName, checkpointId);
        out.append("class ").append(className).append(" {\n\n");

        out.append("    private static final String NAME = ")
                .append(FixtureExport.quote(queryName))
                .append(";\n\n");
        out.append("    private static final String SQL = ")
                .append(FixtureExport.quote(sql))
                .append(";\n\n");
        List<String> keys = new ArrayList<>();
        keyColumns.forEach(ordinal -> keys.add(ordinal.toString()));
        out.append("    private static final List<Integer> KEYS = ")
                .append(FixtureExport.listOf(keys))
                .append(";\n\n");

        schemas(out, inputSchemas);
        expectation(out, expected);
        testMethod(out, script.size());
        replayMethods(out, script);
        harness(out, inputSchemas);

        out.append("}\n");

        String path = FixtureExport.DIRECTORY + "/" + className + ".java";
        return new FixtureExport(className, path, Map.of(path, out.toString()));
    }

    private static void licence(StringBuilder out) {
        out.append("""
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
                """);
    }

    private static void imports(StringBuilder out) {
        out.append("""
                import java.time.Duration;
                import java.util.ArrayList;
                import java.util.List;

                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.Timeout;

                import com.ash.messaging.pravaha.api.data.RowWriter;
                import com.ash.messaging.pravaha.api.data.StreamSchema;
                import com.ash.messaging.pravaha.api.data.Types;
                import com.ash.messaging.pravaha.common.arena.RowArena;
                import com.ash.messaging.pravaha.common.memory.MemoryAccess;
                import com.ash.messaging.pravaha.common.row.BinaryRowView;
                import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
                import com.ash.messaging.pravaha.common.row.RowLayout;
                import com.ash.messaging.pravaha.registry.QueryRegistry;
                import com.ash.messaging.pravaha.registry.RegisteredQuery;
                import com.ash.messaging.pravaha.security.Principal;
                import com.ash.messaging.pravaha.serving.ViewCatalog;
                import com.ash.messaging.pravaha.serving.ViewChange;

                import static org.assertj.core.api.Assertions.assertThat;

                """);
    }

    private static void header(StringBuilder out, String className, String sessionId, String query, long checkpoint) {
        out.append("/**\n")
                .append(" * Generated from debug session ")
                .append(sessionId)
                .append(", forked from checkpoint ")
                .append(checkpoint)
                .append(" of '")
                .append(query)
                .append("'.\n")
                .append(" *\n")
                .append(" * <p>Written by the time-travel debugger (ADR-048, design section 16.4): the rows the\n")
                .append(" * session stepped through, the SQL it stepped them through, and the view it ended with.\n")
                .append(" * Nothing here needs the node it came from, the source it read, or the checkpoint it\n")
                .append(" * was forked from -- it registers the query and replays the rows from empty state.\n")
                .append(" *\n")
                .append(" * <p><strong>What it asserts, exactly.</strong> The answer over <em>these rows, from\n")
                .append(" * empty</em>. The session was forked from a checkpoint, so the running query's view\n")
                .append(" * also held whatever came before it; this fixture does not, and its expectation is\n")
                .append(" * the one recorded from the fork's own view, which started empty in the same sense.\n")
                .append(" * That is what makes it reproducible: a fixture pinned to a checkpoint's bytes would\n")
                .append(" * be a test of a state file format rather than of the query's arithmetic.\n")
                .append(" */\n");
    }

    private static void schemas(StringBuilder out, Map<String, StreamSchema> inputSchemas) {
        inputSchemas.forEach((stream, schema) -> {
            out.append("    private static final StreamSchema ")
                    .append(FixtureExport.constantFor(stream))
                    .append(" = StreamSchema.builder(")
                    .append(FixtureExport.quote(schema.name()))
                    .append(")\n");
            for (Field field : schema.fields()) {
                out.append("            .field(")
                        .append(FixtureExport.quote(field.name()))
                        .append(", ")
                        .append(FixtureExport.typeCall(field.type()))
                        .append(")\n");
            }
            out.append("            .build();\n\n");
        });
    }

    private static void expectation(StringBuilder out, List<ViewChange> expected) {
        List<String> rows = new ArrayList<>();
        expected.stream().map(ViewChange::toString).sorted().forEach(row -> rows.add(FixtureExport.quote(row)));
        out.append("    /** The view the session ended with: one line per row, weight first. */\n");
        out.append("    private static final List<String> EXPECTED = ");
        if (rows.isEmpty()) {
            out.append("List.of();\n\n");
            return;
        }
        out.append("List.of(\n");
        for (int i = 0; i < rows.size(); i++) {
            out.append("            ").append(rows.get(i)).append(i == rows.size() - 1 ? ");\n\n" : ",\n");
        }
    }

    private static void testMethod(StringBuilder out, int scriptSize) {
        int methods = (scriptSize + STATEMENTS_PER_METHOD - 1) / STATEMENTS_PER_METHOD;
        out.append("    @Test\n")
                .append("    @Timeout(180)\n")
                .append("    void theQueryAnswersWhatTheDebugSessionRecorded() {\n")
                .append("        try (Harness harness = new Harness()) {\n");
        for (int index = 0; index < methods; index++) {
            out.append("            replay").append(index).append("(harness);\n");
        }
        out.append("            assertThat(harness.view())\n")
                .append("                    .as(\"the view this session recorded, replayed from empty\")\n")
                .append("                    .containsExactlyInAnyOrderElementsOf(EXPECTED);\n")
                .append("        }\n")
                .append("    }\n\n");
    }

    private static void replayMethods(StringBuilder out, List<DebugSession.Action> script) {
        int methods = Math.max(1, (script.size() + STATEMENTS_PER_METHOD - 1) / STATEMENTS_PER_METHOD);
        for (int index = 0; index < methods; index++) {
            out.append("    private static void replay").append(index).append("(Harness harness) {\n");
            int from = index * STATEMENTS_PER_METHOD;
            int to = Math.min(script.size(), from + STATEMENTS_PER_METHOD);
            for (int i = from; i < to; i++) {
                DebugSession.Action action = script.get(i);
                if (action.isWatermark()) {
                    out.append("        harness.watermark(")
                            .append(action.watermarkNanos())
                            .append("L);\n");
                    continue;
                }
                ReplaySource.ReplayRow row = action.row();
                out.append("        harness.row(")
                        .append(FixtureExport.quote(row.stream()))
                        .append(", ")
                        .append(row.weight())
                        .append("L, ")
                        .append(row.eventTimeNanos())
                        .append("L, ")
                        .append(row.sequence())
                        .append("L, new Object[] {");
                Object[] values = row.values();
                for (int ordinal = 0; ordinal < values.length; ordinal++) {
                    out.append(ordinal == 0 ? "" : ", ").append(FixtureExport.literal(values[ordinal]));
                }
                out.append("});\n");
            }
            out.append("    }\n\n");
        }
    }

    private static void harness(StringBuilder out, Map<String, StreamSchema> inputSchemas) {
        List<String> constants = new ArrayList<>();
        inputSchemas.keySet().forEach(stream -> constants.add(FixtureExport.constantFor(stream)));
        out.append("    /** The registry, the query and the arena, opened once and closed with the test. */\n")
                .append("    private static final class Harness implements AutoCloseable {\n\n")
                .append("        private final QueryRegistry registry = new QueryRegistry(new ViewCatalog(), ")
                .append(String.join(", ", constants))
                .append(");\n")
                .append("        private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 64);\n")
                .append("        private final RegisteredQuery query;\n\n")
                .append("        Harness() {\n")
                .append("            query = registry.register(NAME, SQL, KEYS, Principal.ANONYMOUS);\n")
                .append("        }\n\n")
                .append("        /** One source row, written and applied exactly as an embedder applies one. */\n")
                .append(
                        "        void row(String stream, long weight, long eventTime, long sequence, Object[] values) {\n")
                .append("            StreamSchema schema = schemaOf(stream);\n")
                .append("            RowLayout layout = RowLayout.of(schema);\n")
                .append("            BinaryRowWriter writer = new BinaryRowWriter(layout);\n")
                .append("            BinaryRowView view = new BinaryRowView(layout);\n")
                .append("            long handle = arena.allocate(layout.rowSize(1024));\n")
                .append("            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));\n")
                .append("            for (int ordinal = 0; ordinal < values.length; ordinal++) {\n")
                .append("                set(writer, schema, ordinal, values[ordinal]);\n")
                .append("            }\n")
                .append(
                        "            writer.weight(weight).eventTimestampNanos(eventTime).sequence(sequence).commit();\n")
                .append("            arena.trimTo(handle, writer.sizeSoFar());\n")
                .append(
                        "            query.accept(stream, view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));\n")
                .append("            query.awaitApplied(Duration.ofSeconds(30));\n")
                .append("            query.commit();\n")
                .append("        }\n\n")
                .append("        void watermark(long nanos) {\n")
                .append("            query.advanceWatermark(nanos);\n")
                .append("            query.commit();\n")
                .append("        }\n\n")
                .append("        List<String> view() {\n")
                .append("            List<String> rows = new ArrayList<>();\n")
                .append("            for (ViewChange change : query.view().committedRows()) {\n")
                .append("                rows.add(change.toString());\n")
                .append("            }\n")
                .append("            return rows;\n")
                .append("        }\n\n")
                .append("        private static StreamSchema schemaOf(String stream) {\n");
        inputSchemas.forEach((stream, schema) -> out.append("            if (stream.equals(")
                .append(FixtureExport.quote(stream))
                .append(")) {\n                return ")
                .append(FixtureExport.constantFor(stream))
                .append(";\n            }\n"));
        out.append("            throw new IllegalArgumentException(\"no schema for stream \" + stream);\n")
                .append("        }\n\n")
                .append(
                        "        private static void set(RowWriter writer, StreamSchema schema, int ordinal, Object value) {\n")
                .append("            if (value == null) {\n")
                .append("                writer.setNull(ordinal);\n")
                .append("                return;\n")
                .append("            }\n")
                .append("            switch (schema.field(ordinal).type().typeName()) {\n")
                .append("                case BOOLEAN -> writer.setBoolean(ordinal, (Boolean) value);\n")
                .append("                case INT8 -> writer.setByte(ordinal, ((Number) value).byteValue());\n")
                .append("                case INT16 -> writer.setShort(ordinal, ((Number) value).shortValue());\n")
                .append("                case INT32, DATE -> writer.setInt(ordinal, ((Number) value).intValue());\n")
                .append(
                        "                case INT64, TIME, TIMESTAMP_LTZ -> writer.setLong(ordinal, ((Number) value).longValue());\n")
                .append("                case FLOAT32 -> writer.setFloat(ordinal, ((Number) value).floatValue());\n")
                .append("                case FLOAT64 -> writer.setDouble(ordinal, ((Number) value).doubleValue());\n")
                .append("                case STRING -> writer.setString(ordinal, String.valueOf(value));\n")
                .append("                case BYTES -> writer.setBytes(ordinal, (byte[]) value);\n")
                .append("                default -> throw new IllegalArgumentException(\n")
                .append(
                        "                        \"a fixture cannot write a \" + schema.field(ordinal).type().typeName());\n")
                .append("            }\n")
                .append("        }\n\n")
                .append("        @Override\n")
                .append("        public void close() {\n")
                .append("            registry.close();\n")
                .append("            arena.close();\n")
                .append("        }\n")
                .append("    }\n");
    }
}
