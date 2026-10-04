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
package com.ash.messaging.pravaha.server.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What {@code api/openapi.lock.json} records, and which differences from it break a client.
 *
 * <p>Per operation: its statuses, its parameters, and the shape of its request body and of each
 * response body. Per body schema: every field, flattened to a path -- {@code $ref}s resolved, a
 * nested object's fields as {@code a.b}, an array's items as {@code a[]}, a map's values as
 * {@code a{}} -- with its type and whether it is required (OPENAPILOCK-1). Before, the lock held no
 * body fields at all, so renaming, removing or retyping a DTO field passed the contract test.
 *
 * <p>Descriptions, examples and ordering stay out: a lock that fails for cosmetic reasons is one
 * people learn to regenerate without reading.
 */
final class OpenApiLock {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenApiLock() {}

    // ------------------------------------------------------------------------------ summarising

    /** The lock's content for {@code document}, as the file holds it. */
    static String summarise(JsonNode document) {
        return new Summariser(document).summarise();
    }

    private static final class Summariser {
        private final JsonNode document;
        private final JsonNode components;
        private final Map<String, Map<String, String>> schemas = new TreeMap<>();

        Summariser(JsonNode document) {
            this.document = document;
            this.components = document.path("components").path("schemas");
        }

        String summarise() {
            Map<String, Object> surface = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it =
                            document.path("paths").properties().iterator();
                    it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                Map<String, Object> methods = new TreeMap<>();
                for (Iterator<Map.Entry<String, JsonNode>> m =
                                entry.getValue().properties().iterator();
                        m.hasNext(); ) {
                    Map.Entry<String, JsonNode> method = m.next();
                    JsonNode op = method.getValue();
                    Map<String, Object> operation = new TreeMap<>();
                    operation.put("responses", OpenApiContractTest.names(op.path("responses")));
                    List<String> parameters = new ArrayList<>();
                    op.path("parameters")
                            .forEach(p -> parameters.add(
                                    p.path("in").asText() + ":" + p.path("name").asText()));
                    Collections.sort(parameters);
                    operation.put("parameters", parameters);
                    operation.put("hasBody", !op.path("requestBody").isMissingNode());
                    String label = method.getKey().toUpperCase(java.util.Locale.ROOT) + " " + entry.getKey();
                    if (!op.path("requestBody").isMissingNode()) {
                        String shape = bodyShape(op.path("requestBody").path("content"), label + " request");
                        if (shape != null) {
                            operation.put("requestBody", shape);
                        }
                    }
                    Map<String, String> responseBodies = new TreeMap<>();
                    for (Iterator<Map.Entry<String, JsonNode>> r =
                                    op.path("responses").properties().iterator();
                            r.hasNext(); ) {
                        Map.Entry<String, JsonNode> response = r.next();
                        String shape = bodyShape(response.getValue().path("content"), label + " " + response.getKey());
                        if (shape != null) {
                            responseBodies.put(response.getKey(), shape);
                        }
                    }
                    if (!responseBodies.isEmpty()) {
                        operation.put("responseBodies", responseBodies);
                    }
                    methods.put(method.getKey(), operation);
                }
                surface.put(entry.getKey(), methods);
            }
            try {
                ObjectNode root = JSON.createObjectNode();
                root.set("paths", JSON.valueToTree(surface));
                root.set("schemas", JSON.valueToTree(schemas));
                return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
            } catch (Exception e) {
                throw new IllegalStateException("cannot summarise the OpenAPI document", e);
            }
        }

        /** One shape for a body's content; media types that disagree are each named. */
        private String bodyShape(JsonNode content, String inlineName) {
            Map<String, String> byType = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = content.properties().iterator(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> media = it.next();
                if (media.getValue().has("schema")) {
                    byType.put(media.getKey(), shape(media.getValue().path("schema"), inlineName));
                }
            }
            if (byType.isEmpty()) {
                return null;
            }
            if (new HashSet<>(byType.values()).size() == 1) {
                return byType.values().iterator().next();
            }
            List<String> each = new ArrayList<>();
            byType.forEach((type, shape) -> each.add(type + "=" + shape));
            return String.join("; ", each);
        }

        /**
         * A body's top-level shape: a schema's name (its fields recorded under {@code "schemas"}),
         * {@code X[]}, {@code map<X>}, or a scalar type.
         */
        private String shape(JsonNode schema, String inlineName) {
            if (schema.has("$ref")) {
                String name = refName(schema);
                JsonNode target = resolve(schema);
                if (!isObject(target)) {
                    return typeName(target);
                }
                schemas.computeIfAbsent(name, n -> flatten(target, Set.of(n)));
                return name;
            }
            JsonNode resolved = resolve(schema);
            if (isArray(resolved)) {
                return shape(resolved.path("items"), inlineName) + "[]";
            }
            if (isMap(resolved)) {
                return "map<" + shape(resolved.path("additionalProperties"), inlineName) + ">";
            }
            if (resolved.has("properties")) {
                String name = "(inline) " + inlineName;
                schemas.put(name, flatten(resolved, Set.of()));
                return name;
            }
            return typeName(resolved);
        }

