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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Undecodable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The schema registry, against a registry of the test's own: the five-byte prefix, one fetch per
 * schema id however many records carry it, the two ways of authenticating, and every way a registry
 * can fail to answer usefully.
 *
 * <p>An {@code HttpServer} from the JDK, so this exercises the documented REST shape and nothing
 * Confluent-specific -- which is the whole point of speaking the protocol rather than linking the
 * client: Karapace and Apicurio's compatibility endpoint answer the same way, and the bare-document
 * answer some of them give is accepted too.
 */
class SchemaRegistryTest {

    private static final String ORDER_SCHEMA = "{\"type\":\"record\",\"name\":\"Order\",\"fields\":["
            + "{\"name\":\"id\",\"type\":\"long\"},{\"name\":\"name\",\"type\":\"string\"}]}";

    private final List<AutoCloseable> closeables = new ArrayList<>();
    private FakeRegistry registry;

    @AfterEach
    void stopEverything() throws Exception {
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
        if (registry != null) {
            registry.stop();
        }
    }

    @Test
    void aFramedRecordIsReadWithTheSchemaItsIdNames() throws Undecodable {
        registry = new FakeRegistry();
        registry.serve(7, envelope(ORDER_SCHEMA));
        AvroValueDecoder decoder = decoder(client(""));

        byte[] value = new AvroWriter().number(42).text("ashutosh").framed(7);
        assertThat(decoder.decode(value, 1_000L).values()).containsExactly(42L, "ashutosh");
    }

    @Test
    void anIdIsFetchedOnceHoweverManyRecordsCarryIt() throws Undecodable {
        registry = new FakeRegistry();
        registry.serve(7, envelope(ORDER_SCHEMA));
        registry.serve(8, envelope(ORDER_SCHEMA));
        SchemaRegistry client = client("");
        AvroValueDecoder decoder = decoder(client);

        for (int i = 0; i < 25; i++) {
            decoder.decode(new AvroWriter().number(i).text("x").framed(7), 0);
        }
        assertThat(client.requestCount()).as("one request for the one id").isEqualTo(1);
        assertThat(registry.requests).hasSize(1);

        decoder.decode(new AvroWriter().number(1).text("y").framed(8), 0);
        assertThat(client.requestCount()).isEqualTo(2);
    }

    @Test
    void theClientItselfRemembersAnIdSoEveryReaderOfTheBindingSharesTheOneFetch() {
        registry = new FakeRegistry();
        registry.serve(7, envelope(ORDER_SCHEMA));
        SchemaRegistry client = client("");

        // Each partition's reader has a decoder of its own; what stops them each fetching the same
        // schema is this cache, not theirs.
        for (int reader = 0; reader < 4; reader++) {
            assertThat(client.schemaText(7)).isEqualTo(ORDER_SCHEMA);
        }
        assertThat(client.requestCount()).isEqualTo(1);
        assertThat(registry.requests).hasSize(1);
    }

    @Test
    void basicAuthAndABearerTokenAreSentTheWayEachRegistryExpects() throws Undecodable {
        registry = new FakeRegistry();
        registry.serve(7, envelope(ORDER_SCHEMA));

        decoder(client(SchemaRegistry.authorizationHeader("pravaha", "s3cret", "")))
                .decode(new AvroWriter().number(1).text("a").framed(7), 0);
        assertThat(registry.authorizations).containsExactly("Basic cHJhdmFoYTpzM2NyZXQ=");

        registry.authorizations.clear();
        decoder(client(SchemaRegistry.authorizationHeader("", "", "a-token")))
                .decode(new AvroWriter().number(1).text("a").framed(7), 0);
        assertThat(registry.authorizations).containsExactly("Bearer a-token");

        registry.authorizations.clear();
        decoder(client("")).decode(new AvroWriter().number(1).text("a").framed(7), 0);
        assertThat(registry.authorizations)
                .as("nothing configured, nothing sent")
                .isEmpty();
    }

    @Test
    void aRegistryThatIsDownStopsTheReaderWithItsOwnCodeAfterRetrying() throws Exception {
        registry = new FakeRegistry();
        int port = registry.port();
        registry.stop();
        registry = null;
        SchemaRegistry client =
                new SchemaRegistry("orders", "http://127.0.0.1:" + port, null, "", Duration.ofMillis(500));
        closeables.add(client);
        AvroValueDecoder decoder = decoder(client);
        byte[] value = new AvroWriter().number(1).text("a").framed(7);

        assertThatThrownBy(() -> decoder.decode(value, 0))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5109")
                .hasMessageContaining("could not be read after 3 attempts")
                .hasMessageContaining("the reader stops rather than setting good records aside");
        assertThat(client.requestCount()).as("three attempts").isEqualTo(3);
    }

