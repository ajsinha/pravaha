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
package com.ash.messaging.pravaha.it.qa.types;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TYPE-044 .. TYPE-059 ({@code docs/project/qa/cases/TYPE.md}, sections 5 "Join key" and 6 "GROUP BY key"),
 * executed in-process against the real {@code SqlPlanner}/{@code PhysicalPlanBuilder}/
 * {@code InterpretedPipeline} path -- the same planner and runtime a registered query or {@code
 * pravaha run} goes through, without the server's HTTP/Flight layer.
 */
@Tag("qa")
class TypeBatch2Test {

    // -------------------------------------------------------------- fixtures (join)

    private static StreamSchema jl() {
        return StreamSchema.builder("jl")
                .field("id", Types.int64())
                .field("kb", Types.bool().withNullable(true))
                .field("k8", Types.int8().withNullable(true))
                .field("k16", Types.int16().withNullable(true))
                .field("k32", Types.int32().withNullable(true))
                .field("k64", Types.int64().withNullable(true))
                .field("kf", Types.float64().withNullable(true))
                .field("ks", Types.string().withNullable(true))
                .field("kbin", Types.bytes().withNullable(true))
                .field("kts", Types.timestamp().withNullable(true))
                .field("kf32", Types.float32().withNullable(true))
                .field("ts", Types.timestamp())
                .eventTime("ts")
                .build();
    }

    /**
     * As {@link #jl()}/{@link #jr()} but without {@code kbin}. TYPE-044..049 and TYPE-052 are about
     * the *named* key column's type; {@code kbin} (BYTES, present on every row per the case file's
     * own fixture) turns out to break {@code JoinSide.add}/{@code markMatched} for every key type,
     * not only a BYTES key -- see the {@code newFinding_*} test below. Using it here would make
     * every one of those cases BLOCKED by a defect none of them is about.
     */
    private static StreamSchema jlKey() {
        return StreamSchema.builder("jl")
                .field("id", Types.int64())
                .field("kb", Types.bool().withNullable(true))
                .field("k8", Types.int8().withNullable(true))
                .field("k16", Types.int16().withNullable(true))
                .field("k32", Types.int32().withNullable(true))
                .field("k64", Types.int64().withNullable(true))
                .field("kf", Types.float64().withNullable(true))
                .field("ks", Types.string().withNullable(true))
                .field("kts", Types.timestamp().withNullable(true))
                .field("kf32", Types.float32().withNullable(true))
                .field("ts", Types.timestamp())
                .eventTime("ts")
                .build();
    }

    private static StreamSchema jrKey() {
        return StreamSchema.builder("jr")
                .field("id", Types.int64())
                .field("kb", Types.bool().withNullable(true))
                .field("k8", Types.int8().withNullable(true))
                .field("k16", Types.int16().withNullable(true))
                .field("k32", Types.int32().withNullable(true))
                .field("k64", Types.int64().withNullable(true))
                .field("kf", Types.float64().withNullable(true))
                .field("ks", Types.string().withNullable(true))
                .field("kts", Types.timestamp().withNullable(true))
                .field("kf32", Types.float32().withNullable(true))
                .field("tag", Types.string())
                .field("ts", Types.timestamp())
                .eventTime("ts")
                .build();
    }

    /** Drops ordinal 8 (kbin) from a {@link #left}/{@link #right} row, for {@link #jlKey}/{@link #jrKey}. */
    private static Object[] noBin(Object[] row) {
        Object[] out = new Object[row.length - 1];
        System.arraycopy(row, 0, out, 0, 8);
        System.arraycopy(row, 9, out, 8, row.length - 9);
        return out;
    }

    private static List<Object[]> noBinAll(Object[]... rows) {
        List<Object[]> out = new ArrayList<>();
        for (Object[] row : rows) {
            out.add(noBin(row));
        }
        return out;
    }

    /** As {@link #jl()} but with the ARRAY/MAP/ROW columns TYPE-051's second half needs -- kept out
     *  of {@link #jl()} because the join operator's raw (pre-project) output schema includes every
     *  input column, and ARRAY/MAP/ROW map to Calcite ANY, whose inverse throws for every join,
     *  not only ones that reference those columns. */
    private static StreamSchema jlNested() {
        return StreamSchema.builder("jl")
                .field("id", Types.int64())
                .field("karr", Types.array(Types.int64()).withNullable(true))
                .field("kmap", Types.map(Types.string(), Types.int64()).withNullable(true))
                .field(
                        "krow",
                        Types.row(List.of(new com.ash.messaging.pravaha.api.data.Field("a", Types.int64(), 0)))
                                .withNullable(true))
                .field("ts", Types.timestamp())
                .eventTime("ts")
                .build();
    }

    private static StreamSchema jrNested() {
        return StreamSchema.builder("jr")
                .field("id", Types.int64())
                .field("karr", Types.array(Types.int64()).withNullable(true))
                .field("kmap", Types.map(Types.string(), Types.int64()).withNullable(true))
                .field(
                        "krow",
                        Types.row(List.of(new com.ash.messaging.pravaha.api.data.Field("a", Types.int64(), 0)))
                                .withNullable(true))
                .field("tag", Types.string())
                .field("ts", Types.timestamp())
                .eventTime("ts")
                .build();
    }

