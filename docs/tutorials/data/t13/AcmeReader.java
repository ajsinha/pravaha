/*
 * Tutorial 13 (docs/tutorials/13-writing-a-connector.md): reads one symbol's ticks.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
 */
package com.example.acme;

import java.time.Instant;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * One symbol's ticks, from {@code next} to {@code last}. Non-blocking: when there is nothing more it
 * answers 0 at once and the engine decides how to wait. A real feed polls its client here.
 */
final class AcmeReader implements PartitionReader {

    private final String symbol;
    private final int symbolIndex;
    private long next;
    private final long last;
    private final Instant start;
    private final long stepSeconds;
    private volatile boolean paused;

    AcmeReader(String symbol, int symbolIndex, long next, long last, Instant start, long stepSeconds) {
        this.symbol = symbol;
        this.symbolIndex = symbolIndex;
        this.next = next;
        this.last = last;
        this.start = start;
        this.stepSeconds = stepSeconds;
    }

    @Override
    public int poll(RecordSink sink, int maxRecords) {
        if (paused) {
            return 0;                                 // flow control: the engine asked for nothing
        }
        int produced = 0;
        while (produced < maxRecords && next <= last) {
            Instant at = start.plusSeconds((next - 1) * stepSeconds);
            long nanos = at.getEpochSecond() * 1_000_000_000L + at.getNano();
            RowWriter row = sink.beginRow();          // a cell in the lane's inbox
            row.setString(0, symbol);
            row.setLong(1, next);
            row.setLong(2, price(next));
            row.setLong(3, nanos);                    // a TIMESTAMP is nanoseconds since 1970
            row.weight(1L)                            // +1: an insert
                    .eventTimestampNanos(nanos)       // the row's event time, for windows
                    .sequence(next)
                    .commit();
            next++;
            produced++;
        }
        return produced;                              // 0 means "not now", never "never"
    }

    /** A made-up but repeatable price: the same tick always has the same price. */
    private long price(long seq) {
        return 100 + symbolIndex * 50 + (seq * 7 + symbolIndex * 3) % 11;
    }

    @Override
    public SourceOffset position() {
        return new SourceOffset(Long.toString(next)); // where a restart resumes: the next tick
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
    public void close() {}
}
