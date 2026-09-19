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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * One backfill, as an operator controls it and as a console watches it (design section 16.2).
 *
 * <p>The readers are created deep inside the ingest path, one per partition, and the controls an
 * operator needs -- the rate, pause, resume, how far it has got -- belong to the <em>job</em> rather
 * than to any one of them. So each {@link OffsetSplicedReader} joins this as it is created and
 * leaves as it closes, and this is the handle everything above the ingest path holds.
 *
 * <p>The rate is a {@link BackfillThrottle} with its ceiling from {@code backfill.rate.limit}. An
 * operator who sets a rate pins it, which is permanent by that class's design and right here: a
 * number chosen during an incident must not be quietly undone. Unset means unlimited, which is the
 * right default for a source nothing else is reading and the wrong one for a production cluster --
 * so a deployment says.
 */
public final class BackfillJob {

    /** What a backfill has done and how fast, for a progress bar and an alert. */
    public record Progress(
            long historyRows,
            long liveRows,
            double rowsPerSecond,
            int partitions,
            int partitionsLive,
            boolean historyComplete,
            long rateLimit,
            boolean paused) {}

    private final String name;
    private final List<OffsetSplicedReader> readers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final LongSupplier clock;

    private final BackfillThrottle throttle;
    private final boolean limited;

    /** Rows and time at the last sample, so a rate is over the interval rather than since the start. */
    private long sampledAtNanos;

    private long sampledRows;
    private double rowsPerSecond;

    /** Partitions that have finished their history, counted as they go rather than by scanning. */
    private int partitionsLive;

    /** Partitions this job has ever had readers for, so a closed one still counts as done. */
    private int partitions;

    private volatile long historyRowsClosed;
    private volatile long liveRowsClosed;

    /** An unthrottled backfill: as fast as the source will go. */
    public BackfillJob(String name) {
        this(name, 0, System::nanoTime);
    }

    /**
     * @param rateLimit the {@code backfill.rate.limit} ceiling in records a second, or zero for no
     *     limit
     */
    public BackfillJob(String name, long rateLimit) {
        this(name, rateLimit, System::nanoTime);
    }

    BackfillJob(String name, long rateLimit, LongSupplier clock) {
        this.name = name;
        this.clock = clock;
        this.limited = rateLimit > 0;
        // The floor is one row a second: a throttle that can reach zero is a backfill that never
        // finishes, which is worse than one that is visibly too slow. The latency target is out of
        // reach because nothing here probes the store's own latency -- adaptive throttling
        // (section 16.2's `backfill.adaptive`) is refused by name rather than pretended.
        this.throttle = new BackfillThrottle(limited ? rateLimit : Long.MAX_VALUE / 4, 1, Long.MAX_VALUE);
        this.sampledAtNanos = clock.getAsLong();
    }

    public String name() {
        return name;
    }

    /** Records a second the readers may take, or zero when nothing is limiting them. */
    public long rowsPerSecond() {
        return limited || throttle.isPinned() ? throttle.rowsPerSecond() : 0;
    }

    /**
     * Sets the rate an operator has chosen. Pinned, and so permanent: see {@link
     * BackfillThrottle#pin}.
     *
     * @throws IllegalArgumentException above the configured ceiling, which is a ceiling and not a
     *     suggestion
     */
    public void throttleTo(long rowsPerSecond) {
        throttle.pin(rowsPerSecond);
    }

    /** The configured ceiling, or zero when there is none. */
    public long rateLimit() {
        return limited ? throttle.rowsPerSecond() : 0;
    }

    /** Stops reading, history or live, without giving up what has been read. */
    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
    }

    public boolean isPaused() {
        return paused.get();
    }

    /** True when every partition has reached the live stream: the seam is behind all of them. */
    public boolean historyComplete() {
        return partitions > 0 && partitionsLive >= partitions;
    }

    public synchronized Progress progress() {
        long history = historyRows();
        long now = clock.getAsLong();
        long elapsed = now - sampledAtNanos;
        // A tenth of a second, so a caller sampling twice in quick succession does not divide by
        // nearly nothing and report a rate of millions.
        if (elapsed > 100_000_000L) {
            rowsPerSecond = (history - sampledRows) * 1_000_000_000.0 / elapsed;
            sampledAtNanos = now;
            sampledRows = history;
        }
        return new Progress(
                history,
                liveRows(),
                rowsPerSecond,
                partitions,
                partitionsLive,
                historyComplete(),
                rateLimit(),
                isPaused());
    }

    public long historyRows() {
        long total = historyRowsClosed;
        for (OffsetSplicedReader reader : readers) {
            total += reader.historyRows();
        }
        return total;
    }

    public long liveRows() {
        long total = liveRowsClosed;
        for (OffsetSplicedReader reader : readers) {
            total += reader.liveRows();
        }
        return total;
    }

    synchronized void joined(OffsetSplicedReader reader) {
        readers.add(reader);
        partitions++;
        if (reader.phase() == BackfillPhase.LIVE) {
            partitionsLive++;
        }
    }

    synchronized void partitionReachedLive() {
        partitionsLive++;
    }

    synchronized void left(OffsetSplicedReader reader) {
        if (readers.remove(reader)) {
            // Kept, so closing a reader does not make the progress bar go backwards.
            historyRowsClosed += reader.historyRows();
            liveRowsClosed += reader.liveRows();
        }
    }

    @Override
    public String toString() {
        return "BackfillJob[" + name + ", " + historyRows() + " history rows, " + partitionsLive + " of " + partitions
                + " partitions live" + (isPaused() ? ", paused" : "") + "]";
    }
}
