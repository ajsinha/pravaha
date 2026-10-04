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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The hand-written half of ADR-041 without a server: {@code pgoutput} bytes built here the way the
 * protocol documentation lays them out, decoded and assembled into weighted transactions.
 */
class PgOutputTest {

    private static final int OID = 16_384;

    private static final CdcOptions OPTIONS = CdcOptions.from(new PgServer.Ctx(
            "cdc", Map.of("url", "jdbc:postgresql://db:5432/crm", "table", "public.customers", "slot", "crm")));

    private static final CdcSchema.Mapping MAPPING = CdcSchema.resolve(
            OPTIONS,
            List.of(
                    new CdcSchema.Column("id", PgValues.INT8, 'b', true, -1, "bigint"),
                    new CdcSchema.Column("tier", PgValues.TEXT, 'b', true, -1, "text"),
                    new CdcSchema.Column("notes", PgValues.TEXT, 'b', false, -1, "text")));

    /** Builds one message the way PostgreSQL writes it. */
    private static final class Message {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final DataOutputStream out = new DataOutputStream(bytes);

        Message(char type) {
            byteValue(type);
        }

        Message byteValue(int value) {
            try {
                out.writeByte(value);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            return this;
        }

        Message int16(int value) {
            try {
                out.writeShort(value);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            return this;
        }

        Message int32(int value) {
            try {
                out.writeInt(value);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            return this;
        }

        Message int64(long value) {
            try {
                out.writeLong(value);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            return this;
        }

        Message string(String value) {
            bytes.writeBytes(value.getBytes(StandardCharsets.UTF_8));
            return byteValue(0);
        }

        /** A tuple: {@code null} is 'n', "\u0000u" is the unchanged-TOAST placeholder, else 't'. */
        Message tuple(String... values) {
            int16(values.length);
            for (String value : values) {
                if (value == null) {
                    byteValue('n');
                } else if (value.equals("\u0000u")) {
                    byteValue('u');
                } else {
                    byte[] text = value.getBytes(StandardCharsets.UTF_8);
                    byteValue('t').int32(text.length);
                    bytes.writeBytes(text);
                }
            }
            return this;
        }

        PgOutput.Message decode() {
            return PgOutput.decode(ByteBuffer.wrap(bytes.toByteArray()));
        }

        PgOutput.Message decodeWith(byte[] tail) {
            bytes.writeBytes(tail);
            return decode();
        }
    }

    private static final String TOAST = "\u0000u";

    private static PgOutput.Message relation(char identity) {
        return new Message('R')
                .int32(OID)
                .string("public")
                .string("customers")
                .byteValue(identity)
                .int16(3)
                .byteValue(1)
                .string("id")
                .int32(PgValues.INT8)
                .int32(-1)
                .byteValue(0)
                .string("tier")
                .int32(PgValues.TEXT)
                .int32(-1)
                .byteValue(0)
                .string("notes")
                .int32(PgValues.TEXT)
                .int32(-1)
                .decode();
    }

    private static PgOutput.Message begin() {
        return new Message('B').int64(0x100).int64(0).int32(7).decode();
    }

    private static PgOutput.Message commit(long end) {
        return new Message('C').byteValue(0).int64(end - 8).int64(end).int64(0).decode();
    }

    private static PgOutput.Message insert(@Nullable String... values) {
        return new Message('I').int32(OID).byteValue('N').tuple(values).decode();
    }

    private static PgOutput.Message update(char oldKind, String[] before, String[] after) {
        Message message = new Message('U').int32(OID);
        if (oldKind != 0) {
            message.byteValue(oldKind).tuple(before);
        }
        return message.byteValue('N').tuple(after).decode();
    }

    private final List<CdcTransaction> out = new ArrayList<>();

    private TransactionAssembler assembler(CdcOffset resume) {
        return new TransactionAssembler(OPTIONS, MAPPING, OID, resume, out::add, content -> {});
    }

    private static List<String> rows(CdcTransaction transaction) {
        return transaction.changes().stream()
                .map(change -> (change.weight() > 0 ? "+" : "") + change.weight() + " "
                        + java.util.Arrays.asList(change.values()))
                .toList();
    }

    @Test
    void anUpdateBecomesTheWholeOldRowAtMinusOneAndTheNewRowAtPlusOne() {
        TransactionAssembler assembler = assembler(CdcOffset.BEGINNING);
        assembler.accept(begin());
        assembler.accept(relation('f'));
        assembler.accept(insert("42", "silver", null));
        assembler.accept(update('O', new String[] {"42", "silver", null}, new String[] {"42", "gold", null}));
        assertThat(out).as("nothing leaves before Commit").isEmpty();
        assembler.accept(commit(0x200));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).endLsn()).isEqualTo(0x200);
        assertThat(rows(out.get(0)))
                .containsExactly("+1 [42, silver, null]", "-1 [42, silver, null]", "+1 [42, gold, null]");
    }

    @Test
    void anUnchangedToastValueIsTakenFromTheOldRowAndThePlaceholderIsNeverAValue() {
        TransactionAssembler assembler = assembler(CdcOffset.BEGINNING);
        assembler.accept(begin());
        assembler.accept(relation('f'));
        assembler.accept(update('O', new String[] {"7", "silver", "long text"}, new String[] {"7", "gold", TOAST}));
        assembler.accept(commit(0x200));
        assertThat(rows(out.get(0))).containsExactly("-1 [7, silver, long text]", "+1 [7, gold, long text]");
        assertThat(assembler.carriedForward()).isEqualTo(1);

        PgOutput.Tuple placeholder = ((PgOutput.Insert) insert("1", "x", TOAST)).after();
        assertThatThrownBy(() -> placeholder.value(2)).hasMessageContaining("placeholder");
    }

    @Test
    void aKeyOnlyBeforeImageRefusesTheWholeTransaction() {
        TransactionAssembler assembler = assembler(CdcOffset.BEGINNING);
        assembler.accept(begin());
        assembler.accept(relation('f'));
        assembler.accept(insert("1", "silver", null));
        assembler.accept(update('K', new String[] {"42", null, null}, new String[] {"42", "gold", null}));
        assembler.accept(commit(0x200));

        assertThat(out.get(0).failure())
                .isNotNull()
                .hasMessageContaining("only the key")
                .hasMessageContaining("ALTER TABLE public.customers REPLICA IDENTITY FULL;");
    }

    @Test
    void aRelationThatIsNoLongerReplicaIdentityFullIsRefused() {
        TransactionAssembler assembler = assembler(CdcOffset.BEGINNING);
        assembler.accept(begin());
        assembler.accept(relation('d'));
        assembler.accept(insert("1", "silver", null));
        assembler.accept(commit(0x200));
        assertThat(out.get(0).failure()).hasMessageContaining("PRV-5112");
    }

    @Test
    void aTruncateOfTheCapturedTableIsRefusedAndOneOfAnotherTableIsNot() {
        TransactionAssembler assembler = assembler(CdcOffset.BEGINNING);
        assembler.accept(begin());
        assembler.accept(new Message('T').int32(1).byteValue(0).int32(OID + 1).decode());
        assembler.accept(commit(0x200));
        assertThat(out.get(0).failure()).isNull();

        assembler.accept(begin());
        assembler.accept(
                new Message('T').int32(2).byteValue(0).int32(OID + 1).int32(OID).decode());
        assembler.accept(commit(0x300));
        assertThat(out.get(1).failure()).hasMessageContaining("TRUNCATE public.customers");
    }

    @Test
    void transactionsEndingAtOrBeforeTheResumePointAreDroppedAndAPartialOneLosesExactlyWhatWasDelivered() {
        TransactionAssembler assembler = assembler(new CdcOffset(0x200, 0x300, 2));
        assembler.accept(begin());
        assembler.accept(relation('f'));
        assembler.accept(insert("1", "a", null));
        assembler.accept(commit(0x200));
        assertThat(out).as("ends at the resume point: already held").isEmpty();

        assembler.accept(begin());
        assembler.accept(insert("2", "b", null));
        assembler.accept(insert("3", "c", null));
        assembler.accept(insert("4", "d", null));
        assembler.accept(commit(0x300));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).alreadyDelivered()).isEqualTo(2);
        assertThat(rows(out.get(0))).containsExactly("+1 [4, d, null]");
    }