    private static StreamSchema jr() {
        return StreamSchema.builder("jr")
                .field("id", Types.int64())
                .field("kb", Types.bool().withNullable(true))
                .field("k8", Types.int8().withNullable(true))
                .field("k16", Types.int16().withNullable(true))
                .field("k32", Types.int32().withNullable(true))
                .field("k64", Types.int64().withNullable(true))
                .field("kf", Types.float64().withNullable(true))
                .field("ks", Types.string().withNullable(true))
                .field("kbin", Types.bytes().withNullable(true))
                .field("kts", Types.timestamp().withNullable(true))
                .field("kf32", Types.float32().withNullable(true))
                .field("tag", Types.string())
                .field("ts", Types.timestamp())
                .eventTime("ts")
                .build();
    }

    private static final long S = 1_000_000_000L;
    private static final long T0 = 1_700_000_000L * S;

    // l1..l4 and r1..r4, ordinals: id,kb,k8,k16,k32,k64,kf,ks,kbin,kts,kf32,ts
    private static Object[] left(
            long id,
            Object kb,
            Object k8,
            Object k16,
            Object k32,
            Object k64,
            Object kf,
            Object ks,
            Object kbin,
            Object kts,
            Object kf32,
            long tsOffsetSeconds) {
        return new Object[] {id, kb, k8, k16, k32, k64, kf, ks, kbin, kts, kf32, T0 + tsOffsetSeconds * S};
    }

    private static Object[] right(
            long id,
            Object kb,
            Object k8,
            Object k16,
            Object k32,
            Object k64,
            Object kf,
            Object ks,
            Object kbin,
            Object kts,
            Object kf32,
            String tag,
            double tsOffsetSeconds) {
        return new Object[] {
            id, kb, k8, k16, k32, k64, kf, ks, kbin, kts, kf32, tag, T0 + Math.round(tsOffsetSeconds * S)
        };
    }

    private static final Object[] L1 = left(
            1, true, (byte) 7, (short) 700, 70000, 7_000_000_000L, 1.5, "alpha", "cafe".getBytes(UTF_8), T0, 1.5f, 0);
    private static final Object[] L2 = left(
            2,
            false,
            (byte) -8,
            (short) -800,
            -80000,
            -8_000_000_000L,
            2.5,
            "beta",
            "beef".getBytes(UTF_8),
            T0 + S,
            2.5f,
            1);

    @SuppressWarnings("NullAway") // nulls passed on purpose
    private static final Object[] L3 = left(3, null, null, null, null, null, null, null, null, null, null, 2);

    private static final Object[] L4 = left(
            4, true, (byte) 7, (short) 700, 70000, 7_000_000_000L, 1.5, "alpha", "cafe".getBytes(UTF_8), T0, 1.5f, 3);

    private static final Object[] R1 = right(
            1,
            true,
            (byte) 7,
            (short) 700,
            70000,
            7_000_000_000L,
            1.5,
            "alpha",
            "cafe".getBytes(UTF_8),
            T0,
            1.5f,
            "L",
            0.5);
    private static final Object[] R2 = right(
            2,
            true,
            (byte) 9,
            (short) 900,
            90000,
            9_000_000_000L,
            3.5,
            "gamma",
            "dead".getBytes(UTF_8),
            T0 + 2 * S,
            3.5f,
            "M",
            1.5);

    @SuppressWarnings("NullAway") // nulls passed on purpose
    private static final Object[] R3 = right(3, null, null, null, null, null, null, null, null, null, null, "N", 2.5);

    private static final Object[] R4 = right(
            4,
            false,
            (byte) -8,
            (short) -800,
            -80000,
            -8_000_000_000L,
            2.5,
            "beta",
            "beef".getBytes(UTF_8),
            T0 + S,
            2.5f,
            "O",
            3.5);

    private static final String TIME_BOUND =
            " AND l.ts BETWEEN r.ts - INTERVAL '10' SECOND AND r.ts + INTERVAL '10' SECOND";

    private static void setField(BinaryRowWriter w, StreamSchema schema, int ordinal, Object v) {
        if (v == null) {
            w.setNull(ordinal);
            return;
        }
        TypeName t = schema.field(ordinal).type().typeName();
        switch (t) {
            case BOOLEAN -> w.setBoolean(ordinal, (Boolean) v);
            case INT8 -> w.setByte(ordinal, ((Number) v).byteValue());
            case INT16 -> w.setShort(ordinal, ((Number) v).shortValue());
            case INT32, DATE -> w.setInt(ordinal, ((Number) v).intValue());
            case INT64, TIME, TIMESTAMP_LTZ -> w.setLong(ordinal, ((Number) v).longValue());
            case FLOAT32 -> w.setFloat(ordinal, ((Number) v).floatValue());
            case FLOAT64 -> w.setDouble(ordinal, ((Number) v).doubleValue());
            case STRING -> w.setString(ordinal, (String) v);
            case BYTES, ARRAY, MAP, ROW -> w.setBytes(ordinal, (byte[]) v);
            case DECIMAL -> {
                long[] hl = (long[]) v;
                w.setDecimal(ordinal, hl[0], hl[1]);
            }
        }
    }

