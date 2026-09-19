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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/**
 * An Avro <em>writer</em> schema, as the specification's JSON declares it, read into a tree this
 * plugin's own binary reader walks.
 *
 * <p>Ours rather than {@code org.apache.avro}'s, deliberately: the Kafka plugin takes no new
 * dependency, and Avro's binary encoding is a small, frozen format -- the same trade {@code
 * postgres-cdc} made for {@code pgoutput}. Only what a <em>reader</em> needs is kept: the type of
 * every branch, the fields of every record in their written order, and the logical type where one
 * changes the meaning of the bytes. Defaults, documentation, aliases and every other attribute are
 * read past; they matter to schema <em>resolution</em>, which this plugin does not do -- the schema
 * it is given is the one the bytes were written with ({@code schema.file}, or the registry's, by the
 * record's own schema id).
 *
 * <p>Named types are registered as they are declared, by full name and (when unambiguous) by simple
 * name, so a later reference to one -- including a record inside itself -- resolves. A schema that
 * does not parse, or that names a type nothing declared, is {@link Invalid}.
 *
 * <p>Parsed with the Jackson streaming parser already on this plugin's classpath, into maps and
 * lists; Jackson's tree model (a separate artifact) is not needed for a document this small.
 */
final class AvroSchema {

    /** Everything Avro's binary encoding can hold. */
    enum Kind {
        NULL,
        BOOLEAN,
        INT,
        LONG,
        FLOAT,
        DOUBLE,
        BYTES,
        STRING,
        RECORD,
        ENUM,
        ARRAY,
        MAP,
        UNION,
        FIXED
    }

