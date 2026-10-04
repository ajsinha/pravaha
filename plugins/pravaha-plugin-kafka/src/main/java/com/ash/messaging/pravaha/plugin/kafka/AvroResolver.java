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
package com.ash.messaging.pravaha.plugin.kafka;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Undecodable;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Unmappable;

/**
 * Avro schema resolution, as the specification's "Schema Resolution" section defines it: bytes
 * written with one schema (the <em>writer's</em>) read as another (the <em>reader's</em>), compiled
 * once into the {@link Step}s that read a record.
 *
 * <p><strong>What resolves</strong>, and nothing else:
 *
 * <ul>
 *   <li>the same primitive, or a promotion the specification allows: {@code int} to {@code long},
 *       {@code float} or {@code double}; {@code long} to {@code float} or {@code double}; {@code float}
 *       to {@code double}; {@code string} to {@code bytes} and {@code bytes} to {@code string};
 *   <li>records, enums and fixeds whose unqualified names are the same, or whose writer name is one of
 *       the reader type's {@code aliases};
 *   <li>a record's fields by name, or by one of the reader field's {@code aliases}. A writer field the
 *       reader does not have is read past; a reader field the writer does not have takes the reader
 *       field's {@code default};
 *   <li>an enum whose every writer symbol is a reader symbol, or whose reader declares a {@code
 *       default} symbol for the rest;
 *   <li>a writer union, branch by branch against the reader; a reader union, by its first branch that
 *       matches the writer.
 * </ul>
 *
 * <p><strong>What is refused, and when.</strong> Everything the specification calls "an error is
 * signalled" is refused here, when the two schemas are compiled, and not when a record happens to
 * reach it: a reader field with no default that the writer lacks, a writer enum symbol with nowhere to
 * go, a writer union branch that resolves against nothing in the reader, fixeds of different sizes,
 * two types that neither match nor promote. A logical type must be the same on both sides, and a
 * decimal's precision and scale too: the specification falls back to the underlying type when they
 * differ, and a {@code DECIMAL} read as raw bytes is exactly the garbage this refusal exists to stop.
 * The refusal is {@link Unmappable}, so it is {@code PRV-5108} for {@code schema.file} and a dead letter
 * naming the schema id for the registry's schemas, like every other schema this source cannot use.
 *
 * <p>A reader schema identical to the writer's resolves to itself, which is how a binding without
 * {@code schema.reader.file} reads: one path, not two.
 */
final class AvroResolver {

    /** One compiled piece of reading: consumes the writer's bytes for one value and fills columns. */
    interface Step {
        void run(AvroBinary in, Object[] out) throws Undecodable;
    }

    /** Where a reader value goes: nowhere, one column, or the columns under a record's fields. */
    sealed interface Target permits Leaf, Group {}

    /** One column, by ordinal. */
    record Leaf(int ordinal) implements Target {}

    /**
     * The columns under a reader record: by the reader field's index, and every ordinal below, which a
     * {@code null} in a union around the record fills with null.
     */
    @SuppressWarnings("ArrayRecordComponent") // carries the array; nothing compares or hashes one
    record Group(Map<Integer, Target> byField, int[] ordinals) implements Target {}

    /** Turns a resolved reader value into its column's value; {@link AvroRowReader} owns the rules. */
    interface Columns {
        Object column(Object value, AvroSchema.Node reader, int ordinal) throws Undecodable;
    }

    private final Columns columns;
    private final Set<String> validated = new HashSet<>();

    private AvroResolver(Columns columns) {
        this.columns = columns;
    }

    /** The steps that read one {@code writer} record as {@code reader} and fill {@code target}. */
    static Step compile(AvroSchema.Node writer, AvroSchema.Node reader, Group target, Columns columns) {
        return new AvroResolver(columns).step(writer, reader, target, "the record");
    }

    // ---- compiling ----------------------------------------------------------------------------

