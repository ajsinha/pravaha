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

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.runtime.lane.LaneMultiplexer;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A row says which stream it came from, through the writer that real rows go through.
 *
 * <p>W9-9. `BinaryRowWriter` wrote `schema.version()` into the header field called "schema id" --
 * the count of how many times a schema had evolved, which is 1 for every stream that never has. So
 * every row of every stream carried the same value, and `LaneMultiplexer`, whose entire dispatch is
 * "group by that field and hand each group only to the pipelines subscribed to it", would have
 * handed a query subscribed to `txn` the rows of `orders`.
 *
 * <p>`LaneMultiplexerTest` could not see it: it writes ids straight into the region rather than
 * through a writer, so it proves the dispatch is right given distinct ids and never touches what
 * produces them. This goes through the writer, which is the half that was wrong.
 */
final class StreamIdentityTest {

    private static StreamSchema stream(String name) {
        return StreamSchema.builder(name)
                .field("k", Types.string())
                .field("v", Types.int64())
                .build();
    }

    @Test
    void rowsFromDifferentStreamsCarryDifferentIds() {
        // Two streams in one catalogue, which is the situation the dispatch exists for.
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), stream("txn"), stream("orders"));
        StreamSchema txn = registry.streams()[0];
        StreamSchema orders = registry.streams()[1];

        assertThat(txn.streamId()).as("a stream in a catalogue has an identity").isPositive();
        assertThat(orders.streamId()).isPositive();
        assertThat(txn.streamId())
                .as("and two streams do not share one -- this is the whole of W9-9")
                .isNotEqualTo(orders.streamId());
        assertThat(txn.version())
                .as("while their evolution versions are identical, which is what was being read")
                .isEqualTo(orders.version());

        // Through the writer, not around it. A row's id has to survive the path real rows take.
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 2)) {
            assertThat(idOfWrittenRow(arena, txn))
                    .as("the row carries its own stream's id, not a version")
                    .isEqualTo(txn.streamId());
            assertThat(idOfWrittenRow(arena, orders)).isEqualTo(orders.streamId());
            assertThat(idOfWrittenRow(arena, txn)).isNotEqualTo(idOfWrittenRow(arena, orders));
        }
        registry.close();
    }

    @Test
    void aStreamWithNoIdentityIsRefusedByTheLaneRatherThanMisdispatched() {
        // A schema built by hand has no id, and so does every row written from it. Accepting that
        // would group one pipeline with every other equally anonymous stream and hand it their
        // rows, which is precisely the failure this refusal exists to make impossible.
        LaneMultiplexer multiplexer = new LaneMultiplexer();
        assertThatThrownBy(() -> multiplexer.register(new LaneMultiplexer.Pipeline(
                        "q", StreamSchema.UNASSIGNED_STREAM_ID, (region, offsets, count) -> count)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no stream id")
                .hasMessageContaining("cannot tell which rows are its own");

        assertThat(multiplexer.pipelineCount()).as("and nothing was registered").isZero();
    }

    @Test
    void anIdentifiedStreamKeepsItsIdWhenAnotherRegistryWrapsIt() {
        // A schema already identified must not be renumbered underneath rows already written with
        // its old id.
        StreamSchema identified = stream("txn").withStreamId(77);
        QueryRegistry second = new QueryRegistry(new ViewCatalog(), identified);
        assertThat(second.streams()[0].streamId()).isEqualTo(77);
        second.close();
    }

    private static int idOfWrittenRow(RowArena arena, StreamSchema schema) {
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, "k1").setLong(1, 1L);
        writer.weight(1L).eventTimestampNanos(0L).sequence(1L).commit();
        return new BinaryRowView(layout)
                .wrap(arena.regionOf(handle), arena.offsetOf(handle))
                .schemaId();
    }
}