    /** Two-input join harness: feeds jl/jr rows through the real plan and captures the output. */
    private static final class JoinHarness implements AutoCloseable {
        final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        final InterpretedPipeline pipeline;
        final List<CapturingRowWriter.Captured> out = new ArrayList<>();
        final StreamSchema leftSchema;
        final StreamSchema rightSchema;
        long sequence;

        JoinHarness(String sql) {
            this(sql, jl(), jr());
        }

        JoinHarness(String sql, StreamSchema leftSchema, StreamSchema rightSchema) {
            this.leftSchema = leftSchema;
            this.rightSchema = rightSchema;
            PhysicalOperator plan = new PhysicalPlanBuilder()
                    .build(SqlPlanner.withStreams(leftSchema, rightSchema).plan(sql));
            this.pipeline = InterpretedPipeline.compile(
                    plan, (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), out::add));
        }

        void feed(String stream, StreamSchema schema, Object[] values) {
            RowLayout layout = RowLayout.of(schema);
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            long handle = arena.allocate(layout.rowSize(512));
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            for (int i = 0; i < values.length; i++) {
                setField(writer, schema, i, values[i]);
            }
            sequence++;
            long eventTime = schema.eventTimeOrdinal().isPresent()
                    ? ((Number) values[schema.eventTimeOrdinal().getAsInt()]).longValue()
                    : sequence;
            writer.weight(1).eventTimestampNanos(eventTime).sequence(sequence).commit();
            arena.trimTo(handle, writer.sizeSoFar());
            pipeline.accept(stream, new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        }

        void feedAll(List<Object[]> lefts, List<Object[]> rights) {
            for (Object[] l : lefts) {
                feed("jl", leftSchema, l);
            }
            for (Object[] r : rights) {
                feed("jr", rightSchema, r);
            }
        }

        @Override
        public void close() {
            pipeline.close();
            arena.close();
        }
    }

    private static List<String> pairs(List<CapturingRowWriter.Captured> out) {
        // output columns are l.id, r.id, r.tag (ordinals 0,1,2) for every SQL below.
        List<String> result = new ArrayList<>();
        for (CapturingRowWriter.Captured row : out) {
            result.add(row.asLong(0) + "," + row.asLong(1) + "," + row.asString(2));
        }
        result.sort(null);
        return result;
    }

    // ---------------------------------------------------------------------- TYPE-044

    @Test
    void type044_stringJoinKey() {
        String sql = "SELECT l.id AS lid, r.id AS rid, r.tag FROM jl AS l JOIN jr AS r ON l.ks = r.ks" + TIME_BOUND;
        try (JoinHarness h = new JoinHarness(sql, jlKey(), jrKey())) {
            h.feedAll(noBinAll(L1, L2, L3, L4), noBinAll(R1, R2, R3, R4));
            assertThat(pairs(h.out)).containsExactlyInAnyOrder("1,1,L", "4,1,L", "2,4,O");
        }
        // inverted control: on ks = tag, must return zero rows.
        String inverted =
                "SELECT l.id AS lid, r.id AS rid, r.tag FROM jl AS l JOIN jr AS r ON l.ks = r.tag" + TIME_BOUND;
        try (JoinHarness h = new JoinHarness(inverted, jlKey(), jrKey())) {
            h.feedAll(noBinAll(L1, L2, L3, L4), noBinAll(R1, R2, R3, R4));
            assertThat(h.out).isEmpty();
        }
    }

    // ---------------------------------------------------------------------- TYPE-045

    @Test
    void type045_int64JoinKey() {
        String sql = "SELECT l.id AS lid, r.id AS rid, r.tag FROM jl AS l JOIN jr AS r ON l.k64 = r.k64" + TIME_BOUND;
        try (JoinHarness h = new JoinHarness(sql, jlKey(), jrKey())) {
            h.feedAll(noBinAll(L1, L2, L3, L4), noBinAll(R1, R2, R3, R4));
            assertThat(pairs(h.out)).containsExactlyInAnyOrder("1,1,L", "4,1,L", "2,4,O");
        }
    }

    // ---------------------------------------------------------------------- TYPE-046

    @Test
    void type046_int32JoinKey() {
        String sql = "SELECT l.id AS lid, r.id AS rid, r.tag FROM jl AS l JOIN jr AS r ON l.k32 = r.k32" + TIME_BOUND;
        try (JoinHarness h = new JoinHarness(sql, jlKey(), jrKey())) {
            h.feedAll(noBinAll(L1, L2, L3, L4), noBinAll(R1, R2, R3, R4));
            assertThat(pairs(h.out)).containsExactlyInAnyOrder("1,1,L", "4,1,L", "2,4,O");
        }
    }

    // ---------------------------------------------------------------------- TYPE-047