    private Step step(AvroSchema.Node w, AvroSchema.Node r, Target t, String where) {
        if (t == null) {
            validate(w, r, where);
            return (in, out) -> in.skip(w);
        }
        if (w.kind == AvroSchema.Kind.UNION) {
            Step[] branches = new Step[w.branches.size()];
            for (int i = 0; i < branches.length; i++) {
                branches[i] = step(w.branches.get(i), r, t, where + " (writer branch " + w.branches.get(i) + ")");
            }
            return (in, out) -> branches[in.branchIndex(w)].run(in, out);
        }
        if (r.kind == AvroSchema.Kind.UNION) {
            return step(w, chosenBranch(w, r, where), t, where);
        }
        if (w.kind == AvroSchema.Kind.NULL && r.kind == AvroSchema.Kind.NULL) {
            int[] ordinals = ordinals(t);
            return (in, out) -> {
                for (int ordinal : ordinals) {
                    out[ordinal] = null;
                }
            };
        }
        if (w.kind == AvroSchema.Kind.RECORD && r.kind == AvroSchema.Kind.RECORD) {
            if (!named(w, r)) {
                throw unresolved(where, mismatch(w, r));
            }
            return record(w, r, t, where);
        }
        if (t instanceof Group) {
            throw unresolved(where, "the reader's " + r + " holds columns, and the writer's " + w + " is not a record");
        }
        int ordinal = ((Leaf) t).ordinal();
        String why = mismatch(w, r);
        if (why != null) {
            throw unresolved(where, why);
        }
        return leaf(w, r, ordinal, where);
    }

    private Step record(AvroSchema.Node w, AvroSchema.Node r, Target t, String where) {
        Map<Integer, Target> byField = t instanceof Group group ? group.byField() : Map.of();
        List<AvroSchema.Field> readerFields = r.fields();
        boolean[] written = new boolean[readerFields.size()];
        List<Step> steps = new ArrayList<>();
        for (AvroSchema.Field wf : w.fields()) {
            int at = readerFieldFor(wf, r);
            if (at < 0) {
                steps.add((in, out) -> in.skip(wf.type()));
                continue;
            }
            if (written[at]) {
                throw unresolved(
                        where,
                        "two writer fields resolve to reader field '"
                                + readerFields.get(at).name() + "' of " + r.name);
            }
            written[at] = true;
            steps.add(step(
                    wf.type(),
                    readerFields.get(at).type(),
                    byField.get(at),
                    "field '" + readerFields.get(at).name() + "' of " + r.name));
        }
        List<int[]> defaultOrdinals = new ArrayList<>();
        List<Object[]> defaultValues = new ArrayList<>();
        for (int at = 0; at < readerFields.size(); at++) {
            if (written[at]) {
                continue;
            }
            AvroSchema.Field rf = readerFields.get(at);
            if (!rf.hasDefault()) {
                throw unresolved(
                        where,
                        "reader field '" + rf.name() + "' of " + r.name + " has no default, and the " + "writer's "
                                + w.name + " has no field of that name or of its aliases " + rf.aliases());
            }
            Target below = byField.get(at);
            if (below != null) {
                List<Integer> ordinals = new ArrayList<>();
                List<Object> values = new ArrayList<>();
                defaults(
                        rf.type(),
                        rf.defaultValue(),
                        below,
                        ordinals,
                        values,
                        "the default of reader field '" + rf.name() + "' of " + r.name);
                defaultOrdinals.add(
                        ordinals.stream().mapToInt(Integer::intValue).toArray());
                defaultValues.add(values.toArray());
            }
        }
        Step[] reads = steps.toArray(new Step[0]);
        int[][] fillOrdinals = defaultOrdinals.toArray(new int[0][]);
        Object[][] fillValues = defaultValues.toArray(new Object[0][]);
        return (in, out) -> {
            for (Step read : reads) {
                read.run(in, out);
            }
            for (int i = 0; i < fillOrdinals.length; i++) {
                for (int j = 0; j < fillOrdinals[i].length; j++) {
                    out[fillOrdinals[i][j]] = fillValues[i][j];
                }
            }
        };
    }

