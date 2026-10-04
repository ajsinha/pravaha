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

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;

/**
 * Everything that reads one binding: one plugin, one reader per partition, many queries.
 *
 * <p>SRC-3. The unit of sharing is the <em>binding</em> -- a stream, the plugin it names and the
 * options it was configured with -- because that is what decides where rows come from. It is not the
 * fingerprint, which decides what is computed from them and is already shared by
 * {@code QueryFingerprint} for the case where two registrations ask the same question.
 *
 * <p>Reference counted, and the last query out closes the plugin. A group left behind would hold a
 * connection and a tend thread for a cluster nothing is reading.
 */
final class SharedSourceGroup {

    /**
     * Where this class used to synchronize on itself. A ReentrantLock rather than a monitor,
     * because the source's partitions are listed, and readers opened on any it has gained, under
     * it, over the network, and on JDK 21 a virtual thread blocked inside a monitor pins its
     * carrier (ADR-062).
     */
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    /**
     * What makes two queries readers of the same thing.
     *
     * <p>The schema is in the key and it has to be: a plan may push a projection into its scan, and
     * two scans of one set that emit different columns are two different row layouts. Fanning one
     * decoded record into lanes expecting different layouts would write each field at whatever
     * offset the first consumer's layout put it -- silently wrong rows rather than a failure. Two
     * layouts, two readers; the sharing is worth having only where it is exact.
     */
    record Key(String stream, SourceBinding binding, StreamSchema schema) {}

    private final Key key;
    private final StreamSourcePlugin plugin;
    private final List<SourcePartition> partitions;
    private final List<SharedPartitionFeed> feeds;
    private final @Nullable BackpressurePolicy policy;

    /** How each query joins a partition this group gains while it runs. Guarded by {@code this}. */
    private final List<Joiner> joiners = new ArrayList<>();

    /** Partitions gained while running, in the order they appeared. */
    private final List<Integer> gained = new java.util.concurrent.CopyOnWriteArrayList<>();

    private @Nullable Thread watcher;
    private volatile boolean closed;

    /** Joins one query to a partition the source gained after the query registered. */
    @FunctionalInterface
    interface Joiner {
        void join(SharedPartitionFeed feed, int partition);
    }

    /** Queries holding this group. Guarded by the owning {@link PluginSourceFeeds}'s sharing lock. */
    private int holders;

    SharedSourceGroup(
            Key key, StreamSourcePlugin plugin, List<SourcePartition> partitions, @Nullable BackpressurePolicy policy) {
        this.key = key;
        this.plugin = plugin;
        this.policy = policy;
        this.partitions = new java.util.concurrent.CopyOnWriteArrayList<>(partitions);
        List<SharedPartitionFeed> built = new ArrayList<>(partitions.size());
        for (SourcePartition partition : this.partitions) {
            built.add(feedFor(partition));
        }
        this.feeds = new java.util.concurrent.CopyOnWriteArrayList<>(built);
    }

    private SharedPartitionFeed feedFor(SourcePartition partition) {
        return new SharedPartitionFeed(
                key.stream(), partition, plugin, key.binding(), policy, plugin.orderedPositions());
    }

    /**
     * Registers how a query joins partitions this group gains, and starts watching the source for them
     * when it says it may gain any (A2's {@code partitionRefreshInterval}). The returned handle stops
     * this query being joined to anything more; the query closes it before it closes its members.
     */
    AutoCloseable watch(Joiner joiner) {
        lock.lock();
        try {
            joiners.add(joiner);
            java.time.Duration interval = plugin.partitionRefreshInterval();
            if (watcher == null && interval != null && !interval.isZero() && !interval.isNegative()) {
                watcher = Thread.ofVirtual()
                        .name("pravaha-shared-partitions-" + key.stream())
                        .start(() -> watchLoop(interval));
            }
            return () -> {
                lock.lock();
                try {
                    joiners.remove(joiner);
                } finally {
                    lock.unlock();
                }
            };
        } finally {
            lock.unlock();
        }
    }

    private void watchLoop(java.time.Duration interval) {
        while (!closed) {
            try {
                Thread.sleep(interval);
                grow();
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // Listing partitions failed this round: the source may be briefly unreachable. The
                // partitions already read carry on; the next round asks again.
                System.getLogger(SharedSourceGroup.class.getName())
                        .log(
                                System.Logger.Level.WARNING,
                                "listing the partitions of " + key.stream() + " failed: " + e.getMessage());
            }
        }
    }

