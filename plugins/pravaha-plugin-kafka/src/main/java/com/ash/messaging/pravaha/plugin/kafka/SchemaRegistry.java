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
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.InvalidProtocolBufferException;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * A Confluent-compatible schema registry, spoken over its REST API with the JDK's own HTTP client.
 *
 * <p><strong>No Confluent client library.</strong> {@code kafka-schema-registry-client} is under the
 * Confluent Community License, is not on Maven Central, and would pull a tree of its own. What this
 * source needs of it is two things, both documented and both small: the <em>wire format</em> a
 * registry-aware producer writes -- one {@code 0x00} magic byte, then a four-byte big-endian schema
 * id, then the payload ({@link #SCHEMA_ID_BYTES} + 1 bytes of prefix) -- and one request, {@code GET
 * <base>/schemas/ids/{id}}, whose answer is a JSON object with a {@code schema} member holding the
 * schema as text.
 *
 * <p>That shape is what <strong>Karapace</strong> serves, and what <strong>Apicurio</strong> serves
 * from its Confluent compatibility endpoint ({@code .../apis/ccompat/v7}); point {@code
 * schema.registry.url} at whichever, including any path prefix, and nothing here changes. A registry
 * that answers with the bare schema document instead of the envelope is accepted too.
 *
 * <p><strong>Cached by id, forever.</strong> A registered schema's id never changes meaning -- that
 * is the guarantee the whole format rests on -- so an id fetched once is never fetched again, and a
 * topic with one schema costs one request per source. {@link #CACHE_LIMIT} ids are kept; past that
 * the cache stops growing rather than holding a producer's runaway ids.
 *
 * <p><strong>Protobuf schemas</strong> are asked for with {@code ?format=serialized}, which Confluent
 * Schema Registry answers with the schema as a base64 {@code FileDescriptorProto} instead of {@code
 * .proto} source -- the parsed form {@code DynamicMessage} needs, with no {@code protoc} and no {@code
 * .proto} parser here. Each of its {@code references} (an import, by the name the file imports it as)
 * is fetched the same way from {@code GET <base>/subjects/{subject}/versions/{version}}. A registry
 * that ignores the parameter answers with {@code .proto} source, and that is refused by name ({@code
 * PRV-5109}) rather than guessed at: {@code schema.descriptor} is the way to read such a topic.
 *
 * <p>A registry that cannot be reached, refuses the credentials, or answers with something that is
 * not a schema is {@code PRV-5109}, after {@link #ATTEMPTS} tries a short pause apart: it is an
 * infrastructure fault, not a bad record, so it stops the reader instead of dead-lettering a record
 * that is probably fine.
 */
final class SchemaRegistry implements AutoCloseable {

    /** The bytes of the schema id in the wire format's prefix. */
    static final int SCHEMA_ID_BYTES = 4;

    /** The byte a registry-aware producer puts first. */
    static final byte MAGIC = 0;

    private static final int CACHE_LIMIT = 1024;
    private static final int ATTEMPTS = 3;

    private final String instanceName;
    /** "source" or "sink", as a refusal names the binding; and what an unreachable registry costs it. */
    private final String role;

    private final String consequence;
    private final String base;
    private final HttpClient http;
    private final String authorization;
    private final Duration timeout;
    private final Map<Integer, String> cache = new ConcurrentHashMap<>();
    private final Map<Integer, ProtobufSchema> protobufCache = new ConcurrentHashMap<>();

    /** A Protobuf schema as its files: the root's name and every file by the name it is imported as. */
    record ProtobufSchema(String root, Map<String, FileDescriptorProto> files) {}

    /** How many files one Protobuf schema may reference, directly and through its references. */
    private static final int MAX_FILES = 64;

    private final AtomicInteger requests = new AtomicInteger();

    SchemaRegistry(String instanceName, String base, SSLContext tls, String authorization, Duration timeout) {
        this(
                instanceName,
                "source",
                "Records carrying a schema id cannot be decoded without it, so the reader stops rather than setting "
                        + "good records aside as dead letters.",
                base,
                tls,
                authorization,
                timeout);
    }

    /** For {@code kafka-sink}, which asks the registry only at configuration, to check its schema ids. */
    SchemaRegistry(
            String instanceName,
            String role,
            String consequence,
            String base,
            SSLContext tls,
            String authorization,
            Duration timeout) {
        this.instanceName = instanceName;
        this.role = role;
        this.consequence = consequence;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.authorization = authorization;
        this.timeout = timeout;
        HttpClient.Builder builder =
                HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL);
        if (tls != null) {
            builder.sslContext(tls);
        }
        this.http = builder.build();
    }

    /** The schema registered under {@code id}, as text; fetched once and then remembered. */
    String schemaText(int id) {
        String cached = cache.get(id);
        if (cached != null) {
            return cached;
        }
        String fetched = fetch(id);
        if (cache.size() < CACHE_LIMIT) {
            cache.putIfAbsent(id, fetched);
        }
        return fetched;
    }

    /** How many HTTP requests this registry has made: what a test counts to prove the cache. */
    int requestCount() {
        return requests.get();
    }

    /**
     * The Protobuf schema registered under {@code id}, with every file it references; fetched once
     * and then remembered.
     */
    ProtobufSchema protobufSchema(int id) {
        ProtobufSchema cached = protobufCache.get(id);
        if (cached != null) {
            return cached;
        }
        Map<String, FileDescriptorProto> files = new LinkedHashMap<>();
        URI uri = URI.create(base + "/schemas/ids/" + id + "?format=serialized");
        String root = "schema-" + id + ".proto";
        collect(uri, "no schema with id " + id, root, files);
        ProtobufSchema schema = new ProtobufSchema(root, Map.copyOf(files));
        if (protobufCache.size() < CACHE_LIMIT) {
            protobufCache.putIfAbsent(id, schema);
        }
        return schema;
    }

    /** One serialized file from {@code uri}, stored under {@code name}, and then its references. */
    private void collect(URI uri, String missing, String name, Map<String, FileDescriptorProto> files) {
        if (files.containsKey(name)) {
            return;
        }
        if (files.size() >= MAX_FILES) {
            throw unreachable(uri, "the schema references more than " + MAX_FILES + " files", null);
        }
        Object parsed;
        String body = get(uri, missing);
        try {
            parsed = AvroSchema.readJson(body);
        } catch (AvroSchema.Invalid e) {
            throw unreachable(uri, "it answered with something that is not JSON: " + shorten(body), null);
        }
        if (!(parsed instanceof Map<?, ?> envelope) || !(envelope.get("schema") instanceof String text)) {
            throw unreachable(uri, "it answered 200 with " + shorten(body) + ", which has no 'schema' member", null);
        }
        Object type = envelope.get("schemaType");
        if (!"PROTOBUF".equals(type)) {
            throw unreachable(
                    uri,
                    "the schema there is " + (type == null ? "AVRO (no schemaType)" : type)
                            + ", not PROTOBUF, so it cannot describe a protobuf record",
                    null);
        }
        FileDescriptorProto file;
        try {
            file = FileDescriptorProto.parseFrom(Base64.getDecoder().decode(text.strip()));
        } catch (IllegalArgumentException | InvalidProtocolBufferException e) {
            throw unreachable(
                    uri,
                    "it answered with .proto source rather than a serialized descriptor: "
                            + shorten(text)
                            + ". It does not honour ?format=serialized (Confluent Schema Registry does); "
                            + "read this topic with schema.descriptor instead",
                    null);
        }
        files.put(name, file.toBuilder().setName(name).build());
        Object references = envelope.get("references");
        if (references == null) {
            return;
        }
        if (!(references instanceof List<?> list)) {
            throw unreachable(uri, "its 'references' is not an array", null);
        }
        List<Map<?, ?>> pending = new ArrayList<>();
        for (Object reference : list) {
            if (!(reference instanceof Map<?, ?> ref)
                    || !(ref.get("name") instanceof String)
                    || !(ref.get("subject") instanceof String)
                    || !(ref.get("version") instanceof Number)) {
                throw unreachable(uri, "a reference is not {name, subject, version}: " + reference, null);
            }
            pending.add(ref);
        }
        for (Map<?, ?> ref : pending) {
            String subject = (String) ref.get("subject");
            long version = ((Number) ref.get("version")).longValue();
            URI next = URI.create(base + "/subjects/"
                    + URLEncoder.encode(subject, StandardCharsets.UTF_8).replace("+", "%20") + "/versions/" + version
                    + "?format=serialized");
            collect(next, "no version " + version + " of subject '" + subject + "'", (String) ref.get("name"), files);
        }
    }

    private String fetch(int id) {
        return schemaIn(
                URI.create(base + "/schemas/ids/" + id),
                get(URI.create(base + "/schemas/ids/" + id), "no schema with id " + id));
    }

    /** The body of a 200 from {@code uri}; {@code missing} is what a 404 means there. */
    private String get(URI uri, String missing) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(timeout)
                .header("Accept", "application/vnd.schemaregistry.v1+json, application/json");
        if (!authorization.isEmpty()) {
            request.header("Authorization", authorization);
        }
        HttpRequest built = request.build();
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            requests.incrementAndGet();
            HttpResponse<String> response;
            try {
                response = http.send(built, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // A connection refused, a reset, a timeout: worth trying again, briefly.
                lastFailure = e;
                pause(attempt);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw unreachable(uri, "the fetch was interrupted", null);
            }
            if (response.statusCode() == 200) {
                return response.body();
            }
            throw refused(uri, missing, response);
        }
        throw unreachable(uri, lastFailure == null ? "it did not answer" : lastFailure.toString(), lastFailure);
    }

    /** The {@code schema} member of the documented envelope, or a bare schema document. */
    private String schemaIn(URI uri, String body) {
        Object parsed;
        try {
            parsed = AvroSchema.readJson(body);
        } catch (AvroSchema.Invalid e) {
            throw unreachable(uri, "it answered with something that is not JSON: " + shorten(body), null);
        }
        if (parsed instanceof Map<?, ?> envelope) {
            Object schema = envelope.get("schema");
            if (schema instanceof String text && !text.isBlank()) {
                return text;
            }
            if (schema == null && (envelope.containsKey("type") || envelope.containsKey("fields"))) {
                // Some registries answer with the schema document itself rather than the envelope.
                return body;
            }
        }
        throw unreachable(
                uri,
                "it answered 200 with " + shorten(body) + ", which has no 'schema' member: that is not the "
                        + "documented shape of GET /schemas/ids/{id}",
                null);
    }

    private PravahaException refused(URI uri, String missing, HttpResponse<String> response) {
        int status = response.statusCode();
        String detail =
                switch (status) {
                    case 401, 403 ->
                        "it refused the credentials (" + status + "). Set schema.registry.user and "
                                + "schema.registry.password, or schema.registry.token";
                    case 404 ->
                        "it has " + missing + " (404). The records were written against another "
                                + "registry, or the subject was hard-deleted";
                    default -> "it answered " + status + ": " + shorten(response.body());
                };
        return new PravahaException(
                KafkaErrors.REGISTRY_UNAVAILABLE,
                role + " '" + instanceName + "': the schema registry at " + uri + " " + detail);
    }

    private PravahaException unreachable(URI uri, String detail, Throwable cause) {
        return new PravahaException(
                KafkaErrors.REGISTRY_UNAVAILABLE,
                role + " '" + instanceName + "': the schema registry at " + uri + " could not be read after " + ATTEMPTS
                        + " attempts: " + detail + ". " + consequence,
                cause);
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(100L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String shorten(String body) {
        String oneLine = body == null ? "" : body.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 200 ? "'" + oneLine + "'" : "'" + oneLine.substring(0, 200) + "...'";
    }

    /** The schema id in a value's five-byte prefix; the caller has checked the magic byte. */
    static int schemaIdIn(byte[] value) {
        return ((value[1] & 0xFF) << 24) | ((value[2] & 0xFF) << 16) | ((value[3] & 0xFF) << 8) | (value[4] & 0xFF);
    }

    /** Whether {@code value} begins with the wire format's magic byte and a whole schema id. */
    static boolean framed(byte[] value) {
        return value.length >= SCHEMA_ID_BYTES + 1 && value[0] == MAGIC;
    }

    /** {@code Basic} or {@code Bearer}, or empty when the registry needs neither. */
    static String authorizationHeader(String user, String password, String token) {
        if (!token.isEmpty()) {
            return "Bearer " + token;
        }
        if (user.isEmpty()) {
            return "";
        }
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        http.close();
    }
}