    private Step leaf(AvroSchema.Node w, AvroSchema.Node r, int ordinal, String where) {
        if (w.kind == AvroSchema.Kind.ENUM) {
            String[] symbols = enumMapping(w, r, where);
            return (in, out) -> {
                int index = in.readInt();
                if (index < 0 || index >= symbols.length) {
                    throw new Undecodable("an enum names symbol " + index + " of " + symbols.length + " in " + w.name);
                }
                out[ordinal] = columns.column(symbols[index], r, ordinal);
            };
        }
        if (w.kind == AvroSchema.Kind.FIXED) {
            return (in, out) -> out[ordinal] = columns.column(in.readFixed(w.size, "a fixed"), r, ordinal);
        }
        AvroSchema.Kind to = r.kind;
        return switch (w.kind) {
            case BOOLEAN -> (in, out) -> out[ordinal] = columns.column(in.readBoolean(), r, ordinal);
            case INT, LONG ->
                (in, out) -> {
                    long raw = w.kind == AvroSchema.Kind.INT ? in.readInt() : in.readLong();
                    Object promoted =
                            switch (to) {
                                case FLOAT -> (float) raw;
                                case DOUBLE -> (double) raw;
                                default -> raw;
                            };
                    out[ordinal] = columns.column(promoted, r, ordinal);
                };
            case FLOAT ->
                (in, out) -> {
                    float single = in.readFloat();
                    out[ordinal] = columns.column(
                            to == AvroSchema.Kind.DOUBLE ? (Object) (double) single : single, r, ordinal);
                };
            case DOUBLE -> (in, out) -> out[ordinal] = columns.column(in.readDouble(), r, ordinal);
            case STRING ->
                (in, out) -> {
                    String text = in.readString();
                    out[ordinal] = columns.column(
                            to == AvroSchema.Kind.BYTES ? text.getBytes(StandardCharsets.UTF_8) : text, r, ordinal);
                };
            case BYTES ->
                (in, out) -> {
                    byte[] raw = in.readBytes();
                    out[ordinal] = columns.column(
                            to == AvroSchema.Kind.STRING ? new String(raw, StandardCharsets.UTF_8) : raw, r, ordinal);
                };
            default -> throw unresolved(where, "the writer's " + w + " fills no column");
        };
    }

    // ---- validating what no column reads ------------------------------------------------------

    /**
     * Refuses what the specification would signal as an error even where no column reads it: a
     * schema pair that does not resolve is the wrong schema, whichever of its fields a binding uses.
     */
    private void validate(AvroSchema.Node w, AvroSchema.Node r, String where) {
        if (w.kind == AvroSchema.Kind.UNION) {
            for (AvroSchema.Node branch : w.branches) {
                validate(branch, r, where + " (writer branch " + branch + ")");
            }
            return;
        }
        if (r.kind == AvroSchema.Kind.UNION) {
            validate(w, chosenBranch(w, r, where), where);
            return;
        }
        switch (w.kind) {
            case RECORD -> {
                if (r.kind != AvroSchema.Kind.RECORD || !named(w, r)) {
                    throw unresolved(
                            where,
                            mismatch(w, r) == null
                                    ? "the writer's " + w + " is not the reader's " + r
                                    : mismatch(w, r));
                }
                if (!validated.add(w.name + "\u0000" + r.name)) {
                    return;
                }
                boolean[] written = new boolean[r.fields().size()];
                for (AvroSchema.Field wf : w.fields()) {
                    int at = readerFieldFor(wf, r);
                    if (at >= 0) {
                        written[at] = true;
                        validate(
                                wf.type(),
                                r.fields().get(at).type(),
                                "field '" + r.fields().get(at).name() + "' of " + r.name);
                    }
                }
                for (int at = 0; at < written.length; at++) {
                    AvroSchema.Field rf = r.fields().get(at);
                    if (!written[at] && !rf.hasDefault()) {
                        throw unresolved(
                                where,
                                "reader field '" + rf.name() + "' of " + r.name + " has no default, "
                                        + "and the writer's " + w.name + " has no field of that name or of its aliases "
                                        + rf.aliases());
                    }
                }
            }
            case ARRAY -> {
                if (r.kind != AvroSchema.Kind.ARRAY) {
                    throw unresolved(where, "the writer's array is the reader's " + r);
                }
                validate(w.element, r.element, where + " (its items)");
            }
            case MAP -> {
                if (r.kind != AvroSchema.Kind.MAP) {
                    throw unresolved(where, "the writer's map is the reader's " + r);
                }
                validate(w.values, r.values, where + " (its values)");
            }
            case ENUM -> enumMapping(w, r, where);
            default -> {
                String why = mismatch(w, r);
                if (why != null) {
                    throw unresolved(where, why);
                }
            }
        }
    }

    // ---- the specification's rules ------------------------------------------------------------

