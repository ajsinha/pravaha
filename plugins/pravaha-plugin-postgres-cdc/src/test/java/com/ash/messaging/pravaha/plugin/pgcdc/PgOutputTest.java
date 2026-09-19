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

    private static PgOutput.Message insert(String... values) {
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
    }
}
