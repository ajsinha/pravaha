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

import java.time.Duration;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;

/**
 * Design section 28.4's Profile B, encoded once: a ten-second tumbling aggregate over 100 000
 * keys drawn Zipf s = 1.1.
 *
 * <p>The skew is the specification and not a decoration. A uniform key distribution gives every
 * hash bucket the same depth and every window the same amount of work, which is the one shape real
 * data never has; design section 28.4 says Zipf s = 1.1 precisely so the profile exercises a hot
 * key. Measuring Profile B with uniform keys would report a number about a workload nobody runs.
 *
 * <p>The pool is encoded once and replayed, and it carries its own arithmetic: how many distinct
 * (user, window) groups it contains and what each group's count and sum should be. That is what
 * lets {@code ProfileBGateIT} assert the aggregate actually computed something rather than
 * reporting a splendid rate for rows that fell on the floor.
 */
final class ProfileBRows implements AutoCloseable {

    /** Design section 28.4: 100 000 distinct keys. */
    static final int KEYS = 100_000;

    /** Design section 28.4: Zipf s = 1.1. */
    static final double ZIPF_S = 1.1;

    /** Ten seconds, in nanoseconds: the tumbling window design section 28.4 names. */
    static final long WINDOW_NANOS = 10_000_000_000L;

    /** How many windows the pool's event timestamps span. Four keeps the group count under the view's ceiling. */
    static final int WINDOWS = 4;

    private static final long EPOCH_NANOS = 1_700_000_000_000_000_000L;

    private final MemoryRegion region;
    private final long[] offsets;
    private final int widest;
    private final int rows;
    private final int groups;
    private final int distinctKeys;

    private ProfileBRows(MemoryRegion region, long[] offsets, int widest, int rows, int groups, int distinctKeys) {
        this.region = region;
        this.offsets = offsets;
        this.widest = widest;
        this.rows = rows;
        this.groups = groups;
        this.distinctKeys = distinctKeys;
    }

    /**
     * Twelve fields and about 200 bytes, the payload design section 5.2 assumes, with an event-time
     * column -- without one no watermark advances, no window ever closes and the view stays empty
     * while the query reports RUNNING.
     */
    static StreamSchema schema() {
        return StreamSchema.builder("txn")
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .field("ts", Types.timestamp())
                .field("fee", Types.int64())
                .field("score", Types.int32())
                .field("rank", Types.int32())
                .field("ratio", Types.float64())
                .field("weight", Types.float64())
                .field("active", Types.bool())
                .field("flagged", Types.bool())
                .field("status", Types.string())
                .field("region", Types.string())
                .eventTime("ts")
                .outOfOrderness(Duration.ofSeconds(1))
                .build();
    }

    /** Ten-second tumbling COUNT, SUM and AVG by user: design section 28.4's Profile B, as SQL. */
    static String sql() {
        return "SELECT user_id, TUMBLE_START(ts, INTERVAL '10' SECOND), COUNT(*), SUM(amount), AVG(amount) "
                + "FROM txn GROUP BY user_id, TUMBLE(ts, INTERVAL '10' SECOND)";
    }

    /** Past every window in the pool, which is the watermark that closes all of them. */
    static long watermarkPastEverything() {
        return EPOCH_NANOS + (WINDOWS + 2L) * WINDOW_NANOS;
    }

    static ProfileBRows encode(MemoryAccess access, int rowCount) {
        RowLayout layout = RowLayout.of(schema());
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        MemoryRegion region = access.allocate(Math.toIntExact((long) rowCount * 256));
        long[] offsets = new long[rowCount];
        double[] cumulative = zipfCumulative();
        Random random = new Random(20260920L);
        Set<Long> groupIds = new HashSet<>();
        Set<Integer> keys = new HashSet<>();
        int cursor = 0;
        int widest = 0;
        for (int i = 0; i < rowCount; i++) {
            int user = zipfSample(cumulative, random.nextDouble());
            int window = i % WINDOWS;
            // Spread within the window rather than sitting on its edge, so slicing has something
            // to do and a boundary bug is not hidden by every row landing at the same instant.
            long ts =
                    EPOCH_NANOS + window * WINDOW_NANOS + random.nextInt((int) (WINDOW_NANOS / 1_000_000)) * 1_000_000L;
            long amount = 1L + random.nextInt(1000);
            offsets[i] = cursor;
            writer.begin(region, cursor);
            writer.setLong(0, user)
                    .setLong(1, amount)
                    .setLong(2, ts)
                    .setLong(3, random.nextInt(100))
                    .setInt(4, random.nextInt(1000))
                    .setInt(5, random.nextInt(100))
                    .setDouble(6, random.nextDouble())
                    .setDouble(7, random.nextDouble())
                    .setBoolean(8, random.nextBoolean())
                    .setBoolean(9, random.nextBoolean())
                    .setString(10, "COMPLETED")
                    .setString(11, "eu-west-1")
                    .weight(1L)
                    .eventTimestampNanos(ts)
                    .sequence(i)
                    .commit();
            int length = writer.sizeSoFar();
            widest = Math.max(widest, length);
            cursor += (length + 7) & ~7;
            groupIds.add((long) user * WINDOWS + window);
            keys.add(user);
        }
        if (groupIds.isEmpty()) {
            throw new IllegalStateException("the pool has no groups, so an aggregate over it would aggregate nothing");
        }
        return new ProfileBRows(region, offsets, widest, rowCount, groupIds.size(), keys.size());
    }

    /**
     * The Zipf CDF over {@link #KEYS} ranks, built once.
     *
     * <p>A table rather than a rejection sampler, because the sampler's cost would land inside the
     * encode loop and the encode loop is not what is being measured -- but more importantly because
     * a table is checkable: the harness can say what the distribution was.
     */
    private static double[] zipfCumulative() {
        double[] cumulative = new double[KEYS];
        double total = 0;
        for (int rank = 1; rank <= KEYS; rank++) {
            total += 1.0 / Math.pow(rank, ZIPF_S);
            cumulative[rank - 1] = total;
        }
        for (int i = 0; i < KEYS; i++) {
            cumulative[i] /= total;
        }
        return cumulative;
    }

    private static int zipfSample(double[] cumulative, double uniform) {
        int at = java.util.Arrays.binarySearch(cumulative, uniform);
        return at >= 0 ? at : Math.min(KEYS - 1, -at - 1);
    }

    MemoryRegion region() {
        return region;
    }

    long offset(int index) {
        return offsets[index];
    }

    int rows() {
        return rows;
    }

    /** Distinct (user, window) pairs in the pool: exactly what the view must hold once every window closes. */
    int groups() {
        return groups;
    }

    /** Distinct users the Zipf draw actually produced, which is fewer than {@link #KEYS}. */
    int distinctKeys() {
        return distinctKeys;
    }

    int widestRow() {
        return widest;
    }

    @Override
    public void close() {
        region.close();
    }
}
