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
package com.ash.messaging.pravaha.plugin.mysqlcdc;

import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventData;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.QueryEventData;
import com.github.shyiko.mysql.binlog.event.RotateEventData;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import com.github.shyiko.mysql.binlog.event.XidEventData;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Binlog events to weighted rows, without a server: the mapping ADR-041 rests on. */
class TransactionAssemblerTest {

    record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    static MySqlCdcOptions options(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of("host", "db", "user", "cdc", "table", "shop.customers"));
        config.putAll(extra);
        return MySqlCdcOptions.from(new Ctx("customers-cdc", config));
    }

    private static final MySqlCdcOptions OPTIONS = options(Map.of());

    static final MySqlSchema.Mapping MAPPING = MySqlSchema.resolve(
            OPTIONS,
            List.of(
                    new MySqlSchema.Column("id", "bigint", "bigint", false, ""),
                    new MySqlSchema.Column("tier", "varchar", "varchar(16)", false, "utf8mb4"),
                    new MySqlSchema.Column("credit", "decimal", "decimal(10,2)", true, "")));

    private long position = 100;

    private Event event(EventType type, EventData data) {
        EventHeaderV4 header = new EventHeaderV4();
        header.setEventType(type);
        header.setTimestamp(1_700_000_000_000L);
        header.setEventLength(50);
        position += 50;
        header.setNextPosition(position);
        return new Event(header, data);
    }

    private Event query(String sql) {
        QueryEventData data = new QueryEventData();
        data.setSql(sql);
        data.setDatabase("shop");
        return event(EventType.QUERY, data);
    }

    /** BIGINT, VARCHAR(16), DECIMAL(10,2): MAPPING's columns as the binlog describes them. */
    private static final byte[] TYPES = {8, 15, (byte) 246};

    private static final int[] METADATA = {0, 64, 10 | (2 << 8)};

    private Event tableMap(long id, String table, int columns) {
        byte[] types = java.util.Arrays.copyOf(TYPES, columns);
        int[] metadata = java.util.Arrays.copyOf(METADATA, columns);
        return tableMap(id, table, types, metadata, null);
    }

    private Event tableMap(long id, String table, byte[] types, int[] metadata, java.util.BitSet nullable) {
        TableMapEventData data = new TableMapEventData();
        data.setTableId(id);
        data.setDatabase("shop");
        data.setTable(table);
        data.setColumnTypes(types);
        data.setColumnMetadata(metadata);
        data.setColumnNullability(nullable);
        return event(EventType.TABLE_MAP, data);
    }

    private Event gtid(String gtid) {
        com.github.shyiko.mysql.binlog.event.GtidEventData data =
                new com.github.shyiko.mysql.binlog.event.GtidEventData(
                        com.github.shyiko.mysql.binlog.event.MySqlGtid.fromString(gtid), (byte) 0, 0, 0, 0, 0, 0, 0, 0);
        return event(EventType.GTID, data);
    }

    private Event heartbeat(long at) {
        EventHeaderV4 header = new EventHeaderV4();
        header.setEventType(EventType.HEARTBEAT);
        header.setNextPosition(at);
        return new Event(header, null);
    }

    /** The rotate a server sends on connecting: no position of its own. */
    private Event connectRotate(String file, long at) {
        EventHeaderV4 header = new EventHeaderV4();
        header.setEventType(EventType.ROTATE);
        header.setNextPosition(0);
        RotateEventData data = new RotateEventData();
        data.setBinlogFilename(file);
        data.setBinlogPosition(at);
        return new Event(header, data);
    }

    private static Serializable[] row(long id, String tier, String credit) {
        return new Serializable[] {
            id, tier.getBytes(StandardCharsets.UTF_8), credit == null ? null : new BigDecimal(credit)
        };
    }

    private Event insert(long table, Serializable[]... rows) {
        WriteRowsEventData data = new WriteRowsEventData();
        data.setTableId(table);
        data.setRows(Arrays.asList(rows));
        return event(EventType.EXT_WRITE_ROWS, data);
    }

    private Event update(long table, Serializable[] before, Serializable[] after) {
        UpdateRowsEventData data = new UpdateRowsEventData();
        data.setTableId(table);
        data.setRows(List.of(new AbstractMap.SimpleEntry<>(before, after)));
        return event(EventType.EXT_UPDATE_ROWS, data);
    }

    private Event delete(long table, Serializable[] row) {
        DeleteRowsEventData data = new DeleteRowsEventData();
        data.setTableId(table);
        List<Serializable[]> rows = new ArrayList<>();
        rows.add(row);
        data.setRows(rows);
        return event(EventType.EXT_DELETE_ROWS, data);
    }

    private Event xid() {
        return event(EventType.XID, new XidEventData());
    }

    private static List<String> texts(BinlogTransaction transaction) {
        return transaction.changes().stream()
                .map(c -> (c.weight() > 0 ? "+" : "") + c.weight() + " " + Arrays.toString(c.values()))
                .toList();
    }

    private static List<BinlogTransaction> feed(TransactionAssembler assembler, Event... events) {
        List<BinlogTransaction> done = new ArrayList<>();
        for (Event event : events) {
            BinlogTransaction transaction = assembler.accept(event);
            if (transaction != null) {
                done.add(transaction);
            }
        }
        return done;
    }

    @Test
    void anInsertIsPlusOneAnUpdateIsMinusOldPlusNewAndADeleteIsMinusTheWholeOldRow() {
        TransactionAssembler assembler = new TransactionAssembler(OPTIONS, MAPPING, "bin.000001", 0);
        List<BinlogTransaction> done = feed(
                assembler,
                query("BEGIN"),
                tableMap(7, "customers", 3),
                insert(7, row(42, "silver", "10.50")),
                update(7, row(42, "silver", "10.50"), row(42, "gold", "10.50")),
                delete(7, row(42, "gold", "10.50")),
                xid());

        assertThat(done).hasSize(1);
        assertThat(texts(done.get(0)))
                .containsExactly(
                        "+1 [42, silver, 1050]", "-1 [42, silver, 1050]", "+1 [42, gold, 1050]", "-1 [42, gold, 1050]");
        assertThat(done.get(0).endPosition()).isEqualTo(position);
        assertThat(done.get(0).file()).isEqualTo("bin.000001");
        assertThat(done.get(0).commitNanos()).isEqualTo(1_700_000_000_000_000_000L);
    }

    @Test
    void anotherTablesRowsAreNotDeliveredAndItsTransactionIsOnlyAPositionMarker() {
        TransactionAssembler assembler = new TransactionAssembler(OPTIONS, MAPPING, "bin.000001", 0);
        List<BinlogTransaction> done = feed(
                assembler,
                event(EventType.ROTATE, rotate("bin.000002")),
                query("BEGIN"),
                tableMap(9, "orders", 3),
                insert(9, row(1, "x", null)),
                xid());
        assertThat(done).hasSize(2);
        assertThat(done.get(0).offset().toString())
                .as("a rotation to a new file is a position too (MYC-1)")
                .isEqualTo("binlog=bin.000002:4");
        assertThat(done.subList(1, 2)).singleElement().satisfies(t -> {
            assertThat(t.size()).isZero();
            assertThat(t.file()).isEqualTo("bin.000002");
            assertThat(t.endPosition()).isEqualTo(position);
        });
    }

    @Test
    void aRestoreInsideATransactionSkipsExactlyTheChangesTheCheckpointHolds() {
        TransactionAssembler assembler = new TransactionAssembler(OPTIONS, MAPPING, "bin.000001", 2);
        List<BinlogTransaction> done = feed(
                assembler,
                query("BEGIN"),
                tableMap(7, "customers", 3),
                insert(7, row(1, "a", null), row(2, "b", null), row(3, "c", null)),
                xid(),
                query("BEGIN"),
                tableMap(7, "customers", 3),
                insert(7, row(4, "d", null)),
                xid());
        assertThat(done.get(0).alreadyDelivered()).isEqualTo(2);
        assertThat(texts(done.get(0))).containsExactly("+1 [3, c, null]");
        assertThat(texts(done.get(1)))
                .as("only the first transaction is skipped into")
                .containsExactly("+1 [4, d, null]");
    }

    @Test
    void aTruncateOfTheTableIsRefusedAndOfAnotherTableIsNot() {
        TransactionAssembler assembler = new TransactionAssembler(OPTIONS, MAPPING, "bin.000001", 0);
        assertThat(feed(assembler, query("TRUNCATE TABLE orders")))
                .singleElement()
                .satisfies(t -> assertThat(t.failure()).isNull());
        assertThat(feed(assembler, query("TRUNCATE TABLE `shop`.`customers`")))
                .singleElement()
                .satisfies(t -> assertThat(t.failure())
                        .hasMessageContaining("TRUNCATE of shop.customers")
                        .extracting(e -> ((PravahaException) e).errorCode())
                        .isEqualTo(MySqlCdcErrors.UNREPRESENTABLE_CHANGE));
    }

    @Test
    void aTableMapWithADifferentColumnCountRefusesTheTransactionWhole() {
        TransactionAssembler assembler = new TransactionAssembler(OPTIONS, MAPPING, "bin.000001", 0);
        List<BinlogTransaction> done =
                feed(assembler, query("BEGIN"), tableMap(7, "customers", 4), insert(7, row(1, "a", null)), xid());
        assertThat(done).singleElement().satisfies(t -> {
            assertThat(t.failure()).hasMessageContaining("has 4 columns in the binlog and 3 in the stream");
            assertThat(t.changes()).isEmpty();
        });
    }

    @Test
    void aTypeChangeThatKeepsTheColumnCountIsRefusedWholeByName() {
        // MYC-4: SMALLINT to INT UNSIGNED, DECIMAL(10,2) to (12,4), NOT NULL to NULL, signed to unsigned.
        TransactionAssembler assembler = new TransactionAssembler(OPTIONS, MAPPING, "bin.000001", 0);
        assertThat(feed(
                        assembler,
                        query("BEGIN"),
                        tableMap(7, "customers", new byte[] {3, 15, (byte) 246}, METADATA, null),
                        insert(7, row(1, "a", null)),
                        xid()))
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.changes()).isEmpty();
                    assertThat(t.failure())
                            .hasMessageContaining("column 'id' (bigint) arrives in the binlog as MySQL type 3 (INT)")
                            .extracting(e -> ((PravahaException) e).errorCode())
                            .isEqualTo(MySqlCdcErrors.UNREPRESENTABLE_CHANGE);
                });
        assertThat(feed(
                        assembler,
                        query("BEGIN"),
                        tableMap(7, "customers", TYPES, new int[] {0, 64, 12 | (4 << 8)}, null),
                        xid()))
                .singleElement()
                .satisfies(t -> assertThat(t.failure()).hasMessageContaining("arrives in the binlog as DECIMAL(12,4)"));
        java.util.BitSet nullable = new java.util.BitSet();
        nullable.set(1);
        assertThat(feed(assembler, query("BEGIN"), tableMap(7, "customers", TYPES, METADATA, nullable), xid()))
                .singleElement()
                .satisfies(t -> assertThat(t.failure())
                        .hasMessageContaining("column 'tier' (varchar(16)) is NOT NULL in the stream and nullable"));
        TableMapEventData unsigned =
                (TableMapEventData) tableMap(7, "customers", 3).getData();
        com.github.shyiko.mysql.binlog.event.TableMapEventMetadata metadata =
                new com.github.shyiko.mysql.binlog.event.TableMapEventMetadata();
        java.util.BitSet signedness = new java.util.BitSet();
        signedness.set(0);
        metadata.setSignedness(signedness);
        unsigned.setEventMetadata(metadata);
        assertThat(feed(assembler, query("BEGIN"), event(EventType.TABLE_MAP, unsigned), xid()))
                .singleElement()
                .satisfies(t -> assertThat(t.failure()).hasMessageContaining("column 'id' (bigint) is UNSIGNED"));
        assertThat(feed(assembler, query("BEGIN"), tableMap(7, "customers", 3), insert(7, row(2, "b", null)), xid()))
                .as("the columns as the stream has them are read")
                .singleElement()
                .satisfies(t -> assertThat(texts(t)).containsExactly("+1 [2, b, null]"));
    }

    @Test
    void anAlterBehindACommentIsStillRefused() {
        TransactionAssembler assembler = new TransactionAssembler(OPTIONS, MAPPING, "bin.000001", 0);
        assertThat(feed(assembler, query("/* app: migrate */ -- step 2\n ALTER TABLE customers MODIFY credit INT")))
                .singleElement()
                .satisfies(t -> assertThat(t.failure()).hasMessageContaining("ALTER of shop.customers"));
        assertThat(feed(assembler, query("/* app */ ALTER TABLE orders ADD x INT")))
                .singleElement()
                .satisfies(t -> assertThat(t.failure()).isNull());
    }

    @Test
    void aHeartbeatOrARotationBetweenTransactionsMovesThePositionAndNeverInsideOne() {
        // MYC-1: an idle table's position follows the log.
        TransactionAssembler assembler = new TransactionAssembler(OPTIONS, MAPPING, "bin.000001", 0);
        assertThat(feed(assembler, connectRotate("bin.000001", 4)))
                .as("the rotate a connection starts with repeats where reading starts")
                .isEmpty();
        assertThat(feed(assembler, heartbeat(900)))
                .singleElement()
                .satisfies(t -> assertThat(t.offset().toString()).isEqualTo("binlog=bin.000001:900"));
        assertThat(feed(
                        assembler,
                        query("BEGIN"),
                        tableMap(7, "customers", 3),
                        heartbeat(1200),
                        insert(7, row(1, "a", null)),
                        xid()))
                .as("inside a transaction a heartbeat is not a boundary")
                .singleElement()
                .satisfies(t -> assertThat(t.size()).isEqualTo(1));

        TransactionAssembler restored = new TransactionAssembler(OPTIONS, MAPPING, "bin.000001", 1);
        assertThat(feed(restored, heartbeat(900), event(EventType.ROTATE, rotate("bin.000002"))))
                .as("a restored partial transaction's position holds until it has arrived")
                .isEmpty();
    }

    @Test
    void inGtidModeEveryPositionCarriesTheExecutedSetAndAPartialSkipFindsItsOwnTransaction() {
        String uuid = "3e11fa47-71ca-11e1-9e33-c80aa9429562";
        TransactionAssembler assembler =
                new TransactionAssembler(OPTIONS, MAPPING, "bin.000001", 1, uuid + ":1-5", uuid + ":7");
        List<BinlogTransaction> done = feed(
                assembler,
                gtid(uuid + ":6"),
                query("BEGIN"),
                tableMap(7, "customers", 3),
                insert(7, row(1, "a", null), row(2, "b", null)),
                xid(),
                gtid(uuid + ":7"),
                query("BEGIN"),
                tableMap(7, "customers", 3),
                insert(7, row(3, "c", null), row(4, "d", null)),
                xid(),
                gtid(uuid + ":8"),
                query("CREATE TABLE other (x INT)"),
                heartbeat(2000));
        assertThat(texts(done.get(0)))
                .as("not the transaction the checkpoint counted into")
                .containsExactly("+1 [1, a, null]", "+1 [2, b, null]");
        assertThat(done.get(0).executedGtids()).isEqualTo(uuid + ":1-6");
        assertThat(texts(done.get(1))).as("the one it did").containsExactly("+1 [4, d, null]");
        assertThat(done.get(1).gtid()).isEqualTo(uuid + ":7");
        assertThat(done.get(2).offset().gtidSet()).isEqualTo(uuid + ":1-8");
        assertThat(done.get(3).offset().toString()).isEqualTo("gtid=" + uuid + ":1-8;binlog=bin.000001:2000");
    }

    @Test
    void gtidOffsetsRoundTrip() {
        String uuid = "3e11fa47-71ca-11e1-9e33-c80aa9429562";
        BinlogOffset offset = new BinlogOffset(
                "mysql-bin.000003", 1547, 12, uuid + ":1-5,4a0f0a1b-71ca-11e1-9e33-c80aa9429562:1", uuid + ":6");
        assertThat(BinlogOffset.parse(offset.toSourceOffset())).isEqualTo(offset);
        assertThat(offset.toSourceOffset().token())
                .startsWith("gtid=" + uuid + ":1-5,")
                .endsWith(";partial=12@" + uuid + ":6");
        BinlogOffset empty = BinlogOffset.at("mysql-bin.000001", 4, "");
        assertThat(BinlogOffset.parse(empty.toSourceOffset())).isEqualTo(empty);
        assertThatThrownBy(() -> BinlogOffset.parse(new SourceOffset("binlog=f:10;partial=2@" + uuid + ":6")))
                .as("a partial transaction named by GTID belongs to a GTID position")
                .hasMessageContaining("not a mysql-cdc offset");
        assertThatThrownBy(() -> BinlogOffset.parse(new SourceOffset("gtid=x'1;binlog=f:10")))
                .hasMessageContaining("not a mysql-cdc offset");
    }

    @Test
    void aValueThatCannotBeReadMakesARejectedRowNotAGuess() {
        BinlogTransaction.Change change =
                BinlogTransaction.Change.of(MAPPING, new Serializable[] {1L, null, new BigDecimal("1.234")}, 1);
        assertThat(change.rejected()).contains("'tier' is NULL");
        change = BinlogTransaction.Change.of(
                MAPPING, new Serializable[] {1L, "a".getBytes(UTF_8), new BigDecimal("1.234")}, 1);
        assertThat(change.rejected()).contains("column 'credit'");
    }

    @Test
    void offsetsRoundTripAndAnyOtherTokenIsRefused() {
        BinlogOffset partial = new BinlogOffset("mysql-bin.000003", 1547, 4096);
        assertThat(partial.toSourceOffset().token()).isEqualTo("binlog=mysql-bin.000003:1547;partial=4096");
        assertThat(BinlogOffset.parse(partial.toSourceOffset())).isEqualTo(partial);
        assertThat(BinlogOffset.parse(SourceOffset.BEGINNING)).isNull();
        assertThatThrownBy(() -> BinlogOffset.parse(new SourceOffset("lsn=0/16B3748")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("not a mysql-cdc offset");
    }

    @Test
    void snapshotModeInitialAndADeclaredSchemaAreRefusedByName() {
        assertThatThrownBy(() -> options(Map.of("snapshot.mode", "initial")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("snapshot.mode 'initial' is not built for mysql-cdc");
        assertThatThrownBy(() -> options(Map.of("schema", "id:INT64")))
                .hasMessageContaining("declared 'schema' is not supported");
        assertThatThrownBy(() -> options(Map.of("table", "customers"))).hasMessageContaining("'database.table'");
        assertThat(options(Map.of("start.timeout", "500ms")).startTimeout().toMillis())
                .isEqualTo(500);
    }

    private static RotateEventData rotate(String file) {
        RotateEventData data = new RotateEventData();
        data.setBinlogFilename(file);
        data.setBinlogPosition(4);
        return data;
    }
}
