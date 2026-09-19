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
package com.ash.messaging.pravaha.registry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.backfill.BackfillPlan;
import com.ash.messaging.pravaha.backfill.OffsetSplicedReader;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;

/**
 * A source that behaves the way every replayable one here does, with none of the plumbing.
 *
 * <p>An append-only list of rows; a reader created at {@code n=k} delivers the rows after it, and
 * its position after a row names that row. That is the entire contract a blue/green replacement
 * needs of a source -- it is what a file, a Kafka topic and a Delta table all provide -- and having
 * it in a few dozen lines is what lets the registry's cutover be tested without a plugin, a
 * container or a clock.
 */
final class ReplayableLog implements SourceFeedFactory {

    private final StreamSchema schema;
    private final List<Object[]> rows = new CopyOnWriteArrayList<>();
    private final AtomicInteger readers = new AtomicInteger();

    ReplayableLog(StreamSchema schema) {
        this.schema = schema;
    }

    void append(Object... row) {
        rows.add(row);
    }

    int size() {
        return rows.size();
    }

    /** Readers ever created, so a test can see that a backfill opened its own rather than sharing. */
    int readersOpened() {
        return readers.get();
    }

    @Override
    public SourceFeed open(
            String queryName,
            QueryExecution execution,
            List<String> sourceStreams,
            Runnable afterDelivery,
            Map<String, String> resumeFrom) {
        String token = resumeFrom.get("partition-0");
        SourceOffset from = token == null ? SourceOffset.BEGINNING : new SourceOffset(token);
        return pumping(queryName, execution, afterDelivery, openAt(from));
    }

    @Override
    public SourceFeed openBackfill(
            String queryName,
            QueryExecution execution,
            List<String> sourceStreams,
            Runnable afterDelivery,
            Map<String, String> resumeFrom,
            BackfillPlan plan) {
        String token = resumeFrom.get("partition-0");
        SourceOffset splice = plan.spliceFor(schema.name(), 0).orElse(null);
        SourceOffset from = SourceOffset.BEGINNING;
        boolean historyDone = false;
        if (token != null && OffsetSplicedReader.isBackfillToken(token)) {
            splice = OffsetSplicedReader.spliceOf(token);
            from = OffsetSplicedReader.historyOf(token);
        } else if (token != null) {
            from = new SourceOffset(token);
            historyDone = true;
        } else if (!plan.readHistory() && splice != null) {
            from = splice;
        }
        return pumping(
                queryName,
                execution,
                afterDelivery,
                new OffsetSplicedReader(this::openAt, from, splice, plan.job(), historyDone));
    }

    @Override
    public java.util.Optional<String> backfillRefusal(String stream) {
        return schema.name().equals(stream) ? java.util.Optional.empty() : java.util.Optional.of("no such stream");
    }

    private SourceFeed pumping(
            String queryName, QueryExecution execution, Runnable afterDelivery, PartitionReader reader) {
        IngestPump pump = execution.pumpInto(0, schema.name(), reader, BackpressurePolicy.defaults());
        return new Feed(queryName, pump, afterDelivery);
    }

    private PartitionReader openAt(SourceOffset from) {
        readers.incrementAndGet();
        return new Reader(
                from.isBeginning() ? -1 : Integer.parseInt(from.token().substring(2)));
    }

    /** One thread polling one pump, which is what every feed in this engine is. */
    private static final class Feed implements SourceFeed {
        private final IngestPump pump;
        private final Runnable afterDelivery;
        private final Thread thread;
        private volatile boolean paused;
        private volatile boolean closed;
        private volatile long fed;

        Feed(String queryName, IngestPump pump, Runnable afterDelivery) {
            this.pump = pump;
            this.afterDelivery = afterDelivery == null ? () -> {} : afterDelivery;
            this.thread = new Thread(this::run, "log-feed-" + queryName);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private void run() {
            while (!closed) {
                if (paused) {
                    sleep();
                    continue;
                }
                int moved = pump.pumpOnce(64);
                fed += moved;
                afterDelivery.run();
                if (moved == 0) {
                    sleep();
                }
            }
        }

        private void sleep() {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                closed = true;
            }
        }

        @Override
        public void pause() {
            paused = true;
        }

        @Override
        public void resume() {
            paused = false;
        }

        @Override
        public long rowsFed() {
            return fed;
        }

        @Override
        public String describe() {
            return "a replayable log" + (paused ? " (paused)" : "");
        }

        @Override
        public void close() {
            closed = true;
            try {
                thread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            pump.close();
        }
    }

    private final class Reader implements PartitionReader {
        private int delivered;

        Reader(int after) {
            this.delivered = after;
        }

        @Override
        public int poll(RecordSink sink, int maxRecords) {
            int emitted = 0;
            while (emitted < maxRecords && delivered + 1 < rows.size()) {
                Object[] row = rows.get(++delivered);
                com.ash.messaging.pravaha.api.data.RowWriter writer = sink.beginRow();
                for (int ordinal = 0; ordinal < row.length; ordinal++) {
                    switch (row[ordinal]) {
                        case String text -> writer.setString(ordinal, text);
                        case Long value -> writer.setLong(ordinal, value);
                        case Integer value -> writer.setInt(ordinal, value);
                        default -> throw new IllegalArgumentException("unsupported column " + row[ordinal]);
                    }
                }
                writer.weight(1)
                        .eventTimestampNanos(delivered * 1_000_000L)
                        .sequence(delivered)
                        .commit();
                emitted++;
            }
            return emitted;
        }

        @Override
        public SourceOffset position() {
            return delivered < 0 ? SourceOffset.BEGINNING : new SourceOffset("n=" + delivered);
        }

        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public void close() {}
    }
}
