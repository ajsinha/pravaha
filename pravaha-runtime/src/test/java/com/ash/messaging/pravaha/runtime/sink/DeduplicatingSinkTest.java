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
package com.ash.messaging.pravaha.runtime.sink;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.api.plugin.Version;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Closing the duplicate window that recovery opens.
 *
 * <p>A source resumes from the last checkpoint and re-delivers everything after it, so rows written
 * between the checkpoint and the crash are written twice. A sink with neither transactions nor
 * idempotent upsert cannot tell the second write from a new one, and the duplicates surface weeks
 * later as a reconciliation that does not balance.
 */
class DeduplicatingSinkTest {

    private static final StreamSchema SCHEMA =
            StreamSchema.builder("out").field("id", Types.int64()).build();

    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 4);

    /** Records what it was given, and claims nothing it cannot do. */
    private static class RecordingSink implements StreamSinkPlugin {
        private final List<Long> written = new ArrayList<>();

        @Override
        public SinkCapabilities capabilities() {
            return SinkCapabilities.appendOnly();
        }

        @Override
        public int write(List<RowView> batch) {
            batch.forEach(row -> written.add(row.getLong(0)));
            return batch.size();
        }

        @Override
        public void flush() {}

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public Version version() {
            return new Version(1, 0, 0);
        }

        @Override
        public void configure(PluginContext context) {}

        @Override
        public void open() {}

        @Override
        public void close() {}
    }

    @Test
    void everythingIsWrittenOnceWhenNothingIsReplayed() {
        RecordingSink sink = new RecordingSink();
        DeduplicatingSink dedup = new DeduplicatingSink(sink);

        assertThat(dedup.write(rows(1, 2, 3))).isEqualTo(3);
        assertThat(dedup.write(rows(4, 5))).isEqualTo(2);

        assertThat(sink.written).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(dedup.duplicatesDropped()).isZero();
        assertThat(dedup.highWaterMark()).isEqualTo(5);
    }

    @Test
    void aReplayAfterRestoreIsDroppedUpToTheMarkAndNotBeyondIt() {
        // The whole point. The checkpoint recorded that rows up to 3 had been written; recovery
        // re-delivers 2, 3, 4, 5 and only 4 and 5 are new.
        RecordingSink sink = new RecordingSink();
        DeduplicatingSink dedup = new DeduplicatingSink(sink);
        dedup.restoreTo(3);

        assertThat(dedup.write(rows(2, 3, 4, 5))).isEqualTo(2);

        assertThat(sink.written).containsExactly(4L, 5L);
        assertThat(dedup.duplicatesDropped()).isEqualTo(2);
    }

    @Test
    void aBatchThatIsEntirelyDuplicateDoesNotReachTheSinkAtAll() {
        // Not merely filtered to empty: a sink that is called with nothing may still flush, open a
        // transaction, or make a round trip, and recovery would pay for it once per batch.
        RecordingSink sink = new RecordingSink() {
            @Override
            public int write(List<RowView> batch) {
                throw new AssertionError("the sink was called with a batch that was entirely duplicate");
            }
        };
        DeduplicatingSink dedup = new DeduplicatingSink(sink);
        dedup.restoreTo(10);

        assertThat(dedup.write(rows(1, 2, 3))).isZero();
        assertThat(dedup.duplicatesDropped()).isEqualTo(3);
    }

    @Test
    void theMarkAdvancesOnlyAfterTheSinkHasTakenTheRows() {
        // A mark advanced before the write would claim rows reached the sink when the write then
        // failed, and the next restore would skip them permanently.
        DeduplicatingSink dedup = new DeduplicatingSink(new RecordingSink() {
            @Override
            public int write(List<RowView> batch) {
                throw new IllegalStateException("the sink is down");
            }
        });

        assertThatThrownBy(() -> dedup.write(rows(1, 2, 3))).isInstanceOf(IllegalStateException.class);

        assertThat(dedup.highWaterMark())
                .as("a failed write must not count as written")
                .isEqualTo(DeduplicatingSink.NOTHING_WRITTEN);
    }

    @Test
    void aSequenceGoingBackwardsIsRefusedRatherThanDroppingLiveRows() {
        RecordingSink sink = new RecordingSink();
        DeduplicatingSink dedup = new DeduplicatingSink(sink);
        dedup.write(rows(10));

        // Not a duplicate -- a source that reuses or reorders sequences. Silently dropping this
        // would mean a query writing less than it computed, with nothing to show for it.
        assertThatThrownBy(() -> dedup.write(rows(20, 15)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("only increase");
    }

    @Test
    void restoringAheadOfWhatWasWrittenIsRefused() {
        DeduplicatingSink dedup = new DeduplicatingSink(new RecordingSink());
        dedup.write(rows(1, 2));

        assertThatThrownBy(() -> dedup.restoreTo(50))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("would skip rows that never reached the sink");
    }

    @Test
    void anAtLeastOnceSinkBecomesEffectivelyOnceAndSaysSo() {
        DeduplicatingSink dedup = new DeduplicatingSink(new RecordingSink());

        assertThat(new RecordingSink().capabilities().guarantee()).isEqualTo(DeliveryGuarantee.AT_LEAST_ONCE);
        assertThat(dedup.capabilities().guarantee()).isEqualTo(DeliveryGuarantee.EXACTLY_ONCE);
        assertThat(dedup.capabilities().idempotentUpsert()).isTrue();
        assertThat(dedup.capabilities().emitModes()).isEqualTo(EnumSet.of(EmitMode.APPEND));
    }

    @Test
    void aTransactionalSinkIsLeftExactlyAsItDeclaredItself() {
        // Wrapping one changes nothing it did not already do, and rewriting its declaration would
        // report a guarantee that came from the wrapper rather than from the sink.
        DeduplicatingSink dedup = new DeduplicatingSink(new RecordingSink() {
            @Override
            public SinkCapabilities capabilities() {
                return new SinkCapabilities(EnumSet.of(EmitMode.UPSERT), true, false, 500);
            }
        });

        assertThat(dedup.capabilities().transactional()).isTrue();
        assertThat(dedup.capabilities().idempotentUpsert()).isFalse();
        assertThat(dedup.capabilities().maxBatchRows()).isEqualTo(500);
    }

    /** Rows whose id and sequence are both the given value. */
    private List<RowView> rows(long... sequences) {
        RowLayout layout = RowLayout.of(SCHEMA);
        List<RowView> rows = new ArrayList<>();
        for (long sequence : sequences) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            long handle = arena.allocate(layout.rowSize(64));
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            writer.setLong(0, sequence)
                    .weight(1)
                    .eventTimestampNanos(sequence)
                    .sequence(sequence)
                    .commit();
            rows.add(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        }
        return rows;
    }
}
