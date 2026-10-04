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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ReadChannel;
import org.apache.arrow.vector.ipc.message.MessageSerializer;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FLIGHTDECIMAL-1: a DECIMAL column reaches a Flight client as Arrow's Decimal128, digit for digit.
 *
 * <p>It was refused with PRV-6100 until 2.1, so a view whose answer carried a DECIMAL could be read
 * only over the PostgreSQL gateway -- while every SDK, the CLI and the console read over Flight.
 * These are the wire-level facts, asserted with Arrow's own Flight SQL client rather than an SDK:
 * the type a client is told (statement schema and GetTables), the values it reads, and that a value
 * the column cannot hold exactly is refused by name rather than rounded.
 */
@Timeout(60)
class FlightDecimalTest {

    private static final StreamSchema LEDGER = StreamSchema.builder("ledger")
            .field("entry_id", Types.string())
            .field("amount", Types.decimal(18, 2))
            .field("rate", Types.decimal(38, 10).withNullable(true))
            .build();

    /** The largest DECIMAL(38, 10): 28 integer digits and 10 places, all nines. */
    private static final BigDecimal WIDEST = new BigDecimal("9".repeat(28) + "." + "9".repeat(10));

    private BufferAllocator allocator;
    private PravahaFlightServer server;
    private FlightSqlClient client;

    @BeforeEach
    void start() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        ServedView ledger = new ServedView("ledger", LEDGER, List.of(0), 1_000);
        ledger.applyValues(new Object[] {"e1", new BigDecimal("1234567890123456.78"), WIDEST}, 1, 10);
        ledger.applyValues(new Object[] {"e2", new BigDecimal("-0.01"), WIDEST.negate()}, 1, 10);
        ledger.applyValues(new Object[] {"e3", new BigDecimal("0.00"), null}, 1, 10);
        // Fewer places than the scale is the same number, and arrives at the column's scale.
        ledger.applyValues(new Object[] {"e4", new BigDecimal("2.5"), new BigDecimal("0.0000000001")}, 1, 10);
        ledger.commit(10);
        server = new PravahaFlightServer(new ViewCatalog().register(ledger), allocator).start("localhost", 0);
        client = new FlightSqlClient(org.apache.arrow.flight.FlightClient.builder(
                        allocator, Location.forGrpcInsecure("localhost", server.port()))
                .build());
    }

    @AfterEach
    void stop() throws Exception {
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

    @Test
    void aDecimalColumnIsDecimal128WithItsOwnPrecisionAndScale() {
        Schema schema = client.getExecuteSchema("SELECT entry_id, amount, rate FROM ledger")
                .getSchema();

        assertThat(schema.findField("amount").getType()).isEqualTo(new ArrowType.Decimal(18, 2, 128));
        assertThat(schema.findField("rate").getType()).isEqualTo(new ArrowType.Decimal(38, 10, 128));
        assertThat(schema.findField("amount").isNullable()).isFalse();
        assertThat(schema.findField("rate").isNullable()).isTrue();
    }

    @Test
    void everyValueArrivesExactlyAtItsColumnsScale() throws Exception {
        List<List<BigDecimal>> rows = new ArrayList<>();
        FlightInfo info = client.execute("SELECT entry_id, amount, rate FROM ledger");
        try (FlightStream stream = client.getStream(info.getEndpoints().get(0).getTicket())) {
            while (stream.next()) {
                VectorSchemaRoot root = stream.getRoot();
                DecimalVector amount = (DecimalVector) root.getVector("amount");
                DecimalVector rate = (DecimalVector) root.getVector("rate");
                for (int i = 0; i < root.getRowCount(); i++) {
                    List<BigDecimal> row = new ArrayList<>();
                    row.add(amount.getObject(i));
                    row.add(rate.isNull(i) ? null : rate.getObject(i));
                    rows.add(row);
                }
            }
        }

        // equals, not compareTo: BigDecimal.equals compares the scale too, so 2.5 arriving as 2.5
        // rather than 2.50 would fail here.
        assertThat(rows)
                .containsExactlyInAnyOrder(
                        java.util.Arrays.asList(new BigDecimal("1234567890123456.78"), WIDEST),
                        java.util.Arrays.asList(new BigDecimal("-0.01"), WIDEST.negate()),
                        java.util.Arrays.asList(new BigDecimal("0.00"), null),
                        java.util.Arrays.asList(new BigDecimal("2.50"), new BigDecimal("0.0000000001")));
    }

    @Test
    void getTablesDescribesTheColumnAsTheQueryDoes() throws Exception {
        FlightInfo info = client.getTables(null, null, "ledger", null, true);
        byte[] serialised = null;
        try (FlightStream stream = client.getStream(info.getEndpoints().get(0).getTicket())) {
            while (stream.next() && serialised == null) {
                VectorSchemaRoot root = stream.getRoot();
                if (root.getRowCount() > 0) {
                    serialised = ((org.apache.arrow.vector.VarBinaryVector) root.getVector("table_schema")).get(0);
                }
            }
        }
        assertThat(serialised).isNotNull();
        Schema schema = MessageSerializer.deserializeSchema(
                new ReadChannel(java.nio.channels.Channels.newChannel(new java.io.ByteArrayInputStream(serialised))));

        assertThat(schema.findField("amount").getType()).isEqualTo(new ArrowType.Decimal(18, 2, 128));
    }

    @Test
    void aValueWithMorePlacesThanItsScaleIsRefusedByNameNotRounded() {
        Schema arrow = ArrowSchemas.toArrow(LEDGER);
        try (VectorSchemaRoot root = VectorSchemaRoot.create(arrow, allocator)) {
            assertThatThrownBy(() ->
                            ArrowSchemas.write(root, 0, new Object[] {"x", new BigDecimal("1.005"), null}, LEDGER))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-6100")
                    .hasMessageContaining("'amount'")
                    .hasMessageContaining("1.005")
                    .hasMessageContaining("rather than rounded");
            assertThatThrownBy(() -> ArrowSchemas.write(
                            root, 0, new Object[] {"x", new BigDecimal("12345678901234567.00"), null}, LEDGER))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("'amount'")
                    .hasMessageContaining("precision");
            assertThatThrownBy(() -> ArrowSchemas.write(root, 0, new Object[] {"x", 1.25d, null}, LEDGER))
                    .as("a double is never an exact decimal, whatever value it happens to hold")
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("not an exact decimal");

            ArrowSchemas.write(root, 0, new Object[] {"x", 7L, null}, LEDGER);
            assertThat(((DecimalVector) root.getVector("amount")).getObject(0)).isEqualTo(new BigDecimal("7.00"));
        }
    }
}
