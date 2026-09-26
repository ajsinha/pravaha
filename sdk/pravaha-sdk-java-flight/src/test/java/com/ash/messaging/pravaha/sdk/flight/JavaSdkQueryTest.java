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
package com.ash.messaging.pravaha.sdk.flight;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.flight.PravahaFlightServer;
import com.ash.messaging.pravaha.sdk.PravahaClientException;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Java SDK asking a question, over the wire, exactly as an application would.
 *
 * <p>This is the acceptance test for the Java half of ADR-030: connect, query, iterate, close. The
 * server is real, the protocol is real, and nothing here is a stub -- the only thing the test does
 * that an application would not is start the server in the same process.
 */
@Timeout(60)
class JavaSdkQueryTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("tier", Types.string().withNullable(true))
            .field("total", Types.int64())
            .build();

    private PravahaFlightServer server;
    private PravahaFlightClient client;

    @BeforeEach
    void start() {
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", "gold", 300L}, 1, 10);
        view.applyValues(new Object[] {"u2", "silver", 50L}, 1, 10);
        view.applyValues(new Object[] {"u3", null, 7L}, 1, 10);
        view.commit(10);

        server = new PravahaFlightServer(new ViewCatalog().register(view)).start("localhost", 0);
        // "grpc://" spells out plaintext. Omitting the scheme means TLS, which is the right default
        // for a client SDK and the reason this test has to be explicit rather than the reverse.
        client = PravahaFlightClient.connect("grpc://localhost:" + server.port());
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void omittingTheSchemeMeansTlsAndSoFailsAgainstAPlaintextServer() {
        // Secure by default, asserted rather than assumed: a client that silently fell back to
        // plaintext when TLS failed would be worse than one that refuses, because nobody would
        // notice.
        assertThatThrownBy(() -> {
                    try (PravahaFlightClient insecure = PravahaFlightClient.connect("localhost:" + server.port())) {
                        insecure.query("SELECT user_id FROM user_volume").close();
                    }
                })
                .isInstanceOf(PravahaClientException.class);
    }

    @Test
    void everyTypeTheEngineDeclaresCanActuallyReachAClient() {
        // TY-17 and TY-18. A TIME column was declared on the wire as Time(NANOSECOND, 64) -- which
        // materialises as TimeNanoVector -- and written through the zoned *timestamp* vector, so the
        // first non-null TIME value ever sent threw ClassCastException. A TIME column could not
        // reach a client at all. The two types had shared a case since before either worked, and
        // the fix that corrected TIMESTAMP's vector swept TIME along with it.
        //
        // BYTES is here for the same reason: declaring a type and being able to send one are
        // different claims, and this test is the second.
        StreamSchema everyType = StreamSchema.builder("shapes")
                .field("id", Types.string())
                .field("stamped_at", Types.timestamp())
                .field("time_of_day", Types.time())
                .field("on_day", Types.date())
                .field("raw", Types.bytes())
                .field("flag", Types.bool())
                .field("ratio", Types.float64())
                .build();
        ServedView shapes = new ServedView("shapes", everyType, List.of(0), 10);
        shapes.applyValues(
                new Object[] {
                    "r1",
                    1_700_000_000_000_000_000L,
                    3_600_000_000_000L,
                    20_345,
                    new byte[] {0x00, (byte) 0xFF, (byte) 0xC3, 0x28},
                    true,
                    2.5d
                },
                1,
                10);
        shapes.commit(10);

        try (PravahaFlightServer other =
                        new PravahaFlightServer(new ViewCatalog().register(shapes)).start("localhost", 0);
                PravahaFlightClient reader = PravahaFlightClient.connect("grpc://localhost:" + other.port());
                QueryResult result =
                        reader.query("SELECT id, stamped_at, time_of_day, on_day, raw, flag, ratio FROM shapes")) {
            Row row = result.iterator().next();
            assertThat(row.getString("id")).isEqualTo("r1");
            assertThat(row.getLong("time_of_day"))
                    .as("an hour, in nanoseconds of day")
                    .isEqualTo(3_600_000_000_000L);
            assertThat(row.getLong("stamped_at")).isEqualTo(1_700_000_000_000_000_000L);
            // Byte-exact, and deliberately not valid UTF-8. An earlier fix materialised binary by
            // decoding it to a String and encoding it back, which survives the type check and
            // replaces every byte the decoder cannot read -- a column that arrives with the right
            // shape and the wrong contents.
            assertThat(row.get("raw")).isInstanceOf(byte[].class);
            assertThat((byte[]) row.get("raw")).containsExactly((byte) 0x00, (byte) 0xFF, (byte) 0xC3, (byte) 0x28);
            assertThat(row.getDouble("ratio")).isEqualTo(2.5d);
        }
    }

    @Test
    void anUnmappableWireTypeReachesTheClientAsItsOwnRefusal() {
        // DECIMAL has no Arrow form this engine will emit, and it says so precisely: PRV-6100,
        // naming the column. That refusal used to be thrown one line outside every try/catch --
        // after planning, which has its own, because the SQL is valid and only the wire type is
        // not. So it escaped the gRPC method uncaught and the client got Arrow's own text, "There
        // was an error servicing your request": no code, no column, nothing to act on, for a
        // refusal the engine had already stated exactly.
        StreamSchema withDecimal = StreamSchema.builder("ledger")
                .field("entry_id", Types.string())
                .field("amount", Types.decimal(18, 2))
                .build();
        ServedView ledger = new ServedView("ledger", withDecimal, List.of(0), 10);
        try (PravahaFlightServer other =
                        new PravahaFlightServer(new ViewCatalog().register(ledger)).start("localhost", 0);
                PravahaFlightClient reader = PravahaFlightClient.connect("grpc://localhost:" + other.port())) {
            assertThatThrownBy(() -> reader.query("SELECT amount FROM ledger").close())
                    .isInstanceOf(PravahaClientException.class)
                    .hasMessageContaining("PRV-6100")
                    .hasMessageContaining("amount");
        }
    }

    @Test
    void aLoneSurrogateIsRefusedByNameRatherThanSentAsAQuestionMark() {
        // Protobuf encodes half a surrogate pair as '?', so the server would plan different SQL
        // from the SQL written and have no way to know. Over HTTP it is PRV-1053; over Flight the
        // client is the last place it can still be seen, so the client refuses it the same way.
        assertThatThrownBy(() -> client.query("SELECT user_id FROM user_volume WHERE user_id = '\ud800'"))
                .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class)
                .hasMessageContaining("PRV-1053")
                .hasMessageContaining("the SQL");
        assertThatThrownBy(() -> client.register("bad_\udc00", "SELECT user_id FROM user_volume", List.of(0)))
                .hasMessageContaining("PRV-1053");
    }

    @Test
    void anApplicationQueriesAndIterates() {
        List<String> seen = new ArrayList<>();
        try (QueryResult result = client.query("SELECT user_id, total FROM user_volume WHERE total > 40")) {
            for (Row row : result) {
                seen.add(row.getString("user_id") + "=" + row.getLong("total"));
            }
        }

        assertThat(seen).containsExactlyInAnyOrder("u1=300", "u2=50");
    }

    @Test
    void columnsAreKnownBeforeTheFirstRow() {
        try (QueryResult result = client.query("SELECT user_id, tier, total FROM user_volume")) {
            assertThat(result.columns()).containsExactly("user_id", "tier", "total");
        }
    }

    @Test
    void aNullColumnIsNullRatherThanEmpty() {
        try (QueryResult result = client.query("SELECT tier FROM user_volume WHERE user_id = 'u3'")) {
            Row row = result.iterator().next();
            assertThat(row.isNull("tier")).isTrue();
            assertThat(row.getString("tier")).isNull();
        }
    }

    @Test
    void readingANullAsAPrimitiveSaysSoRatherThanReturningZero() {
        // Zero is a value the view does not hold, and a caller reading a primitive has to decide
        // what absent means -- returning zero decides for them, wrongly and silently.
        try (QueryResult result = client.query("SELECT tier FROM user_volume WHERE user_id = 'u3'")) {
            Row row = result.iterator().next();
            assertThatThrownBy(() -> row.getLong("tier"))
                    .isInstanceOf(PravahaClientException.class)
                    .hasMessageContaining("is null");
        }
    }

    @Test
    void anUnknownColumnNamesWhatIsThere() {
        try (QueryResult result = client.query("SELECT user_id FROM user_volume")) {
            Row row = result.iterator().next();
            assertThatThrownBy(() -> row.getString("nope"))
                    .isInstanceOf(PravahaClientException.class)
                    .hasMessageContaining("[user_id]");
        }
    }

    @Test
    void aRefusedQueryCarriesTheServersOwnDiagnosis() {
        // "PRV-4023 ... this server serves [user_volume]" is actionable; "query failed" is not.
        assertThatThrownBy(() -> client.query("SELECT * FROM nowhere"))
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("nowhere");
    }

    @Test
    void iteratingTwiceIsRefusedRatherThanSilentlyEmpty() {
        // A stream is consumed once. A second loop quietly seeing nothing is a bug that looks like
        // missing data.
        try (QueryResult result = client.query("SELECT user_id FROM user_volume")) {
            assertThat(result.toList()).hasSize(3);
            assertThatThrownBy(result::iterator)
                    .isInstanceOf(PravahaClientException.class)
                    .hasMessageContaining("already been iterated");
        }
    }

    @Test
    void aResultSpanningManyBatchesIteratesStraightThrough() {
        ServedView big = new ServedView("big", SCHEMA, List.of(0), 20_000);
        for (int i = 0; i < 10_000; i++) {
            big.applyValues(new Object[] {"u" + i, "gold", (long) i}, 1, 10);
        }
        big.commit(10);
        server.catalog().register(big);

        try (QueryResult result = client.query("SELECT user_id, total FROM big")) {
            long total = 0;
            int rows = 0;
            for (Row row : result) {
                total += row.getLong("total");
                rows++;
            }
            assertThat(rows).isEqualTo(10_000);
            assertThat(total).isEqualTo(49_995_000L);
        }
    }
}