        private Map<String, String> flatten(JsonNode object, Set<String> visiting) {
            Map<String, String> out = new TreeMap<>();
            properties(object, "", out, visiting);
            return out;
        }

        private void properties(JsonNode object, String prefix, Map<String, String> out, Set<String> visiting) {
            JsonNode merged = merge(object);
            Set<String> required = new HashSet<>();
            merged.path("required").forEach(r -> required.add(r.asText()));
            for (Iterator<Map.Entry<String, JsonNode>> it =
                            merged.path("properties").properties().iterator();
                    it.hasNext(); ) {
                Map.Entry<String, JsonNode> property = it.next();
                String path = prefix.isEmpty() ? property.getKey() : prefix + "." + property.getKey();
                field(path, property.getValue(), required.contains(property.getKey()), out, visiting);
            }
        }

        private void field(
                String path, JsonNode schema, boolean required, Map<String, String> out, Set<String> visiting) {
            String flag = required ? "required " : "optional ";
            JsonNode resolved = resolve(schema);
            if (isArray(resolved)) {
                field(path + "[]", resolved.path("items"), required, out, visiting);
                return;
            }
            if (isMap(resolved)) {
                out.put(path, flag + "map");
                field(path + "{}", resolved.path("additionalProperties"), true, out, visiting);
                return;
            }
            String name = schema.has("$ref") ? refName(schema) : null;
            if (name != null && visiting.contains(name)) {
                out.put(path, flag + "object " + name + " (recursive)");
                return;
            }
            out.put(path, flag + typeName(resolved));
            if (merge(resolved).has("properties")) {
                Set<String> deeper = new HashSet<>(visiting);
                if (name != null) {
                    deeper.add(name);
                }
                properties(resolved, path, out, deeper);
            }
        }

        private JsonNode resolve(JsonNode schema) {
            JsonNode node = schema;
            for (int hops = 0; node.has("$ref") && hops < 32; hops++) {
                node = components.path(refName(node));
            }
            return node;
        }

        /** {@code allOf} parts folded into one object, so their fields read as one schema's. */
        private JsonNode merge(JsonNode schema) {
            if (!schema.has("allOf")) {
                return schema;
            }
            ObjectNode merged = JSON.createObjectNode();
            ObjectNode properties = merged.putObject("properties");
            var required = merged.putArray("required");
            for (JsonNode part : schema.path("allOf")) {
                JsonNode each = merge(resolve(part));
                each.path("properties")
                        .properties()
                        .iterator()
                        .forEachRemaining(e -> properties.set(e.getKey(), e.getValue()));
                each.path("required").forEach(required::add);
            }
            schema.path("properties")
                    .properties()
                    .iterator()
                    .forEachRemaining(e -> properties.set(e.getKey(), e.getValue()));
            schema.path("required").forEach(required::add);
            return merged;
        }

        private static String refName(JsonNode schema) {
            String ref = schema.path("$ref").asText();
            return ref.substring(ref.lastIndexOf('/') + 1);
        }

        private boolean isArray(JsonNode schema) {
            return "array".equals(type(schema)) || schema.has("items");
        }

        private boolean isMap(JsonNode schema) {
            return schema.path("additionalProperties").isObject() && !schema.has("properties");
        }

        private boolean isObject(JsonNode schema) {
            return !isArray(schema)
                    && !isMap(schema)
                    && (merge(schema).has("properties") || "object".equals(type(schema)));
        }

        private static String type(JsonNode schema) {
            JsonNode type = schema.path("type");
            if (type.isArray()) {
                List<String> each = new ArrayList<>();
                type.forEach(t -> {
                    if (!"null".equals(t.asText())) {
                        each.add(t.asText());
                    }
                });
                return String.join("|", each);
            }
            return type.isMissingNode() ? null : type.asText();
        }

