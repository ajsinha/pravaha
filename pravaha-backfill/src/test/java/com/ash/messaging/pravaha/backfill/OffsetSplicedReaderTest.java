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
package com.ash.messaging.pravaha.backfill;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The offset seam: history to here, the live stream from here, and nothing either lost or twice.
 *
 * <p>The property every case comes back to is one comparison: what the spliced reader delivers must
 * be, record for record and in order, what a single reader of the whole log delivers. That is the
 * only statement worth making, because both failure modes -- a record in the gap, a record in the
 * overlap -- are invisible in any weaker one. A count would pass while the seam dropped one record
 * and repeated another.
 *
 * <p>An off-by-one at the seam is what these are seeded against: the history stopping one record
 * early, the live reader starting one record late, the seam compared before the record rather than
 * after it. Each shows up here as a missing or repeated element in the delivered list.
 */
class OffsetSplicedReaderTest {

    private static final StreamSchema SCHEMA =
            StreamSchema.builder("log").field("n", Types.int64()).build();

    /**
     * A log of records with positions, as every replayable source has: a reader created at position
     * {@code n=k} delivers the records after it, and its position after a record names that record.
     */
    private static final class Log {
        private final List<Long> records = new ArrayList<>();
        private final AtomicLong readersOpened = new AtomicLong();

        void append(long value) {
            records.add(value);
        }

        int size() {
            return records.size();
        }

        /** The token naming the record at {@code index}, zero-based. */
        SourceOffset at(int index) {
            return index < 0 ? SourceOffset.BEGINNING : new SourceOffset("n=" + index);
        }

        Reader openAt(SourceOffset from) {
            readersOpened.incrementAndGet();
            return new Reader(
                    from.isBeginning() ? -1 : Integer.parseInt(from.token().substring(2)));
        }

        final class Reader implements PartitionReader {
            private int delivered;
            private boolean closed;

            Reader(int after) {
                this.delivered = after;
            }

            @Override
            public int poll(RecordSink sink, int maxRecords) {
                int emitted = 0;
                while (emitted < maxRecords && delivered + 1 < records.size()) {
                    long value = records.get(++delivered);
                    sink.beginRow()
                            .setLong(0, value)
                            .weight(1)
                            .eventTimestampNanos(value)
                            .sequence(value)
                            .commit();
                    emitted++;
                }
                return emitted;
            }

            @Override
            public SourceOffset position() {
                return at(delivered);
            }

            @Override
            public void pause() {}

            @Override
            public void resume() {}

            @Override
            public void close() {
                closed = true;
            }

            boolean isClosed() {
                return closed;
            }
        }
    }

    /** Collects everything a reader delivers, in order. */
    private static final class Delivered implements PartitionReader.RecordSink {
        private final List<Long> values = new ArrayList<>();

        @Override
        public RowWriter beginRow() {
            return new CapturingRowWriter(SCHEMA, captured -> values.add(captured.asLong(0)));
        }
    }

    private static List<Long> readAll(PartitionReader reader, int batch, int polls) {
        Delivered delivered = new Delivered();
        for (int i = 0; i < polls; i++) {
            reader.poll(delivered, batch);
        }
        return delivered.values;
    }

    @Test
    void historyAndTheLiveStreamMeetAtTheSeamWithNothingLostOrRepeated() {
        Log log = new Log();
        for (long value = 0; value < 50; value++) {
            log.append(value);
        }
        // The running version has read the first twenty records; that is the seam.
        SourceOffset seam = log.at(19);
        BackfillJob job = new BackfillJob("replace:orders");
        OffsetSplicedReader reader = new OffsetSplicedReader(log::openAt, SourceOffset.BEGINNING, seam, job, false);

        // Read the history, a few records at a time. It stops at the seam whatever the batch size:
        // three polls of seven would be twenty-one records, and the twenty-first is past the seam.
        List<Long> delivered = readAll(reader, 7, 3);
        assertThat(delivered).containsExactlyElementsOf(range(0, 20));
        assertThat(reader.phase()).isEqualTo(BackfillPhase.LIVE);
        assertThat(job.historyComplete()).isTrue();

        // And from the seam on, the live reader delivers the rest, once each.
        delivered.addAll(readAll(reader, 64, 2));
        assertThat(delivered).containsExactlyElementsOf(range(0, 50));

        // Records appended after the splice arrive on the live reader.
        log.append(50L);
        delivered.addAll(readAll(reader, 64, 1));
        assertThat(delivered).containsExactlyElementsOf(range(0, 51));
        reader.close();
    }

