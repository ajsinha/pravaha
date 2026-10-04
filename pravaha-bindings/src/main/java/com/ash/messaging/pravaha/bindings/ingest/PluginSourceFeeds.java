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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.backfill.OffsetSplicedReader;
import com.ash.messaging.pravaha.connect.PluginDiscovery;
import com.ash.messaging.pravaha.registry.ReplaySource;
import com.ash.messaging.pravaha.registry.SourceFeed;
import com.ash.messaging.pravaha.registry.SourceFeedFactory;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;
import com.ash.messaging.pravaha.sql.plan.SourcePushdown;

/**
 * Turns configured {@link SourceBinding}s into rows arriving at a registered query.
 *
 * <p>This is the piece that makes a Pravaha server a server. Everything below it existed and was
 * tested -- four source plugins, the pump, backpressure, watermark partitions -- and nothing above
 * it ever called down: the only production code that drove a pump was the CLI's one-shot {@code
 * run}, so a query registered on a server sat at zero rows indefinitely.
 *
 * <p><strong>Plugins are discovered, not compiled in.</strong> A binding names a plugin the way the
 * plugin names itself, and {@link java.util.ServiceLoader} finds it on the classpath -- so adding Delta or
 * JDBC to a deployment is dropping a jar in, not rebuilding the server. The alternative, a compile
 * time dependency per plugin, would drag Hadoop and Parquet into every server that only ever reads
 * a directory.
 *
 * <p><strong>A stream with no binding is not an error.</strong> Plenty of queries are fed by an
 * embedder pushing rows through {@code accept}, and a server that refused to register a query
 * because it could not find a file to read would break every one of them. The query registers, runs,
 * and reports through {@link SourceFeed#describe()} that nothing is attached -- which is the
 * difference between a silent query an operator can diagnose and one they cannot.
 */
public final class PluginSourceFeeds implements SourceFeedFactory {

