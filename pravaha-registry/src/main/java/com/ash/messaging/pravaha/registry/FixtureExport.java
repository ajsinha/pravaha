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

import java.util.List;
import java.util.Map;

/**
 * A debug session, written out as a JUnit test this repository can run offline (ADR-048,
 * design section 16.4).
 *
 * <p>This is the step that closes the loop the design's §16.4 describes: a production incident
 * becomes a permanent regression test. What it carries is everything needed to reproduce the
 * session without the production node, the source, or the checkpoint it was forked from:
 *
 * <ul>
 *   <li>the <strong>schema</strong> of every stream the query reads, declared in the generated
 *       Java rather than looked up;
 *   <li>the <strong>SQL</strong> and the key columns, registered the way any embedder registers;
 *   <li>the <strong>rows</strong> the session consumed, in the order it consumed them, with their
 *       weights and event times, and every watermark advance in its place in that sequence;
 *   <li>the <strong>expected view</strong>, exactly as the session left it.
 * </ul>
 *
 * <p><strong>Not the checkpoint.</strong> A fixture that restored a checkpoint's bytes would be a
 * test of this engine's ability to read that particular file, tied to a state format that is free
 * to change, and it would carry a copy of production state into the repository. This replays the
 * input instead, from empty state, and asserts the answer the session reached -- which is the
 * assertion that actually fails when the bug comes back. The consequence is stated in the generated
 * file: the expectation is the answer over <em>these rows from empty</em>, not over the history
 * that preceded the checkpoint.
 *
 * <p>{@code DebugFixtureExportTest} generates one, compiles it and runs it.
 */
public record FixtureExport(String className, String path, Map<String, String> files) {

    public FixtureExport {
        files = Map.copyOf(files);
    }

    /** The generated Java source, which is the file the export is for. */
    public String source() {
        return files.get(path);
    }

    /** Where a generated fixture belongs, and the package the generator writes. */
    public static final String PACKAGE = "com.ash.messaging.pravaha.it.fixtures";

    static final String DIRECTORY = "pravaha-it/src/test/java/com/ash/messaging/pravaha/it/fixtures";

    /**
     * Makes {@code name} into a Java class name, and refuses one that cannot be.
     *
     * <p>A class name comes from a person naming an incident, so it arrives as "user 42 goes
     * negative" rather than as an identifier. Letters and digits survive, everything else becomes a
     * word boundary, and a name that leaves nothing behind is refused rather than turned into
     * {@code Fixture0}: a directory of {@code Fixture0..Fixture7} is a directory nobody can read.
     */
    static String classNameFrom(String name) {
        StringBuilder out = new StringBuilder();
        boolean upper = true;
        for (char c : (name == null ? "" : name).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            } else {
                upper = true;
            }
        }
        if (out.isEmpty() || Character.isDigit(out.charAt(0))) {
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    DebugErrors.BAD_STEP,
                    "'" + name + "' does not make a Java class name. Name the fixture after the thing it "
                            + "reproduces -- 'user 42 goes negative' becomes User42GoesNegative -- starting "
                            + "with a letter.");
        }
        if (!out.toString().endsWith("Test")) {
            out.append("FixtureTest");
        }
        return out.toString();
    }

    /** A Java string literal for {@code text}, including the quotes. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** A Java literal for one replayed value, of the type the column declares. */
    static String literal(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return quote(text);
        }
        if (value instanceof Long number) {
            return number + "L";
        }
        if (value instanceof Integer number) {
            return number.toString();
        }
        if (value instanceof Short number) {
            return "(short) " + number;
        }
        if (value instanceof Byte number) {
            return "(byte) " + number;
        }
        if (value instanceof Boolean flag) {
            return flag.toString();
        }
        if (value instanceof Float number) {
            return number + "f";
        }
        if (value instanceof Double number) {
            return number + "d";
        }
        if (value instanceof byte[] bytes) {
            StringBuilder out = new StringBuilder("new byte[] {");
            for (int i = 0; i < bytes.length; i++) {
                out.append(i == 0 ? "" : ", ").append("(byte) ").append(bytes[i]);
            }
            return out.append('}').toString();
        }
        return quote(String.valueOf(value));
    }

    /** The {@code Types.xxx()} call that rebuilds one column's type. */
    static String typeCall(com.ash.messaging.pravaha.api.data.PravahaType type) {
        String call =
                switch (type.typeName()) {
                    case BOOLEAN -> "Types.bool()";
                    case INT8 -> "Types.int8()";
                    case INT16 -> "Types.int16()";
                    case INT32 -> "Types.int32()";
                    case INT64 -> "Types.int64()";
                    case FLOAT32 -> "Types.float32()";
                    case FLOAT64 -> "Types.float64()";
                    case DATE -> "Types.date()";
                    case TIME -> "Types.time()";
                    case TIMESTAMP_LTZ -> "Types.timestamp()";
                    case STRING -> "Types.string()";
                    case BYTES -> "Types.bytes()";
                    default ->
                        throw new com.ash.messaging.pravaha.api.PravahaException(
                                DebugErrors.BAD_STEP,
                                "a column of type " + type.typeName() + " cannot be written into a fixture: "
                                        + "there is no literal form for it that the generated test could "
                                        + "declare. Export a session over a query that projects it away.");
                };
        return type.nullable() ? call + ".withNullable(true)" : call;
    }

    /** A Java identifier for a stream, used as the generated schema constant's name. */
    static String constantFor(String stream) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < stream.length(); i++) {
            char c = stream.charAt(i);
            out.append(Character.isLetterOrDigit(c) ? Character.toUpperCase(c) : '_');
        }
        if (out.isEmpty() || Character.isDigit(out.charAt(0))) {
            out.insert(0, "S_");
        }
        return out.toString();
    }

    /** The lines of a list of strings, as a Java {@code List.of(...)} argument list. */
    static String listOf(List<String> items) {
        if (items.isEmpty()) {
            return "List.of()";
        }
        StringBuilder out = new StringBuilder("List.of(");
        for (int i = 0; i < items.size(); i++) {
            out.append(i == 0 ? "" : ", ").append(items.get(i));
        }
        return out.append(')').toString();
    }
}
