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
package com.ash.messaging.pravaha.plugin.delta;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.types.StructType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.plugin.delta.DeltaSinkRows.Change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sink's rows away from any table: what a staged batch holds, what Kernel is handed, and where
 * the conversions refuse.
 */
class DeltaSinkRowsTest {

    private static final StreamSchema EVERYTHING = DeltaSinkSchema.parse(
            "t",
            "a:BOOLEAN,b:INT8,c:INT16,d:INT32,e:INT64,f:FLOAT32,g:FLOAT64,h:STRING,i:BYTES,"
                    + "j:DATE,k:TIMESTAMP,l:DECIMAL(12,4),m:STRING?");

    private final SinkTestRows rows = new SinkTestRows();

    @AfterEach
    void tearDown() {
        rows.close();
    }

    @Test
    void aStagedBatchIsTheRowTheEngineWrote() {
        DeltaSinkRows reader = new DeltaSinkRows(EVERYTHING);
        Change change = reader.read(rows.row(
                EVERYTHING,
                1,
                true,
                (byte) 7,
                (short) 9,
                11,
                13L,
                1.5f,
                2.5d,
                "text",
                new byte[] {1, 2, 3},
                19_000,
                1_700_000_000_000_000_000L,
                new BigDecimal("12.3456"),
                null));

        List<Change> back = reader.decode(reader.encode(List.of(change)));

        assertThat(back).hasSize(1);
        assertThat(back.get(0).weight()).isEqualTo(1L);
        assertThat(back.get(0).values()[0]).isEqualTo(true);
        assertThat(back.get(0).values()[7]).isEqualTo("text");
        assertThat((byte[]) back.get(0).values()[8]).containsExactly(1, 2, 3);
        assertThat(back.get(0).values()[10]).isEqualTo(1_700_000_000_000_000_000L);
        assertThat(back.get(0).values()[11]).isEqualTo(new BigDecimal("12.3456"));
        assertThat(back.get(0).values()[12]).isNull();
    }

    @Test
    void aRetractionKeepsItsNegativeWeight() {
        StreamSchema schema = DeltaSinkSchema.parse("t", "k:STRING,v:INT64");
        DeltaSinkRows reader = new DeltaSinkRows(schema);
        List<Change> back = reader.decode(reader.encode(List.of(reader.read(rows.row(schema, -1, "u1", 3L)))));
        assertThat(back.get(0).weight()).isEqualTo(-1L);
    }

    @Test
    void aBatchStagedByADifferentlyShapedSinkIsRefusedRatherThanRead() {
        DeltaSinkRows wide = new DeltaSinkRows(DeltaSinkSchema.parse("t", "k:STRING,v:INT64"));
        DeltaSinkRows narrow = new DeltaSinkRows(DeltaSinkSchema.parse("t", "k:STRING"));
        byte[] staged = wide.encode(List.of(new Change(new Object[] {"u1", 3L}, 1)));

        assertThatThrownBy(() -> narrow.decode(staged))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5058")
                .hasMessageContaining("configured differently under the same transaction.id");
    }

    @Test
    void aKeyReadBackOutOfTheTableEqualsTheKeyOfTheChangeThatReplacesIt() {
        StreamSchema schema = DeltaSinkSchema.parse("t", "k:DECIMAL(12,4),v:INT64");
        DeltaSinkRows reader = new DeltaSinkRows(schema);

        assertThat(reader.keyOf(new Object[] {new BigDecimal("1.5000"), 1L}, new int[] {0}))
                .as("trailing zeros are part of BigDecimal.equals, so both sides are set to the declared scale")
                .isEqualTo(reader.keyOf(new Object[] {new BigDecimal("1.5"), 2L}, new int[] {0}));
        assertThat(reader.keyOf(new Object[] {new BigDecimal("1.5"), 1L}, new int[] {0}))
                .isNotEqualTo(reader.keyOf(new Object[] {new BigDecimal("1.6"), 1L}, new int[] {0}));
    }

    @Test
    void aNullKeyIsRefusedRatherThanKeyingARecordOnNothing() {
        StreamSchema schema = DeltaSinkSchema.parse("t", "k:STRING,v:INT64");
        DeltaSinkRows reader = new DeltaSinkRows(schema);
        assertThatThrownBy(() -> reader.keyOf(new Object[] {null, 1L}, new int[] {0}))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("cannot be keyed by nothing");
    }

    @Test
    void aTimestampIsMicrosecondsForDeltaAndARemainderIsRefused() {
        assertThat(DeltaSinkRows.microsOf("seen", 1_700_000_000_000_000_000L)).isEqualTo(1_700_000_000_000_000L);
        assertThatThrownBy(() -> DeltaSinkRows.microsOf("seen", 1_000_000_001L))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5058")
                .hasMessageContaining("not a whole number of them");
    }

    @Test
    void theBatchHandedToKernelCarriesTheChangelogColumns() {
        StreamSchema schema = DeltaSinkSchema.parse("t", "k:STRING,v:INT64");
        DeltaSinkRows reader = new DeltaSinkRows(schema);
        StructType delta = DeltaSinkSchema.toDeltaSchema("s", schema, true);
        ColumnarBatch batch = reader.batchOf(
                List.of(new Change(new Object[] {"u1", 3L}, 1), new Change(new Object[] {"u2", 4L}, -2)), delta, true);

        assertThat(batch.getSize()).isEqualTo(2);
        assertThat(batch.getColumnVector(0).getString(0)).isEqualTo("u1");
        assertThat(batch.getColumnVector(1).getLong(1)).isEqualTo(4L);
        assertThat(batch.getColumnVector(2).getString(0)).isEqualTo("insert");
        assertThat(batch.getColumnVector(2).getString(1)).isEqualTo("delete");
        assertThat(batch.getColumnVector(3).getLong(1)).isEqualTo(-2L);
    }

    @Test
    void aSelectionVectorSaysWhichRowsSurviveARewrite() {
        assertThat(DeltaSinkRows.selection(new boolean[] {true, false}).getBoolean(0))
                .isTrue();
        assertThat(DeltaSinkRows.selection(new boolean[] {true, false}).getBoolean(1))
                .isFalse();
        assertThat(DeltaSinkRows.selection(new boolean[] {true}).getSize()).isEqualTo(1);
    }

    // ---- staging, as files ---------------------------------------------------------------

    @TempDir
    Path root;

    @Test
    void stagingKeepsEachTransactionsBatchesInOrderAndForgetsThemOnDemand() {
        DeltaSinkStaging staging = new DeltaSinkStaging(root.resolve("sink"));
        staging.stage(7, 0, new byte[] {1});
        staging.stage(7, 1, new byte[] {2});
        staging.stage(8, 0, new byte[] {3});

        assertThat(staging.staged(7)).containsExactly(new byte[] {1}, new byte[] {2});
        assertThat(staging.labels()).containsExactly(7L, 8L);

        staging.discardAfter(7);
        assertThat(staging.labels()).containsExactly(7L);
        staging.discard(7);
        assertThat(staging.labels()).isEmpty();
        assertThat(staging.staged(7)).isEmpty();
    }

    @Test
    void aBatchHalfWrittenByADeadProcessIsNotRead() throws Exception {
        Path directory = root.resolve("sink").resolve(String.format("%016d", 3L));
        Files.createDirectories(directory);
        Files.write(directory.resolve("000000.batch.tmp"), new byte[] {9});

        assertThat(new DeltaSinkStaging(root.resolve("sink")).staged(3)).isEmpty();
    }
}