    @Property(tries = 200)
    void aSplicedReadEqualsReadingTheWholeLogWithOneReader(
            @ForAll @IntRange(min = 0, max = 200) int history,
            @ForAll @IntRange(min = 0, max = 60) int afterwards,
            @ForAll @IntRange(min = 1, max = 32) int batch,
            @ForAll @IntRange(min = 0, max = 4096) int seed) {
        Log log = new Log();
        Random random = new Random(seed);
        for (int i = 0; i < history; i++) {
            log.append(random.nextInt(1_000));
        }
        int seamIndex = history - 1 - (history == 0 ? 0 : random.nextInt(Math.max(1, history)));
        SourceOffset seam = log.at(seamIndex);

        OffsetSplicedReader spliced = new OffsetSplicedReader(
                log::openAt, SourceOffset.BEGINNING, seamIndex < 0 ? null : seam, new BackfillJob("p"), false);
        Delivered delivered = new Delivered();
        // Enough polls to drain the history a batch at a time, whatever the batch size.
        for (int i = 0; i < history + 4; i++) {
            spliced.poll(delivered, batch);
        }
        for (int i = 0; i < afterwards; i++) {
            log.append(random.nextInt(1_000));
        }
        for (int i = 0; i < afterwards + 4; i++) {
            spliced.poll(delivered, batch);
        }
        spliced.close();

        assertThat(delivered.values)
                .as("a spliced read of %d history records at seam %d plus %d live", history, seamIndex, afterwards)
                .containsExactlyElementsOf(log.records);
    }

    @Test
    void aStreamWithNoSeamIsOneReaderThatBecomesLiveWhenTheHistoryRunsOut() {
        Log log = new Log();
        for (long value = 0; value < 5; value++) {
            log.append(value);
        }
        BackfillJob job = new BackfillJob("replace:orders");
        OffsetSplicedReader reader = new OffsetSplicedReader(log::openAt, SourceOffset.BEGINNING, null, job, false);

        List<Long> delivered = readAll(reader, 2, 5);
        assertThat(delivered).containsExactlyElementsOf(range(0, 5));
        assertThat(reader.phase()).isEqualTo(BackfillPhase.LIVE);
        // One reader for both phases: nothing was opened a second time, so there is no seam to get
        // wrong in the first place.
        assertThat(log.readersOpened).hasValue(1);
        reader.close();
    }

    @Test
    void aSeamThatNeverArrivesStopsTheBackfillRatherThanReadingPastIt() {
        Log log = new Log();
        for (long value = 0; value < 3; value++) {
            log.append(value);
        }
        AtomicLong clock = new AtomicLong();
        OffsetSplicedReader reader = new OffsetSplicedReader(
                log::openAt,
                SourceOffset.BEGINNING,
                // A token this log will never report: the records are numbered, and this is not one.
                new SourceOffset("n=99"),
                new BackfillJob("p"),
                false,
                clock::get,
                1_000_000_000L);

        Delivered delivered = new Delivered();
        reader.poll(delivered, 64);
        assertThat(delivered.values).containsExactlyElementsOf(range(0, 3));

        // The first empty poll starts the clock rather than failing: a source is entitled to have
        // nothing for a moment.
        reader.poll(delivered, 64);
        clock.addAndGet(2_000_000_000L);
        assertThatThrownBy(() -> reader.poll(delivered, 64))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4013")
                .hasMessageContaining("n=99");
    }

    @Test
    void theThrottleBoundsHowFastHistoryIsRead() {
        Log log = new Log();
        for (long value = 0; value < 10_000; value++) {
            log.append(value);
        }
        AtomicLong clock = new AtomicLong();
        BackfillJob job = new BackfillJob("replace:orders", 1_000, clock::get);
        job.throttleTo(100);
        OffsetSplicedReader reader = new OffsetSplicedReader(
                log::openAt, SourceOffset.BEGINNING, log.at(9_999), job, false, clock::get, 1_000_000_000L);

        Delivered delivered = new Delivered();
        // Ten seconds of wall clock, polled a thousand times a second with a batch far above the
        // rate: what comes out is the rate, not the batch.
        for (int tick = 0; tick < 10_000; tick++) {
            clock.addAndGet(1_000_000L);
            reader.poll(delivered, 4096);
        }
        assertThat(delivered.values.size())
                .as("100 rows/s for 10 s, within one second's burst")
                .isBetween(900, 1_100);

        // Raising it raises the rate, up to the configured ceiling and no further.
        job.throttleTo(1_000);
        for (int tick = 0; tick < 1_000; tick++) {
            clock.addAndGet(1_000_000L);
            reader.poll(delivered, 4096);
        }
        assertThat(delivered.values.size()).isBetween(1_900, 2_200);
        reader.close();
    }