        private String typeName(JsonNode schema) {
            String type = type(schema);
            if (type == null) {
                if (schema.has("oneOf") || schema.has("anyOf")) {
                    return "oneOf";
                }
                return merge(schema).has("properties") ? "object" : "any";
            }
            return schema.has("format") ? type + "/" + schema.path("format").asText() : type;
        }
    }

    // ------------------------------------------------------------------------------- comparing

    /**
     * The differences between the recorded lock and the live one that break an existing client: an
     * operation gone, a body field removed or renamed, a field's type changed, a response field no
     * longer required, or a request field newly required. An added optional response field, a new
     * operation or a new optional request field is not listed -- it still has to be recorded.
     */
    static List<String> breakingChanges(JsonNode recorded, JsonNode live) {
        List<String> out = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it =
                        recorded.path("paths").properties().iterator();
                it.hasNext(); ) {
            Map.Entry<String, JsonNode> path = it.next();
            for (Iterator<Map.Entry<String, JsonNode>> m =
                            path.getValue().properties().iterator();
                    m.hasNext(); ) {
                Map.Entry<String, JsonNode> method = m.next();
                String label = method.getKey().toUpperCase(java.util.Locale.ROOT) + " " + path.getKey();
                JsonNode was = method.getValue();
                JsonNode is = live.path("paths").path(path.getKey()).path(method.getKey());
                if (is.isMissingNode()) {
                    out.add(label + ": the operation is gone");
                    continue;
                }
                Map<String, String> wasRequest = fields(was.path("requestBody"), recorded);
                Map<String, String> isRequest = fields(is.path("requestBody"), live);
                compare(label + " request", wasRequest, isRequest, false, out);
                isRequest.forEach((field, type) -> {
                    if (required(type) && (!wasRequest.containsKey(field) || !required(wasRequest.get(field)))) {
                        out.add(label + " request: " + field + " is newly required");
                    }
                });
                for (Iterator<Map.Entry<String, JsonNode>> r =
                                was.path("responseBodies").properties().iterator();
                        r.hasNext(); ) {
                    Map.Entry<String, JsonNode> response = r.next();
                    compare(
                            label + " " + response.getKey(),
                            fields(response.getValue(), recorded),
                            fields(is.path("responseBodies").path(response.getKey()), live),
                            true,
                            out);
                }
            }
        }
        return out;
    }

    private static void compare(
            String label, Map<String, String> was, Map<String, String> is, boolean response, List<String> out) {
        was.forEach((field, type) -> {
            String now = is.get(field);
            if (now == null) {
                out.add(label + ": " + field + " was removed or renamed (was " + type + ")");
            } else if (!typeOf(now).equals(typeOf(type))) {
                out.add(label + ": " + field + " changed type from " + typeOf(type) + " to " + typeOf(now));
            } else if (response && required(type) && !required(now)) {
                out.add(label + ": " + field + " is no longer required, so a client may not find it");
            }
        });
    }

    /** A body's fields by path, the body itself as {@code (body)}; empty when there is no body. */
    static Map<String, String> fields(JsonNode shapeNode, JsonNode lock) {
        Map<String, String> out = new LinkedHashMap<>();
        if (shapeNode.isMissingNode() || shapeNode.isNull()) {
            return out;
        }
        String shape = shapeNode.asText();
        if (shape.contains("=")) {
            for (String each : shape.split("; ", -1)) {
                int eq = each.indexOf('=');
                expand(each.substring(eq + 1), each.substring(0, eq) + ":", lock.path("schemas"), out);
            }
            return out;
        }
        expand(shape, "", lock.path("schemas"), out);
        return out;
    }

    private static void expand(String shape, String prefix, JsonNode schemas, Map<String, String> out) {
        out.put(prefix.isEmpty() ? "(body)" : prefix + "(body)", "required " + rootType(shape, schemas));
        expandInto(shape, prefix, schemas, out);
    }

    private static void expandInto(String shape, String prefix, JsonNode schemas, Map<String, String> out) {
        if (shape.endsWith("[]")) {
            String items = shape.substring(0, shape.length() - 2);
            out.put(prefix + "[]", "required " + rootType(items, schemas));
            expandInto(items, prefix + "[]", schemas, out);
        } else if (shape.startsWith("map<") && shape.endsWith(">")) {
            String values = shape.substring(4, shape.length() - 1);
            out.put(prefix + "{}", "required " + rootType(values, schemas));
            expandInto(values, prefix + "{}", schemas, out);
        } else if (schemas.has(shape)) {
            String dot = prefix.isEmpty() || prefix.endsWith(":") ? prefix : prefix + ".";
            schemas.path(shape)
                    .properties()
                    .iterator()
                    .forEachRemaining(
                            f -> out.put(dot + f.getKey(), f.getValue().asText()));
        }
    }

    private static String rootType(String shape, JsonNode schemas) {
        if (shape.endsWith("[]")) {
            return "array";
        }
        if (shape.startsWith("map<")) {
            return "map";
        }
        return schemas.has(shape) ? "object" : shape;
    }

    private static boolean required(String type) {
        return type.startsWith("required ");
    }

    private static String typeOf(String type) {
        return type.substring(type.indexOf(' ') + 1);
    }
}