    @Test
    void aRegistryThatRefusesTheCredentialsOrHasNoSuchIdSaysWhichAndDoesNotRetry() {
        registry = new FakeRegistry();
        registry.status(7, 401, "{\"error_code\":40101,\"message\":\"Unauthorized\"}");
        registry.status(8, 404, "{\"error_code\":40403,\"message\":\"Schema not found\"}");
        registry.status(9, 500, "boom");
        SchemaRegistry client = client("");

        assertThatThrownBy(() -> decoder(client)
                        .decode(new AvroWriter().number(1).text("a").framed(7), 0))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5109")
                .hasMessageContaining("refused the credentials (401)")
                .hasMessageContaining("schema.registry.token");
        assertThatThrownBy(() -> decoder(client)
                        .decode(new AvroWriter().number(1).text("a").framed(8), 0))
                .hasMessageContaining("no schema with id 8 (404)");
        assertThatThrownBy(() -> decoder(client)
                        .decode(new AvroWriter().number(1).text("a").framed(9), 0))
                .hasMessageContaining("it answered 500");
        assertThat(client.requestCount())
                .as("one request each: a refusal is not retried")
                .isEqualTo(3);
    }

    @Test
    void anAnswerThatIsNotTheDocumentedShapeIsRefusedAsTheRegistrysFault() {
        registry = new FakeRegistry();
        registry.serve(7, "{\"subject\":\"orders-value\",\"version\":3}");
        registry.serve(8, "<html>a proxy's error page</html>");

        assertThatThrownBy(() -> decoder(client(""))
                        .decode(new AvroWriter().number(1).text("a").framed(7), 0))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5109")
                .hasMessageContaining("no 'schema' member");
        assertThatThrownBy(() -> decoder(client(""))
                        .decode(new AvroWriter().number(1).text("a").framed(8), 0))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("not JSON");
    }

    @Test
    void aRegistryThatAnswersWithTheSchemaDocumentItselfIsAccepted() throws Undecodable {
        registry = new FakeRegistry();
        registry.serve(7, ORDER_SCHEMA);

        assertThat(decoder(client(""))
                        .decode(new AvroWriter().number(5).text("z").framed(7), 0)
                        .values())
                .containsExactly(5L, "z");
    }

    @Test
    void aPathPrefixSuchAsApicuriosCompatibilityEndpointIsKept() throws Undecodable {
        registry = new FakeRegistry("/apis/ccompat/v7");
        registry.serve(7, envelope(ORDER_SCHEMA));
        SchemaRegistry client =
                new SchemaRegistry("orders", registry.url() + "/apis/ccompat/v7", null, "", Duration.ofSeconds(30));
        closeables.add(client);

        assertThat(decoder(client)
                        .decode(new AvroWriter().number(1).text("a").framed(7), 0)
                        .values())
                .containsExactly(1L, "a");
        assertThat(registry.requests).containsExactly("/apis/ccompat/v7/schemas/ids/7");
    }

    @Test
    void aRecordWithNoMagicByteIsADeadLetterNamingTheRegistryOption() {
        registry = new FakeRegistry();
        AvroValueDecoder decoder = decoder(client(""));
        byte[] bare = new AvroWriter().number(1).text("a").bytes();

        assertThatThrownBy(() -> decoder.decode(bare, 0))
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("does not begin with the schema registry's wire format")
                .hasMessageContaining("schema.file");
    }