    @Test
    void aPausedBackfillReadsNothingAndResumesWhereItStopped() {
        Log log = new Log();
        for (long value = 0; value < 10; value++) {
            log.append(value);
        }
        BackfillJob job = new BackfillJob("replace:orders");
        OffsetSplicedReader reader =
                new OffsetSplicedReader(log::openAt, SourceOffset.BEGINNING, log.at(9), job, false);

        List<Long> delivered = readAll(reader, 3, 1);
        assertThat(delivered).containsExactlyElementsOf(range(0, 3));
        job.pause();
        assertThat(readAll(reader, 64, 3)).isEmpty();
        job.resume();
        delivered.addAll(readAll(reader, 64, 2));
        assertThat(delivered).containsExactlyElementsOf(range(0, 10));
        reader.close();
    }

    @Test
    void aPositionTakenDuringTheHistoryCarriesTheSeamAndResumesInTheSamePhase() {
        Log log = new Log();
        for (long value = 0; value < 20; value++) {
            log.append(value);
        }
        SourceOffset seam = log.at(14);
        OffsetSplicedReader reader =
                new OffsetSplicedReader(log::openAt, SourceOffset.BEGINNING, seam, new BackfillJob("p"), false);
        List<Long> delivered = readAll(reader, 5, 1);
        assertThat(delivered).containsExactlyElementsOf(range(0, 5));

        String token = reader.position().token();
        assertThat(OffsetSplicedReader.isBackfillToken(token)).isTrue();
        assertThat(OffsetSplicedReader.spliceOf(token)).isEqualTo(seam);
        assertThat(OffsetSplicedReader.historyOf(token)).isEqualTo(log.at(4));
        reader.close();

        // A restart resumes the same backfill: the same seam, from where the history had got to.
        OffsetSplicedReader resumed = new OffsetSplicedReader(
                log::openAt,
                OffsetSplicedReader.historyOf(token),
                OffsetSplicedReader.spliceOf(token),
                new BackfillJob("p"),
                false);
        delivered.addAll(readAll(resumed, 64, 4));
        assertThat(delivered).containsExactlyElementsOf(range(0, 20));
        assertThat(resumed.position().token()).isEqualTo("n=19");
        resumed.close();
    }

    @Test
    void aPositionTakenAfterTheSeamIsThePluginsOwn() {
        Log log = new Log();
        for (long value = 0; value < 4; value++) {
            log.append(value);
        }
        OffsetSplicedReader reader =
                new OffsetSplicedReader(log::openAt, SourceOffset.BEGINNING, log.at(1), new BackfillJob("p"), false);
        readAll(reader, 64, 3);
        // Plain, so a checkpoint taken after a cutover is readable by a registration that knows
        // nothing about backfills.
        assertThat(reader.position().token()).isEqualTo("n=3");
        assertThat(OffsetSplicedReader.isBackfillToken(reader.position().token()))
                .isFalse();
        reader.close();
    }

    @Test
    void aBackfillWhoseSeamIsWhereItStartsHasNoHistoryToRead() {
        Log log = new Log();
        for (long value = 0; value < 4; value++) {
            log.append(value);
        }
        // backfill = 'none': the new version starts from the running version's position.
        BackfillJob job = new BackfillJob("p");
        OffsetSplicedReader reader = new OffsetSplicedReader(log::openAt, log.at(1), log.at(1), job, false);
        assertThat(reader.phase()).isEqualTo(BackfillPhase.LIVE);
        assertThat(job.historyComplete()).isTrue();
        assertThat(readAll(reader, 64, 1)).containsExactly(2L, 3L);
        reader.close();
    }

    @Test
    void progressCountsHistoryAndLiveSeparatelyAndSurvivesTheReaderClosing() {
        Log log = new Log();
        for (long value = 0; value < 10; value++) {
            log.append(value);
        }
        AtomicLong clock = new AtomicLong();
        BackfillJob job = new BackfillJob("replace:orders", 0, clock::get);
        OffsetSplicedReader reader = new OffsetSplicedReader(
                log::openAt, SourceOffset.BEGINNING, log.at(5), job, false, clock::get, 1_000_000_000L);
        readAll(reader, 64, 1);
        clock.addAndGet(1_000_000_000L);
        BackfillJob.Progress progress = job.progress();
        assertThat(progress.historyRows()).isEqualTo(6);
        assertThat(progress.partitions()).isEqualTo(1);
        assertThat(progress.partitionsLive()).isEqualTo(1);
        assertThat(progress.rowsPerSecond()).isEqualTo(6.0);

        readAll(reader, 64, 1);
        assertThat(job.liveRows()).isEqualTo(4);
        reader.close();
        assertThat(job.historyRows()).isEqualTo(6);
        assertThat(job.liveRows()).isEqualTo(4);
    }

    private static List<Long> range(long from, long to) {
        List<Long> values = new ArrayList<>();
        for (long value = from; value < to; value++) {
            values.add(value);
        }
        return values;
    }
}
