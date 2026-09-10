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
package com.ash.messaging.pravaha.flight;

import java.util.ArrayList;
import java.util.List;

import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A client asks a question over the wire and iterates the answer.
 *
 * <p>The point of ADR-030 in one test: SQL in, Arrow record batches out, over a protocol whose
 * clients somebody else maintains. This uses the Flight SQL Java client, which is the same client
 * the JDBC driver and the Python and Go clients are built on -- so what passes here is what those
 * see, rather than a Java-shaped approximation of it.
 */
@Timeout(60)
class FlightSqlEndToEndTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("tier", Types.string().withNullable(true))
            .field("total", Types.int64())
            .build();

    private BufferAllocator allocator;
    private PravahaFlightServer server;
    private FlightSqlClient client;

    @BeforeEach
    void startServer() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", "gold", 300L}, 1, 10);
        view.applyValues(new Object[] {"u2", "silver", 50L}, 1, 10);
        view.applyValues(new Object[] {"u3", null, 7L}, 1, 10);
        view.commit(10);

        server = new PravahaFlightServer(new ViewCatalog().register(view), allocator).start("localhost", 0);
        client = new FlightSqlClient(org.apache.arrow.flight.FlightClient.builder(
                        allocator, Location.forGrpcInsecure("localhost", server.port()))
                .build());
    }

    @AfterEach
    void stopServer() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        if (allocator != null) {
            allocator.close();
        }
    }

    /** Runs a query and collects (user_id, total) pairs, iterating the stream as a client would. */
    private List<String> query(String sql) {
        FlightInfo info = client.execute(sql);
        List<String> rows = new ArrayList<>();
        try (FlightStream stream = client.getStream(info.getEndpoints().get(0).getTicket())) {
            while (stream.next()) {
                VectorSchemaRoot root = stream.getRoot();
                for (int i = 0; i < root.getRowCount(); i++) {
                    StringBuilder row = new StringBuilder();
                    for (int column = 0; column < root.getFieldVectors().size(); column++) {
                        if (column > 0) {
                            row.append('|');
                        }
                        row.append(valueAt(root, column, i));
                    }
                    rows.add(row.toString());
                }
            }
        } catch (Exception e) {
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
        }
        return rows;
    }

    private static String valueAt(VectorSchemaRoot root, int column, int index) {
        var vector = root.getVector(column);
        if (vector.isNull(index)) {
            return "null";
        }
        if (vector instanceof VarCharVector text) {
            return new String(text.get(index), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (vector instanceof BigIntVector number) {
            return String.valueOf(number.get(index));
        }
        return String.valueOf(vector.getObject(index));
    }

    @Test
    void aClientRunsSqlAndIteratesTheAnswer() {
        assertThat(query("SELECT user_id, total FROM user_volume"))
                .containsExactlyInAnyOrder("u1|300", "u2|50", "u3|7");
    }

    @Test
    void theSchemaIsKnownBeforeAnyRowsArrive() {
        // What getFlightInfo is for, and why the query is planned before it is run: a JDBC driver
        // asks for column types to build its ResultSetMetaData before fetching anything.
        FlightInfo info = client.execute("SELECT user_id, tier, total FROM user_volume");

        assertThat(info.getSchema().getFields().stream().map(f -> f.getName()).toList())
                .containsExactly("user_id", "tier", "total");
        assertThat(info.getSchema().findField("total").getType())
                .isEqualTo(new org.apache.arrow.vector.types.pojo.ArrowType.Int(64, true));
    }

    @Test
    void aPredicateIsTheEnginesOwnPredicate() {
        assertThat(query("SELECT user_id FROM user_volume WHERE total > 100")).containsExactly("u1");
    }

    @Test
    void nullCrossesTheWireAsNullRatherThanAsEmpty() {
        // A tier that is absent must arrive absent. An empty string here would be a value the view
        // does not hold, and every downstream COALESCE would be wrong about it.
        assertThat(query("SELECT tier FROM user_volume WHERE user_id = 'u3'")).containsExactly("null");
    }

    @Test
    void anAggregateIsAnsweredOverTheWire() {
        assertThat(query("SELECT SUM(total) FROM user_volume")).containsExactly("357");
    }

    @Test
    void anEmptyResultStillCompletesRatherThanHanging() {
        // A result with no rows still needs a batch, or a client iterating waits for data that will
        // never come -- which looks exactly like a hung server.
        assertThat(query("SELECT user_id FROM user_volume WHERE total > 99999")).isEmpty();
    }

    @Test
    void aBadQueryComesBackAsAMessageRatherThanACrash() {
        assertThatThrownBy(() -> query("SELECT * FROM nowhere")).hasMessageContaining("nowhere");
    }

    @Test
    void aResultLargerThanOneBatchIsStreamedInSeveral() {
        // The server must not need to hold what the client is iterating. Ten thousand rows is more
        // than one batch, so this exercises the boundary rather than assuming it.
        ServedView big = new ServedView("big", SCHEMA, List.of(0), 20_000);
        for (int i = 0; i < 10_000; i++) {
            big.applyValues(new Object[] {"u" + i, "gold", (long) i}, 1, 10);
        }
        big.commit(10);
        server.catalog().register(big);

        assertThat(query("SELECT user_id, total FROM big")).hasSize(10_000);
    }
}