    /**
     * Why a non-union writer type cannot be read as a non-union reader type, or null when it can:
     * names for named types, promotions for primitives, and the logical type on both sides.
     */
    private static String mismatch(AvroSchema.Node w, AvroSchema.Node r) {
        boolean kinds =
                switch (w.kind) {
                    case INT ->
                        r.kind == AvroSchema.Kind.INT
                                || r.kind == AvroSchema.Kind.LONG
                                || r.kind == AvroSchema.Kind.FLOAT
                                || r.kind == AvroSchema.Kind.DOUBLE;
                    case LONG ->
                        r.kind == AvroSchema.Kind.LONG
                                || r.kind == AvroSchema.Kind.FLOAT
                                || r.kind == AvroSchema.Kind.DOUBLE;
                    case FLOAT -> r.kind == AvroSchema.Kind.FLOAT || r.kind == AvroSchema.Kind.DOUBLE;
                    case STRING, BYTES -> r.kind == AvroSchema.Kind.STRING || r.kind == AvroSchema.Kind.BYTES;
                    default -> r.kind == w.kind;
                };
        if (!kinds) {
            return "the writer's " + w + " is not the reader's " + r + ", and does not promote to it";
        }
        if ((w.kind == AvroSchema.Kind.RECORD || w.kind == AvroSchema.Kind.ENUM || w.kind == AvroSchema.Kind.FIXED)
                && !named(w, r)) {
            return "the writer's " + w.name + " is not the reader's " + r.name + ", and is not one of its aliases "
                    + r.aliases();
        }
        if (w.kind == AvroSchema.Kind.FIXED && w.size != r.size) {
            return "the writer's fixed " + w.name + " is " + w.size + " bytes and the reader's is " + r.size;
        }
        if (!w.logical.equals(r.logical)) {
            return "the writer's " + w + " and the reader's " + r + " do not carry the same logical type, so the "
                    + "value would change meaning";
        }
        if (w.logical.equals("decimal") && (w.precision != r.precision || w.scale != r.scale)) {
            return "the writer's decimal(" + w.precision + "," + w.scale + ") is not the reader's decimal("
                    + r.precision + "," + r.scale + ")";
        }
        return null;
    }

    /** Whether the writer's named type is the reader's: the same unqualified name, or a reader alias. */
    private static boolean named(AvroSchema.Node w, AvroSchema.Node r) {
        if (unqualified(w.name).equals(unqualified(r.name))) {
            return true;
        }
        for (String alias : r.aliases()) {
            if (alias.equals(w.name) || unqualified(alias).equals(unqualified(w.name))) {
                return true;
            }
        }
        return false;
    }