    /** A schema that is not Avro, or not one this reader can use. */
    static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Invalid(String reason) {
            super(reason, null, false, false);
        }
    }

    /** One field of a record, in the order it is written. */
    record Field(String name, Node type) {}

    /** One schema node. Only a record's fields are filled in after construction, so a record can contain itself. */
    static final class Node {
        final Kind kind;
        /** {@code date}, {@code timestamp-millis}, ... or empty. */
        final String logical;
        /** A named type's full name, or the primitive's own name. */
        final String name;

        final Node element;
        final Node values;
        final List<Node> branches;
        final List<String> symbols;
        final int size;
        final int precision;
        final int scale;
        private List<Field> fields = List.of();

        private Node(
                Kind kind,
                String logical,
                String name,
                Node element,
                Node values,
                List<Node> branches,
                List<String> symbols,
                int size,
                int precision,
                int scale) {
            this.kind = kind;
            this.logical = logical;
            this.name = name;
            this.element = element;
            this.values = values;
            this.branches = branches;
            this.symbols = symbols;
            this.size = size;
            this.precision = precision;
            this.scale = scale;
        }

        List<Field> fields() {
            return fields;
        }

        /** How this node reads in a refusal: {@code long (timestamp-millis)}, {@code ["null","string"]}. */
        @Override
        public String toString() {
            if (kind == Kind.UNION) {
                return branches.toString();
            }
            String base = kind == Kind.RECORD || kind == Kind.ENUM || kind == Kind.FIXED
                    ? name
                    : kind.name().toLowerCase(java.util.Locale.ROOT);
            return logical.isEmpty() ? base : base + " (" + logical + ")";
        }
    }

    private final Map<String, Node> named = new HashMap<>();

    private AvroSchema() {}

    /** The schema {@code json} declares. */
    static Node parse(String json) {
        return new AvroSchema().node(readJson(json), "");
    }

    // ---- the schema tree ----------------------------------------------------------------------

    private Node node(Object json, String enclosingNamespace) {
        if (json instanceof String name) {
            return byName(name, enclosingNamespace);
        }
        if (json instanceof List<?> union) {
            if (union.isEmpty()) {
                throw new Invalid("a union with no branches");
            }
            List<Node> branches = new ArrayList<>();
            for (Object branch : union) {
                branches.add(node(branch, enclosingNamespace));
            }
            return new Node(Kind.UNION, "", "union", null, null, List.copyOf(branches), List.of(), 0, 0, 0);
        }
        if (!(json instanceof Map<?, ?> object)) {
            throw new Invalid("a schema must be a name, an object or a union, not " + describe(json));
        }
        Object type = object.get("type");
        if (type == null) {
            throw new Invalid("a schema object with no 'type'");
        }
        if (!(type instanceof String)) {
            // {"type": {...}} and {"type": [...]} are legal: the attributes around it are decoration.
            return node(type, enclosingNamespace);
        }
        String logical = text(object.get("logicalType"));
        String namespace = namespaceOf(object, enclosingNamespace);
        return switch ((String) type) {
            case "record", "error" -> record(object, namespace, logical);
            case "enum" -> enumeration(object, namespace, logical);
            case "fixed" -> fixed(object, namespace, logical);
            case "array" ->
                new Node(
                        Kind.ARRAY,
                        logical,
                        "array",
                        node(required(object, "items", "an array"), namespace),
                        null,
                        List.of(),
                        List.of(),
                        0,
                        0,
                        0);
            case "map" ->
                new Node(
                        Kind.MAP,
                        logical,
                        "map",
                        null,
                        node(required(object, "values", "a map"), namespace),
                        List.of(),
                        List.of(),
                        0,
                        0,
                        0);
            default -> decorated((String) type, object, logical, enclosingNamespace);
        };
    }

    /** A primitive, or a reference to a named type, carrying a logical type and a decimal's digits. */
    private Node decorated(String type, Map<?, ?> object, String logical, String enclosingNamespace) {
        Node base = byName(type, enclosingNamespace);
        if (logical.isEmpty() && base.kind != Kind.FIXED) {
            return base;
        }
        int precision = logical.equals("decimal") ? number(object, "precision", "a decimal") : 0;
        int scale = logical.equals("decimal") ? optionalNumber(object.get("scale")) : 0;
        if (logical.equals("decimal") && (precision < 1 || scale < 0 || scale > precision)) {
            throw new Invalid("a decimal with precision " + precision + " and scale " + scale);
        }
        return new Node(
                base.kind,
                logical,
                base.name,
                base.element,
                base.values,
                base.branches,
                base.symbols,
                base.size,
                precision,
                scale);
    }

    private Node record(Map<?, ?> object, String namespace, String logical) {
        String full = fullName(object, namespace, "a record");
        Node node = new Node(Kind.RECORD, logical, full, null, null, List.of(), List.of(), 0, 0, 0);
        register(full, node);
        Object fields = required(object, "fields", "a record");
        if (!(fields instanceof List<?> list)) {
            throw new Invalid("record " + full + "'s 'fields' is not an array");
        }
        List<Field> parsed = new ArrayList<>();
        for (Object field : list) {
            if (!(field instanceof Map<?, ?> member)) {
                throw new Invalid("record " + full + " has a field that is not an object");
            }
            String name = text(member.get("name"));
            if (name.isEmpty()) {
                throw new Invalid("record " + full + " has a field with no name");
            }
            parsed.add(new Field(name, node(required(member, "type", "field '" + name + "'"), namespace)));
        }
        node.fields = List.copyOf(parsed);
        return node;
    }

    private Node enumeration(Map<?, ?> object, String namespace, String logical) {
        String full = fullName(object, namespace, "an enum");
        Object symbols = required(object, "symbols", "an enum");
        if (!(symbols instanceof List<?> list) || list.isEmpty()) {
            throw new Invalid("enum " + full + "'s 'symbols' is not a non-empty array");
        }
        List<String> names = new ArrayList<>();
        for (Object symbol : list) {
            names.add(text(symbol));
        }
        Node node = new Node(Kind.ENUM, logical, full, null, null, List.of(), List.copyOf(names), 0, 0, 0);
        register(full, node);
        return node;
    }

    private Node fixed(Map<?, ?> object, String namespace, String logical) {
        String full = fullName(object, namespace, "a fixed");
        int size = number(object, "size", "a fixed");
        if (size < 0) {
            throw new Invalid("fixed " + full + " has size " + size);
        }
        int precision = logical.equals("decimal") ? number(object, "precision", "a decimal") : 0;
        int scale = logical.equals("decimal") ? optionalNumber(object.get("scale")) : 0;
        Node node = new Node(Kind.FIXED, logical, full, null, null, List.of(), List.of(), size, precision, scale);
        register(full, node);
        return node;
    }

    private void register(String full, Node node) {
        if (named.putIfAbsent(full, node) != null) {
            throw new Invalid("the name " + full + " is declared twice");
        }
        int dot = full.lastIndexOf('.');
        if (dot > 0) {
            named.putIfAbsent(full.substring(dot + 1), node);
        }
    }

    private Node byName(String name, String enclosingNamespace) {
        Node primitive =
                switch (name) {
                    case "null" -> primitive(Kind.NULL, name);
                    case "boolean" -> primitive(Kind.BOOLEAN, name);
                    case "int" -> primitive(Kind.INT, name);
                    case "long" -> primitive(Kind.LONG, name);
                    case "float" -> primitive(Kind.FLOAT, name);
                    case "double" -> primitive(Kind.DOUBLE, name);
                    case "bytes" -> primitive(Kind.BYTES, name);
                    case "string" -> primitive(Kind.STRING, name);
                    default -> null;
                };
        if (primitive != null) {
            return primitive;
        }
        Node declared = named.get(name);
        if (declared == null && !name.contains(".") && !enclosingNamespace.isEmpty()) {
            declared = named.get(enclosingNamespace + "." + name);
        }
        if (declared == null) {
            throw new Invalid("'" + name + "' is not an Avro type and nothing in this schema declares it");
        }
        return declared;
    }

    private static Node primitive(Kind kind, String name) {
        return new Node(kind, "", name, null, null, List.of(), List.of(), 0, 0, 0);
    }

    private static String namespaceOf(Map<?, ?> object, String enclosing) {
        String declared = text(object.get("namespace"));
        return declared.isEmpty() ? enclosing : declared;
    }

    private static String fullName(Map<?, ?> object, String namespace, String what) {
        String name = text(object.get("name"));
        if (name.isEmpty()) {
            throw new Invalid(what + " with no 'name'");
        }
        if (name.contains(".") || namespace.isEmpty()) {
            return name;
        }
        return namespace + "." + name;
    }

    private static Object required(Map<?, ?> object, String key, String what) {
        Object value = object.get(key);
        if (value == null) {
            throw new Invalid(what + " with no '" + key + "'");
        }
        return value;
    }

    private static int number(Map<?, ?> object, String key, String what) {
        Object value = required(object, key, what);
        if (!(value instanceof Number n)) {
            throw new Invalid(what + " whose '" + key + "' is not a number");
        }
        return n.intValue();
    }

    private static int optionalNumber(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static String text(Object value) {
        return value instanceof String s ? s : "";
    }

    private static String describe(Object json) {
        if (json == null) {
            return "null";
        }
        return json instanceof Number ? "a number" : json instanceof Boolean ? "a boolean" : "a " + json.getClass();
    }

    // ---- the smallest JSON reader that will do ------------------------------------------------

    private static final JsonFactory JSON = new JsonFactory();

    /** {@code json} as maps, lists, strings, numbers, booleans and nulls. */
    static Object readJson(String json) {
        try (JsonParser parser = JSON.createParser(json)) {
            if (parser.nextToken() == null) {
                throw new Invalid("the schema is empty");
            }
            Object value = value(parser);
            if (parser.nextToken() != null) {
                throw new Invalid("there is more after the schema's JSON");
            }
            return value;
        } catch (IOException e) {
            throw new Invalid("the schema is not valid JSON: " + firstLine(e.getMessage()));
        }
    }

    private static Object value(JsonParser parser) throws IOException {
        JsonToken token = parser.currentToken();
        return switch (token) {
            case START_OBJECT -> {
                Map<String, Object> object = new HashMap<>();
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String name = parser.currentName();
                    parser.nextToken();
                    object.put(name, value(parser));
                }
                yield object;
            }
            case START_ARRAY -> {
                List<Object> array = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    array.add(value(parser));
                }
                yield array;
            }
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT -> parser.getLongValue();
            case VALUE_NUMBER_FLOAT -> parser.getDoubleValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw new Invalid("unexpected " + token + " in the schema's JSON");
        };
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unreadable";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
