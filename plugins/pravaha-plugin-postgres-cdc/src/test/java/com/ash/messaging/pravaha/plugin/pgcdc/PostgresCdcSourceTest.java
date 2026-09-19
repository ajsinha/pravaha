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
package com.ash.messaging.pravaha.plugin.pgcdc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code postgres-cdc} against a real PostgreSQL 16 running with {@code wal_level=logical}: every
 * row here came through a replication slot, decoded from {@code pgoutput} by this plugin.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class PostgresCdcSourceTest {

    private String table;
    private final List<AutoCloseable> open = new ArrayList<>();

    @BeforeEach
    void createTable() {
        table = PgServer.unique("customers");
        PgServer.sql(
                "CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, tier TEXT NOT NULL, region TEXT)",
                "ALTER TABLE " + table + " REPLICA IDENTITY FULL");
    }

    @AfterEach
    void closeEverything() throws Exception {
        for (AutoCloseable closeable : open.reversed()) {
            closeable.close();
        }
        PgServer.dropSlotQuietly(table);
    }

    private PostgresCdcSourcePlugin plugin(Map<String, String> extra) {
        Map<String, String> options = PgServer.options(table);
        options.putAll(extra);
        PostgresCdcSourcePlugin plugin = PgServer.open(options);
        open.add(plugin);
        return plugin;
    }

    private PostgresCdcSourcePlugin plugin() {
        return plugin(Map.of());
    }

    private PartitionReader reader(PostgresCdcSourcePlugin plugin, SourceOffset from) {
        PartitionReader reader = plugin.createReader(new SourcePartition(table, 0, Map.of()), from);
        open.add(reader);
        return reader;
    }

    private static int drain(PartitionReader reader, Captured sink) {
        int total = 0;
        int polled;
        while ((polled = reader.poll(sink, 1024)) > 0) {
            total += polled;
        }
        return total;
    }

    @Test
    void anInsertIsPlusOneADeleteIsMinusOneOfTheWholeOldRowAndAnUpdateIsBoth() {
        PostgresCdcSourcePlugin plugin = plugin();
        PgServer.sql(
                "INSERT INTO " + table + " VALUES (42, 'silver', 'EU')",
                "UPDATE " + table + " SET tier = 'gold' WHERE id = 42",
                "DELETE FROM " + table + " WHERE id = 42");
        Captured sink = new Captured(plugin.schema());
        PartitionReader reader = reader(plugin, null);
        drain(reader, sink);

        assertThat(sink.texts())
                .as("the worked example of CONNECTORS.md section 5, from a real WAL: the update retracts the "
                        + "whole silver row, region and all, which only REPLICA IDENTITY FULL makes possible")
                .containsExactly(
                        "+1 [42, silver, EU]", "-1 [42, silver, EU]", "+1 [42, gold, EU]", "-1 [42, gold, EU]");
    }

    @Test
    void aMultiStatementTransactionArrivesWholeInOnePollAndARolledBackOneNeverArrives() {
        PostgresCdcSourcePlugin plugin = plugin();
        PgServer.transaction(
                "INSERT INTO " + table + " VALUES (1, 'silver', 'EU')",
                "INSERT INTO " + table + " VALUES (2, 'silver', 'US')",
                "UPDATE " + table + " SET tier = 'gold' WHERE id = 1");
        try (java.sql.Connection connection = PgServer.connect()) {
            connection.setAutoCommit(false);
            connection.createStatement().execute("INSERT INTO " + table + " VALUES (3, 'bronze', 'EU')");
            connection.rollback();
        } catch (java.sql.SQLException e) {
            throw new AssertionError(e);
        }
        PgServer.sql("INSERT INTO " + table + " VALUES (4, 'gold', 'EU')");

        Captured sink = new Captured(plugin.schema());
        PartitionReader reader = reader(plugin, null);
        assertThat(reader.poll(sink, 4))
                .as("the three-statement transaction's four rows fit in a poll of four, and come as one")
                .isEqualTo(4);
        assertThat(sink.texts())
                .containsExactly("+1 [1, silver, EU]", "+1 [2, silver, US]", "-1 [1, silver, EU]", "+1 [1, gold, EU]");
        SourceOffset afterFirst = reader.position();

        assertThat(reader.poll(sink, 1024)).isEqualTo(1);
        assertThat(sink.texts())
                .as("the rolled-back insert of id 3 was never committed, so it never appears")
                .doesNotContain("+1 [3, bronze, EU]")
                .endsWith("+1 [4, gold, EU]");
        assertThat(CdcOffset.parse(afterFirst).isPartial())
                .as("a checkpoint between polls falls between transactions")
                .isFalse();
    }

    @Test
    void aPollWithTooLittleRoomWaitsForTheWholeTransactionRatherThanSplittingIt() {
        PostgresCdcSourcePlugin plugin = plugin();
        PgServer.transaction(
                "INSERT INTO " + table + " VALUES (1, 'a', 'EU')",
                "INSERT INTO " + table + " VALUES (2, 'b', 'EU')",
                "INSERT INTO " + table + " VALUES (3, 'c', 'EU')");
        Captured sink = new Captured(plugin.schema());
        PartitionReader reader = reader(plugin, null);
        assertThat(reader.poll(sink, 1024)).isEqualTo(3);

        PgServer.transaction(
                "INSERT INTO " + table + " VALUES (4, 'd', 'EU')", "INSERT INTO " + table + " VALUES (5, 'e', 'EU')");
        sink.clear();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        int taken = 0;
        while (taken == 0 && System.nanoTime() < deadline) {
            taken = reader.poll(sink, 1);
            if (taken == 0) {
                // Room for one: the two-row transaction must wait for a poll it fits in.
                assertThat(sink.rows()).isEmpty();
                taken = reader.poll(sink, 2);
            }
        }
        assertThat(sink.texts()).containsExactly("+1 [4, d, EU]", "+1 [5, e, EU]");
    }

    @Test
    void aTransactionLargerThanAnyPollArrivesInPartsAndAResumeInsideItDeliversExactlyTheRest() {
        PostgresCdcSourcePlugin plugin = plugin();
        PgServer.sql("INSERT INTO " + table + " SELECT g, 'bulk', 'EU' FROM generate_series(1, 10) g");
        Captured sink = new Captured(plugin.schema());
        PartitionReader reader = reader(plugin, null);
        assertThat(reader.poll(sink, 4)).isEqualTo(4);
        SourceOffset inside = reader.position();
        assertThat(CdcOffset.parse(inside).isPartial()).isTrue();
        assertThat(inside.token()).endsWith("+4");
        reader.close();

        Captured resumed = new Captured(plugin.schema());
        PartitionReader again = reader(plugin, inside);
        drain(again, resumed);
        assertThat(resumed.rows())
                .extracting(row -> row.values().get(0))
                .as("rows 5 to 10 of the one INSERT: none of the four the checkpoint holds, none missing")
                .containsExactly(5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(CdcOffset.parse(again.position()).isPartial()).isFalse();
    }

    @Test
    void aTableWithReplicaIdentityDefaultIsRefusedNamingTheAlterTable() {
        PgServer.sql("ALTER TABLE " + table + " REPLICA IDENTITY DEFAULT");
        Map<String, String> options = PgServer.options(table);

        assertThatThrownBy(() -> PgServer.open(options))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5112")
                .hasMessageContaining("REPLICA IDENTITY DEFAULT")
                .hasMessageContaining("ALTER TABLE public." + table + " REPLICA IDENTITY FULL;");
        assertThat(PgServer.scalar("SELECT count(*) FROM pg_replication_slots WHERE slot_name = '" + table + "'"))
                .as("refused before any server-side state was created")
                .isEqualTo("0");
    }

    @Test
    void anUnchangedToastValueIsCarriedForwardFromTheBeforeImageNeverWrittenAsAPlaceholder() {
        PgServer.sql(
                "ALTER TABLE " + table + " ADD COLUMN notes TEXT",
                // EXTERNAL: stored out of line, uncompressed, so a value this size is certainly TOASTed.
                "ALTER TABLE " + table + " ALTER COLUMN notes SET STORAGE EXTERNAL");
        PostgresCdcSourcePlugin plugin = plugin();
        PgServer.sql("INSERT INTO " + table
                + " SELECT 7, 'silver', 'EU', string_agg(md5(g::text), '') FROM generate_series(1, 2000) g");
        String notes = PgServer.scalar("SELECT notes FROM " + table + " WHERE id = 7");
        assertThat(notes).hasSize(64_000);
        PgServer.sql("UPDATE " + table + " SET tier = 'gold' WHERE id = 7");

        Captured sink = new Captured(plugin.schema());
        PostgresCdcReader reader = (PostgresCdcReader) reader(plugin, null);
        drain(reader, sink);

        assertThat(sink.rows()).hasSize(3);
        assertThat(sink.rows().get(2).toString()).startsWith("+1 [7, gold, EU, ");
        assertThat(sink.rows().get(2).values().get(3))
                .as("the after-image's notes arrived as the 'u' placeholder and were filled from the old row")
                .isEqualTo(notes);
        assertThat(sink.rows().get(1).values().get(3)).isEqualTo(notes);
        assertThat(reader.stream().carriedForward())
                .as("the placeholder path really ran: PostgreSQL did not send the unchanged value")
                .isEqualTo(1L);
    }

    @Test
    void restartingFromACheckpointedPositionLosesNothingAndRepeatsNothing() {
        PostgresCdcSourcePlugin plugin = plugin();
        for (int id = 1; id <= 3; id++) {
            PgServer.sql("INSERT INTO " + table + " VALUES (" + id + ", 'silver', 'EU')");
        }
        Captured first = new Captured(plugin.schema());
        PartitionReader reader = reader(plugin, null);
        drain(reader, first);
        // The checkpoint is durable and the process dies before its confirmation reaches the slot,
        // so the slot still stands at its creation: the restore must resume from the checkpoint's
        // LSN, not from wherever the slot is.
        SourceOffset checkpointed = reader.position();

        // Delivered after the checkpoint and then lost with the process: a restore must deliver it
        // again, because the restored state does not hold it.
        PgServer.sql("UPDATE " + table + " SET tier = 'gold' WHERE id = 2");
        Captured.pollUntil(reader, first, 1024, () -> first.rows().size() == 5, 20_000);
        // Running on for a while after delivering it, long enough for anything that would confirm
        // it to the slot to have done so -- which nothing may, since no checkpoint holds it.
        PgServer.sleep(500);
        reader.close();
        assertThat(PgServer.confirmedFlush(table))
                .as("delivered after the checkpoint, never confirmed")
                .isLessThanOrEqualTo(CdcOffset.parse(checkpointed).lsn());
        PgServer.sql("DELETE FROM " + table + " WHERE id = 3");

        Captured second = new Captured(plugin.schema());
        PartitionReader restarted = reader(plugin, checkpointed);
        drain(restarted, second);
        assertThat(second.texts())
                .as("exactly what came after the checkpoint: the update the dead process had delivered, and "
                        + "the delete made while nothing was reading -- and none of the three inserts before it")
                .containsExactly("-1 [2, silver, EU]", "+1 [2, gold, EU]", "-1 [3, silver, EU]");
    }

    @Test
    void theSlotIsConfirmedOnlyAtCheckpointedPositionsAndNeverBackwards() {
        PostgresCdcSourcePlugin plugin = plugin();
        long created = PgServer.confirmedFlush(table);
        PgServer.sql("INSERT INTO " + table + " VALUES (1, 'silver', 'EU')");
        Captured sink = new Captured(plugin.schema());
        PartitionReader reader = reader(plugin, null);
        drain(reader, sink);
        assertThat(sink.rows()).hasSize(1);
        SourceOffset first = reader.position();

        PgServer.sleep(500);
        assertThat(PgServer.confirmedFlush(table))
                .as("delivered is not checkpointed: the slot has not moved")
                .isEqualTo(created);

        reader.checkpointed(first);
        awaitConfirmed(CdcOffset.parse(first).lsn());

        PgServer.sql("INSERT INTO " + table + " VALUES (2, 'silver', 'EU')");
        Captured.pollUntil(reader, sink, 1024, () -> sink.rows().size() == 2, 20_000);
        SourceOffset second = reader.position();
        reader.checkpointed(second);
        awaitConfirmed(CdcOffset.parse(second).lsn());

        reader.checkpointed(first);
        PgServer.sleep(300);
        assertThat(PgServer.confirmedFlush(table))
                .as("an older checkpoint reported late does not pull the slot back")
                .isEqualTo(CdcOffset.parse(second).lsn());
    }

    @Test
    void aRestoreFromAPositionTheSlotHasReleasedIsRefusedRatherThanSilentlySkipped() {
        PostgresCdcSourcePlugin plugin = plugin();
        PgServer.sql("INSERT INTO " + table + " VALUES (1, 'silver', 'EU')");
        Captured sink = new Captured(plugin.schema());
        PartitionReader reader = reader(plugin, null);
        drain(reader, sink);
        SourceOffset older = reader.position();
        PgServer.sql("INSERT INTO " + table + " VALUES (2, 'silver', 'EU')");
        Captured.pollUntil(reader, sink, 1024, () -> sink.rows().size() == 2, 20_000);
        SourceOffset newer = reader.position();
        reader.checkpointed(newer);
        awaitConfirmed(CdcOffset.parse(newer).lsn());
        reader.close();

        assertThatThrownBy(() -> reader(plugin, older))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5115")
                .hasMessageContaining("has already confirmed");
    }

    @Test
    void aHeartbeatMovesThePositionAndSoTheSlotOnATableThatNeverChanges() {
        PostgresCdcSourcePlugin plugin = plugin(Map.of("heartbeat.interval", "200ms"));
        Captured sink = new Captured(plugin.schema());
        PartitionReader reader = reader(plugin, null);
        reader.poll(sink, 1024);
        long start = CdcOffset.parse(reader.position()).lsn();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (CdcOffset.parse(reader.position()).lsn() <= start && System.nanoTime() < deadline) {
            reader.poll(sink, 1024);
            PgServer.sleep(50);
        }
        assertThat(sink.rows()).as("not one change to the table").isEmpty();
        SourceOffset moved = reader.position();
        assertThat(CdcOffset.parse(moved).lsn())
                .as("the heartbeat's WAL position became the reader's")
                .isGreaterThan(start);

        reader.checkpointed(moved);
        awaitConfirmed(CdcOffset.parse(moved).lsn());
    }

    @Test
    void withTheHeartbeatOffAQuietTablesPositionStandsStillWhileOtherTablesWriteWal() {
        PostgresCdcSourcePlugin plugin = plugin(Map.of("heartbeat.interval", "0"));
        Captured sink = new Captured(plugin.schema());
        PartitionReader reader = reader(plugin, null);
        reader.poll(sink, 1024);
        SourceOffset start = reader.position();
        String other = PgServer.unique("other");
        PgServer.sql(
                "CREATE TABLE " + other + " (id INT)", "INSERT INTO " + other + " SELECT generate_series(1, 1000)");
        for (int i = 0; i < 20; i++) {
            reader.poll(sink, 1024);
            PgServer.sleep(50);
        }
        assertThat(reader.position())
                .as("the problem the heartbeat exists for: WAL moves on, this slot's position does not")
                .isEqualTo(start);
    }

    @Test
    void aTruncateOfTheCapturedTableStopsTheSourceAfterDeliveringWhatCameBefore() {
        PostgresCdcSourcePlugin plugin = plugin();
        PgServer.sql("INSERT INTO " + table + " VALUES (1, 'silver', 'EU')", "TRUNCATE " + table);
        Captured sink = new Captured(plugin.schema());
        PartitionReader reader = reader(plugin, null);
        assertThat(reader.poll(sink, 1024)).isEqualTo(1);
        SourceOffset beforeTruncate = reader.position();

        assertThatThrownBy(() -> reader.poll(sink, 1024))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5116")
                .hasMessageContaining("TRUNCATE public." + table)
                .hasMessageContaining("pg_drop_replication_slot");
        assertThat(reader.position())
                .as("the position stays before the truncate, so nothing after it is claimed as delivered")
                .isEqualTo(beforeTruncate);
    }

    @Test
    void aDeclaredSchemaIsCheckedAgainstTheTableAndMayProjectIt() {
        Map<String, String> wrong = PgServer.options(table);
        wrong.put("schema", "id:INT64,tier:INT64");
        assertThatThrownBy(() -> PgServer.open(wrong))
                .hasMessageContaining("PRV-5113")
                .hasMessageContaining("column 'tier' is text in the table and declared INT64");

        Map<String, String> notNull = PgServer.options(table);
        notNull.put("schema", "id:INT64,region:STRING");
        assertThatThrownBy(() -> PgServer.open(notNull)).hasMessageContaining("region:STRING?");

        PostgresCdcSourcePlugin plugin = plugin(Map.of("schema", "id:INT64,tier:STRING"));
        PgServer.sql(
                "INSERT INTO " + table + " VALUES (1, 'silver', 'EU')",
                "UPDATE " + table + " SET region = 'US' WHERE id = 1");
        Captured sink = new Captured(plugin.schema());
        drain(reader(plugin, null), sink);
        assertThat(sink.texts())
                .as("an update to a column the stream leaves out is a retraction and an insertion that cancel")
                .containsExactly("+1 [1, silver]", "-1 [1, silver]", "+1 [1, silver]");
    }

    @Test
    void declaresTheCapabilitiesOfAChangelogAndReportsTheSlotsLagAsHealth() {
        PostgresCdcSourcePlugin plugin = plugin(Map.of("slot.lag.warn.bytes", "1"));
        SourceCapabilities capabilities = plugin.capabilities();
        assertThat(capabilities.emitsDeletes()).isTrue();
        assertThat(capabilities.emitsBeforeImage()).isTrue();
        assertThat(capabilities.replayableOffsets()).isTrue();
        assertThat(capabilities.guarantee()).isEqualTo(DeliveryGuarantee.EXACTLY_ONCE);

        PgServer.sql("INSERT INTO " + table + " SELECT g, 'x', 'EU' FROM generate_series(1, 100) g");
        HealthStatus health = plugin.health();
        assertThat(health.state())
                .as("retaining more than the configured one byte of WAL")
                .isEqualTo(HealthStatus.State.DEGRADED);
        assertThat(health.detail()).contains("slot '" + table + "'").contains("bytes of WAL");
        assertThat(plugin.slotStatus())
                .hasValueSatisfying(slot -> assertThat(slot.retainedBytes()).isPositive());
    }

    @Test
    void aMissingSlotIsRefusedWhenCreateSlotIsFalseNamingTheStatement() {
        Map<String, String> options = PgServer.options(table);
        options.put("create.slot", "false");
        assertThatThrownBy(() -> PgServer.open(options))
                .hasMessageContaining("PRV-5112")
                .hasMessageContaining("pg_create_logical_replication_slot('" + table + "', 'pgoutput')");
    }

    @Test
    void theSharedTlsOptionsAreRefusedBecauseTheUrlCarriesTls() {
        Map<String, String> options = PgServer.options(table);
        options.put("tls.enabled", "true");
        assertThatThrownBy(() -> PgServer.open(options))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("sslmode=verify-full");
    }

    private void awaitConfirmed(long lsn) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (PgServer.confirmedFlush(table) != lsn && System.nanoTime() < deadline) {
            PgServer.sleep(50);
        }
        assertThat(CdcOffset.format(PgServer.confirmedFlush(table)))
                .as("the slot's confirmed_flush_lsn")
                .isEqualTo(CdcOffset.format(lsn));
    }
}
