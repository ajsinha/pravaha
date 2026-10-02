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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.LongSupplier;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * Reads a bounded range of a source's history and then joins the live stream at an exact offset.
 *
 * <p>Design section 16.1's seam, for a source whose positions are offsets into an ordered log --
 * which is the seam this engine actually has. {@link SplicedReader} joins a <em>table snapshot</em>
 * to a change feed and deduplicates by key and version, because a snapshot has no position in the
 * feed; here the history and the present are the same log read twice, so the seam is a position and
 * no deduplication is needed or wanted.
 *
 * <p><strong>Why the seam is exact.</strong> The splice offset {@code S} is a position the live
 * version of the query has already read to: a token the plugin itself handed out, naming a record.
 * The history reader is created at the beginning and is polled <em>one record at a time</em> until
 * its position is exactly {@code S}; the live reader is then created at {@code S}, and a reader
 * created at a position delivers the records <em>after</em> it -- the same contract a restore from a
 * checkpoint relies on, which is the one contract every source plugin here already keeps. So every
 * record before and including {@code S} arrives once, from the history reader, and every record
 * after it arrives once, from the live reader. One record either side of the seam is the whole risk,
 * and it is why the history is polled one record at a time rather than in batches: a batch that
 * straddles {@code S} could not be stopped in the middle of itself.
 *
 * <p><strong>What happens if the seam is never reached.</strong> Nothing silent. A history reader
 * that runs out of records without its position ever equalling {@code S} is a source whose tokens do
 * not name records the way this assumes, and reading past the seam would deliver the overlap twice.
 * After {@code graceNanos} of empty polls the reader fails with {@link BackfillErrors#SPLICE_MISSED}
 * and the backfill with it.
 *
 * <p><strong>No seam at all</strong> is the other shape: a stream the new version reads and the old
 * one does not has no position to meet at, so the history reader keeps going and simply
 * <em>becomes</em> the live reader at its first empty poll -- one reader, no seam, nothing to get
 * wrong. The throttle lifts at the same moment, because from there on this is ordinary ingest.
 *
 * <p>The throttle governs history and nothing else. Throttling the live phase would hold a query
 * behind the present to protect a store from the past, which is backwards: the live feed is load the
 * store is already carrying.
 */
public final class OffsetSplicedReader implements PartitionReader {

    /** Opens a reader of this one partition at a position. */
    @FunctionalInterface
    public interface Partition {
        PartitionReader openAt(SourceOffset from);
    }

    /** The prefix that marks a position taken while the history was still being read. */
    private static final String TOKEN_PREFIX = "pravaha-backfill:1:";

    private final Partition partition;

    /** The seam, or null when this stream has none and one reader covers both phases. */
    private final SourceOffset splice;

    private final BackfillJob job;
    private final LongSupplier clock;
    private final long graceNanos;

    private PartitionReader history;
    private PartitionReader live;
    private BackfillPhase phase;

    private long historyRows;
    private long liveRows;
    private double tokens;
    private long lastRefillNanos;
    private long firstEmptyPollNanos = Long.MIN_VALUE;
    private boolean paused;

    /**
     * @param partition opens a reader of this partition at any position it handed out
     * @param historyFrom where the history starts: the beginning, or a checkpointed position part
     *     way through it
     * @param splice the position the live reader is created at, or null for a stream with no seam
     * @param job the control and progress this backfill reports to and is throttled by
     */
    public OffsetSplicedReader(
            Partition partition, SourceOffset historyFrom, SourceOffset splice, BackfillJob job, boolean historyDone) {
        this(partition, historyFrom, splice, job, historyDone, System::nanoTime, DEFAULT_GRACE_NANOS);
    }

    /** Thirty seconds of empty polls before a seam that has not arrived is called unreachable. */
    public static final long DEFAULT_GRACE_NANOS = 30_000_000_000L;

    OffsetSplicedReader(
            Partition partition,
            SourceOffset historyFrom,
            SourceOffset splice,
            BackfillJob job,
            boolean historyDone,
            LongSupplier clock,
            long graceNanos) {
        this.partition = partition;
        this.splice = splice;
        this.job = job;
        this.clock = clock;
        this.graceNanos = graceNanos;
        this.lastRefillNanos = clock.getAsLong();
        if (historyDone) {
            // Resumed after the seam: there is no history left to read, and the live reader is
            // created where the checkpoint said this partition had got to.
            this.phase = BackfillPhase.LIVE;
            this.live = partition.openAt(historyFrom);
        } else if (splice != null && splice.equals(historyFrom)) {
            // The old version has read nothing this one has not, so there is no history to read.
            this.phase = BackfillPhase.LIVE;
            this.live = partition.openAt(splice);
        } else {
            this.phase = BackfillPhase.SNAPSHOT;
            this.history = partition.openAt(historyFrom);
        }
        job.joined(this);
    }

    /** Whether this partition is reading history, or is on the live stream. */
    public BackfillPhase phase() {
        return phase;
    }

    /** Records read from history. What a progress bar counts. */
    public long historyRows() {
        return historyRows;
    }

    /** Records read from the live stream since the seam. */
    public long liveRows() {
        return liveRows;
    }

    @Override
    public int poll(PartitionReader.RecordSink sink, int maxRecords) {
        if (paused || job.isPaused()) {
            return 0;
        }
        if (phase == BackfillPhase.LIVE) {
            int moved = live.poll(sink, maxRecords);
            liveRows += moved;
            return moved;
        }
        return pollHistory(sink, maxRecords);
    }

    /**
     * Reads history one record at a time, up to what the throttle allows, stopping on the seam.
     *
     * <p>One at a time is the price of an exact seam, and it is a poll call per record rather than a
     * round trip per record: a plugin buffers what it fetched and hands it over a record at a time,
     * which is what {@code maxRecords} has always meant.
     */
    private int pollHistory(PartitionReader.RecordSink sink, int maxRecords) {
        int budget = allowance(maxRecords);
        int moved = 0;
        int consumedThisPoll = 0;
        Counting counting = new Counting(sink);
        while (consumedThisPoll < budget) {
            counting.rejected = 0;
            int read = history.poll(counting, 1);
            // A record the reader set aside on the dead-letter queue is a record read: the reader's
            // position has moved past it (PartitionReader#poll counts it against maxRecords). Before
            // REPL-2 only delivered rows counted, so a poll whose one record was rejected looked like
            // the end of the history -- and when that record was the one the running version had
            // stopped on, the seam was never seen and the backfill failed with PRV-4013.
            int consumed = read + counting.rejected;
            if (consumed == 0) {
                atEndOfWhatThereIs();
                break;
            }
            consumedThisPoll += consumed;
            moved += read;
            historyRows += read;
            if (tokens > 0) {
                tokens -= consumed;
            }
            firstEmptyPollNanos = Long.MIN_VALUE;
            if (splice != null && splice.equals(history.position())) {
                spliceNow();
                break;
            }
        }
        return moved;
    }

    /**
     * The engine's sink, counting what the history reader rejected so a poll that only rejected is
     * seen as progress. Everything else is the sink's own.
     */
    private static final class Counting implements PartitionReader.RecordSink {
        private final PartitionReader.RecordSink target;
        private int rejected;

        Counting(PartitionReader.RecordSink target) {
            this.target = target;
        }

        @Override
        public com.ash.messaging.pravaha.api.data.RowWriter beginRow() {
            return target.beginRow();
        }

        @Override
        public boolean reject(byte[] raw, String sourceOffset, String reason) {
            boolean taken = target.reject(raw, sourceOffset, reason);
            if (taken) {
                rejected++;
            }
            return taken;
        }

        @Override
        public boolean reject(byte[] raw, String sourceOffset, String reason, String code) {
            boolean taken = target.reject(raw, sourceOffset, reason, code);
            if (taken) {
                rejected++;
            }
            return taken;
        }
    }

    /**
     * The history reader had nothing: either this stream has no seam and the present has been
     * reached, or the seam has not arrived and may never.
     */
    private void atEndOfWhatThereIs() {
        if (splice == null) {
            spliceNow();
            return;
        }
        long now = clock.getAsLong();
        if (firstEmptyPollNanos == Long.MIN_VALUE) {
            firstEmptyPollNanos = now;
            return;
        }
        if (now - firstEmptyPollNanos > graceNanos) {
            throw new PravahaException(
                    BackfillErrors.SPLICE_MISSED,
                    "the backfill read all the history this source has and never reached the position the running "
                            + "version is at (" + splice.token() + "). A source whose positions do not name the "
                            + "record they were taken after cannot be spliced at an offset: reading past the seam "
                            + "would deliver the overlap twice. The backfill is stopped rather than doubling it.");
        }
    }

    /** Hands over to the live stream: the same reader when there is no seam, a new one at it. */
    private void spliceNow() {
        if (splice == null) {
            live = history;
        } else {
            live = partition.openAt(splice);
            history.close();
        }
        history = null;
        phase = BackfillPhase.LIVE;
        job.partitionReachedLive();
    }

    /** How many records the throttle will allow this poll. Zero means wait. */
    private int allowance(int maxRecords) {
        long rate = job.rowsPerSecond();
        if (rate <= 0) {
            return maxRecords;
        }
        long now = clock.getAsLong();
        tokens += (now - lastRefillNanos) / 1_000_000_000.0 * rate;
        lastRefillNanos = now;
        // At most a second of burst: a backfill that was idle for a minute must not then read a
        // minute's ration in one poll, which is exactly the spike the rate limit exists to prevent.
        tokens = Math.max(0, Math.min(tokens, (double) rate));
        return (int) Math.max(0, Math.min(maxRecords, Math.floor(tokens)));
    }

    /**
     * Where this partition is, in a form that resumes in the same phase.
     *
     * <p>A position taken while history was being read carries the seam with it, because a restart
     * has to resume the same backfill rather than start a different one -- and the seam is not
     * recoverable afterwards: the running version has moved on. Once the seam is behind us the token
     * is the plugin's own, unchanged, so a checkpoint taken after the cutover is readable by a plain
     * registration that knows nothing about backfills.
     */
    @Override
    public SourceOffset position() {
        if (phase == BackfillPhase.LIVE) {
            return live.position();
        }
        return new SourceOffset(encode(splice, history.position()));
    }

    /** True when {@code token} was taken while a backfill was still reading history. */
    public static boolean isBackfillToken(String token) {
        return token != null && token.startsWith(TOKEN_PREFIX);
    }

    /** {@code pravaha-backfill:1:<seam>:<history>}, each base64 so a token may hold anything. */
    public static String encode(SourceOffset splice, SourceOffset history) {
        return TOKEN_PREFIX + base64(splice == null ? null : splice.token()) + ":" + base64(history.token());
    }

    /** The seam a backfill token carries, or null when the stream it names has none. */
    public static SourceOffset spliceOf(String token) {
        String encoded = field(token, 0);
        return encoded == null ? null : new SourceOffset(encoded);
    }

    /** How far through the history a backfill token had got. */
    public static SourceOffset historyOf(String token) {
        String encoded = field(token, 1);
        return encoded == null ? SourceOffset.BEGINNING : new SourceOffset(encoded);
    }

    private static String base64(String value) {
        return value == null ? "-" : Base64.getUrlEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String field(String token, int index) {
        if (!isBackfillToken(token)) {
            throw new IllegalArgumentException("'" + token + "' is not a backfill position");
        }
        String[] parts = token.substring(TOKEN_PREFIX.length()).split(":", -1);
        if (parts.length != 2) {
            throw new PravahaException(
                    BackfillErrors.SPLICE_MISSED,
                    "a checkpointed backfill position is unreadable: '" + token + "'. It records the seam the "
                            + "backfill was heading for, and without it the backfill cannot be resumed without "
                            + "either losing the records before the seam or reading them twice.");
        }
        String part = parts[index];
        return "-".equals(part) ? null : new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }

    @Override
    public void pause() {
        paused = true;
        if (history != null) {
            history.pause();
        }
        if (live != null) {
            live.pause();
        }
    }

    @Override
    public void resume() {
        paused = false;
        if (history != null) {
            history.resume();
        }
        if (live != null) {
            live.resume();
        }
    }

    @Override
    public void checkpointed(SourceOffset offset) {
        // Only the live reader is told: a source that prunes what a consumer has confirmed -- a
        // replication slot, a queue -- must not be told that history it is still being read from is
        // finished with, and the token it would be handed is this class's rather than its own.
        if (phase == BackfillPhase.LIVE && live != null && !isBackfillToken(offset.token())) {
            live.checkpointed(offset);
        }
    }

    @Override
    public void close() {
        job.left(this);
        if (history != null) {
            history.close();
        }
        if (live != null) {
            live.close();
        }
    }
}
