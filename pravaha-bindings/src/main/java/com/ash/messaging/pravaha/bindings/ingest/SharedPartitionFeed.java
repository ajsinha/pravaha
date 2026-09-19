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
package com.ash.messaging.pravaha.bindings.ingest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;

/**
 * One reader of one partition, feeding every query bound to it.
 *
 * <p><strong>SRC-3.</strong> Before this, N continuous queries over one Aerospike set were N
 * readers and N scans of that set. The sharing that existed was {@code QueryFingerprint}'s, which
 * shares one <em>computation</em> across identical normalised SQL -- real, and the wrong seam for
 * this, because a deployment with a thousand continuous queries has a thousand different questions
 * and not a thousand copies of one. The seam that matches the load is the binding: one reader per
 * (binding, stream, partition), many computations.
 *
 * <p><strong>What is shared and what is not.</strong> The reader, its plugin, its connection and the
 * thread that drives it are shared. Nothing below them is: each query keeps its own lane, arena,
 * inbox, operator state and checkpoint, and each row is written separately into each lane's own
 * inbox cell by {@link BroadcastSink}. Thread confinement is therefore narrowed rather than
 * weakened -- an inbox still has exactly one producer thread, and there are now fewer producer
 * threads than lanes rather than one each.
 *
 * <h2>Offsets, which is the hard half</h2>
 *
 * <p>A shared reader has one position and its consumers sit at several. Three cases, and only the
 * third needed anything built:
 *
 * <ul>
 *   <li>The <strong>first</strong> consumer creates the reader at its own offset, so they agree by
 *       construction.
 *   <li>A consumer joining at the position the reader already holds simply attaches.
 *   <li>A consumer joining <em>behind</em> the reader -- which is every fresh registration against a
 *       group that has been running, and the case the finding is actually about -- is attached to
 *       the live fan-out <strong>first</strong> and then given a private <em>catch-up</em> reader
 *       for the history it missed, polled on this same thread into that one lane. Attaching first is
 *       what makes it lossless: the gap the catch-up has to cover stops moving the moment the
 *       consumer is attached. Doing it the other way round -- catch up, then attach -- leaves the
 *       records that arrived during the catch-up belonging to neither reader, which is silent loss.
 * </ul>
 *
 * <p>The cost is duplication at the handover: records between the position the reader held when the
 * consumer attached and the point its catch-up scan actually covers arrive twice, for that one
 * consumer. <strong>That is why this is offered only to sources that declare
 * {@code AT_LEAST_ONCE}</strong> -- see {@link SharedSourceGroup#canShare}. A source promising
 * exactly-once keeps a private reader per query, exactly as before, because there is no way to hand
 * a consumer over between two readers without either duplicating the overlap or losing it, and
 * losing it is not on the table. Recording that limit is part of the fix, not a gap in it.
 *
 * <p><strong>A catch-up ends at the first poll that yields nothing.</strong> For a scan source that
 * is exact: the first poll of a reader created at an offset runs one whole scan from it, which is
 * all of the history by construction. A source that answered its first poll with nothing while a
 * fetch was still in flight would end its catch-up early, and that is the second reason the
 * at-least-once gate is where it is rather than wider.
 *
 * <h2>What sharing couples</h2>
 *
 * <p>One reader for many lanes means the slowest lane sets the pace: the poll is sized to the
 * smallest free space among the consumers, so a query whose inbox is full stalls the group rather
 * than losing its rows. That is the honest trade and it is the same trade any shared consumer group
 * makes. Sizing to the largest and dropping for the rest would make the numbers better and the
 * answers wrong.
 */
final class SharedPartitionFeed {

    /** Rows per poll. Same reasoning as {@code PumpingFeed.BATCH}, clamped by the smallest inbox. */
    private static final int BATCH = 1024;

    private static final long IDLE_NAP_NANOS = 1_000_000L;

    private static final long PUBLISH_INTERVAL_NANOS = 20_000_000L;

