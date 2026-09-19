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

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;

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

    /** Queries holding this group. Guarded by the owning {@link PluginSourceFeeds}'s monitor. */
    private int holders;

    SharedSourceGroup(Key key, StreamSourcePlugin plugin, List<SourcePartition> partitions) {
        this.key = key;
        this.plugin = plugin;
        this.partitions = List.copyOf(partitions);
        List<SharedPartitionFeed> built = new ArrayList<>(partitions.size());
        for (SourcePartition partition : this.partitions) {
            built.add(new SharedPartitionFeed(key.stream(), partition, plugin));
        }
        this.feeds = List.copyOf(built);
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
    static boolean canShare(SourceCapabilities capabilities) {
        return whyNotShared(capabilities) == null;
    }

    /** Why a source is read once per query, in a sentence, or null when it is not. */
    static String whyNotShared(SourceCapabilities capabilities) {
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

    StreamSourcePlugin plugin() {
        return plugin;
    }

    void retain() {
        holders++;
    }

    /** @return true when nobody is left and the caller should close this group */
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
}
