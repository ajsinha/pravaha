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
import com.ash.messaging.pravaha.runtime.exec.SharedLaneInput;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;
import com.ash.messaging.pravaha.runtime.lane.Lane;
import com.ash.messaging.pravaha.runtime.lane.LaneMultiplexer;

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
 * <h2>What is pushed down, and changing it without losing or repeating a row</h2>
 *
 * <p>The reader asks its source for {@link SharedReadRequest#union} of every member's own request:
 * the OR of their filters and the union of their columns, each query keeping its own filter in the
 * engine as the backstop. It used to fall back to pushing nothing the moment a second query with a
 * different WHERE clause joined, which made the filter the price of sharing.
 *
 * <p>Membership changes what that union is, so the reader is replaced by one created at its own
 * position with the new request. <strong>Replaced only when it is idle</strong> -- when its last
 * poll returned nothing -- because only then is its position exactly "everything handed over and
 * nothing more": a scan reader mid-drain reports the position its current scan started from, so a
 * reader replaced then would hand the members every row of that scan a second time. This is the
 * same contract a catch-up already relies on to know it has finished.
 *
 * <ul>
 *   <li>A joiner that <em>widens</em> the request cannot wait: until the new reader exists, rows
 *       only it wants are not being read, and nothing would ever deliver them. So {@link #join}
 *       first drains the current reader into the members it already has, then replaces it, then
 *       attaches the joiner and starts its catch-up exactly as before.
 *   <li>A member leaving can only <em>narrow</em> it, and a wider reader than needed costs bytes,
 *       not answers. So {@link #leave} records the narrower request and the feed's own thread
 *       applies it the next time the reader is idle.
 * </ul>
 *
 * <h2>One copy per shared lane (LANE-2)</h2>
 *
 * <p>Members whose queries are hosted on one multiplexed lane share that lane's inbox, so writing
 * each of them its own copy would put N copies of every row into one inbox -- and the lane used to
 * dispatch by stream, so each of those queries was handed all N (LANE-1). Such members are grouped
 * by lane into a {@link LaneRoute}: the row is written into that lane <em>once</em>, stamped with a
 * route of its own, and the lane's multiplexer hands the one copy to every member listening to it.
 * A member on a lane of its own is written to exactly as before.
 *
 * <p>Listening is switched on and off by control tasks on the lane, submitted under this feed's lock
 * -- that is, while nothing is writing the route -- so a member starts receiving at the exact row it
 * was attached at, and a paused member stops at the exact row its resume position records. What a
 * member is fed on its own, its catch-up, is stamped with that query's private route and reaches it
 * alone. A checkpoint of any member still freezes that member's pump, and a poll still freezes every
 * live member's pump before it writes, so the one copy is held between rows exactly as the N copies
 * were.
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
    private final SourceBinding binding;
    private final StreamSourcePlugin plugin;

    /**
     * Guards the reader, the membership and every poll.
     *
     * <p>Held for the length of a poll, so a query joining or being dropped waits for the read in
     * flight. That wait is deliberate: the alternative is a lane being closed while this thread is
     * part way through writing a row into it, and a bounded wait that gave up would be that same
     * race with a timer on it.
     *
     * <p>Fair, so that a join or a leave waits for the poll in flight and not for every poll after
     * it: the feed thread releases and retakes this between polls with no pause while a reader has
     * rows, and an unfair lock lets it barge ahead of a waiting registration for as long as a scan
     * takes to drain. Fairness is paid per poll -- per batch -- not per row.
     */
    /**
     * The watermarks this feed's routes pause and resume at.
     *
     * <p>The node's configured pair, not {@code defaults()}: a shared reader that ignored it would
     * make {@code pravaha.lane.backpressure.*} true of a query with a lane of its own and silently
     * false of the same query once it shares one -- and sharing is a deployment's choice, not the
     * query's, so the setting would stop holding for a reason nothing tells the operator.
     */
    private final BackpressurePolicy policy;

    private final ReentrantLock lock = new ReentrantLock(true);

    private final List<Member> members = new ArrayList<>();

    private PartitionReader reader;

    /** What the reader was created with: the union of the members' requests when it was made. */
    private ReadRequest request = ReadRequest.NOTHING;

    /** A narrower request to switch to once the reader is next idle, or null. See {@link #leave}. */
    private ReadRequest pendingRequest;

    /**
     * Whether the reader's last poll returned nothing, so its position is exact. Guarded by the
     * lock. A new reader has not been polled and is idle by construction.
     */
    private boolean readerIdle = true;

    /** How long a widening join waits for the reader to drain before replacing it anyway. */
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);

    private Thread thread;
    private volatile boolean closed;
    private volatile PravahaException failure;

    /** When {@link #failure} was recorded. Written before it, so a reader that sees one sees both. */
    private volatile java.time.Instant stoppedAt;

    private long lastPublishedNanos;

    private static final System.Logger LOG = System.getLogger(SharedPartitionFeed.class.getName());

    /** Rows this reader has handed to its consumers, counted once however many received them. */
    private final AtomicLong rowsRead = new AtomicLong();

    /** Copies of those rows written into lanes: one per own-lane member and one per shared lane. */
    private final AtomicLong copiesWritten = new AtomicLong();

    /** One route per shared lane some member is hosted on. Guarded by the lock. */
    private final java.util.Map<Lane, LaneRoute> routes = new java.util.HashMap<>();

    SharedPartitionFeed(String stream, SourcePartition partition, StreamSourcePlugin plugin) {
        this(stream, partition, plugin, null, BackpressurePolicy.defaults());
    }

    /** @param binding what this reads, whose option values a recorded failure must not carry; may be null */
    SharedPartitionFeed(
            String stream,
            SourcePartition partition,
            StreamSourcePlugin plugin,
            SourceBinding binding,
            BackpressurePolicy policy) {
        this.binding = binding;
        this.stream = stream;
        this.partition = partition;
        this.plugin = plugin;
        this.policy = policy == null ? BackpressurePolicy.defaults() : policy;
    }

    /**
     * Attaches one query's lane to this reader.
     *
     * @param from where this query needs to start: {@code BEGINNING} for a fresh registration, the
     *     checkpoint's token for a restored one
     * @param wanted what this query would have pushed down had it opened its own reader
     * @param pumpFactory builds the query's pump around the reader handed to it; the caller owns
     *     what a pump needs -- the lane, the schema, the dead-letter queue -- and this does not
     * @param laneInput how the query's lane takes one copy shared with other queries on it, or null
     *     when the query has a lane of its own (LANE-2)
     */
    Member join(
            String queryName,
            SourceOffset from,
            ReadRequest wanted,
            Runnable afterDelivery,
            Function<PartitionReader, IngestPump> pumpFactory,
            SharedLaneInput laneInput) {
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("this shared reader has been closed");
            }
            Member member = new Member(queryName, wanted.withoutAggregates(), afterDelivery);
            member.feed = this;
            SourceOffset catchUpFrom = null;
            if (reader == null) {
                // The first consumer decides where the reader starts and what it pushes down. A
                // group of one therefore keeps every optimisation a private reader had.
                request = SharedReadRequest.union(List.of(member.request));
                reader = plugin.createReader(partition, from, request);
                readerIdle = true;
            } else {
                // Two questions, two WHERE clauses, one reader: it must return every row either
                // could want, which is the OR of the two -- not, as it was, everything.
                List<ReadRequest> all = new ArrayList<>(members.size() + 1);
                members.forEach(m -> all.add(m.request));
                all.add(member.request);
                ReadRequest union = SharedReadRequest.union(all);
                pendingRequest = null;
                if (!union.equals(request)) {
                    drainToIdle();
                    replaceReader(union);
                }
                SourceOffset here = reader.position();
                if (!here.equals(from)) {
                    catchUpFrom = from;
                }
            }
            member.pump = pumpFactory.apply(new MemberReader(member));
            if (laneInput != null) {
                // Listening from here: every row this reader writes from now on reaches this query
                // through the lane's one copy, and nothing it wrote before does. The catch-up below
                // covers what came before, through this query's own route.
                member.laneInput = laneInput;
                member.route = routeOn(laneInput);
                member.route.members.add(member);
                laneInput.listen(member.route.id);
            }
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

    /** The watermarks this feed's routes are opened with. Read by the test that keeps them threaded. */
    BackpressurePolicy backpressurePolicy() {
        return policy;
    }

    /** The route shared by every member hosted on {@code input}'s lane, made on first use. Lock held. */
    private LaneRoute routeOn(SharedLaneInput input) {
        return routes.computeIfAbsent(input.lane(), lane -> {
            int id = LaneMultiplexer.newRoute();
            LaneRoute route = new LaneRoute(id);
            route.writer = input.openRoute(id, policy);
            route.writer.observeEventTimeWith(route::observe);
            return route;
        });
    }

    /** Members still reading history of their own, which a test waits out before changing the source. */
    int catchingUp() {
        lock.lock();
        try {
            return (int)
                    members.stream().filter(member -> member.catchUp != null).count();
        } finally {
            lock.unlock();
        }
    }

    /** Copies written into lanes, one per lane per row however many queries each copy serves. */
    long copiesWritten() {
        return copiesWritten.get();
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

    /** When {@link #failure()} was recorded, or null while this reader is reading. */
    java.time.Instant stoppedAt() {
        return stoppedAt;
    }

    /** The stream this reader reads, for a stop that has to say where it happened (FEED-1). */
    String stream() {
        return stream;
    }

    /** The partition this reader reads. */
    int partitionIndex() {
        return partition.index();
    }

    /**
     * Records why this reader stopped, and says so once in the log, as {@link PumpingFeed} does for
     * a reader of its own. Every member of the group stops with it.
     */
    private void stop(PravahaException cause) {
        PravahaException recorded = binding == null ? cause : FeedRedaction.redact(cause, java.util.List.of(binding));
        stoppedAt = java.time.Instant.now();
        failure = recorded;
        LOG.log(
                System.Logger.Level.ERROR,
                "the shared source feed reading " + stream + "#" + partition.index() + " stopped with "
                        + cause.errorCode().code() + " and will not retry; every query it fed keeps answering "
                        + "at the frontier it reached: " + recorded.getMessage(),
                cause);
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
            if (member.route != null) {
                // Off the fan-out at this row, rather than whenever the query's pipeline is dropped:
                // the other members on the lane keep this route busy until then.
                member.laneInput.stopListening(member.route.id);
                member.route.members.remove(member);
                if (member.route.members.isEmpty()) {
                    routes.values().remove(member.route);
                    closeQuietly(member.route.writer);
                }
                member.route = null;
            }
            if (!members.isEmpty()) {
                // Narrower, never wider: the remaining members wanted a subset of what is being
                // read. Applied by the feed thread at the reader's next idle poll -- see the class
                // javadoc -- because replacing a reader mid-scan repeats that scan's rows.
                ReadRequest union = SharedReadRequest.union(
                        members.stream().map(m -> m.request).toList());
                pendingRequest = union.equals(request) ? null : union;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Polls the shared reader into the members it already has until a poll returns nothing, so
     * its position is exact and it can be replaced without repeating a row. Called with the lock
     * held, by a join about to widen the request.
     *
     * <p>Bounded, because it waits on the members' lanes to make room. Past {@link
     * #DRAIN_TIMEOUT} the reader is replaced where it stands and the rows of its current scan are
     * read again for the members that already had them -- a duplicate, which the at-least-once
     * gate on sharing ({@link SharedSourceGroup#canShare}) already permits, and never a loss.
     */
    private void drainToIdle() {
        long deadline = System.nanoTime() + DRAIN_TIMEOUT.toNanos();
        // With every member paused there is nobody to hand the rest of the scan to: each of them
        // resumes through a catch-up from its own recorded position, whatever this reader does.
        while (!readerIdle && members.stream().anyMatch(m -> !m.paused)) {
            if (pollLive() < 0) {
                if (System.nanoTime() > deadline) {
                    return;
                }
                LockSupport.parkNanos(IDLE_NAP_NANOS);
            }
        }
    }

    /** Replaces the reader with one at its own position asking for {@code wanted}. Lock held. */
    private void replaceReader(ReadRequest wanted) {
        PartitionReader replacement = plugin.createReader(partition, reader.position(), wanted);
        closeQuietly(reader);
        reader = replacement;
        request = wanted;
        readerIdle = true;
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
            routes.values().forEach(route -> closeQuietly(route.writer));
            routes.clear();
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
            if (member.route != null) {
                // At the row resumeAt names: everything written before it has been, or will be,
                // handed to this query, and nothing after it will be.
                member.laneInput.stopListening(member.route.id);
            }
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
            if (member.route != null) {
                // Back on the fan-out from here; the catch-up below covers the gap, as for a join.
                member.laneInput.listen(member.route.id);
            }
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
                stop(e);
                return;
            } catch (Throwable e) {
                stop(new PravahaException(
                        IngestErrors.FEED_FAILED, "the shared source feed for '" + stream + "' stopped: " + e, e));
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
            int read = pollLive();
            if (read > 0) {
                moved += read;
            }
            if (pendingRequest != null && readerIdle) {
                // A member left and the rest want less. Only now, with nothing of the current scan
                // still to hand over, is the reader's position exact enough to start another at.
                replaceReader(pendingRequest);
                pendingRequest = null;
            }
            return moved;
        } finally {
            lock.unlock();
        }
    }

    /**
     * One poll of the shared reader into every member not paused, or -1 when it could not be
     * polled at all -- a full inbox, a checkpoint holding a pump, or nobody live. Lock held.
     */
    private int pollLive() {
        // The live set: everything not paused. A consumer still catching up is in it, because it
        // was attached before its catch-up started and the whole point was that the gap it has to
        // cover stops growing at that moment.
        List<Member> live = new ArrayList<>(members.size());
        int room = BATCH;
        for (Member member : members) {
            if (member.paused) {
                continue;
            }
            int free = member.pump.roomForSharedPoll();
            if (free <= 0) {
                // A full inbox stalls the group. Polling anyway and dropping this consumer's copy
                // would turn backpressure into silent loss for whichever query happened to be
                // slowest, which is the failure backpressure exists to prevent.
                return -1;
            }
            room = Math.min(room, free);
            live.add(member);
        }
        if (live.isEmpty() || room <= 0) {
            return -1;
        }
        return pollShared(live, room);
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
            return -1;
        }
        try {
            List<PartitionReader.RecordSink> sinks = new ArrayList<>(live.size());
            for (LaneRoute route : routes.values()) {
                route.listening.clear();
            }
            for (Member member : live) {
                if (member.route == null) {
                    sinks.add(member.pump.sharedSink());
                } else {
                    // One copy per shared lane, whoever else on it is listening (LANE-2).
                    if (member.route.listening.isEmpty()) {
                        sinks.add(member.route);
                    }
                    member.route.listening.add(member);
                }
            }
            int read = reader.poll(new BroadcastSink(sinks), room);
            // Nothing returned is the one moment the position covers exactly what was handed over.
            readerIdle = read == 0;
            if (read > 0) {
                rowsRead.addAndGet(read);
                copiesWritten.addAndGet((long) read * sinks.size());
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
     * <p>Each member under the lock, which is not an oversight: leaving it means a query that has
     * just been dropped can still have its commit called on the execution it is in the middle of
     * closing. Taking the lock is what makes "left the group" mean "will not be called again" --
     * {@link #leave} cannot return until a publish of that member in flight has finished.
     *
     * <p>Per member rather than once for the whole round. A commit waits for its lane to publish
     * the query's continuous aggregates, so a round over a thousand members is a thousand lane
     * round trips; held for all of them, the lock kept every join, pause and drop waiting for the
     * whole round -- measured at seconds per registration once a thousand queries shared one
     * reader (LANE-2's density test), which made registering the thousandth take minutes.
     */
    private void publishPeriodically() {
        long now = System.nanoTime();
        if (now - lastPublishedNanos < PUBLISH_INTERVAL_NANOS) {
            return;
        }
        lastPublishedNanos = now;
        List<Member> round;
        lock.lock();
        try {
            round = List.copyOf(members);
        } finally {
            lock.unlock();
        }
        for (Member member : round) {
            lock.lock();
            try {
                if (member.publishFailure != null || !members.contains(member)) {
                    continue;
                }
                member.afterDelivery.run();
            } catch (RuntimeException e) {
                // One query's commit refusing must not stop the reader every other query on this
                // binding is fed by. It used to: this ran outside run()'s catch, so the throw ended
                // the group's thread unrecorded and every member froze at RUNNING. Recorded on the
                // member, whose describe() says so, as PumpingFeed does for a query with a reader of
                // its own.
                member.publishFailedAt = java.time.Instant.now();
                member.publishFailure = new PravahaException(
                        IngestErrors.FEED_FAILED,
                        "publishing query '" + member.queryName + "' failed: " + e.getMessage(),
                        e);
                LOG.log(
                        System.Logger.Level.ERROR,
                        "the shared source feed stopped publishing query '" + member.queryName
                                + "'; the other queries on " + stream + "#" + partition.index()
                                + " carry on: " + e.getMessage(),
                        e);
            } finally {
                lock.unlock();
            }
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
        private SharedLaneInput laneInput;
        private LaneRoute route;
        private PartitionReader catchUp;
        private boolean catchUpPolled;
        private boolean paused;
        private SourceOffset resumeAt;

        /** Why publishing this query stopped, or null. Guarded by the feed's lock. */
        private volatile PravahaException publishFailure;

        /** When {@link #publishFailure} was recorded. Written before it. */
        private volatile java.time.Instant publishFailedAt;

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

        /**
         * Publishes this query's frontier now, outside the group's own delivery (B5).
         *
         * <p>A replayed row is applied on the caller's thread, not the group's, so nothing would
         * otherwise publish it: the group's loop publishes after a delivery, and a source that has
         * gone quiet -- the usual state of a query somebody is cleaning a dead-letter queue for --
         * has no next delivery.
         */
        void publishFrontier() {
            afterDelivery.run();
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

        PravahaException publishFailure() {
            return publishFailure;
        }

        java.time.Instant publishFailedAt() {
            return publishFailedAt;
        }

        @Override
        public void close() {
            feed.leave(this);
        }
    }

    /**
     * One copy of each row into one shared lane, for every member hosted on it (LANE-2).
     *
     * <p>A sink to the reader's {@link BroadcastSink} like any member's own: the row is written
     * through {@link #writer}, stamped with {@link #id}, and the lane hands it to every query
     * listening. Guarded by the feed's lock, and touched only by a caller holding it.
     */
    private static final class LaneRoute implements PartitionReader.RecordSink {

        final int id;
        final List<Member> members = new ArrayList<>();

        /** The members this poll is writing for: the live ones, set before every poll. */
        final List<Member> listening = new ArrayList<>();

        IngestPump writer;

        LaneRoute(int id) {
            this.id = id;
        }

        @Override
        public com.ash.messaging.pravaha.api.data.RowWriter beginRow() {
            return writer.sharedSink().beginRow();
        }

        /**
         * Offers an undecodable record to each listening query's own dead-letter queue, true only
         * when every one of them took it -- for the reason {@link BroadcastSink#reject} gives.
         */
        @Override
        public boolean reject(byte[] raw, String sourceOffset, String reason) {
            boolean all = true;
            for (Member member : listening) {
                all = member.pump.sharedSink().reject(raw, sourceOffset, reason) && all;
            }
            return all;
        }

        /** Each listening query's watermark hears the event time of the one copy they share. */
        void observe(long eventTimeNanos) {
            for (Member member : listening) {
                member.pump.eventTimeObserver().accept(eventTimeNanos);
            }
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
                if (member.paused && member.resumeAt != null) {
                    // Paused: this query has been handed nothing since resumeAt, however far the
                    // shared reader has read for the others since. A checkpoint that recorded the
                    // reader's position instead resumed a restored query past every row it missed
                    // while paused -- silent loss, and a pause is exactly when an operator takes one.
                    return member.resumeAt;
                }
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