    @Test
    void type047_int8AndInt16JoinKeys() {
        String sql8 = "SELECT l.id AS lid, r.id AS rid, r.tag FROM jl AS l JOIN jr AS r ON l.k8 = r.k8" + TIME_BOUND;
        try (JoinHarness h = new JoinHarness(sql8, jlKey(), jrKey())) {
            h.feedAll(noBinAll(L1, L2, L3, L4), noBinAll(R1, R2, R3, R4));
            assertThat(pairs(h.out)).containsExactlyInAnyOrder("1,1,L", "4,1,L", "2,4,O");
        }
        String sql16 = "SELECT l.id AS lid, r.id AS rid, r.tag FROM jl AS l JOIN jr AS r ON l.k16 = r.k16" + TIME_BOUND;
        try (JoinHarness h = new JoinHarness(sql16, jlKey(), jrKey())) {
            h.feedAll(noBinAll(L1, L2, L3, L4), noBinAll(R1, R2, R3, R4));
            assertThat(pairs(h.out)).containsExactlyInAnyOrder("1,1,L", "4,1,L", "2,4,O");
        }
    }

    // ---------------------------------------------------------------------- TYPE-048

    @Test
    void type048_booleanJoinKey() {
        String sql = "SELECT l.id AS lid, r.id AS rid, r.tag FROM jl AS l JOIN jr AS r ON l.kb = r.kb" + TIME_BOUND;
        try (JoinHarness h = new JoinHarness(sql, jlKey(), jrKey())) {
            h.feedAll(noBinAll(L1, L2, L3, L4), noBinAll(R1, R2, R3, R4));
            // every true-true and false-false pair is inside the +-10s window.
            assertThat(pairs(h.out)).containsExactlyInAnyOrder("1,1,L", "1,2,M", "4,1,L", "4,2,M", "2,4,O");
        }
    }

    // ---------------------------------------------------------------------- TYPE-049

    @Test
    void type049_timestampJoinKey() {
        String sql = "SELECT l.id AS lid, r.id AS rid, r.tag FROM jl AS l JOIN jr AS r ON l.kts = r.kts" + TIME_BOUND;
        try (JoinHarness h = new JoinHarness(sql, jlKey(), jrKey())) {
            h.feedAll(noBinAll(L1, L2, L3, L4), noBinAll(R1, R2, R3, R4));
            assertThat(pairs(h.out)).containsExactlyInAnyOrder("1,1,L", "4,1,L", "2,4,O");
        }
    }

    // ---------------------------------------------------------------------- TYPE-050

    /**
     * {@code JoinKeys.checkJoinable} is called from {@code SymmetricHashJoin}'s constructor, which
     * runs during {@code InterpretedPipeline.compile} -- not during {@code SqlPlanner.plan} or
     * {@code PhysicalPlanBuilder.build}, confirmed directly: building the plan alone raises nothing
     * for either float width. What a live {@code pravaha register} call does atomically (plan, then
     * build, then compile) is where the refusal actually happens, which is why it still reads as
     * "at registration" from the CLI -- recorded here at the harness layer that can tell the two
     * apart, since the case file's Fact 9 attributes this refusal to {@code checkJoinable} without
     * saying which of the three stages calls it.
     */
    @Test
    void type050_floatJoinKeysRefusedAtPlanTime() {
        assertThatThrownBy(() -> new JoinHarness(
                        "SELECT l.id FROM jl AS l JOIN jr AS r ON l.kf = r.kf" + TIME_BOUND, jlKey(), jrKey()))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("FLOAT64")
                .hasMessageContaining("kf");
        assertThatThrownBy(() -> new JoinHarness(
                        "SELECT l.id FROM jl AS l JOIN jr AS r ON l.kf32 = r.kf32" + TIME_BOUND, jlKey(), jrKey()))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("FLOAT32")
                .hasMessageContaining("kf32");

        // the suggested rewrite works: CAST(kf AS BIGINT) truncates toward zero.
        String rewrite = "SELECT l.id AS lid, r.id AS rid, r.tag FROM jl AS l JOIN jr AS r "
                + "ON CAST(l.kf AS BIGINT) = CAST(r.kf AS BIGINT)" + TIME_BOUND;
        try (JoinHarness h = new JoinHarness(rewrite, jlKey(), jrKey())) {
            h.feedAll(noBinAll(L1, L2, L3, L4), noBinAll(R1, R2, R3, R4));
            assertThat(pairs(h.out)).containsExactlyInAnyOrder("1,1,L", "4,1,L", "2,4,O");
        }
    }

    // ---------------------------------------------------------------------- new finding (not a numbered case)

