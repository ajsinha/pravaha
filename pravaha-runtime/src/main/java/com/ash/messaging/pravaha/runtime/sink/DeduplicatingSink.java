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
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;

/**
 * Effectively-once output for a sink that cannot be transactional.
 *
 * <p>Recovery replays. A source resumes from the last checkpoint and re-delivers everything after
 * it, so any row the engine wrote between the checkpoint and the crash is written again -- and a
 * sink with neither transactions nor idempotent upsert has no way to tell that second write from a
 * new one. The result is duplicates that nobody sees until a reconciliation fails weeks later.
 *
 * <p>This closes that window without asking the sink for anything: it remembers the highest sequence
 * number it has written, stores that in the checkpoint alongside the source offsets, and drops rows
 * at or below it after a restore. Everything before the mark has already reached the sink; anything
 * after it has not.
 *
 * <p><strong>It needs sequences that only ever increase, and it checks.</strong> The engine stamps
 * each row with the sequence its source assigned, and the whole mechanism reduces to a comparison
 * against a single number -- which is correct exactly when a later row never carries a smaller
 * sequence than an earlier one. A source that reuses or reorders sequences would have this drop
 * live rows, so a decrease outside a restore raises rather than silently discarding data. Detecting
 * it here, once, is far cheaper than the alternative: a query that quietly writes less than it
 * computed.
 *
 * <p>What this does not do is make an <em>arbitrary</em> sink exactly-once. A row written and then
 * lost by the sink itself is gone; the mark says it was written. That is the honest boundary of
 * dedup-on-the-writer-side, and a sink that needs more has to be transactional.
 */
public final class DeduplicatingSink implements StreamSinkPlugin {

    /** No row has been written. Below every real sequence, including zero. */
    public static final long NOTHING_WRITTEN = Long.MIN_VALUE;

    private final StreamSinkPlugin delegate;

    private long highWaterMark = NOTHING_WRITTEN;
    private long duplicatesDropped;
    private long rowsWritten;

    public DeduplicatingSink(StreamSinkPlugin delegate) {
        this.delegate = delegate;
    }

    /**
     * Writes the rows this sink has not already seen.
     *
     * @return how many rows the underlying sink accepted, which is what the engine reports -- a
     *     dropped duplicate is not an output row and counting it as one would make the metrics say
     *     recovery produced work it did not
     */
    @Override
    public int write(List<RowView> batch) {
        List<RowView> fresh = new ArrayList<>(batch.size());
        for (RowView row : batch) {
            long sequence = row.sequence();
            if (sequence <= highWaterMark) {
                duplicatesDropped++;
                continue;
            }
            fresh.add(row);
        }
        if (fresh.isEmpty()) {
            return 0;
        }

        // The mark advances only after the sink has taken the rows. Advancing first and then
        // failing would leave the mark claiming rows were written that were not, and the next
        // restore would skip them for good.
        int written = delegate.write(fresh);
        long highest = highWaterMark;
        for (RowView row : fresh) {
            if (row.sequence() < highest) {
                throw new PravahaException(
                        RuntimeErrors.LANE_FAILED,
                        "sequence " + row.sequence() + " arrived after " + highest + " on the same sink. "
                                + "Deduplication assumes sequences only increase; out of order, it would drop "
                                + "live rows rather than duplicates. The source is reusing or reordering "
                                + "sequence numbers.");
            }
            highest = Math.max(highest, row.sequence());
        }
        highWaterMark = highest;
        rowsWritten += written;
        return written;
    }

    /** The mark to store in the checkpoint. */
    public long highWaterMark() {
        return highWaterMark;
    }

    /**
     * Restores the mark from a checkpoint.
     *
     * <p>Only ever backwards or level in practice -- a restore is a return to a past state -- but
     * moving it forwards is refused rather than accepted, because a mark ahead of what was actually
     * written skips real rows, and it does so silently.
     */
    public void restoreTo(long mark) {
        if (mark > highWaterMark && highWaterMark != NOTHING_WRITTEN) {
            throw new PravahaException(
                    RuntimeErrors.LANE_FAILED,
                    "cannot restore this sink to " + mark + ": it has only written up to " + highWaterMark
                            + ", and a mark ahead of that would skip rows that never reached the sink");
        }
        this.highWaterMark = mark;
    }

    /** Rows dropped because the sink had already seen them. Zero except after a restore. */
    public long duplicatesDropped() {
        return duplicatesDropped;
    }

    /** Rows the underlying sink accepted. */
    public long rowsWritten() {
        return rowsWritten;
    }

    /**
     * The delegate's capabilities, with the guarantee it can now honour.
     *
     * <p>Reported rather than assumed: with the duplicate window closed, a sink that could only
     * offer at-least-once delivers effectively-once, and the negotiation at registration should see
     * that. A transactional sink is left exactly as it declared itself -- wrapping it changes
     * nothing it did not already do.
     */
    @Override
    public SinkCapabilities capabilities() {
        SinkCapabilities declared = delegate.capabilities();
        if (declared.transactional() || declared.idempotentUpsert()) {
            return declared;
        }
        return new SinkCapabilities(declared.emitModes(), false, true, declared.maxBatchRows());
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public void beginTransaction(long checkpointId) {
        delegate.beginTransaction(checkpointId);
    }

    @Override
    public String prepare(long checkpointId) {
        return delegate.prepare(checkpointId);
    }

    @Override
    public void commit(String handle) {
        delegate.commit(handle);
    }

    @Override
    public void abort(String handle) {
        delegate.abort(handle);
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public com.ash.messaging.pravaha.api.plugin.Version version() {
        return delegate.version();
    }

    @Override
    public com.ash.messaging.pravaha.api.plugin.HealthStatus health() {
        return delegate.health();
    }

    @Override
    public void configure(com.ash.messaging.pravaha.api.plugin.PluginContext context) {
        delegate.configure(context);
    }

    @Override
    public void open() {
        delegate.open();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
