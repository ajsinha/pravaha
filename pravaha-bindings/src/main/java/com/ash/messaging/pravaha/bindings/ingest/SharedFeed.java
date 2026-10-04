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

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.registry.FeedStatus;
import com.ash.messaging.pravaha.registry.SourceFeed;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;

/**
 * One query's view of sources it shares with other queries.
 *
 * <p>SRC-3. The rows arrive on somebody else's thread -- the group's, one per binding rather than
 * one per query -- so what is left here is what a query still owns on its own: whether it is paused,
 * how many rows reached it, and what to tell an operator looking at a view that is not moving.
 *
 * <p>A query may be in both worlds at once. A join between an Aerospike set and a file shares the
 * reader for the set and keeps its own for the file, so this holds a {@link PumpingFeed} for the
 * unshared half when there is one, and nothing when there is not -- in which case the query has no
 * feed thread at all, which is the thread count this finding removes.
 */
final class SharedFeed implements SourceFeed {

    private final List<SharedPartitionFeed.Member> members;
    private final List<SharedSourceGroup> groups;
    private final Runnable releaseGroups;
    private final @Nullable PumpingFeed unshared;
    private final List<AutoCloseable> resources;
    private final String description;

    /** Whether this query paused its reading, for {@link #status()}; the group reads on for the others. */
    private volatile boolean paused;

    SharedFeed(
            List<SharedPartitionFeed.Member> members,
            List<SharedSourceGroup> groups,
            Runnable releaseGroups,
            @Nullable PumpingFeed unshared,
            List<AutoCloseable> resources,
            String description) {
        this.members = List.copyOf(members);
        this.groups = List.copyOf(groups);
        this.releaseGroups = releaseGroups;
        this.unshared = unshared;
        this.resources = List.copyOf(resources);
        this.description = description;
    }

    void start() {
        if (unshared != null) {
            unshared.start();
        }
    }

    @Override
    public void pause() {
        // Leaves the group reading for everybody else and records where this query stopped, so
        // resume picks up from there rather than from wherever the others have got to. A pause that
        // stalled the shared reader would be one query's operator pausing a thousand.
        members.forEach(SharedPartitionFeed.Member::pauseReading);
        if (unshared != null) {
            unshared.pause();
        }
        paused = true;
    }

    @Override
    public void resume() {
        paused = false;
        members.forEach(SharedPartitionFeed.Member::resumeReading);
        if (unshared != null) {
            unshared.resume();
        }
    }

    @Override
    public long rowsFed() {
        long rows = unshared == null ? 0 : unshared.rowsFed();
        for (SharedPartitionFeed.Member member : members) {
            rows += member.pump().rowsPumped();
        }
        return rows;
    }

    /**
     * Feeds one dead letter back through the pump that reads its stream, shared or not (B5).
     *
     * <p>Every pump this query has, whether its reader is its own or the group's: a pump writes
     * into this query's lane and nothing else's, so a replay on a shared reader reaches the query
     * that asked and no other member of the group. Asking the reader to re-poll would have reached
     * all of them, which is why replay goes through the pump.
     */
    @Override
    public com.ash.messaging.pravaha.runtime.dlq.DeadLetterEntry.Replay replay(
            String stream, byte[] raw, String sourceOffset, String schema, String id) {
        List<IngestPump> pumps = new ArrayList<>();
        members.forEach(member -> pumps.add(member.pump()));
        if (unshared != null) {
            pumps.addAll(unshared.pumps());
        }
        com.ash.messaging.pravaha.runtime.dlq.DeadLetterEntry.Replay outcome =
                FeedReplay.through(pumps, stream, raw, sourceOffset, schema, id);
        members.forEach(SharedPartitionFeed.Member::publishFrontier);
        return outcome;
    }

    /**
     * The shared readers this query joined, then its own.
     *
     * <p>A shared reader's stop is the reader's, so it is attributed to the partition as its origin
     * and every member of the group reports it; a publish that failed for this query alone is this
     * query's, and reported as stopping the partition it arrived through without being its cause.
     */
    @Override
    public FeedStatus status() {
        List<FeedStatus.Source> sources = new ArrayList<>(members.size());
        for (SharedPartitionFeed.Member member : members) {
            SharedPartitionFeed feed = member.feed();
            PravahaException read = feed.failure();
            PravahaException publish = member.publishFailure();
            FeedStatus.Stop stop = null;
            if (read != null) {
                stop = new FeedStatus.Stop(read, feed.stoppedAt(), true);
            } else if (publish != null) {
                stop = new FeedStatus.Stop(publish, member.publishFailedAt(), false);
            }
            FeedStatus.SourceState state = stop != null
                    ? FeedStatus.SourceState.STOPPED
                    : paused ? FeedStatus.SourceState.PAUSED : FeedStatus.SourceState.RUNNING;
            sources.add(new FeedStatus.Source(feed.stream(), feed.partitionIndex(), true, state, stop));
        }
        if (unshared != null) {
            sources.addAll(unshared.sources(false));
        }
        return FeedStatus.of(sharingText(), sources);
    }

    @Override
    public String describe() {
        StringBuilder text = new StringBuilder(sharingText());
        for (SharedPartitionFeed.Member member : members) {
            PravahaException failure = member.feed().failure();
            if (failure == null) {
                failure = member.publishFailure();
            }
            if (failure != null) {
                return text.append(" -- stopped: ").append(failure.getMessage()).toString();
            }
        }
        if (unshared != null) {
            String rest = unshared.describe();
            if (rest.contains("stopped:")) {
                return text.append(" -- ").append(rest).toString();
            }
        }
        return text.toString();
    }

    /** What this query reads, and how many others share the reader. */
    private String sharingText() {
        StringBuilder text = new StringBuilder(description);
        int sharing = 0;
        for (SharedSourceGroup group : groups) {
            sharing = Math.max(sharing, group.queryCount());
        }
        for (SharedSourceGroup group : groups) {
            String growth = group.describeGrowth();
            if (!growth.isEmpty()) {
                text.append("; ").append(growth);
            }
        }
        if (sharing > 1) {
            // The number an operator needs when asking why a source is slower than they expected:
            // the poll is sized to the smallest free inbox among these, so a stalled one of them
            // stalls the rest.
            text.append(" -- one reader shared with ")
                    .append(sharing - 1)
                    .append(sharing == 2 ? " other query" : " other queries");
        }
        return text.toString();
    }

    /** Stops this query being joined to partitions its groups gain; closed before its members. */
    private List<AutoCloseable> watches = List.of();

    SharedFeed watching(List<AutoCloseable> watches) {
        this.watches = watches;
        return this;
    }

    @Override
    public void close() {
        for (AutoCloseable watch : watches) {
            try {
                watch.close();
            } catch (Exception e) {
                // Unregistering a callback cannot meaningfully fail; nothing to do if it does.
            }
        }
        if (unshared != null) {
            unshared.close();
        }
        // Leave the groups before closing anything this query owns. Leaving is what stops the
        // group's thread writing into this query's lanes; closing a pump first would be closing a
        // reader under a live writer, which is the ordering mistake PumpingFeed's own close is
        // written to avoid one level down.
        for (SharedPartitionFeed.Member member : members) {
            member.close();
        }
        for (SharedPartitionFeed.Member member : members) {
            IngestPump pump = member.pump();
            if (pump != null) {
                pump.close();
            }
        }
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception e) {
                // A dead-letter queue that will not close must not stop the rest from closing.
            }
        }
        releaseGroups.run();
    }
}