    /**
     * Not one of TYPE-044..052 by number, but found while building their harness and load-bearing
     * for how to read all of them: {@code JoinSide.add}/{@code markMatched} compare two same-keyed
     * rows field by field over the <em>whole</em> row ({@code RowValues.sameFields}), not just the
     * key columns, to consolidate a Z-set duplicate. {@code RowValues.equal}'s {@code default}
     * branch throws {@code UnsupportedOperationException} for BYTES/ARRAY/MAP/ROW -- so a join whose
     * key is an entirely ordinary STRING or INT64 column still crashes, uncaught and with no PRV-
     * code, the moment two rows sharing that key also share a schema that has a BYTES column
     * *anywhere*, such as the case file's own {@code jl}/{@code jr} fixture (column {@code kbin}).
     * This is exactly what TYPE-044's fixture triggers on l1/l4 (both keyed {@code ks='alpha'}, both
     * carrying {@code kbin}): confirmed live against the real server during this session (a
     * registered {@code ks}-keyed join over the documented fixture went from 1 row emitted to
     * {@code FAILED} state within seconds, with no error surfaced to a client reading the view).
     */
    @Test
    void aBytesColumnAnywhereInTheRowNoLongerCrashesAJoinOnASecondSameKeyedRow() {
        // Written 2026-09-14 to pin the crash described above; by 2026-09-28 the join consolidates
        // same-keyed rows without comparing a BYTES column it cannot compare, so it now pins the fix.
        String sql = "SELECT l.id AS lid, r.id AS rid, r.tag FROM jl AS l JOIN jr AS r ON l.ks = r.ks" + TIME_BOUND;
        try (JoinHarness h = new JoinHarness(sql)) { // jl()/jr(), which carries kbin
            org.assertj.core.api.Assertions.assertThatCode(
                            () -> h.feedAll(List.of(L1, L2, L3, L4), List.of(R1, R2, R3, R4)))
                    .doesNotThrowAnyException();
        }
    }

    // ---------------------------------------------------------------------- TYPE-051