    private static String unqualified(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    /** The reader field a writer field resolves to, by name then by the reader field's aliases, or -1. */
    private static int readerFieldFor(AvroSchema.Field wf, AvroSchema.Node r) {
        List<AvroSchema.Field> fields = r.fields();
        for (int at = 0; at < fields.size(); at++) {
            if (fields.get(at).name().equals(wf.name())) {
                return at;
            }
        }
        for (int at = 0; at < fields.size(); at++) {
            if (fields.get(at).aliases().contains(wf.name())) {
                return at;
            }
        }
        return -1;
    }

    /** The reader's first union branch the writer's type matches, as the specification picks it. */
    private static AvroSchema.Node chosenBranch(AvroSchema.Node w, AvroSchema.Node r, String where) {
        for (AvroSchema.Node branch : r.branches) {
            if (branch.kind == AvroSchema.Kind.UNION) {
                continue;
            }
            boolean shallow =
                    switch (w.kind) {
                        case ARRAY, MAP, NULL -> branch.kind == w.kind;
                        default -> mismatch(w, branch) == null;
                    };
            if (shallow) {
                return branch;
            }
        }
        throw unresolved(where, "the writer's " + w + " matches no branch of the reader's union " + r);
    }

    /** Each writer symbol's reader symbol, or the refusal naming the symbols with nowhere to go. */
    private static String[] enumMapping(AvroSchema.Node w, AvroSchema.Node r, String where) {
        if (r.kind != AvroSchema.Kind.ENUM || !named(w, r)) {
            String why = mismatch(w, r);
            throw unresolved(where, why != null ? why : "the writer's enum " + w.name + " is the reader's " + r);
        }
        String[] mapped = new String[w.symbols.size()];
        List<String> lost = new ArrayList<>();
        for (int i = 0; i < mapped.length; i++) {
            String symbol = w.symbols.get(i);
            if (r.symbols.contains(symbol)) {
                mapped[i] = symbol;
            } else if (r.enumDefault() != null) {
                mapped[i] = r.enumDefault();
            } else {
                lost.add(symbol);
            }
        }
        if (!lost.isEmpty()) {
            throw unresolved(
                    where,
                    "the writer's enum " + w.name + " has symbols " + lost + " that the reader's " + r.name
                            + " does not, and the reader declares no default symbol");
        }
        return mapped;
    }

    // ---- defaults -----------------------------------------------------------------------------

    /**
     * The column values a reader field's default gives the columns in {@code target}, read from the
     * default's JSON as the specification encodes it: a union's default is its first branch's, bytes
     * and fixed are strings of code points 0 to 255, a record's is an object of its fields.
     */
    private void defaults(
            AvroSchema.Node r, Object json, Target target, List<Integer> ordinals, List<Object> values, String where) {
        AvroSchema.Node type = r.kind == AvroSchema.Kind.UNION ? r.branches.get(0) : r;
        if (type.kind == AvroSchema.Kind.NULL) {
            if (json != null) {
                throw unresolved(where, "a null default must be JSON null");
            }
            for (int ordinal : ordinals(target)) {
                ordinals.add(ordinal);
                values.add(null);
            }
            return;
        }
        if (type.kind == AvroSchema.Kind.RECORD) {
            if (!(json instanceof Map<?, ?> object)) {
                throw unresolved(where, "a record's default must be a JSON object");
            }
            Map<Integer, Target> byField = target instanceof Group group ? group.byField() : Map.of();
            for (Map.Entry<Integer, Target> below : byField.entrySet()) {
                AvroSchema.Field field = type.fields().get(below.getKey());
                Object member;
                if (object.containsKey(field.name())) {
                    member = object.get(field.name());
                } else if (field.hasDefault()) {
                    member = field.defaultValue();
                } else {
                    throw unresolved(where, "it has no value for field '" + field.name() + "', which has no default");
                }
                defaults(field.type(), member, below.getValue(), ordinals, values, where + "." + field.name());
            }
            return;
        }
        if (!(target instanceof Leaf leaf)) {
            throw unresolved(where, "the reader's " + type + " holds columns and is not a record");
        }
        Object value =
                switch (type.kind) {
                    case BOOLEAN -> json instanceof Boolean b ? b : null;
                    case INT, LONG ->
                        json instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                                ? n.longValue()
                                : null;
                    case FLOAT -> json instanceof Number n ? n.floatValue() : null;
                    case DOUBLE -> json instanceof Number n ? n.doubleValue() : null;
                    case STRING -> json instanceof String text ? text : null;
                    case ENUM -> json instanceof String text && type.symbols.contains(text) ? text : null;
                    case BYTES, FIXED -> json instanceof String text ? latin1(text, type, where) : null;
                    default -> null;
                };
        if (value == null) {
            throw unresolved(where, "the default " + json + " is not a " + type);
        }
        try {
            values.add(columns.column(value, type, leaf.ordinal()));
        } catch (Undecodable e) {
            throw unresolved(where, e.getMessage());
        }
        ordinals.add(leaf.ordinal());
    }

    private static byte[] latin1(String text, AvroSchema.Node type, String where) {
        byte[] bytes = new byte[text.length()];
        for (int i = 0; i < bytes.length; i++) {
            char c = text.charAt(i);
            if (c > 0xFF) {
                throw unresolved(where, "a bytes default holds only code points 0 to 255");
            }
            bytes[i] = (byte) c;
        }
        if (type.kind == AvroSchema.Kind.FIXED && bytes.length != type.size) {
            throw unresolved(
                    where, "the default is " + bytes.length + " bytes and fixed " + type.name + " is " + type.size);
        }
        return bytes;
    }

    private static int[] ordinals(Target target) {
        return target instanceof Leaf leaf ? new int[] {leaf.ordinal()} : ((Group) target).ordinals();
    }

    private static Unmappable unresolved(String where, String why) {
        return new Unmappable(
                "the writer schema cannot be resolved against the reader schema at " + where + ": " + why);
    }
}