    /**
     * Opens a feed for every partition the source has gained and joins every query to it, each from the
     * partition's first record, since it has no history anybody chose to skip. Package-private for the
     * test that grows a group without waiting a refresh interval.
     */
    void grow() {
        lock.lock();
        try {
            java.util.Set<Integer> known = new java.util.HashSet<>();
            partitions.forEach(p -> known.add(p.index()));
            for (SourcePartition partition : plugin.partitions(key.stream())) {
                if (closed || known.contains(partition.index())) {
                    continue;
                }
                SharedPartitionFeed feed = feedFor(partition);
                partitions.add(partition);
                feeds.add(feed);
                gained.add(partition.index());
                for (Joiner joiner : List.copyOf(joiners)) {
                    joiner.join(feed, partition.index());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Whether a source may be read by one reader on behalf of several queries.
     *
     * <p><strong>This is the honest half of SRC-3 and the reason the fix is narrower than the
     * finding.</strong> A shared reader has one offset and its consumers sit at several, and the
     * only way a consumer joining behind it can be made whole is a catch-up read that overlaps what
     * the shared reader has already delivered (see {@link SharedPartitionFeed}). That overlap is a
     * duplicate. A source declaring {@code EXACTLY_ONCE} has promised there are none, so it does not
     * get this -- it keeps a reader per query, which is what it had, and the load on such a store is
     * a cost paid for a guarantee somebody asked for.
     *
     * <p>Ordering is refused for the same kind of reason. While a consumer is catching up it is
     * receiving history from one reader and live rows from another, so it sees them interleaved. A
     * source that declares order within a partition has told the engine that cannot happen.
     *
     * <p>There is a way to share an ordered source and it is not built: stall the whole group while
     * a joiner catches up, so nothing live is delivered to anybody until the two readers meet. It
     * keeps the order for every consumer and it pays for it by stopping every query on that binding
     * for as long as one new registration takes to read the history -- which for a table rather than
     * a change feed is not bounded by anything this could promise. That is a separate decision from
     * this one, and today it means JDBC keeps a reader per query.
     *
     * <p>Replayable offsets are required outright: without them a catch-up reader cannot be created
     * at a position at all, and "start again from the beginning" is a different query's answer.
     *
     * <p>What that leaves is exactly the shape ADR-036 section 3 was written about -- an unordered,
     * at-least-once, offset-replayable scan of a store, which is what Aerospike Community can
     * offer -- and it leaves the stronger sources alone rather than quietly weakening them.
     */
    static boolean canShare(@Nullable SourceCapabilities capabilities) {
        return whyNotShared(capabilities, null) == null;
    }

    /**
     * Why a source is read once per query, in a sentence, or null when it is not.
     *
     * <p>A source whose positions are ordered ({@code order} non-null) is shared whatever it promises,
     * because a query joining its reader meets it at an exact seam rather than overlapping it
     * (ADR-054): each record reaches each query once and in order. The refusals below are for the
     * sources that cannot say where two positions stand.
     */
    static @Nullable String whyNotShared(
            @Nullable SourceCapabilities capabilities,
            com.ash.messaging.pravaha.api.plugin.@Nullable OrderedPositions order) {
        if (order != null && capabilities != null && capabilities.replayableOffsets()) {
            return null;
        }
        return whyNotShared(capabilities);
    }

    /** Why a source without ordered positions is read once per query, in a sentence, or null. */
    static @Nullable String whyNotShared(@Nullable SourceCapabilities capabilities) {
        if (capabilities == null) {
            return "the plugin declares no capabilities, so nothing is known about what sharing a reader "
                    + "would cost it";
        }
        if (capabilities.guarantee() == DeliveryGuarantee.EXACTLY_ONCE) {
            return "the source promises exactly-once, and handing a late-joining query over from a catch-up "
                    + "read to a shared one duplicates the overlap";
        }
        if (!capabilities.replayableOffsets()) {
            return "the source cannot resume from an offset, so a query joining a reader that has already "
                    + "read cannot be given the records it missed";
        }
        if (capabilities.orderedWithinPartition()) {
            return "the source promises order within a partition, and a query catching up receives history "
                    + "and live records interleaved";
        }
        return null;
    }

    Key key() {
        return key;
    }

    int partitionCount() {
        return partitions.size();
    }

    SharedPartitionFeed feed(int partition) {
        return feeds.get(partition);
    }

    /** What a status line says about growth, in {@link PartitionGrowth}'s words, or empty. */
    String describeGrowth() {
        return gained.isEmpty()
                ? ""
                : key.stream() + " gained " + gained.size() + (gained.size() == 1 ? " partition" : " partitions")
                        + " while running " + gained;
    }

    /** The partitions this group reads, in the order its feeds were made. */
    List<SourcePartition> partitions() {
        return List.copyOf(partitions);
    }

    StreamSourcePlugin plugin() {
        return plugin;
    }

    void retain() {
        holders++;
    }

    /** Drops one holder; true when nobody is left and the caller should close this group. */
    boolean release() {
        return --holders <= 0;
    }

    /** Rows read, and copies of them written into lanes, summed over every partition's reader. */
    long[] rowsReadAndCopiesWritten() {
        long read = 0;
        long written = 0;
        for (SharedPartitionFeed feed : feeds) {
            read += feed.rowsRead();
            written += feed.copiesWritten();
        }
        return new long[] {read, written};
    }

    /** Members of any partition's reader still catching up on history of their own. */
    int catchingUp() {
        return feeds.stream().mapToInt(SharedPartitionFeed::catchingUp).sum();
    }

    /** How many queries share this binding's readers, for {@code describe()}. */
    int queryCount() {
        return feeds.isEmpty() ? 0 : feeds.get(0).memberCount();
    }

    void close() {
        closed = true;
        Thread stopping;
        lock.lock();
        try {
            stopping = watcher;
            watcher = null;
        } finally {
            lock.unlock();
        }
        if (stopping != null) {
            stopping.interrupt();
        }
        // Readers first: a registration that failed part way through joining can leave one created
        // with nobody holding it, and closing the plugin under it would be the wrong order anyway.
        feeds.forEach(SharedPartitionFeed::closeReader);
        try {
            plugin.close();
        } catch (Exception e) {
            // The group is going away regardless, and a plugin that will not close cleanly must not
            // stop the registry from forgetting it.
        }
    }

    /** This group's feeds, for the test that keeps the configured watermarks threaded down to them. */
    List<SharedPartitionFeed> feeds() {
        return feeds;
    }
}