    /**
     * The case file's Fact 9 and TYPE-051's own text expect BYTES to pass {@code checkJoinable} and
     * die in {@code fieldHash} on the first row. That is stale: {@code checkJoinable} in the running
     * source now explicitly guards BYTES (and ARRAY/MAP/ROW) and refuses with {@code PRV-3021}
     * before any row -- at {@code InterpretedPipeline.compile}, i.e. at query construction/
     * registration, not "plans, reports RUNNING, dies on the first row". The documented defect is
     * fixed; this records the fixed behaviour and where it now happens.
     */
    @Test
    void type051_bytesJoinKeyIsNowRefusedBeforeAnyRowNotOnTheFirstRow() {
        String sql = "SELECT l.id AS lid, r.id AS rid, r.tag FROM jl AS l JOIN jr AS r ON l.kbin = r.kbin" + TIME_BOUND;
        // SQL-level planning succeeds -- checkJoinable is not a SqlPlanner/PhysicalPlanBuilder check.
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(jl(), jr()).plan(sql));
        assertThat(plan).isNotNull();
        // But InterpretedPipeline.compile -- what registering the query actually does -- refuses
        // immediately, before feed() is ever called.
        assertThatThrownBy(() -> new JoinHarness(sql))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3021")
                .hasMessageContaining("kbin")
                .hasMessageContaining("BYTES")
                .hasMessageContaining("no key encoding");
    }

    /**
     * ARRAY/MAP/ROW fare *worse* than BYTES, not the same: {@code checkJoinable}'s new BYTES/ARRAY/
     * MAP/ROW guard is never reached, because {@code PhysicalPlanBuilder.buildJoin}'s own output
     * schema (every column from both sides, before any projection) is built first and hits the
     * ANY-type dead end from TYPE-020/Fact 2 -- {@code TypeMapping.baseFromCalcite} throwing a bare
     * {@code IllegalArgumentException}-derived PRV-2021 that names neither the column, the side, nor
     * ARRAY/MAP/ROW, and points at an internal class. So a join naming an ARRAY/MAP/ROW key does not
     * even reach registration with a clear reason -- it fails earlier and less honestly than BYTES.
     */
    @Test
    void type051_arrayMapRowJoinKeysFailEvenEarlierThanBytesAtPlanTime() {
        for (String col : List.of("karr", "kmap", "krow")) {
            String sql = "SELECT l.id FROM jl AS l JOIN jr AS r ON l." + col + " = r." + col + TIME_BOUND;
            assertThatThrownBy(() -> new PhysicalPlanBuilder()
                            .build(SqlPlanner.withStreams(jlNested(), jrNested())
                                    .plan(sql)))
                    .as(col)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("ANY");
        }
    }

    // ---------------------------------------------------------------------- TYPE-052

    @SuppressWarnings("NullAway") // nulls passed on purpose
    @Test
    void type052_nullKeyJoinsWithNothingAndDoesNotGrowState() {
        String sql = "SELECT l.id AS lid, r.id AS rid, r.tag FROM jl AS l JOIN jr AS r ON l.ks = r.ks" + TIME_BOUND;
        try (JoinHarness h = new JoinHarness(sql, jlKey(), jrKey())) {
            h.feedAll(noBinAll(L1, L2, L3, L4), noBinAll(R1, R2, R3, R4));
            assertThat(pairs(h.out)).doesNotContain("3,3,N");
            for (CapturingRowWriter.Captured row : h.out) {
                assertThat(row.asLong(0)).isNotEqualTo(3L);
                assertThat(row.asLong(1)).isNotEqualTo(3L);
            }
            long rowsHeldBefore = h.pipeline.joinRowsHeld();
            long keysHeldBefore = h.pipeline.joinKeysHeld();
            for (int i = 0; i < 10_000; i++) {
                h.feed(
                        "jl",
                        jlKey(),
                        noBin(left(1000L + i, null, null, null, null, null, null, null, null, null, null, 0)));
                h.feed(
                        "jr",
                        jrKey(),
                        noBin(right(1000L + i, null, null, null, null, null, null, null, null, null, null, "Z", 0)));
            }
            assertThat(h.pipeline.joinRowsHeld())
                    .as("10,000 null-keyed rows on each side must not grow join state")
                    .isEqualTo(rowsHeldBefore);
            assertThat(h.pipeline.joinKeysHeld()).isEqualTo(keysHeldBefore);
        }
    }

    // ================================================================= GROUP BY (section 6)

    private static StreamSchema types() {
        return StreamSchema.builder("types")
                .field("id", Types.int64())
                .field("b", Types.bool().withNullable(true))
                .field("i8", Types.int8().withNullable(true))
                .field("i16", Types.int16().withNullable(true))
                .field("i32", Types.int32().withNullable(true))
                .field("i64", Types.int64().withNullable(true))
                .field("f32", Types.float32().withNullable(true))
                .field("f64", Types.float64().withNullable(true))
                .field("s", Types.string().withNullable(true))
                .field("bin", Types.bytes().withNullable(true))
                .field("ts", Types.timestamp().withNullable(true))
                .build();
    }

    /** Bounded (view-read shaped) single-stream harness: feed finite rows, finish, capture output. */
    private static List<CapturingRowWriter.Captured> runBounded(StreamSchema schema, String sql, List<Object[]> rows) {
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .overBoundedInput()
                .build(SqlPlanner.withStreams(schema).plan(sql));
        List<CapturingRowWriter.Captured> out = new ArrayList<>();
        RowLayout layout = RowLayout.of(schema);
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(
                        plan, (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), out::add))) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView reader = new BinaryRowView(layout);
            long seq = 0;
            for (Object[] row : rows) {
                long handle = arena.allocate(layout.rowSize(512));
                writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                for (int i = 0; i < row.length; i++) {
                    setField(writer, schema, i, row[i]);
                }
                seq++;
                writer.weight(1).eventTimestampNanos(seq).sequence(seq).commit();
                arena.trimTo(handle, writer.sizeSoFar());
                pipeline.accept(reader.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            }
            pipeline.finish();
        }
        return out;
    }

    private static Object[] typesRow(
            long id,
            Object b,
            Object i8,
            Object i16,
            Object i32,
            Object i64,
            Object f32,
            Object f64,
            Object s,
            Object bin,
            Object ts) {
        return new Object[] {id, b, i8, i16, i32, i64, f32, f64, s, bin, ts};
    }

    // Row 5 spells the integer it starts from, 2^24 + 1 and 2^53 + 1, as the float and double literal
    // the case file writes; the literal rounding is the point of the row.
    // NullAway: nulls passed on purpose
    @SuppressWarnings({"FloatingPointLiteralPrecision", "NullAway"})
    private static final List<Object[]> TYPES_5 = List.of(
            typesRow(
                    1,
                    true,
                    (byte) 127,
                    (short) 32767,
                    2147483647,
                    9223372036854775807L,
                    3.4028235E38f,
                    1.7976931348623157E308,
                    "zed",
                    "cafe".getBytes(UTF_8),
                    1700000000000000000L),
            typesRow(
                    2,
                    false,
                    (byte) -128,
                    (short) -32768,
                    -2147483648,
                    -9223372036854775808L,
                    1.4E-45f,
                    4.9E-324,
                    "ann",
                    null,
                    0L),
            typesRow(3, null, null, null, null, null, null, null, null, null, null),
            typesRow(
                    4, true, (byte) 0, (short) 0, 0, 0L, 0.0f, 0.0, null, "beef".getBytes(UTF_8), 1700000000000000001L),
            typesRow(
                    5,
                    false,
                    (byte) 1,
                    (short) 1,
                    16777217,
                    9007199254740993L,
                    16777217.0f,
                    9007199254740993.0,
                    "  pad  ",
                    "ff".getBytes(UTF_8),
                    -1L));

    // ---------------------------------------------------------------------- TYPE-053

    @Test
    void type053_stringGroupKeyOverBoundedRead() {
        List<CapturingRowWriter.Captured> out =
                runBounded(types(), "SELECT s, COUNT(*) AS n FROM types GROUP BY s", TYPES_5);
        assertThat(out).hasSize(4);
        long total = out.stream().mapToLong(r -> r.asLong(1)).sum();
        assertThat(total).isEqualTo(5);
        long nullGroupCount =
                out.stream().filter(r -> r.isNull(0)).findFirst().orElseThrow().asLong(1);
        assertThat(nullGroupCount).isEqualTo(2);
    }

    // ---------------------------------------------------------------------- TYPE-054

    @Test
    void type054_fourIntegerWidthsAsGroupKeys() {
        for (String col : List.of("i8", "i16", "i32", "i64")) {
            List<CapturingRowWriter.Captured> out =
                    runBounded(types(), "SELECT " + col + ", COUNT(*) FROM types GROUP BY " + col, TYPES_5);
            assertThat(out).as(col).hasSize(5);
            assertThat(out.stream().mapToLong(r -> r.asLong(1)).sum()).as(col).isEqualTo(5);
        }
        // merge check: row 6 (i8=i16=i32=i64=1) merges with row 5's i8=1 and i16=1 (both already
        // "1" in the base fixture), but row 5's i32=16777217 and i64=9007199254740993 are not "1",
        // so for those two widths row 6 is a *new*, sixth, distinct group -- the case file's own
        // "the same for i16, i32 and i64" claim does not hold for i32/i64 given the fixture values
        // it itself specifies; recorded as a case-file arithmetic note, not a product defect. What
        // this still proves: each width reads its own bytes without cross-contamination (an i8 read
        // too wide would corrupt differently), which is the falsifier this sub-case actually guards.
        List<Object[]> six = new ArrayList<>(TYPES_5);
        six.add(typesRow(6, true, (byte) 1, (short) 1, 1, 1L, 0.0f, 0.0, "x", "y".getBytes(UTF_8), 0L));
        for (String col : List.of("i8", "i16")) {
            List<CapturingRowWriter.Captured> out =
                    runBounded(types(), "SELECT " + col + ", COUNT(*) FROM types GROUP BY " + col, six);
            assertThat(out).as(col).hasSize(5);
            assertThat(out.stream().mapToLong(r -> r.asLong(1)).max().orElseThrow())
                    .as(col)
                    .isEqualTo(2);
            assertThat(out.stream().mapToLong(r -> r.asLong(1)).sum()).as(col).isEqualTo(6);
        }
        for (String col : List.of("i32", "i64")) {
            List<CapturingRowWriter.Captured> out =
                    runBounded(types(), "SELECT " + col + ", COUNT(*) FROM types GROUP BY " + col, six);
            assertThat(out).as(col).hasSize(6);
            assertThat(out.stream().mapToLong(r -> r.asLong(1)).max().orElseThrow())
                    .as(col)
                    .isEqualTo(1);
            assertThat(out.stream().mapToLong(r -> r.asLong(1)).sum()).as(col).isEqualTo(6);
        }
    }

    // ---------------------------------------------------------------------- TYPE-055

    @Test
    void type055_floatGroupKeysLegalAggregateArgumentIllegal() {
        // The case file's Expected walks "the ten rows": the base five, plus TYPE-054's row 6
        // (f64=0.0, so it is the second row at that value), plus this case's own rows 7-10.
        List<Object[]> rows = new ArrayList<>(TYPES_5);
        rows.add(typesRow(6, true, (byte) 1, (short) 1, 1, 1L, 0.0f, 0.0, "x", "y".getBytes(UTF_8), 0L));
        rows.add(typesRow(7, true, (byte) 0, (short) 0, 0, 0L, 0.1f, 0.1, "g", "h".getBytes(UTF_8), 0L));
        rows.add(typesRow(8, true, (byte) 0, (short) 0, 0, 0L, 0.2f, 0.2, "g", "h".getBytes(UTF_8), 0L));
        rows.add(
                typesRow(9, true, (byte) 0, (short) 0, 0, 0L, 0.3f, 0.30000000000000004, "g", "h".getBytes(UTF_8), 0L));
        rows.add(typesRow(10, true, (byte) 0, (short) 0, 0, 0L, -0.0f, -0.0, "g", "h".getBytes(UTF_8), 0L));

        List<CapturingRowWriter.Captured> out =
                runBounded(types(), "SELECT f64, COUNT(*) AS n FROM types GROUP BY f64", rows);
        // NANGROUP-1: -0.0 and 0.0 are one group, as SQL equality says (d = 0 keeps both), published
        // 0.0. It used to be a group of its own, nine groups where SQL has eight.
        assertThat(out).hasSize(8);
        long zeroCount = out.stream()
                .filter(r -> !r.isNull(0) && ((Double) r.values()[0]) == 0.0)
                .mapToLong(r -> r.asLong(1))
                .sum();
        assertThat(zeroCount).isEqualTo(3);
        boolean hasNegativeZeroGroup =
                out.stream().anyMatch(r -> !r.isNull(0) && isNegativeZero((Double) r.values()[0]));
        assertThat(hasNegativeZeroGroup).as("-0.0 joins 0.0's group").isFalse();

        assertThatThrownBy(() -> runBounded(types(), "SELECT SUM(f64) FROM types", rows))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2020");

        List<CapturingRowWriter.Captured> having =
                runBounded(types(), "SELECT f64, COUNT(*) AS n FROM types GROUP BY f64 HAVING COUNT(*) > 1", rows);
        assertThat(having).hasSize(1);
        assertThat(having.get(0).asLong(1)).isEqualTo(3);
    }

    private static boolean isNegativeZero(double d) {
        return Double.doubleToRawLongBits(d) == Double.doubleToRawLongBits(-0.0);
    }

    // ---------------------------------------------------------------------- TYPE-056

    @Test
    void type056_booleanGroupKey() {
        List<CapturingRowWriter.Captured> out =
                runBounded(types(), "SELECT b, COUNT(*) FROM types GROUP BY b", TYPES_5);
        assertThat(out).hasSize(3);
        assertThat(out.stream().mapToLong(r -> r.asLong(1)).sum()).isEqualTo(5);

        List<CapturingRowWriter.Captured> countB =
                runBounded(types(), "SELECT b, COUNT(b) FROM types GROUP BY b", TYPES_5);
        long nullGroupCountB = countB.stream()
                .filter(r -> r.isNull(0))
                .findFirst()
                .orElseThrow()
                .asLong(1);
        assertThat(nullGroupCountB)
                .as("COUNT(b) over the NULL group is 0, not 1")
                .isEqualTo(0);
    }

    // ---------------------------------------------------------------------- TYPE-057

    @Test
    void type057_timestampGroupKey() {
        List<CapturingRowWriter.Captured> out =
                runBounded(types(), "SELECT ts, COUNT(*) FROM types GROUP BY ts", TYPES_5);
        assertThat(out).hasSize(5);

        List<Object[]> rows = new ArrayList<>(TYPES_5);
        rows.add(typesRow(11, true, (byte) 0, (short) 0, 0, 0L, 0.0f, 0.0, "x", "y".getBytes(UTF_8), 0L));
        List<CapturingRowWriter.Captured> out2 =
                runBounded(types(), "SELECT ts, COUNT(*) FROM types GROUP BY ts", rows);
        assertThat(out2).hasSize(5);
        assertThat(out2.stream().mapToLong(r -> r.asLong(1)).max().orElseThrow())
                .isEqualTo(2);
    }

    // ---------------------------------------------------------------------- TYPE-058

    @Test
    void type058_bytesGroupKeyRefusedAtRunTime() {
        assertThatThrownBy(() -> runBounded(types(), "SELECT bin, COUNT(*) FROM types GROUP BY bin", TYPES_5))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("BYTES");
    }

    /** DECKEYGROUP-1: a DECIMAL group key was refused here, and is now grouped by its whole value. */
    @Test
    void type058_decimalGroupKeyGroupsByTheWholeValue() {
        StreamSchema n = StreamSchema.builder("n")
                .field("id", Types.int64())
                .field("amt", Types.decimal(10, 2).withNullable(true))
                .build();
        List<Object[]> rows = List.of(
                new Object[] {1L, new long[] {0L, 12345L}},
                new Object[] {2L, new long[] {0L, 99999L}},
                new Object[] {3L, new long[] {0L, 12345L}});
        List<CapturingRowWriter.Captured> out = runBounded(n, "SELECT amt, COUNT(*) FROM n GROUP BY amt", rows);
        assertThat(out).hasSize(2);
        assertThat(out.stream().mapToLong(r -> r.asLong(1)).sorted().toArray()).containsExactly(1L, 2L);
    }

    @Test
    void type058_dateAndTimeGroupKeysSucceed() {
        StreamSchema n = StreamSchema.builder("n")
                .field("id", Types.int64())
                .field("d", Types.date().withNullable(true))
                .field("t", Types.time().withNullable(true))
                .build();
        List<Object[]> rows = List.of(
                new Object[] {1L, 19723, 3_600_000_000_000L},
                new Object[] {2L, 19724, 7_200_000_000_000L},
                new Object[] {3L, 19723, 3_600_000_000_000L});
        List<CapturingRowWriter.Captured> byDate = runBounded(n, "SELECT d, COUNT(*) FROM n GROUP BY d", rows);
        assertThat(byDate).hasSize(2);
        assertThat(byDate.stream().mapToLong(r -> r.asLong(1)).max().orElseThrow())
                .isEqualTo(2);

        List<CapturingRowWriter.Captured> byTime = runBounded(n, "SELECT t, COUNT(*) FROM n GROUP BY t", rows);
        assertThat(byTime).hasSize(2);
    }

    // ---------------------------------------------------------------------- TYPE-059

    @Test
    void type059_nullIsAGroupAndCompositeKeyWithNulls() {
        List<CapturingRowWriter.Captured> out =
                runBounded(types(), "SELECT b, s, COUNT(*) FROM types GROUP BY b, s", TYPES_5);
        assertThat(out).hasSize(5);
        assertThat(out.stream().mapToLong(r -> r.asLong(2)).sum()).isEqualTo(5);

        // row 3 (NULL,NULL) and row 4 (true,NULL) must NOT merge.
        long distinctNullSGroups = out.stream().filter(r -> r.isNull(1)).count();
        assertThat(distinctNullSGroups)
                .as("row3 (NULL,NULL) and row4 (true,NULL) stay separate")
                .isEqualTo(2);

        List<CapturingRowWriter.Captured> countS =
                runBounded(types(), "SELECT b, s, COUNT(s) FROM types GROUP BY b, s", TYPES_5);
        long sum = countS.stream().mapToLong(r -> r.asLong(2)).sum();
        assertThat(sum).isEqualTo(3);
    }
}
