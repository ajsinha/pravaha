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
import java.util.Random;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;

/**
 * Nexmark's three streams -- person, auction and bid -- as Pravaha schemas, with a pool of rows.
 *
 * <p>The field names are Nexmark's, lower-cased and underscored where the reference schema uses
 * camel case, because the SQL in {@link NexmarkCoverageIT} has to be recognisably the published
 * query rather than a rewrite. A comparison against somebody else's published number is worth
 * nothing if the query was quietly changed, and half the point of running Nexmark at all is that
 * the query is not ours to choose.
 *
 * <p>The generator is not Nexmark's. The reference generator produces a particular ratio of
 * persons to auctions to bids and a particular key skew; this produces rows of the right shape with
 * a plausible skew and a seeded random, which is enough to plan a query and enough to push rows
 * through one, and is not enough to publish a number against. {@link NexmarkCoverageIT} says so
 * where it reports.
 */
final class NexmarkStreams implements AutoCloseable {

    static final String PERSON = "person";
    static final String AUCTION = "auction";
    static final String BID = "bid";

    private static final long EPOCH_NANOS = 1_700_000_000_000_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private final MemoryRegion region;
    private final long[] personOffsets;
    private final long[] auctionOffsets;
    private final long[] bidOffsets;

    private NexmarkStreams(MemoryRegion region, long[] personOffsets, long[] auctionOffsets, long[] bidOffsets) {
        this.region = region;
        this.personOffsets = personOffsets;
        this.auctionOffsets = auctionOffsets;
        this.bidOffsets = bidOffsets;
    }

    static StreamSchema person() {
        return StreamSchema.builder(PERSON)
                .field("id", Types.int64())
                .field("name", Types.string())
                .field("email_address", Types.string())
                .field("credit_card", Types.string())
                .field("city", Types.string())
                .field("state", Types.string())
                .field("date_time", Types.timestamp())
                .field("extra", Types.string())
                .eventTime("date_time")
                .outOfOrderness(Duration.ofSeconds(1))
                .build();
    }

    static StreamSchema auction() {
        return StreamSchema.builder(AUCTION)
                .field("id", Types.int64())
                .field("item_name", Types.string())
                .field("description", Types.string())
                .field("initial_bid", Types.int64())
                .field("reserve", Types.int64())
                .field("date_time", Types.timestamp())
                .field("expires", Types.timestamp())
                .field("seller", Types.int64())
                .field("category", Types.int64())
                .field("extra", Types.string())
                .eventTime("date_time")
                .outOfOrderness(Duration.ofSeconds(1))
                .build();
    }

    static StreamSchema bid() {
        return StreamSchema.builder(BID)
                .field("auction", Types.int64())
                .field("bidder", Types.int64())
                .field("price", Types.int64())
                .field("channel", Types.string())
                .field("url", Types.string())
                .field("date_time", Types.timestamp())
                .field("extra", Types.string())
                .eventTime("date_time")
                .outOfOrderness(Duration.ofSeconds(1))
                .build();
    }

    static StreamSchema schemaOf(String stream) {
        return switch (stream) {
            case PERSON -> person();
            case AUCTION -> auction();
            case BID -> bid();
            default -> throw new IllegalArgumentException("nexmark has no stream '" + stream + "'");
        };
    }

    /** A watermark past every row in the pool, which closes every window over it. */
    static long watermarkPastEverything() {
        return EPOCH_NANOS + 3600L * SECOND;
    }

    static NexmarkStreams encode(MemoryAccess access, int people, int auctions, int bids) {
        MemoryRegion region = access.allocate(Math.toIntExact((long) (people + auctions + bids) * 512));
        Random random = new Random(20260920L);
        int[] cursor = {0};
        long[] personOffsets = new long[people];
        long[] auctionOffsets = new long[auctions];
        long[] bidOffsets = new long[bids];

        BinaryRowWriter personWriter = new BinaryRowWriter(RowLayout.of(person()));
        for (int i = 0; i < people; i++) {
            long at = EPOCH_NANOS + (long) i * SECOND / 8;
            personOffsets[i] = cursor[0];
            personWriter.begin(region, cursor[0]);
            personWriter
                    .setLong(0, i)
                    .setString(1, "person-" + i)
                    .setString(2, "p" + i + "@example.test")
                    .setString(3, "4111-1111-1111-" + (1000 + i % 9000))
                    .setString(4, "city-" + (i % 97))
                    .setString(
                            5,
                            switch (i % 4) {
                                case 0 -> "or";
                                case 1 -> "id";
                                case 2 -> "ca";
                                default -> "wa";
                            })
                    .setLong(6, at)
                    .setString(7, "extra")
                    .weight(1L)
                    .eventTimestampNanos(at)
                    .sequence(i)
                    .commit();
            cursor[0] += align8(personWriter.sizeSoFar());
        }

        BinaryRowWriter auctionWriter = new BinaryRowWriter(RowLayout.of(auction()));
        for (int i = 0; i < auctions; i++) {
            long at = EPOCH_NANOS + (long) i * SECOND / 16;
            auctionOffsets[i] = cursor[0];
            auctionWriter.begin(region, cursor[0]);
            auctionWriter
                    .setLong(0, i)
                    .setString(1, "item-" + i)
                    .setString(2, "description of item " + i)
                    .setLong(3, 1 + random.nextInt(100))
                    .setLong(4, 100 + random.nextInt(1000))
                    .setLong(5, at)
                    .setLong(6, at + 600L * SECOND)
                    .setLong(7, random.nextInt(Math.max(1, people)))
                    .setLong(8, 10 + (i % 5))
                    .setString(9, "extra")
                    .weight(1L)
                    .eventTimestampNanos(at)
                    .sequence(i)
                    .commit();
            cursor[0] += align8(auctionWriter.sizeSoFar());
        }

        BinaryRowWriter bidWriter = new BinaryRowWriter(RowLayout.of(bid()));
        for (int i = 0; i < bids; i++) {
            long at = EPOCH_NANOS + (long) i * SECOND / 64;
            bidOffsets[i] = cursor[0];
            bidWriter.begin(region, cursor[0]);
            bidWriter
                    .setLong(0, random.nextInt(Math.max(1, auctions)))
                    .setLong(1, random.nextInt(Math.max(1, people)))
                    .setLong(2, 1 + random.nextInt(10_000))
                    .setString(3, "channel-" + (i % 8))
                    .setString(4, "https://example.test/item?id=" + (i % 1024))
                    .setLong(5, at)
                    .setString(6, "extra")
                    .weight(1L)
                    .eventTimestampNanos(at)
                    .sequence(i)
                    .commit();
            cursor[0] += align8(bidWriter.sizeSoFar());
        }
        return new NexmarkStreams(region, personOffsets, auctionOffsets, bidOffsets);
    }

    MemoryRegion region() {
        return region;
    }

    long[] offsetsOf(String stream) {
        return switch (stream) {
            case PERSON -> personOffsets;
            case AUCTION -> auctionOffsets;
            case BID -> bidOffsets;
            default -> throw new IllegalArgumentException("nexmark has no stream '" + stream + "'");
        };
    }

    private static int align8(int value) {
        return (value + 7) & ~7;
    }

    @Override
    public void close() {
        region.close();
    }
}