    @Test
    void aRegistrySchemaThatDoesNotMapIsADeadLetterAndIsNotAskedForAgain() throws Undecodable {
        registry = new FakeRegistry();
        registry.serve(
                7,
                envelope("{\"type\":\"record\",\"name\":\"Other\",\"fields\":["
                        + "{\"name\":\"id\",\"type\":\"long\"},{\"name\":\"elsewhere\",\"type\":\"string\"}]}"));
        SchemaRegistry client = client("");
        AvroValueDecoder decoder = decoder(client);
        byte[] value = new AvroWriter().number(1).text("a").framed(7);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> decoder.decode(value, 0))
                    .isInstanceOf(Undecodable.class)
                    .hasMessageContaining("schema id 7 cannot be read into this stream's columns")
                    .hasMessageContaining("no field for column [name]");
        }
        assertThat(client.requestCount())
                .as("the mismatch is remembered, not re-fetched per record")
                .isEqualTo(1);
    }

    @Test
    void eachRegistrySchemaIsResolvedAgainstTheReaderSchemaAndOneThatDoesNotResolveIsADeadLetter() throws Undecodable {
        registry = new FakeRegistry();
        // v1 wrote an int id; v2 renamed name to label. Both resolve against the reader.
        registry.serve(
                7,
                envelope("{\"type\":\"record\",\"name\":\"Order\",\"fields\":["
                        + "{\"name\":\"id\",\"type\":\"int\"},{\"name\":\"name\",\"type\":\"string\"}]}"));
        registry.serve(
                8,
                envelope("{\"type\":\"record\",\"name\":\"Order\",\"fields\":["
                        + "{\"name\":\"id\",\"type\":\"long\"},{\"name\":\"label\",\"type\":\"string\"}]}"));
        // v3 made id a string, which no reading turns back into a long.
        registry.serve(
                9,
                envelope("{\"type\":\"record\",\"name\":\"Order\",\"fields\":["
                        + "{\"name\":\"id\",\"type\":\"string\"},{\"name\":\"name\",\"type\":\"string\"}]}"));
        AvroSchema.Node reader = AvroSchema.parse("{\"type\":\"record\",\"name\":\"Order\",\"fields\":["
                + "{\"name\":\"id\",\"type\":\"long\"},"
                + "{\"name\":\"name\",\"type\":\"string\",\"aliases\":[\"label\"]}]}");
        AvroValueDecoder decoder = new AvroValueDecoder(
                "orders", KafkaSchema.parse("orders", "id:INT64,name:STRING"), -1, null, reader, client(""));

        assertThat(decoder.decode(new AvroWriter().integer(1).text("a").framed(7), 0)
                        .values())
                .containsExactly(1L, "a");
        assertThat(decoder.decode(new AvroWriter().number(2).text("b").framed(8), 0)
                        .values())
                .containsExactly(2L, "b");
        assertThatThrownBy(() ->
                        decoder.decode(new AvroWriter().text("3").text("c").framed(9), 0))
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("schema id 9")
                .hasMessageContaining("cannot be resolved against the reader schema at field 'id' of Order");
    }

    @Test
    void aRegistrySchemaThatIsNotAvroIsADeadLetterNamingTheId() {
        registry = new FakeRegistry();
        registry.serve(7, "{\"schema\":\"{ not a schema\"}");

        assertThatThrownBy(() -> decoder(client(""))
                        .decode(new AvroWriter().number(1).text("a").framed(7), 0))
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("schema id 7 in the registry is not a valid Avro schema");
    }

    @Test
    void aRegistryFramedRecordWithNoRegistryConfiguredIsRefusedByName() {
        StreamSchema schema = KafkaSchema.parse("orders", "id:INT64,name:STRING");
        AvroValueDecoder configured = new AvroValueDecoder(
                "orders", schema, -1, AvroRowReader.map(schema, AvroSchema.parse(ORDER_SCHEMA), -1), null, null);
        byte[] framed = new AvroWriter().number(42).text("ashutosh").framed(7);

        assertThatThrownBy(() -> configured.decode(framed, 0))
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("schema id 7")
                .hasMessageContaining("set schema.registry.url instead of schema.file");
    }

    // ---------------------------------------------------------------------------------------

    private SchemaRegistry client(String authorization) {
        // Thirty seconds, not the two a deployment would set: the registry here is a loopback
        // HttpServer in this JVM, so the timeout can only be reached by the machine being busy --
        // and when it is, the client retries and `anIdIsFetchedOnceHoweverManyRecordsCarryIt` sees
        // two requests for one id and calls the cache broken. A test of caching must not be a test
        // of the clock. The timeout's own behaviour is covered by the registry-down cases, which
        // fail on a refused connection rather than on time.
        SchemaRegistry client =
                new SchemaRegistry("orders", registry.url(), null, authorization, Duration.ofSeconds(30));
        closeables.add(client);
        return client;
    }

    private static AvroValueDecoder decoder(SchemaRegistry client) {
        return new AvroValueDecoder(
                "orders", KafkaSchema.parse("orders", "id:INT64,name:STRING"), -1, null, null, client);
    }

    private static String envelope(String schema) {
        return "{\"subject\":\"orders-value\",\"version\":1,\"id\":7,\"schema\":" + quoted(schema) + "}";
    }

    /** The schema as a JSON string member, which is how the REST API carries it. */
    private static String quoted(String schema) {
        return '"' + schema.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** A schema registry of the test's own: the documented path, and whatever answer a test wants. */
    private static final class FakeRegistry {

        private final HttpServer server;
        private final Map<Integer, String> bodies = new ConcurrentHashMap<>();
        private final Map<Integer, Integer> statuses = new ConcurrentHashMap<>();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<String> authorizations = new CopyOnWriteArrayList<>();

        FakeRegistry() {
            this("");
        }

        FakeRegistry(String prefix) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext(prefix + "/schemas/ids/", this::answer);
            server.start();
        }

        void serve(int id, String body) {
            bodies.put(id, body);
        }

        void status(int id, int status, String body) {
            statuses.put(id, status);
            bodies.put(id, body);
        }

        String url() {
            return "http://127.0.0.1:" + port();
        }

        int port() {
            return server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        private void answer(HttpExchange exchange) throws IOException {
            requests.add(exchange.getRequestURI().getPath());
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null) {
                authorizations.add(authorization);
            }
            String path = exchange.getRequestURI().getPath();
            int id = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
            String body = bodies.getOrDefault(id, "{\"error_code\":40403,\"message\":\"Schema not found\"}");
            int status = statuses.getOrDefault(id, bodies.containsKey(id) ? 200 : 404);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.schemaregistry.v1+json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
