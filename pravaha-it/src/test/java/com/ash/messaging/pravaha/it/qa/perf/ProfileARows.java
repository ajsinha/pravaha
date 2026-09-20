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
package com.ash.messaging.pravaha.it.qa.perf;

import java.util.List;
import java.util.Random;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.plan.FilterOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.Predicate;
import com.ash.messaging.pravaha.runtime.plan.ProjectOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;

/**
 * Design section 28.4's Profile A, encoded once: twelve fields, about 200 bytes, 10 % selectivity.
 *
 * <p>The same shape {@code ProfileABenchmark} uses, deliberately, so a figure from the lane path
 * and a figure from the fused operator are about the same rows. The difference between the two
 * harnesses is what surrounds the operator, and that only means anything if the rows are the same.
 *
 * <p>A fixed pool of rows, written once into one region and replayed. Encoding a fresh row per
 * iteration would put the writer inside the measurement, and the writer is the source decoder's
 * job rather than the lane's -- {@code ProfileAGateIT} measures that separately and says which
 * figure includes it.
 */
final class ProfileARows implements AutoCloseable {

    /** Distinct rows in the pool: enough that the predicate is not one branch, small enough to stay warm. */
    static final int POOL = 512;

    private static final String COMPLETED = "COMPLETED";

    private final MemoryRegion region;
    private final long[] offsets;
    private final int[] lengths;
    private final int[] passingBefore;
    private final int passing;
    private final int widest;

    private ProfileARows(
            MemoryRegion region, long[] offsets, int[] lengths, int[] passingBefore, int passing, int widest) {
        this.region = region;
        this.offsets = offsets;
        this.lengths = lengths;
        this.passingBefore = passingBefore;
        this.passing = passing;
        this.widest = widest;
    }

    /** Twelve fields, roughly 200 bytes encoded, exactly as design section 28.4 specifies. */
    static StreamSchema schema() {
        return StreamSchema.builder("txn")
                .field("txn_id", Types.int64())
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .field("fee", Types.int64())
                .field("score", Types.int32())
                .field("rank", Types.int32())
                .field("ratio", Types.float64())
                .field("weight", Types.float64())
                .field("active", Types.bool())
                .field("flagged", Types.bool())
                .field("status", Types.string())
                .field("region", Types.string())
                .build();
    }

    static StreamSchema outputSchema() {
        return StreamSchema.builder("out")
                .field("txn_id", Types.int64())
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .build();
    }

    /** {@code SELECT txn_id, user_id, amount FROM txn WHERE status = 'COMPLETED' AND amount > 900}. */
    static PhysicalOperator plan() {
        StreamSchema in = schema();
        Predicate predicate = new Predicate.And(List.of(
                new Predicate.CompareString(10, "status", Predicate.Op.EQ, COMPLETED),
                new Predicate.CompareLong(2, "amount", Predicate.Op.GT, 900)));
        return new ProjectOperator(
                new FilterOperator(ScanOperator.of("txn", in), predicate), outputSchema(), List.of(0, 1, 2));
    }

    /** The SQL the planner is given for the same query, when the harness goes through the SQL path. */
    static String sql() {
        return "SELECT txn_id, user_id, amount FROM txn WHERE status = 'COMPLETED' AND amount > 900";
    }

    static ProfileARows encode(MemoryAccess access) {
        RowLayout layout = RowLayout.of(schema());
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        MemoryRegion region = access.allocate(POOL * 512);
        long[] offsets = new long[POOL];
        int[] lengths = new int[POOL];
        int[] passingBefore = new int[POOL + 1];
        // Seeded, so two runs on two days compare. The distribution is the benchmark's, not this
        // machine's, and a number taken against different rows is a different number.
        Random random = new Random(20260920L);
        int cursor = 0;
        int passing = 0;
        int widest = 0;
        for (int i = 0; i < POOL; i++) {
            offsets[i] = cursor;
            writer.begin(region, cursor);
            long amount = random.nextInt(1000);
            String status = random.nextInt(3) == 0 ? COMPLETED : "PENDING";
            writer.setLong(0, i)
                    .setLong(1, random.nextInt(100_000))
                    .setLong(2, amount)
                    .setLong(3, random.nextInt(100))
                    .setInt(4, random.nextInt(1000))
                    .setInt(5, random.nextInt(100))
                    .setDouble(6, random.nextDouble())
                    .setDouble(7, random.nextDouble())
                    .setBoolean(8, random.nextBoolean())
                    .setBoolean(9, random.nextBoolean())
                    .setString(10, status)
                    .setString(11, "eu-west-1")
                    .weight(1L)
                    .eventTimestampNanos(1_700_000_000_000_000_000L + i)
                    .sequence(i)
                    .commit();
            int length = writer.sizeSoFar();
            lengths[i] = length;
            widest = Math.max(widest, length);
            if (COMPLETED.equals(status) && amount > 900) {
                passing++;
            }
            passingBefore[i + 1] = passing;
            cursor += (length + 7) & ~7;
        }
        if (passing == 0) {
            throw new IllegalStateException(
                    "no row in the pool passes the Profile A predicate, so a run over it would measure an "
                            + "empty pipeline rather than a filter");
        }
        return new ProfileARows(region, offsets, lengths, passingBefore, passing, widest);
    }

    /**
     * Exactly how many rows a run of {@code fed} rows must emit, given that the pool is replayed
     * round-robin from index zero.
     *
     * <p>Whole cycles times the pool's yield, plus the yield of the partial cycle at the end.
     * Rounding this to whole cycles is what a first version of the harness did, and it failed the
     * run by six rows -- which is exactly the kind of near-miss that invites somebody to relax the
     * assertion into a tolerance and thereby delete the check.
     */
    long emittedBy(long fed) {
        long cycles = fed / POOL;
        int remainder = (int) (fed % POOL);
        return cycles * passing + passingBefore[remainder];
    }

    MemoryRegion region() {
        return region;
    }

    long offset(int index) {
        return offsets[index];
    }

    int length(int index) {
        return lengths[index];
    }

    /** How many of {@link #POOL} rows the predicate lets through: the selectivity, counted. */
    int passing() {
        return passing;
    }

    /** The widest encoded row, which is the floor for an inbox cell. */
    int widestRow() {
        return widest;
    }

    @Override
    public void close() {
        region.close();
    }
}