    /**
     * How long to wait for a consumer's pump between rows before giving up on this round.
     *
     * <p>Timed, and that is not a tuning choice. A checkpoint freezes one query's pumps in its own
     * order and this thread freezes many queries' pumps in another, so the two orders can cross. A
     * pair of timed acquisitions cannot deadlock: whichever loses releases what it holds and comes
     * back a millisecond later. An untimed one here would be a lock-ordering rule nobody could
     * enforce across two packages.
     */
    private static final Duration FREEZE_TIMEOUT = Duration.ofMillis(20);

    private final String stream;
    private final SourcePartition partition;
    private final StreamSourcePlugin plugin;

    /**
     * Guards the reader, the membership and every poll.
     *
     * <p>Held for the length of a poll, so a query joining or being dropped waits for the read in
     * flight. That wait is deliberate: the alternative is a lane being closed while this thread is
     * part way through writing a row into it, and a bounded wait that gave up would be that same
     * race with a timer on it.
     */
    private final ReentrantLock lock = new ReentrantLock();

    private final List<Member> members = new ArrayList<>();

    private PartitionReader reader;

    /** What the reader was created with, so a joiner that needs less can be spotted. */
    private ReadRequest request = ReadRequest.NOTHING;

    /** Whether the reader has already been weakened to push nothing down. See {@link #join}. */
    private boolean unfiltered;

    private Thread thread;
    private volatile boolean closed;
    private volatile PravahaException failure;
    private long lastPublishedNanos;

    /** Rows this reader has handed to its consumers, counted once however many received them. */
    private final AtomicLong rowsRead = new AtomicLong();

    SharedPartitionFeed(String stream, SourcePartition partition, StreamSourcePlugin plugin) {
        this.stream = stream;
        this.partition = partition;
        this.plugin = plugin;
    }