    /**
     * The bindings in force, in the order the configuration file declares them.
     *
     * <p>CFG-3(b). A {@code ConcurrentHashMap}, so the {@code sources bound:} line at startup came
     * out in hash order while the {@code streams declared in configuration:} line immediately above
     * it -- a {@code LinkedHashMap} -- came out in file order. Two adjacent lines describing one
     * file, disagreeing about it: a file declaring {@code s3, s2, s1} logged {@code [s3, s1, s2]},
     * and a diff of two nodes' startup logs was not usable. Synchronised rather than concurrent
     * because binding happens once, at startup, and every read afterwards is a copy.
     */
    private final Map<String, SourceBinding> bindings =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());

    private final BackpressurePolicy policy;
    private volatile java.nio.file.@Nullable Path deadLetterDirectory;

    /**
     * The live dead-letter queue and rejection rate of each query that has one (B5).
     *
     * <p>So that a gauge scraped every fifteen seconds can answer from counters the writer already
     * keeps, rather than by walking a file of up to a quarter of a gigabyte. The entry is removed
     * when the query's feed closes.
     */
    private final Map<String, LiveDeadLetters> liveDeadLetters = new ConcurrentHashMap<>();

    /** The bound applied to every dead-letter file this node writes. */
    private volatile com.ash.messaging.pravaha.runtime.dlq.DeadLetterRetention deadLetterRetention =
            com.ash.messaging.pravaha.runtime.dlq.DeadLetterRetention.defaults();

    /**
     * One reader per binding, shared by every query that reads it. SRC-3.
     *
     * <p>Keyed by the binding and the layout its scan emits rather than by the query, which is the
     * whole finding: {@code QueryFingerprint} already shares a computation between registrations of
     * identical SQL, and a deployment with a thousand continuous queries has a thousand different
     * questions about one set rather than a thousand copies of one.
     */
    private final Map<SharedSourceGroup.Key, SharedSourceGroup> groups = new java.util.HashMap<>();

    /** Bindings whose plugin has told us sharing is not safe for it. See {@code canShare}. */
    private final Map<SourceBinding, String> unshareable = new java.util.HashMap<>();

    /**
     * Bindings whose source has one consumer at a time, and the query reading each (CDCREPL-2).
     *
     * <p>A PostgreSQL replication slot streams to one connection, and a MySQL replica id is one
     * replica: a binding names exactly one of either, and such a source is never shared. A second,
     * different query over the binding used to open a reader of its own on the same slot and fail
     * {@code PRV-5117} fifteen seconds later, after the registration had been accepted. It is
     * refused at registration now, naming the query that holds the binding.
     */
    private final Map<SourceBinding, String> soleReaders = new java.util.HashMap<>();

    /**
     * Guards {@link #groups}, {@link #unshareable} and {@link #soleReaders}. A ReentrantLock rather
     * than a monitor: a registration opens the shared source under it, over the network, and on JDK
     * 21 a virtual thread blocked inside a monitor pins its carrier (ADR-062).
     */
    private final java.util.concurrent.locks.ReentrantLock sharing = new java.util.concurrent.locks.ReentrantLock();

    public PluginSourceFeeds() {
        this(BackpressurePolicy.defaults());
    }

    public PluginSourceFeeds(@Nullable BackpressurePolicy policy) {
        this.policy = policy == null ? BackpressurePolicy.defaults() : policy;
    }

    /**
     * Sends records this node cannot decode to a file under {@code directory}, one per query.
     *
     * <p>TIME-4/W8-11. The dead-letter path already existed and already worked; a server had no key
     * to switch it on, so every node took the unguarded path -- where a decode failure ends the poll
     * and stops the source, taking every other row in the file with it. `pravaha run --dlq` had
     * this and a deployment did not, which is the wrong way round.
     *
     * <p>Null or unset leaves the old behaviour exactly as it was, and that is deliberate: without
     * somewhere durable to put a record, "keep going" is just "drop it", and failing loudly is the
     * better of those two.
     */
    public PluginSourceFeeds deadLetteringTo(java.nio.file.Path directory) {
        this.deadLetterDirectory = directory;
        return this;
    }

    /**
     * Bounds every dead-letter file this node writes (B5).
     *
     * <p>Unbounded, one renamed column in a busy feed fills the disk the node's checkpoints are
     * on. The bound evicts the oldest entries and writes down what it took, rather than refusing
     * -- see {@link com.ash.messaging.pravaha.runtime.dlq.DeadLetterRetention} for why that way
     * round.
     */
    public PluginSourceFeeds retainingDeadLetters(com.ash.messaging.pravaha.runtime.dlq.DeadLetterRetention bound) {
        this.deadLetterRetention =
                bound == null ? com.ash.messaging.pravaha.runtime.dlq.DeadLetterRetention.defaults() : bound;
        return this;
    }

    /**
     * What one query's dead-letter queue looks like right now, from the writer's own counters.
     *
     * <p>{@link com.ash.messaging.pravaha.runtime.dlq.DeadLetterHealth#none()} for a query with no
     * queue attached, which is not the same as one that has rejected nothing and is not reported
     * as though it were.
     */
    public com.ash.messaging.pravaha.runtime.dlq.DeadLetterHealth deadLetterHealth(String queryName) {
        LiveDeadLetters live = liveDeadLetters.get(queryName);
        return live == null
                ? com.ash.messaging.pravaha.runtime.dlq.DeadLetterHealth.none()
                : new com.ash.messaging.pravaha.runtime.dlq.DeadLetterHealth(
                        live.queue().depth(),
                        live.queue().bytesInFile(),
                        live.queue().evicted(),
                        live.queue().evictedBytes(),
                        live.queue().failures(),
                        live.rate().rejectedFraction(),
                        live.rate().isDegraded());
    }

    /** The queries with a dead-letter queue open right now. */
    public java.util.Set<String> deadLetteringQueries() {
        return java.util.Set.copyOf(liveDeadLetters.keySet());
    }

    /** One query's open queue and the rate every one of its pumps reports to. */
    private record LiveDeadLetters(
            com.ash.messaging.pravaha.runtime.dlq.FileDeadLetterQueue queue,
            com.ash.messaging.pravaha.runtime.dlq.DeadLetterRate rate) {}

    /**
     * Moves a query's dead-letter files to its new engine name (ADR-060), before its feed opens: the
     * entries, what was replayed and what retention took, each only when the new name has none yet.
     */
    @Override
    public void renamed(String from, String to) {
        java.nio.file.Path directory = deadLetterDirectory;
        if (directory == null || from.equals(to)) {
            return;
        }
        List<java.util.function.BiFunction<java.nio.file.Path, String, java.nio.file.Path>> files = List.of(
                com.ash.messaging.pravaha.runtime.dlq.DeadLetterFiles::letters,
                com.ash.messaging.pravaha.runtime.dlq.DeadLetterFiles::replays,
                com.ash.messaging.pravaha.runtime.dlq.DeadLetterFiles::evicted);
        for (var file : files) {
            java.nio.file.Path source = file.apply(directory, from);
            java.nio.file.Path target = file.apply(directory, to);
            try {
                if (java.nio.file.Files.exists(source) && !java.nio.file.Files.exists(target)) {
                    java.nio.file.Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (java.io.IOException cannot) {
                throw unusable(directory, cannot);
            }
        }
    }

    /**
     * Reads back what has been dead-lettered, or {@link
     * com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore#NONE} when no directory is configured.
     *
     * <p>A store rather than a path, so that every surface that lists, shows and replays reads the
     * files through one implementation. Built per call, because it holds no state: a directory and
     * a bound.
     */
    public com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore deadLetters() {
        java.nio.file.Path directory = deadLetterDirectory;
        return directory == null
                ? com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore.NONE
                : new com.ash.messaging.pravaha.runtime.dlq.FileDeadLetterStore(directory, deadLetterRetention);
    }

    /**
     * Binds a stream to a source, replacing any previous binding.
     *
     * <p>Takes effect for queries registered afterwards. A query already running keeps the feed it
     * opened with, because swapping a source under a live computation would change what its state
     * was accumulated from without changing the state -- and the view would then be a mixture of two
     * sources that nothing records.
     */
    public PluginSourceFeeds bind(SourceBinding binding) {
        // PLUGINLATE-1: a plugin nothing on the classpath answers to is refused here, where the
        // node binds its sources at startup -- as CONNECTORS.md always said -- and not at the first
        // registration, after the node had come up UP with a binding that could never feed.
        discover(binding);
        bindings.put(binding.streamName(), binding);
        return this;
    }

    /**
     * The bindings in force, by stream name, in declaration order.
     *
     * <p>{@code Map.copyOf} would throw the order away again -- an immutable map has none -- which
     * is half of why CFG-3(b) survived a reading of {@code bind}.
     */
    public Map<String, SourceBinding> bindings() {
        synchronized (bindings) {
            return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(bindings));
        }
    }

    /**
     * Whether the source bound to this stream emits deletes, asked of the configured plugin without
     * opening it (HLP-3). A stream with no binding is fed by hand and is not this class's to judge.
     */
    @Override
    public boolean retracts(String stream) {
        SourceBinding binding = bindings.get(stream);
        if (binding == null) {
            return false;
        }
        StreamSourcePlugin plugin = configure(binding);
        try {
            return plugin.capabilities().emitsDeletes();
        } finally {
            closeQuietly(List.of(plugin));
        }
    }

    /**
     * The bound plugin's name when its source repeats rows, asked of the configured plugin without
     * opening it, as {@link #retracts} is (SCAN-1). The answer is per configuration: a {@code
     * cassandra} binding with {@code deletes: ignore} repeats and one with {@code deletes: detect}
     * does not.
     */
    @Override
    public java.util.Optional<String> repeatingSource(String stream) {
        SourceBinding binding = bindings.get(stream);
        if (binding == null) {
            return java.util.Optional.empty();
        }
        StreamSourcePlugin plugin = configure(binding);
        try {
            return plugin.capabilities().repeatsRows()
                    ? java.util.Optional.of(binding.plugin())
                    : java.util.Optional.empty();
        } finally {
            closeQuietly(List.of(plugin));
        }
    }

    @Override
    public SourceFeed open(
            String queryName,
            QueryExecution execution,
            List<String> sourceStreams,
            Runnable afterDelivery,
            Map<String, String> resumeFrom) {
        // DLQPROJ-1: before the feed starts, so no row it delivers can fail with nowhere to go.
        List<AutoCloseable> owned = new ArrayList<>();
        deadLetterRowFailures(queryName, execution, owned);
        try {
            return RowFailureLetters.closingWith(
                    openFeed(queryName, execution, sourceStreams, afterDelivery, resumeFrom), owned);
        } catch (RuntimeException e) {
            execution.deadLetterRowFailures(null);
            closeQuietly(owned);
            throw e;
        }
    }

    /**
     * Gives the query's lanes its dead-letter queue for a row whose evaluation fails (DLQPROJ-1),
     * when a directory is configured. The queue is the one its pumps write decode failures to;
     * opened here when nothing has opened it yet, and then closed with the feed.
     */
    private void deadLetterRowFailures(String queryName, QueryExecution execution, List<AutoCloseable> owned) {
        java.nio.file.Path directory = deadLetterDirectory;
        if (directory == null) {
            return;
        }
        LiveDeadLetters live = liveDeadLetters(directory, queryName, owned);
        execution.deadLetterRowFailures(new RowFailureLetters(live.queue(), queryName));
    }

    private SourceFeed openFeed(
            String queryName,
            QueryExecution execution,
            List<String> sourceStreams,
            Runnable afterDelivery,
            Map<String, String> resumeFrom) {
        // Distinct, because a self-join names one stream twice and opening two feeds for it would
        // deliver every row twice to a query that asked for it once.
        List<String> bound =
                sourceStreams.stream().distinct().filter(bindings::containsKey).toList();
        if (bound.isEmpty()) {
            return SourceFeed.NONE;
        }

        List<IngestPump> pumps = new ArrayList<>();
        // Which stream and partition each pump reads, beside it, so a feed that stops can say where.
        List<FeedInput> inputs = new ArrayList<>();
        List<AutoCloseable> resources = new ArrayList<>();
        List<AutoCloseable> sharedResources = new ArrayList<>();
        // Copy-on-write: a group that gains a partition adds this query's member for it from its own
        // thread, while the feed may be reading the list.
        List<SharedPartitionFeed.Member> members = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<AutoCloseable> watches = new ArrayList<>();
        List<SharedSourceGroup> joined = new ArrayList<>();
        Map<String, Integer> partitionCounts = new LinkedHashMap<>();
        // What each stream's reader was asked to do, for describe(): an operator asking why a query
        // reads so much should not have to reconstruct the pushdown from the plan.
        Map<String, String> pushed = new LinkedHashMap<>();
        // One publish at a time, whoever calls it. A query with both a shared stream and an
        // unshared one is now published by two threads -- the group's and its own feed's -- and
        // committing a frontier was only ever done by one.
        Runnable publish = serialised(afterDelivery);
        // Matches each recorded offset to its partition: by the partition a checkpoint names beside
        // it, or -- for a checkpoint written before those names -- by counting partitions across
        // every stream, shared and not, which is the order the pumps were created in.
        ResumePositions positions = ResumePositions.of(resumeFrom);
        // The streams whose source may gain partitions while this query runs.
        List<PartitionGrowth> growth = new ArrayList<>();
        try {
            for (String stream : bound) {
                SourceBinding binding = bindingOf(stream);

                // SRC-3. One reader per binding where the source allows it, and the query joins it
                // rather than opening its own.
                SharedSourceGroup group = groupFor(stream, binding, execution);
                if (group != null) {
                    joined.add(group);
                    partitionCounts.put(stream, group.partitionCount());
                    // Rows, never a partial: a reader fanned out to several queries cannot pre-combine
                    // for one of them. The group pushes the OR of every member's filters.
                    ReadRequest shared = SourcePushdown.requestFor(
                                    execution.plan(), stream, group.plugin().capabilities())
                            .withoutAggregates();
                    pushed.put(stream, summarise(shared) + ", shared" + sourceSays(group.plugin(), shared));
                    java.util.function.BiFunction<SharedPartitionFeed, Integer, SharedPartitionFeed.Member> joinAt =
                            (feed, partition) -> feed.join(
                                    queryName,
                                    null,
                                    shared,
                                    publish,
                                    reader -> sharedPump(
                                            execution, queryName, stream, partition, reader, group, sharedResources),
                                    execution.sharedLaneInput(stream));
                    for (int index = 0; index < group.partitionCount(); index++) {
                        int partition = index;
                        String token = positions.tokenFor(stream, partition);
                        // No offset for a partition this restore has never seen: the source gained it
                        // after the checkpoint, so it is read from its first record (null), like any
                        // partition gained while running.
                        SourceOffset from = positions.isNew(token)
                                ? null
                                : token == null ? SourceOffset.BEGINNING : new SourceOffset(token);
                        members.add(group.feed(index)
                                .join(
                                        queryName,
                                        from,
                                        shared,
                                        publish,
                                        reader -> sharedPump(
                                                execution,
                                                queryName,
                                                stream,
                                                partition,
                                                reader,
                                                group,
                                                sharedResources),
                                        execution.sharedLaneInput(stream)));
                    }
                    // A2 for shared readers: a partition the source gains later is joined as it appears.
                    watches.add(group.watch((feed, partition) -> members.add(joinAt.apply(feed, partition))));
                    continue;
                }

                StreamSourcePlugin plugin = configure(binding);
                // CDCREPL-2: before open, which for postgres-cdc touches the slot the holder reads.
                try {
                    resources.add(claimSoleReader(queryName, stream, binding, plugin));
                } catch (RuntimeException e) {
                    closeQuietly(List.of(plugin));
                    throw e;
                }
                openConfigured(plugin, binding);
                resources.add(plugin);

                // Offer the source whatever of the WHERE clause it can evaluate itself. This was
                // missing here for as long as the server path existed: pushdown worked in
                // `pravaha run` and in tests, and a registered query -- the only way anything in
                // production reads a source -- scanned everything and filtered it after the fact.
                // The README's cost-curve claim was true of a code path no deployment used.
                //
                // Safe to offer blindly: the engine keeps its own filter whatever the source does,
                // so this changes how many bytes cross the boundary and nothing else.
                ReadRequest request = SourcePushdown.requestFor(execution.plan(), stream, plugin.capabilities());
                if (!execution.acceptsPartialAggregateFor(stream)) {
                    // A partial with nowhere to be folded in -- a multiplexed lane -- would be
                    // read as a row. Ask for rows instead; the filters and columns still apply.
                    request = request.withoutAggregates();
                }
                boolean partials = false;

                List<SourcePartition> partitions = plugin.partitions(stream);
                partitionCounts.put(stream, partitions.size());
                for (SourcePartition partition : partitions) {
                    // Resume where the checkpoint left off, when there is one. Reading from the
                    // beginning after a restore would replay every record between the checkpoint and
                    // the failure on top of the state that already counted them.
                    String token = positions.tokenFor(stream, partition.index());
                    SourceOffset from = token == null ? SourceOffset.BEGINNING : new SourceOffset(token);
                    // A restore with no offset for this partition: the source gained it after the
                    // checkpoint, so it has no history the query chose to skip.
                    PartitionReader reader = positions.isNew(token)
                            ? plugin.createReaderForNewPartition(partition, request)
                            : plugin.createReader(partition, from, request);
                    resources.add(reader);
                    // The reader decides, because only it knows whether it could express every
                    // filter the partial depends on; the pump routes by its answer.
                    partials |= reader.deliversPartialAggregate();
                    // Lane 0: a registered query is compiled onto one lane today. When that
                    // changes, the partition index is what chooses the lane -- it is already the
                    // unit the source split itself into.
                    IngestPump pump =
                            unsharedPump(execution, queryName, stream, plugin, partition.index(), reader, resources);
                    pumps.add(pump);
                    inputs.add(new FeedInput(stream, partition.index()));
                }
                pushed.put(
                        stream,
                        summarise(request.withoutAggregates())
                                + (partials ? ", partial aggregate" : "")
                                + sourceSays(plugin, request));
                ReadRequest asked = request;
                watch(
                        plugin,
                        stream,
                        asked,
                        partitions,
                        growth,
                        (index, reader, opened) ->
                                unsharedPump(execution, queryName, stream, plugin, index, reader, opened));
            }
        } catch (RuntimeException e) {
            // Nothing half-open survives a failed binding. Without this, a query that failed to
            // register would leave a plugin holding a file handle or a connection for the lifetime
            // of the process.
            members.forEach(SharedPartitionFeed.Member::close);
            closeQuietly(sharedResources);
            closeQuietly(resources);
            release(joined);
            throw e;
        }

        String description = describe(partitionCounts, pushed);
        if (members.isEmpty()) {
            // Nothing shared: exactly the feed this returned before SRC-3, including the thread.
            PumpingFeed feed = new PumpingFeed(queryName, pumps, inputs, resources, description, publish)
                    .redacting(boundTo(bound))
                    .growing(growth);
            feed.start();
            return feed;
        }
        // A feed thread only for the streams that kept a reader of their own. A query whose sources
        // are all shared has none at all, which is the other half of what sharing buys.
        PumpingFeed unshared = pumps.isEmpty()
                ? null
                : new PumpingFeed(queryName, pumps, inputs, resources, description, publish)
                        .redacting(boundTo(bound))
                        .growing(growth);
        List<AutoCloseable> owned = new ArrayList<>(sharedResources);
        List<SharedSourceGroup> held = List.copyOf(joined);
        SharedFeed feed =
                new SharedFeed(members, held, () -> release(held), unshared, owned, description).watching(watches);
        feed.start();
        return feed;
    }

    /** The binding of a stream a feed was asked to read, which the caller has already found bound. */
    private SourceBinding bindingOf(String stream) {
        return java.util.Objects.requireNonNull(bindings.get(stream), "a bound stream has a binding");
    }

    /** The bindings of these streams, for {@link FeedRedaction}. */
    private List<SourceBinding> boundTo(List<String> streams) {
        return streams.stream()
                .map(bindings::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Opens a feed that reads history to the seam and then joins the live stream (ADR-046).
     *
     * <p>Three differences from {@link #open}, and each is the point of the operation rather than
     * an optimisation.
     *
     * <ul>
     *   <li><strong>Its own readers.</strong> A shared reader (SRC-3) hands a joining consumer the
     *       fan-out first and its history afterwards, which duplicates the overlap -- acceptable
     *       for an at-least-once source and not for a replacement, whose whole claim is that the
     *       new version's answer is the one it would have had if it had always been running.
     *   <li><strong>Each reader is spliced.</strong> History from the beginning to the position the
     *       running version has reached, then a reader created at that position: see {@link
     *       OffsetSplicedReader} for why the seam is exact.
     *   <li><strong>The history is throttled</strong> by the job, and the live phase is not.
     * </ul>
     *
     * <p>A checkpointed position taken during the history carries its seam, so a restart resumes
     * the same backfill rather than starting a different one; a plain position means the seam is
     * behind this partition and only the live reader is opened.
     */
    @Override
    public SourceFeed openBackfill(
            String queryName,
            QueryExecution execution,
            List<String> sourceStreams,
            Runnable afterDelivery,
            Map<String, String> resumeFrom,
            com.ash.messaging.pravaha.backfill.BackfillPlan plan) {
        List<String> bound =
                sourceStreams.stream().distinct().filter(bindings::containsKey).toList();
        if (bound.isEmpty()) {
            throw new PravahaException(
                    com.ash.messaging.pravaha.backfill.BackfillErrors.SOURCE_UNSUPPORTED,
                    "nothing is bound to any stream '" + queryName + "' reads, so there is no history to replay "
                            + "and no live stream to splice onto.");
        }
        List<IngestPump> pumps = new ArrayList<>();
        List<FeedInput> inputs = new ArrayList<>();
        List<AutoCloseable> resources = new ArrayList<>();
        Map<String, Integer> partitionCounts = new LinkedHashMap<>();
        Map<String, String> pushed = new LinkedHashMap<>();
        Runnable publish = serialised(afterDelivery);
        ResumePositions positions = ResumePositions.of(resumeFrom);
        List<PartitionGrowth> growth = new ArrayList<>();
        try {
            for (String stream : bound) {
                SourceBinding binding = bindingOf(stream);
                StreamSourcePlugin plugin = openPlugin(binding);
                resources.add(plugin);
                // Rows, never a partial: a spliced reader hands its records to the history reader
                // and the live one in turn, and a pre-combined partial from one phase cannot be
                // folded in beside rows from the other.
                ReadRequest request = SourcePushdown.requestFor(execution.plan(), stream, plugin.capabilities())
                        .withoutAggregates();
                List<SourcePartition> partitions = plugin.partitions(stream);
                partitionCounts.put(stream, partitions.size());
                pushed.put(stream, summarise(request) + ", backfilling" + sourceSays(plugin, request));
                for (int index = 0; index < partitions.size(); index++) {
                    SourcePartition partition = partitions.get(index);
                    inputs.add(new FeedInput(stream, index));
                    String token = positions.tokenFor(stream, partition.index());
                    SourceOffset splice = plan.spliceFor(stream, index).orElse(null);
                    SourceOffset from = SourceOffset.BEGINNING;
                    boolean historyDone = false;
                    if (token != null && OffsetSplicedReader.isBackfillToken(token)) {
                        splice = OffsetSplicedReader.spliceOf(token);
                        from = OffsetSplicedReader.historyOf(token);
                    } else if (token != null) {
                        from = new SourceOffset(token);
                        historyDone = true;
                    } else if (!plan.readHistory() && splice != null) {
                        // backfill = none: start where the running version is, with empty state.
                        from = splice;
                    }
                    PartitionReader reader = new OffsetSplicedReader(
                            at -> plugin.createReader(partition, at, request), from, splice, plan.job(), historyDone);
                    resources.add(reader);
                    IngestPump pump = execution.pumpInto(0, stream, partition.index(), reader, policy);
                    attachDeadLetters(pump, queryName, resources);
                    // B5, on the backfill path too. A spliced reader does not answer hasReadPast --
                    // it is mid-history by construction -- so for an exactly-once source this makes
                    // a replay refuse, which is the right way round: the backfill is going to read
                    // that offset itself, and feeding the record in now would count it twice.
                    pump.sourceGuarantee(plugin.capabilities().guarantee());
                    pumps.add(pump);
                }
                // A partition gained mid-backfill has no history from before the replacement began,
                // and is read live from its first record, as the running version reads it.
                watch(
                        plugin,
                        stream,
                        request,
                        partitions,
                        growth,
                        (index, reader, opened) ->
                                unsharedPump(execution, queryName, stream, plugin, index, reader, opened));
            }
        } catch (RuntimeException e) {
            closeQuietly(resources);
            throw e;
        }
        PumpingFeed feed = new PumpingFeed(
                        queryName, pumps, inputs, resources, describe(partitionCounts, pushed), publish)
                .growing(growth);
        feed.start();
        return feed;
    }

    /**
     * Opens every bound partition for reading by hand, from a checkpoint's offsets (ADR-048).
     *
     * <p>Readers of its own, never a shared one: a shared reader fans one poll out to every query
     * on it, and a debugger pulling a row at a time from it would either starve the live queries
     * or take rows they were about to be handed. A fork must not be able to affect the query it
     * forked from, and this is the sharpest way it could.
     *
     * <p>The refusals are the ones {@link #backfillRefusal} gives, for the same reasons and
     * with the same words, because "can this be rewound to that position" is one question. They
     * are raised before any reader opens, so a fork that cannot be honest is refused rather than
     * started and quietly reading from the present.
     */
    @Override
    public ReplaySource replayFrom(String queryName, List<String> sourceStreams, Map<String, String> from) {
        List<String> wanted = sourceStreams.stream().distinct().toList();
        for (String stream : wanted) {
            backfillRefusal(stream).ifPresent(why -> {
                throw new PravahaException(
                        com.ash.messaging.pravaha.registry.DebugErrors.SOURCE_NOT_REPLAYABLE,
                        "'" + queryName + "' cannot be forked for debugging because " + why + ". A debug "
                                + "session restores a checkpoint and reads the sources from the offsets it "
                                + "recorded, so every stream it reads has to be rewindable to them.");
            });
        }
        List<PluginReplaySource.PartitionSpec> specs = new ArrayList<>();
        List<AutoCloseable> opened = new ArrayList<>();
        ResumePositions positions = ResumePositions.of(from);
        try {
            for (String stream : wanted) {
                SourceBinding binding = bindingOf(stream);
                StreamSourcePlugin plugin = openPlugin(binding);
                opened.add(plugin);
                StreamSchema schema = plugin.discoverSchemas().stream()
                        .filter(candidate -> candidate.name().equals(stream))
                        .findFirst()
                        .orElseGet(() -> plugin.discoverSchemas().get(0));
                List<SourcePartition> partitions = plugin.partitions(stream);
                for (int index = 0; index < partitions.size(); index++) {
                    String token =
                            positions.tokenFor(stream, partitions.get(index).index());
                    SourceOffset at =
                            token == null || token.isBlank() ? SourceOffset.BEGINNING : new SourceOffset(token);
                    // No pushdown: a debugger shows the row as the source has it. See
                    // PluginReplaySource.
                    PartitionReader reader = plugin.createReader(partitions.get(index), at);
                    opened.add(reader);
                    specs.add(new PluginReplaySource.PartitionSpec(stream, index, schema, reader));
                }
            }
        } catch (RuntimeException e) {
            closeQuietly(opened);
            throw e;
        }
        if (specs.isEmpty()) {
            throw new PravahaException(
                    com.ash.messaging.pravaha.registry.DebugErrors.SOURCE_NOT_REPLAYABLE,
                    "nothing is bound to any stream '" + queryName + "' reads, so there are no rows to step "
                            + "through: its rows are pushed in by an embedder rather than read from a source.");
        }
        return new PluginReplaySource(wanted, specs);
    }

    /**
     * Why a replacement's backfill cannot read {@code stream}, or empty when it can (ADR-046).
     *
     * <p>Asked before a replacement starts, so an operator is told which stream and why rather than
     * watching a backfill that will never finish. A source that cannot be read again from a
     * position it handed out has no history to replay; one whose positions do not order the records
     * within a partition -- a scan of a table, which reports where its pass started -- has no seam
     * to splice at, and reading past one would deliver the overlap twice.
     */
    @Override
    public java.util.Optional<String> backfillRefusal(String stream) {
        SourceBinding binding = bindings.get(stream);
        if (binding == null) {
            return java.util.Optional.of("nothing is bound to '" + stream + "', so its rows are pushed in rather "
                    + "than read from a source: there is no history to replay and no position to splice at");
        }
        StreamSourcePlugin plugin = configure(binding);
        try {
            com.ash.messaging.pravaha.api.plugin.SourceCapabilities capabilities = plugin.capabilities();
            if (!capabilities.replayableOffsets()) {
                return java.util.Optional.of("the '" + binding.plugin() + "' source bound to '" + stream
                        + "' cannot be read again from a position it handed out, so there is no history to "
                        + "replay into the new version");
            }
            if (!capabilities.orderedWithinPartition()) {
                return java.util.Optional.of("the '" + binding.plugin() + "' source bound to '" + stream
                        + "' does not order the records within a partition -- its position names where a scan "
                        + "began rather than the record it was taken after -- so there is no offset the "
                        + "history and the live stream can meet at exactly");
            }
            // CDCREPL-1: resumable by the one reader that holds it is not readable by a second.
            java.util.Optional<String> oneReader = plugin.secondReaderRefusal();
            if (oneReader.isPresent()) {
                return java.util.Optional.of(
                        "the '" + binding.plugin() + "' source bound to '" + stream + "' " + oneReader.get());
            }
            return java.util.Optional.empty();
        } finally {
            closeQuietly(List.of(plugin));
        }
    }

    /**
     * Claims a single-consumer binding for {@code queryName}, or refuses because another query holds
     * it (CDCREPL-2); the returned handle gives the claim back when the feed closes.
     *
     * <p>Refused rather than given a slot of its own derived from the binding. A slot retains WAL on
     * the database until it is confirmed, so a slot per query would multiply what the database keeps
     * by the number of queries, invisibly to whoever sized its disk, and every slot outliving its
     * query -- a node that dies before a drop, a drop while the database is unreachable -- would
     * retain WAL until the disk filled. A binding is where an operator declares a slot and sizes for
     * it; a second query over the same table gets a second binding, with a slot the operator named.
     */
    private AutoCloseable claimSoleReader(
            String queryName, String stream, SourceBinding binding, StreamSourcePlugin configured) {
        java.util.Optional<String> oneReader = configured.secondReaderRefusal();
        if (oneReader.isEmpty()) {
            return () -> {};
        }
        sharing.lock();
        try {
            String holder = soleReaders.get(binding);
            if (holder != null) {
                throw new PravahaException(
                        com.ash.messaging.pravaha.registry.RegistryErrors.SOURCE_HELD,
                        "'" + queryName + "' cannot read stream '" + stream + "': query '" + holder + "' is already "
                                + "reading it, and the '" + binding.plugin() + "' source bound to it "
                                + oneReader.get() + ". A second, different query over the same table needs a "
                                + "second binding -- the table bound again under another stream name, with a "
                                + "slot (or server.id) of its own -- or a query over '" + holder + "''s view.");
            }
            soleReaders.put(binding, queryName);
        } finally {
            sharing.unlock();
        }
        return () -> {
            sharing.lock();
            try {
                soleReaders.remove(binding, queryName);
            } finally {
                sharing.unlock();
            }
        };
    }

    /**
     * The group of queries reading one binding, or null when this one keeps a reader of its own.
     *
     * <p>Null for three reasons, and they are different: the deployment turned sharing off for this
     * binding, the plan's scan emits a layout no existing group is fanning out, or the plugin
     * declares a guarantee sharing cannot keep ({@link SharedSourceGroup#whyNotShared}).
     */
    private @Nullable SharedSourceGroup groupFor(String stream, SourceBinding binding, QueryExecution execution) {
        // The escape hatch. A shared reader pushes the OR of its members' filters (ADR-039 item 6),
        // which is wider than any one of them: a deployment running one query against a set it
        // cares about may want its own narrower filter more than it wants the sharing.
        if (!Boolean.parseBoolean(binding.options().getOrDefault("share.reader", "true"))) {
            return null;
        }
        StreamSchema scanned = scanSchemaOf(execution.plan(), stream);
        if (scanned == null) {
            return null;
        }
        SharedSourceGroup.Key key = new SharedSourceGroup.Key(stream, binding, scanned);
        sharing.lock();
        try {
            if (unshareable.containsKey(binding)) {
                return null;
            }
            SharedSourceGroup group = groups.get(key);
            if (group == null) {
                // Configured but not yet opened: what a plugin can promise is a property of its
                // configuration, and asking before connecting means a source that cannot share does
                // not pay for a connection this throws away.
                StreamSourcePlugin plugin = configure(binding);
                String why = SharedSourceGroup.whyNotShared(plugin.capabilities(), plugin.orderedPositions());
                if (why != null) {
                    unshareable.put(binding, why);
                    closeQuietly(List.of(plugin));
                    return null;
                }
                openConfigured(plugin, binding);
                group = new SharedSourceGroup(key, plugin, plugin.partitions(stream), policy);
                groups.put(key, group);
            }
            group.retain();
            return group;
        } finally {
            sharing.unlock();
        }
    }

    /**
     * Rows every shared reader has read, and the copies of them it wrote into lanes: one per lane of
     * its own that a member holds, and one per shared lane however many members are on it (LANE-2).
     * The ratio is what sharing a lane's ingest saves.
     */
    long[] sharedRowsReadAndCopiesWritten() {
        long[] total = new long[2];
        sharing.lock();
        try {
            for (SharedSourceGroup group : groups.values()) {
                long[] each = group.rowsReadAndCopiesWritten();
                total[0] += each[0];
                total[1] += each[1];
            }
        } finally {
            sharing.unlock();
        }
        return total;
    }

    /** Queries still reading history of their own from a shared source: a join or resume settling. */
    int catchUpsInFlight() {
        sharing.lock();
        try {
            return groups.values().stream()
                    .mapToInt(SharedSourceGroup::catchingUp)
                    .sum();
        } finally {
            sharing.unlock();
        }
    }

    /** Gives back one query's hold on the groups it joined, closing any nobody is left reading. */
    private void release(List<SharedSourceGroup> held) {
        sharing.lock();
        try {
            for (SharedSourceGroup group : held) {
                if (group.release()) {
                    groups.remove(group.key());
                    group.close();
                }
            }
        } finally {
            sharing.unlock();
        }
    }

    /**
     * The layout this plan's scan of {@code stream} emits, or null when it does not scan it.
     *
     * <p>Part of the sharing key rather than an assumption, because a pushed projection makes two
     * scans of one set emit different rows -- see {@link SharedSourceGroup.Key}.
     */
    private static @Nullable StreamSchema scanSchemaOf(
            com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan, String stream) {
        if (plan instanceof com.ash.messaging.pravaha.runtime.plan.ScanOperator scan
                && scan.streamName().equals(stream)) {
            return scan.outputSchema();
        }
        for (com.ash.messaging.pravaha.runtime.plan.PhysicalOperator input : plan.inputs()) {
            StreamSchema found = scanSchemaOf(input, stream);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Serialises whatever publishes a query's frontier.
     *
     * <p>A query reading one shared stream and one of its own is now published from two threads. It
     * was published from one, so nothing on that path was ever written to expect two, and the cost
     * of saying so here is an uncontended lock fifty times a second. A ReentrantLock and not a
     * monitor: what it serialises is a commit, which hands rows to every sink -- network I/O for most
     * -- on a feed's virtual thread, and on JDK 21 a virtual thread blocked inside a monitor pins its
     * carrier (ADR-062).
     */
    private static Runnable serialised(Runnable afterDelivery) {
        Runnable delegate = afterDelivery == null ? () -> {} : afterDelivery;
        java.util.concurrent.locks.ReentrantLock publishing = new java.util.concurrent.locks.ReentrantLock();
        return () -> {
            publishing.lock();
            try {
                delegate.run();
            } finally {
                publishing.unlock();
            }
        };
    }

    private StreamSourcePlugin openPlugin(SourceBinding binding) {
        return openConfigured(configure(binding), binding);
    }

    /**
     * Finds the plugin and hands it its configuration, without connecting to anything.
     *
     * <p>Split from {@link #openConfigured} for SRC-3: what a source can promise -- and so whether
     * one reader of it may feed several queries -- is a property of its configuration, and asking
     * before opening means a source that turns out not to be shareable has not opened a connection
     * that is then thrown away.
     */
    private StreamSourcePlugin configure(SourceBinding binding) {
        StreamSourcePlugin plugin = discover(binding);
        try {
            plugin.configure(new BindingContext(binding));
            return plugin;
        } catch (RuntimeException e) {
            closeQuietly(List.of(plugin));
            throw new PravahaException(
                    IngestErrors.BINDING_FAILED,
                    "the '" + binding.plugin() + "' plugin could not be opened for stream '" + binding.streamName()
                            + "': " + e + descriptorHint(),
                    e);
        } catch (Exception e) {
            closeQuietly(List.of(plugin));
            throw new PravahaException(
                    IngestErrors.BINDING_FAILED,
                    "the '" + binding.plugin() + "' plugin refused its configuration for stream '"
                            + binding.streamName() + "': " + e + descriptorHint(),
                    e);
        }
    }

    private StreamSourcePlugin openConfigured(StreamSourcePlugin plugin, SourceBinding binding) {
        try {
            plugin.open();
            return plugin;
        } catch (RuntimeException e) {
            closeQuietly(List.of(plugin));
            throw new PravahaException(
                    IngestErrors.BINDING_FAILED,
                    "the '" + binding.plugin() + "' plugin could not be opened for stream '" + binding.streamName()
                            + "': " + e + descriptorHint(),
                    e);
        } catch (Exception e) {
            closeQuietly(List.of(plugin));
            throw new PravahaException(
                    IngestErrors.BINDING_FAILED,
                    "the '" + binding.plugin() + "' plugin refused its configuration for stream '"
                            + binding.streamName() + "': " + e + descriptorHint(),
                    e);
        }
    }

    /**
     * What to add to an open failure when descriptor exhaustion is the likelier explanation.
     *
     * <p>Appended rather than substituted, because the plugin's own message may well be right. It is
     * a sentence and not a refusal for the same reason: this cannot know, and a node that refused to
     * bind on a guess would be worse than one that binds and explains.
     *
     * <p>The case it exists for is the Aerospike one. Under a low {@code ulimit -n} the client
     * reports "cannot reach Aerospike ... check the host list, that the cluster is up, and that this
     * process can reach the service port" -- where the cluster is up, the host list is right, the
     * port is reachable, and all three remedies are wrong. It cannot be fixed by reading the cause,
     * because the client throws that away. Looking at the descriptor count is the only way to tell
     * the two apart (SRC-4).
     */
    private static String descriptorHint() {
        return com.ash.messaging.pravaha.common.io.FileDescriptors.exhaustionHint()
                .map(hint -> " " + hint)
                .orElse("");
    }

    /**
     * Finds the named plugin on the classpath.
     *
     * <p>A fresh instance per binding rather than one shared: two streams reading different
     * directories are two configurations of one plugin class, and {@code configure} is called once
     * per instance.
     */
    private StreamSourcePlugin discover(SourceBinding binding) {
        // PKG-3: each provider is taken on its own, so one that cannot be loaded no longer ends
        // discovery for every source plugin; it is refused by name, with its cause, only when it
        // could be the one asked for.
        PluginDiscovery.Found<StreamSourcePlugin> found =
                PluginDiscovery.find(StreamSourcePlugin.class, binding.plugin());
        if (found.plugin() != null) {
            return found.plugin();
        }
        if (!found.failures().isEmpty()) {
            throw PluginDiscovery.loadFailed("source", binding.plugin(), found);
        }
        List<String> available = found.available();
        // CFG-4 said what "available" means: on THIS process's classpath. PLUGINLATE-1: it also
        // said the server jar carried filesystem alone, right after listing the nine it carries --
        // so the list is now the only statement of what is here, and the sentence says what to do
        // about a name that is not in it.
        throw new PravahaException(
                IngestErrors.NO_SUCH_PLUGIN,
                "no source plugin named '" + binding.plugin() + "' is on the classpath, so stream '"
                        + binding.streamName() + "' cannot be fed. Available: "
                        + (available.isEmpty() ? "none -- no source plugin jar is on the classpath" : available)
                        + ". A plugin answers to the name it reports for itself and is found by "
                        + "ServiceLoader on THIS process's classpath, so that list is every source plugin "
                        + "this process carries. Check the name against it, or put the module that ships '"
                        + binding.plugin() + "' on the classpath; docs/guides/CONNECTORS.md says which "
                        + "module ships which name.");
    }

    /** A pump of this query's own on {@code reader}, with its dead letters and its source's promise. */
    private IngestPump unsharedPump(
            QueryExecution execution,
            String queryName,
            String stream,
            StreamSourcePlugin plugin,
            int partition,
            PartitionReader reader,
            List<AutoCloseable> resources) {
        // Lane 0: a registered query is compiled onto one lane today. When that changes, the
        // partition index is what chooses the lane -- it is already the unit the source split
        // itself into.
        IngestPump pump = execution.pumpInto(0, stream, partition, reader, policy);
        attachDeadLetters(pump, queryName, resources);
        // What the source promises, so a replay can refuse where re-feeding a record would count it
        // twice (B5).
        pump.sourceGuarantee(plugin.capabilities().guarantee());
        return pump;
    }

    /** The pump a shared reader feeds one partition of one query through. */
    private IngestPump sharedPump(
            QueryExecution execution,
            String queryName,
            String stream,
            int partition,
            PartitionReader reader,
            SharedSourceGroup group,
            List<AutoCloseable> sharedResources) {
        IngestPump pump = execution.pumpInto(0, stream, partition, reader, policy);
        attachDeadLetters(pump, queryName, sharedResources);
        // What the source promises, so a replay can refuse where re-feeding a record would count it
        // twice (B5).
        pump.sourceGuarantee(group.plugin().capabilities().guarantee());
        return pump;
    }

    /** Adds a watch for partitions {@code plugin} gains, when it says it may gain any. */
    private static void watch(
            StreamSourcePlugin plugin,
            String stream,
            ReadRequest request,
            List<SourcePartition> partitions,
            List<PartitionGrowth> growth,
            PartitionGrowth.PumpFactory factory) {
        java.time.Duration interval = plugin.partitionRefreshInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return;
        }
        List<Integer> known = partitions.stream().map(SourcePartition::index).toList();
        growth.add(new PartitionGrowth(stream, plugin, request, known, interval, factory, System.nanoTime()));
    }

    private static String describe(Map<String, Integer> partitionCounts, Map<String, String> pushed) {
        StringBuilder text = new StringBuilder("reading ");
        partitionCounts.forEach((stream, count) -> text.append(stream)
                .append(" (")
                .append(count)
                .append(count == 1 ? " partition" : " partitions")
                .append(pushed.getOrDefault(stream, ""))
                .append("), "));
        text.setLength(text.length() - 2);
        return text.toString();
    }

    /**
     * "; pushed to Cassandra: partition key id = 7": what the source says it asks its store for,
     * beside the offer, or nothing when it says nothing. A source that fails to say is not a reason to
     * fail a registration, so its failure is reported in the text instead.
     */
    static String sourceSays(StreamSourcePlugin plugin, ReadRequest request) {
        String said;
        try {
            said = plugin.describePushdown(request);
        } catch (RuntimeException e) {
            said = "the source could not describe its pushdown: " + e.getMessage();
        }
        return said == null || said.isBlank() ? "" : "; " + said;
    }

    /**
     * What a request asks its source for, in words: "; pushed 2 filters, 3 columns", or nothing
     * when nothing was pushed. What the source actually honoured is its own business -- a filter it
     * could not express is simply not applied there -- so this reports the offer.
     */
    static String summarise(ReadRequest request) {
        List<String> parts = new ArrayList<>(3);
        if (!request.filters().isEmpty()) {
            parts.add(request.filters().size() + (request.filters().size() == 1 ? " filter" : " filters"));
        }
        if (!request.alternatives().isEmpty()) {
            parts.add("an OR of " + request.alternatives().size());
        }
        if (!request.columns().isEmpty()) {
            parts.add(request.columns().size() + (request.columns().size() == 1 ? " column" : " columns"));
        }
        return parts.isEmpty() ? "" : "; pushed " + String.join(", ", parts);
    }

    /** Gives one pump a dead-letter file, when a directory is configured. */
    private void attachDeadLetters(
            com.ash.messaging.pravaha.runtime.ingest.IngestPump pump, String queryName, List<AutoCloseable> resources) {
        java.nio.file.Path directory = deadLetterDirectory;
        if (directory == null) {
            return;
        }
        LiveDeadLetters live = liveDeadLetters(directory, queryName, resources);
        pump.deadLetteringTo(live.queue(), queryName);
        pump.deadLetterRate(live.rate());
    }

    /**
     * The query's open queue and rate, opened when nothing has opened them yet, with what closes them
     * added to {@code resources}.
     */
    private LiveDeadLetters liveDeadLetters(
            java.nio.file.Path directory, String queryName, List<AutoCloseable> resources) {
        try {
            java.nio.file.Files.createDirectories(directory);
            // One file per query, named for it: a shared file would make "which query rejected
            // this" a question you answer by reading, and the queryId is already on every entry.
            java.nio.file.Path file =
                    com.ash.messaging.pravaha.runtime.dlq.DeadLetterFiles.letters(directory, queryName);
            // One queue and one rate per query, however many pumps it has: the file is the
            // query's, and four partitions each keeping their own rejection window would each
            // decide the query was degraded on a quarter of the evidence.
            return liveDeadLetters.computeIfAbsent(queryName, name -> {
                try {
                    com.ash.messaging.pravaha.runtime.dlq.FileDeadLetterQueue opened =
                            new com.ash.messaging.pravaha.runtime.dlq.FileDeadLetterQueue(file, deadLetterRetention);
                    resources.add(opened);
                    resources.add(() -> liveDeadLetters.remove(name));
                    return new LiveDeadLetters(opened, com.ash.messaging.pravaha.runtime.dlq.DeadLetterRate.defaults());
                } catch (java.io.IOException cannot) {
                    throw new java.io.UncheckedIOException(cannot);
                }
            });
        } catch (java.io.UncheckedIOException wrapped) {
            // computeIfAbsent cannot throw a checked exception, so opening the file wraps its
            // IOException; it is unwrapped here so the refusal names the cause and not the wrapper.
            throw unusable(
                    directory,
                    java.util.Objects.requireNonNull(wrapped.getCause(), "an UncheckedIOException has its cause"));
        } catch (java.io.IOException | RuntimeException cannot) {
            throw unusable(directory, cannot);
        }
    }

    /**
     * Refused rather than degraded.
     *
     * <p>An operator who set {@code pravaha.dlq.directory} asked for records to be kept; carrying
     * on without one would silently give them the behaviour they were trying to leave, which is
     * the failure this whole finding is about.
     */
    private static com.ash.messaging.pravaha.api.PravahaException unusable(
            java.nio.file.Path directory, Throwable cannot) {
        return new com.ash.messaging.pravaha.api.PravahaException(
                com.ash.messaging.pravaha.state.StateErrors.DLQ_UNUSABLE,
                "pravaha.dlq.directory is " + directory + " and this node cannot write there: "
                        + (cannot == null ? "" : cannot.getMessage())
                        + ". Fix the path or unset the key -- starting without the "
                        + "queue would give you the behaviour you configured it to avoid.",
                cannot);
    }

    private static void closeQuietly(List<? extends AutoCloseable> resources) {
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception e) {
                // Already unwinding. Reporting this would replace the failure that caused the
                // unwind with a failure to tidy up after it, which is the less useful of the two.
            }
        }
    }

    /**
     * A plugin's view of its binding.
     *
     * <p>Package-private rather than private: a lookup plugin is configured from exactly the same
     * binding, and a second copy of this would be a second place for the two to drift.
     */
    record BindingContext(SourceBinding binding) implements PluginContext {

        @Override
        public Map<String, String> config() {
            return binding.options();
        }

        @Override
        public String instanceName() {
            // The stream, not the plugin: two streams read by one plugin are two instances, and the
            // stream name is what tells them apart in a log line.
            return binding.streamName();
        }
    }
}