    @Test
    void ourOwnLogicalMessageIsAPositionMarkerAndAnyoneElsesIsNot() {
        List<String> seen = new ArrayList<>();
        TransactionAssembler assembler =
                new TransactionAssembler(OPTIONS, MAPPING, OID, CdcOffset.BEGINNING, out::add, seen::add);
        byte[] ours = "crm:heartbeat:1".getBytes(StandardCharsets.UTF_8);
        byte[] theirs = "other:heartbeat:1".getBytes(StandardCharsets.UTF_8);
        assembler.accept(new Message('M')
                .byteValue(0)
                .int64(0x500)
                .string("pravaha-cdc")
                .int32(theirs.length)
                .decodeWith(theirs));
        assembler.accept(new Message('M')
                .byteValue(0)
                .int64(0x600)
                .string("pravaha-cdc")
                .int32(ours.length)
                .decodeWith(ours));
        assertThat(out).extracting(CdcTransaction::endLsn).containsExactly(0x600L);
        assertThat(out.get(0).changes()).isEmpty();
        assertThat(seen).containsExactly("crm:heartbeat:1");
    }

    @Test
    void aTruncatedMessageIsRefusedRatherThanGuessedAt() {
        byte[] cut = {'I', 0, 0};
        assertThatThrownBy(() -> PgOutput.decode(ByteBuffer.wrap(cut)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5116")
                .hasMessageContaining("ended early");
    }

    @Test
    void offsetsRoundTripInPostgresSpellingAndAForeignTokenIsRefused() {
        CdcOffset whole = CdcOffset.at(0x1_0000_00ABL);
        assertThat(whole.toSourceOffset().token()).isEqualTo("lsn=1/AB");
        assertThat(CdcOffset.parse(whole.toSourceOffset())).isEqualTo(whole);
        CdcOffset partial = new CdcOffset(0x10, 0x20, 4);
        assertThat(partial.toSourceOffset().token()).isEqualTo("lsn=0/10;partial=0/20+4");
        assertThat(CdcOffset.parse(partial.toSourceOffset())).isEqualTo(partial);
        assertThat(CdcOffset.parse(SourceOffset.BEGINNING).isBeginning()).isTrue();
        assertThatThrownBy(() -> CdcOffset.parse(new SourceOffset("wm=5;key=3")))
                .hasMessageContaining("PRV-5114");
    }

    @Test
    void snapshotOffsetsRoundTripAndAnOffsetWrittenBeforeSnapshotsExistedStillReads() {
        CdcOffset start = CdcOffset.at(0x10).withSnapshot(CdcOffset.Snapshot.START);
        assertThat(start.toSourceOffset().token()).isEqualTo("lsn=0/10;snapshot=start");
        assertThat(CdcOffset.parse(start.toSourceOffset())).isEqualTo(start);

        // Every character the token uses as punctuation, in key values: they must survive the trip.
        CdcOffset.Snapshot awkward = new CdcOffset.Snapshot(20_000, List.of("a,b;c@d=e%f+g h", "", "\u00e9\u00df"));
        CdcOffset mid = new CdcOffset(0x10, 0x20, 4, awkward);
        CdcOffset parsed = CdcOffset.parse(mid.toSourceOffset());
        assertThat(parsed).isEqualTo(mid);
        assertThat(Objects.requireNonNull(parsed.snapshot()).after())
                .containsExactly("a,b;c@d=e%f+g h", "", "\u00e9\u00df");
        assertThat(CdcOffset.parse(new SourceOffset("lsn=0/10;snapshot=20000@20417"))
                        .snapshot())
                .isEqualTo(new CdcOffset.Snapshot(20_000, List.of("20417")));

        CdcOffset old = CdcOffset.parse(new SourceOffset("lsn=0/16B3748"));
        assertThat(old.inSnapshot())
                .as("written before snapshots existed: finished, or never asked for")
                .isFalse();
        assertThat(CdcOffset.parse(new SourceOffset("lsn=0/10;partial=0/20+4")).inSnapshot())
                .isFalse();
        for (String bad : List.of(
                "lsn=0/10;snapshot=",
                "lsn=0/10;snapshot=0@5",
                "lsn=0/10;snapshot=5",
                "lsn=0/10;snapshot=start;partial=0/20+4",
                "lsn=0/10;snapshot=start;snapshot=start")) {
            assertThatThrownBy(() -> CdcOffset.parse(new SourceOffset(bad)))
                    .as(bad)
                    .hasMessageContaining("PRV-5114");
        }
    }

    /** A frontier of "id at or below 5", decided here in Java; against a server, PostgreSQL decides. */
    private record FrontierAtFive(long until) implements TransactionAssembler.CatchUp {
        @Override
        public List<String> keyColumns() {
            return List.of("id");
        }

        @Override
        public boolean[] atOrBelow(List<List<String>> keys) {
            boolean[] below = new boolean[keys.size()];
            for (int i = 0; i < below.length; i++) {
                below[i] = Long.parseLong(keys.get(i).get(0)) <= 5;
            }
            return below;
        }
    }

    @Test
    void beforeTheSnapshotsPointAChangeIsKeptOnlyAtOrBelowTheFrontierEachImageByItsOwnKey() {
        TransactionAssembler assembler = new TransactionAssembler(
                OPTIONS, MAPPING, OID, CdcOffset.BEGINNING, out::add, content -> {}, new FrontierAtFive(0x300));
        assembler.accept(begin());
        assembler.accept(relation('f'));
        assembler.accept(insert("3", "a", null));
        assembler.accept(insert("7", "b", null));
        // A key moving across the frontier: the old row's retraction is below it, the new row above.
        assembler.accept(update('O', new String[] {"4", "c", null}, new String[] {"9", "c", null}));
        assembler.accept(commit(0x300));
        assertThat(rows(out.get(0)))
                .as("7 and 9 are above the frontier: the snapshot at the point reads them as this left them")
                .containsExactly("+1 [3, a, null]", "-1 [4, c, null]");

        assembler.accept(begin());
        assembler.accept(insert("8", "d", null));
        assembler.accept(commit(0x400));
        assertThat(rows(out.get(1)))
                .as("after the snapshot's point everything is delivered")
                .containsExactly("+1 [8, d, null]");
    }

    @Test
    void aPartialTransactionBeforeTheSnapshotsPointSkipsWhatWasDeliveredOfItsFilteredChanges() {
        CdcOffset resume = new CdcOffset(0x100, 0x300, 1, new CdcOffset.Snapshot(10, List.of("5")));
        TransactionAssembler assembler = new TransactionAssembler(
                OPTIONS, MAPPING, OID, resume, out::add, content -> {}, new FrontierAtFive(0x300));
        assembler.accept(begin());
        assembler.accept(relation('f'));
        assembler.accept(insert("3", "a", null));
        assembler.accept(insert("7", "b", null));
        assembler.accept(insert("5", "c", null));
        assembler.accept(commit(0x300));
        assertThat(rows(out.get(0)))
                .as("the engine held the first of the two kept changes, 3; only 5 is left")
                .containsExactly("+1 [5, c, null]");
    }

    @Test
    void textValuesConvertAsPostgresWritesThem() {
        assertThat(PgValues.parse("2026-09-19 10:00:00.5+05:30", PgValues.TIMESTAMPTZ, Types.timestamp()))
                .isEqualTo(java.time.Instant.parse("2026-09-19T04:30:00.5Z").getEpochSecond() * 1_000_000_000L
                        + 500_000_000L);
        assertThat(PgValues.parse("2026-09-19 10:00:00", PgValues.TIMESTAMP, Types.timestamp()))
                .isEqualTo(java.time.Instant.parse("2026-09-19T10:00:00Z").getEpochSecond() * 1_000_000_000L);
        assertThat(PgValues.parse("\\x0aff", PgValues.BYTEA, Types.bytes())).isEqualTo(new byte[] {10, -1});
        assertThat(PgValues.parse("12.50", PgValues.NUMERIC, Types.decimal(38, 2)))
                .isEqualTo(java.math.BigInteger.valueOf(1250));
        assertThat(PgValues.parse("t", PgValues.BOOL, Types.bool())).isEqualTo(true);
        assertThat(PgValues.parse("1970-01-02", PgValues.DATE, Types.date())).isEqualTo(1);
    }

    @Test
    void optionsAreCheckedWithoutConnecting() {
        Map<String, String> config = new HashMap<>(Map.of("url", "jdbc:mysql://db/crm", "table", "t"));
        assertThatThrownBy(() -> CdcOptions.from(new PgServer.Ctx("cdc", config)))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5110")
                .hasMessageContaining("not a PostgreSQL JDBC URL");
        config.put("url", "jdbc:postgresql://db/crm");
        config.put("slot", "Has-Dashes");
        assertThatThrownBy(() -> CdcOptions.from(new PgServer.Ctx("cdc", config)))
                .hasMessageContaining("slot 'Has-Dashes'");
        config.remove("slot");
        config.put("heartbeat.interval", "soon");
        assertThatThrownBy(() -> CdcOptions.from(new PgServer.Ctx("cdc", config)))
                .hasMessageContaining("heartbeat.interval");
        config.put("heartbeat.interval", "250ms");
        CdcOptions options = CdcOptions.from(new PgServer.Ctx("cdc", config));
        assertThat(options.heartbeat()).isEqualTo(java.time.Duration.ofMillis(250));
        assertThat(options.slot()).as("defaulted from the table").isEqualTo("pravaha_t");
        assertThat(options.qualifiedTable()).isEqualTo("public.t");
        assertThat(options.snapshotInitial())
                .as("never by default: no existing binding starts reading whole tables unasked")
                .isFalse();
        config.put("snapshot.mode", "always");
        assertThatThrownBy(() -> CdcOptions.from(new PgServer.Ctx("cdc", config)))
                .hasMessageContaining("PRV-5110")
                .hasMessageContaining("snapshot.mode must be 'initial'");
        config.put("snapshot.mode", "Initial");
        config.put("snapshot.chunk.rows", "0");
        assertThatThrownBy(() -> CdcOptions.from(new PgServer.Ctx("cdc", config)))
                .hasMessageContaining("snapshot.chunk.rows must be at least 1");
        config.put("snapshot.chunk.rows", "500");
        CdcOptions snapshotting = CdcOptions.from(new PgServer.Ctx("cdc", config));
        assertThat(snapshotting.snapshotInitial()).isTrue();
        assertThat(snapshotting.snapshotChunkRows()).isEqualTo(500);
        assertThat(InitialSnapshot.temporarySlotName("x".repeat(63)))
                .hasSizeLessThanOrEqualTo(63)
                .matches("[a-z0-9_]+");
    }

    @Test
    void aDeclaredSchemaWithATrailingSeparatorIsRefused() {
        // SPLITTRAIL-1: String.split dropped trailing empty strings, so "id:INT64," and "id:INT64:"
        // were read as "id:INT64" while the same slip mid-list was refused.
        List<CdcSchema.Column> columns = List.of(new CdcSchema.Column("id", PgValues.INT8, 'b', true, -1, "bigint"));
        for (String declared : List.of("id:INT64,", "id:INT64:")) {
            CdcOptions options = CdcOptions.from(new PgServer.Ctx(
                    "cdc",
                    Map.of(
                            "url", "jdbc:postgresql://db:5432/crm",
                            "table", "public.customers",
                            "slot", "crm",
                            "schema", declared)));
            assertThatThrownBy(() -> CdcSchema.resolve(options, columns))
                    .as(declared)
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("name:TYPE");
        }
    }
}