    /**
     * Attaches one query's lane to this reader.
     *
     * @param from where this query needs to start: {@code BEGINNING} for a fresh registration, the
     *     checkpoint's token for a restored one
     * @param wanted what this query would have pushed down had it opened its own reader
     * @param pumpFactory builds the query's pump around the reader handed to it; the caller owns
     *     what a pump needs -- the lane, the schema, the dead-letter queue -- and this does not
     */
    Member join(
            String queryName,
            SourceOffset from,
            ReadRequest wanted,
            Runnable afterDelivery,
            Function<PartitionReader, IngestPump> pumpFactory) {
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("this shared reader has been closed");
            }
            Member member = new Member(queryName, wanted, afterDelivery);
            member.feed = this;
            SourceOffset catchUpFrom = null;
            if (reader == null) {
                // The first consumer decides where the reader starts and what it pushes down. A
                // group of one therefore keeps every optimisation a private reader had.
                reader = plugin.createReader(partition, from, wanted);
                request = wanted;
                unfiltered = ReadRequest.NOTHING.equals(wanted);
            } else {
                if (!unfiltered && !request.equals(wanted)) {
                    // Two questions, two WHERE clauses, one reader. The reader must return every
                    // row either of them could want, so it is rebuilt to push nothing down and the
                    // engine's own filters -- which it always kept regardless -- do the work.
                    //
                    // Once per group, ever: after this the reader is already the weakest it can be.
                    // Rejected: intersecting the two filter lists, which is also correct and is a
                    // second thing to get wrong for a benefit that disappears at the third distinct
                    // query. Rejected: refusing to share when the filters differ, which would share
                    // nothing in the only case the finding is about.
                    PartitionReader weaker = plugin.createReader(partition, reader.position(), ReadRequest.NOTHING);
                    closeQuietly(reader);
                    reader = weaker;
                    request = ReadRequest.NOTHING;
                    unfiltered = true;
                }
                SourceOffset here = reader.position();
                if (!here.equals(from)) {
                    catchUpFrom = from;
                }
            }
            member.pump = pumpFactory.apply(new MemberReader(member));
            if (catchUpFrom != null) {
                startCatchUp(member, catchUpFrom);
            }
            members.add(member);
            if (thread == null) {
                thread = Thread.ofVirtual()
                        .name("pravaha-shared-feed-" + stream + "#" + partition.index())
                        .unstarted(this::run);
                thread.start();
            }
            return member;
        } finally {
            lock.unlock();
        }
    }

    /** How many queries this reader feeds, for an operator asking what a source is doing. */
    int memberCount() {
        lock.lock();
        try {
            return members.size();
        } finally {
            lock.unlock();
        }
    }

    long rowsRead() {
        return rowsRead.get();
    }

    PravahaException failure() {
        return failure;
    }

    /**
     * Takes one query out of the group.
     *
     * <p>Under the lock, so it cannot return while a poll or a publish is part way through writing
     * into this query's lane. That wait is the point: what follows it is the query closing the lane,
     * and a bounded wait that gave up would be that race with a timer on it.
     *
     * <p>Leaving the last member does <em>not</em> stop the thread. The group is reference counted
     * one level up and closing is its decision, which keeps "no members" and "closed" from being the
     * same state -- a reader marked closed while its group is still registered would refuse the next
     * query to bind the same source. The thread with nobody in it polls nothing and naps.
     */
    private void leave(Member member) {
        lock.lock();
        try {
            members.remove(member);
            closeQuietly(member.catchUp);
            member.catchUp = null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stops the thread and closes the reader. Called by the group when nobody holds it any more, and
     * on the path where a registration failed part way through joining -- the one case that can
     * leave a reader created with no member holding it.
     */
    void closeReader() {
        Thread stopping;
        lock.lock();
        try {
            closed = true;
            stopping = thread;
            thread = null;
        } finally {
            lock.unlock();
        }
        if (stopping != null) {
            LockSupport.unpark(stopping);
            try {
                stopping.join(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lock.lock();
        try {
            closeQuietly(reader);
            reader = null;
        } finally {
            lock.unlock();
        }
    }

    private void pause(Member member) {
        lock.lock();
        try {
            if (member.paused) {
                return;
            }
            // Where this consumer is, which is where the reader is unless it was still catching up.
            // Resuming from the older of the two is a re-read; resuming from the newer would skip
            // whatever the catch-up had not reached.
            member.resumeAt = member.catchUp != null ? member.catchUp.position() : position();
            member.paused = true;
        } finally {
            lock.unlock();
        }
    }

    private void resume(Member member) {
        lock.lock();
        try {
            if (!member.paused) {
                return;
            }
            member.paused = false;
            SourceOffset resumeAt = member.resumeAt;
            member.resumeAt = null;
            if (resumeAt == null || resumeAt.equals(position())) {
                // Nothing moved while it was paused -- the common case, because a group of one
                // stops reading entirely when its only consumer pauses. Identical to what a private
                // reader did: carry on from where it left off.
                return;
            }
            startCatchUp(member, resumeAt);
        } finally {
            lock.unlock();
        }
    }

    /** Called with the lock held. */
    private void startCatchUp(Member member, SourceOffset from) {
        closeQuietly(member.catchUp);
        member.catchUp = plugin.createReader(partition, from, member.request);
        member.catchUpPolled = false;
    }

    /** Called with the lock held. */
    private SourceOffset position() {
        return reader == null ? SourceOffset.BEGINNING : reader.position();
    }

    private void run() {
        while (!closed) {
            int moved;
            try {
                moved = pollOnce();
            } catch (PravahaException e) {
                // Recorded and not retried, for the reason PumpingFeed records rather than retries:
                // a source that fails mid-read fails for a reason and spinning on it produces a log
                // line a millisecond and no progress. It stops every query in this group rather
                // than one, which is the cost of sharing and is why describe() names the group.
                failure = e;
                return;
            } catch (Throwable e) {
                failure = new PravahaException(
                        IngestErrors.FEED_FAILED, "the shared source feed for '" + stream + "' stopped: " + e, e);
                return;
            }
            if (moved == 0) {
                LockSupport.parkNanos(IDLE_NAP_NANOS);
            }
            publishPeriodically();
        }
    }

    private int pollOnce() {
        lock.lock();
        try {
            if (closed || members.isEmpty() || reader == null) {
                return 0;
            }
            int moved = pollCatchUps();

            // The live set: everything not paused. A consumer still catching up is in it, because
            // it was attached before its catch-up started and the whole point was that the gap it
            // has to cover stops growing at that moment.
            List<Member> live = new ArrayList<>(members.size());
            int room = BATCH;
            for (Member member : members) {
                if (member.paused) {
                    continue;
                }
                int free = member.pump.roomForSharedPoll();
                if (free <= 0) {
                    // A full inbox stalls the group. Polling anyway and dropping this consumer's
                    // copy would turn backpressure into silent loss for whichever query happened to
                    // be slowest, which is the failure backpressure exists to prevent.
                    return moved;
                }
                room = Math.min(room, free);
                live.add(member);
            }
            if (live.isEmpty() || room <= 0) {
                return moved;
            }
            moved += pollShared(live, room);
            return moved;
        } finally {
            lock.unlock();
        }
    }

    /** Called with the lock held. */
    private int pollShared(List<Member> live, int room) {
        int frozen = 0;
        for (; frozen < live.size(); frozen++) {
            if (!live.get(frozen).pump.freezeIngest(FREEZE_TIMEOUT)) {
                break;
            }
        }
        if (frozen < live.size()) {
            // A checkpoint has one of these pumps. Give back what was taken and come round again --
            // see FREEZE_TIMEOUT for why this cannot be an untimed acquisition.
            thaw(live, frozen);
            return 0;
        }
        try {
            List<PartitionReader.RecordSink> sinks = new ArrayList<>(live.size());
            for (Member member : live) {
                sinks.add(member.pump.sharedSink());
            }
            int read = reader.poll(new BroadcastSink(sinks), room);
            if (read > 0) {
                rowsRead.addAndGet(read);
                for (Member member : live) {
                    member.pump.countSharedRows(read);
                }
            }
            return read;
        } finally {
            thaw(live, live.size());
        }
    }

    private static void thaw(List<Member> live, int count) {
        for (int i = count - 1; i >= 0; i--) {
            live.get(i).pump.thawIngest();
        }
    }

    /**
     * Drives the private readers of consumers that joined behind this one.
     *
     * <p>On this thread rather than on one of their own, and that is a correctness requirement
     * rather than a saving: a lane's inbox has a single producer, and a catch-up writing into a lane
     * this thread also fans out into would be a second one.
     *
     * <p>Called with the lock held.
     */
    private int pollCatchUps() {
        int moved = 0;
        for (Member member : members) {
            if (member.catchUp == null || member.paused) {
                continue;
            }
            if (member.pump.roomForSharedPoll() <= 0) {
                // Backpressured, not finished. Telling the two apart is the whole reason this asks
                // before polling: a full inbox answers zero, and treating that as "caught up" would
                // close the catch-up over the history it had not delivered yet.
                continue;
            }
            // Through freezeIngest rather than straight into pumpOnce, and it is a deadlock that
            // makes the difference rather than tidiness. A checkpoint takes a pump's ingest lock and
            // then reads its offset, which for a member of this group comes back through the feed's
            // lock -- and this thread is holding that lock while it asks for the ingest one.
            // pumpOnce's own acquisition is untimed, so the two would wait on each other for ever;
            // a timed one loses the round and comes back a millisecond later. The lock is reentrant,
            // so the pumpOnce inside takes it again for nothing.
            if (!member.pump.freezeIngest(FREEZE_TIMEOUT)) {
                continue;
            }
            int read;
            try {
                read = member.pump.pumpOnce(BATCH);
            } finally {
                member.pump.thawIngest();
            }
            moved += read;
            if (read == 0 && member.catchUpPolled) {
                closeQuietly(member.catchUp);
                member.catchUp = null;
            }
            member.catchUpPolled = true;
        }
        return moved;
    }

    /**
     * Publishes what every consumer has applied, on the same cadence one feed thread used to.
     *
     * <p>Under the lock, which is not an oversight: leaving it means a query that has just been
     * dropped can still have its commit called on the execution it is in the middle of closing.
     * Taking the lock is what makes "left the group" mean "will not be called again" -- {@link
     * #leave} cannot return until any publish in flight has finished.
     */
    private void publishPeriodically() {
        long now = System.nanoTime();
        if (now - lastPublishedNanos < PUBLISH_INTERVAL_NANOS) {
            return;
        }
        lastPublishedNanos = now;
        lock.lock();
        try {
            for (Member member : members) {
                member.afterDelivery.run();
            }
        } finally {
            lock.unlock();
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception e) {
            // Nothing useful to do: this is either teardown or the replacement of a reader that has
            // already been superseded, and reporting it would replace a real failure with a tidying
            // one.
        }
    }

    /**
     * One query's place in the group.
     *
     * <p>Mutable state is guarded by the feed's lock and only ever touched by the feed thread or by
     * a caller holding it, which is why none of it is volatile.
     */
    static final class Member implements AutoCloseable {

        private final String queryName;
        private final ReadRequest request;
        private final Runnable afterDelivery;

        private IngestPump pump;
        private PartitionReader catchUp;
        private boolean catchUpPolled;
        private boolean paused;
        private SourceOffset resumeAt;
        private SharedPartitionFeed feed;

        Member(String queryName, ReadRequest request, Runnable afterDelivery) {
            this.queryName = queryName;
            this.request = request;
            this.afterDelivery = afterDelivery;
        }

        String queryName() {
            return queryName;
        }

        IngestPump pump() {
            return pump;
        }

        void pauseReading() {
            feed.pause(this);
        }

        void resumeReading() {
            feed.resume(this);
        }

        SharedPartitionFeed feed() {
            return feed;
        }

        @Override
        public void close() {
            feed.leave(this);
        }
    }

    /**
     * What a consumer's pump thinks it is reading.
     *
     * <p>A pump needs a reader: it reports the offset a checkpoint records, and it is what the pump
     * pauses when a lane fills. This is that reader, and it answers for the shared one -- except
     * while a catch-up is running, when it answers for the catch-up instead.
     *
     * <p><strong>The offset is deliberately the older of the two.</strong> A consumer mid-catch-up
     * has received live rows from the shared reader and history from its own, and a checkpoint can
     * only write one token. Writing the catch-up's means a restore re-reads rows the query already
     * applied; writing the shared reader's means a restore skips the history the catch-up had not
     * reached. The first is a duplicate and the second is a loss.
     */
    private static final class MemberReader implements PartitionReader {

        private final Member member;

        MemberReader(Member member) {
            this.member = member;
        }

        @Override
        public int poll(RecordSink sink, int maxRecords) {
            PartitionReader catchUp = member.catchUp;
            // Nothing when there is no catch-up: the group's thread writes this consumer's rows
            // through the fan-out, not through here.
            return catchUp == null ? 0 : catchUp.poll(sink, maxRecords);
        }

        @Override
        public SourceOffset position() {
            PartitionReader catchUp = member.catchUp;
            if (catchUp != null) {
                return catchUp.position();
            }
            SharedPartitionFeed feed = member.feed;
            feed.lock.lock();
            try {
                return feed.position();
            } finally {
                feed.lock.unlock();
            }
        }

        @Override
        public void pause() {
            // The pump pausing for backpressure. Not forwarded: this consumer's room is already
            // zero, which stalls the group's poll, and pausing the shared reader would apply one
            // lane's backpressure to every other query reading the same set.
        }

        @Override
        public void resume() {}

        @Override
        public void close() {
            // The shared reader belongs to the group and outlives this consumer. The catch-up does
            // not, and is closed when the member leaves.
        }
    }
}
