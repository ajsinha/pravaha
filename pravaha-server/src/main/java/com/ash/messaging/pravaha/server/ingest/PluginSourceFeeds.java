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
package com.ash.messaging.pravaha.server.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.registry.SourceFeed;
import com.ash.messaging.pravaha.registry.SourceFeedFactory;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;
import com.ash.messaging.pravaha.runtime.plan.Pushdown;

/**
 * Turns configured {@link SourceBinding}s into rows arriving at a registered query.
 *
 * <p>This is the piece that makes a Pravaha server a server. Everything below it existed and was
 * tested -- four source plugins, the pump, backpressure, watermark partitions -- and nothing above
 * it ever called down: the only production code that drove a pump was the CLI's one-shot {@code
 * run}, so a query registered on a server sat at zero rows indefinitely.
 *
 * <p><strong>Plugins are discovered, not compiled in.</strong> A binding names a plugin the way the
 * plugin names itself, and {@link ServiceLoader} finds it on the classpath -- so adding Delta or
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

    private final Map<String, SourceBinding> bindings = new ConcurrentHashMap<>();
    private final BackpressurePolicy policy;
    private volatile java.nio.file.Path deadLetterDirectory;

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

    /** Guards {@link #groups} and {@link #unshareable}. */
    private final Object sharing = new Object();

    public PluginSourceFeeds() {
        this(BackpressurePolicy.defaults());
    }

    public PluginSourceFeeds(BackpressurePolicy policy) {
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
     * Binds a stream to a source, replacing any previous binding.
     *
     * <p>Takes effect for queries registered afterwards. A query already running keeps the feed it
     * opened with, because swapping a source under a live computation would change what its state
     * was accumulated from without changing the state -- and the view would then be a mixture of two
     * sources that nothing records.
     */
    public PluginSourceFeeds bind(SourceBinding binding) {
        bindings.put(binding.streamName(), binding);
        return this;
    }

    /** The bindings in force, by stream name. */
    public Map<String, SourceBinding> bindings() {
        return Map.copyOf(bindings);
    }

    @Override
    public SourceFeed open(
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
        List<AutoCloseable> resources = new ArrayList<>();
        List<AutoCloseable> sharedResources = new ArrayList<>();
        List<SharedPartitionFeed.Member> members = new ArrayList<>();
        List<SharedSourceGroup> joined = new ArrayList<>();
        Map<String, Integer> partitionCounts = new LinkedHashMap<>();
        // One publish at a time, whoever calls it. A query with both a shared stream and an
        // unshared one is now published by two threads -- the group's and its own feed's -- and
        // committing a frontier was only ever done by one.
        Runnable publish = serialised(afterDelivery);
        // Counts partitions across every stream, shared and not, because that is what keys the
        // resume tokens a checkpoint wrote. It used to be pumps.size(), which counted the same
        // thing only while every partition had a pump of this query's own.
        int[] partitionOrdinal = {0};
        try {
            for (String stream : bound) {
                SourceBinding binding = bindings.get(stream);

                // SRC-3. One reader per binding where the source allows it, and the query joins it
                // rather than opening its own.
                SharedSourceGroup group = groupFor(stream, binding, execution);
                if (group != null) {
                    joined.add(group);
                    partitionCounts.put(stream, group.partitionCount());
                    ReadRequest shared = Pushdown.requestFor(
                            execution.plan(), stream, group.plugin().capabilities());
                    for (int index = 0; index < group.partitionCount(); index++) {
                        String token = resumeFrom.get("partition-" + partitionOrdinal[0]++);
                        SourceOffset from = token == null ? SourceOffset.BEGINNING : new SourceOffset(token);
                        members.add(group.feed(index).join(queryName, from, shared, publish, reader -> {
                            IngestPump pump = execution.pumpInto(0, stream, reader, policy);
                            attachDeadLetters(pump, queryName, sharedResources);
                            return pump;
                        }));
                    }
                    continue;
                }

                StreamSourcePlugin plugin = openPlugin(binding);
                resources.add(plugin);

                // Offer the source whatever of the WHERE clause it can evaluate itself. This was
                // missing here for as long as the server path existed: pushdown worked in
                // `pravaha run` and in tests, and a registered query -- the only way anything in
                // production reads a source -- scanned everything and filtered it after the fact.
                // The README's cost-curve claim was true of a code path no deployment used.
                //
                // Safe to offer blindly: the engine keeps its own filter whatever the source does,
                // so this changes how many bytes cross the boundary and nothing else.
                ReadRequest request = Pushdown.requestFor(execution.plan(), stream, plugin.capabilities());

                List<SourcePartition> partitions = plugin.partitions(stream);
                partitionCounts.put(stream, partitions.size());
                for (SourcePartition partition : partitions) {
                    // Resume where the checkpoint left off, when there is one. Reading from the
                    // beginning after a restore would replay every record between the checkpoint and
                    // the failure on top of the state that already counted them.
                    String token = resumeFrom.get("partition-" + partitionOrdinal[0]++);
                    SourceOffset from = token == null ? SourceOffset.BEGINNING : new SourceOffset(token);
                    PartitionReader reader = plugin.createReader(partition, from, request);
                    resources.add(reader);
                    // Lane 0: a registered query is compiled onto one lane today. When that
                    // changes, the partition index is what chooses the lane -- it is already the
                    // unit the source split itself into.
                    com.ash.messaging.pravaha.runtime.ingest.IngestPump pump =
                            execution.pumpInto(0, stream, reader, policy);
                    attachDeadLetters(pump, queryName, resources);
                    pumps.add(pump);
                }
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

        String description = describe(partitionCounts);
        if (members.isEmpty()) {
            // Nothing shared: exactly the feed this returned before SRC-3, including the thread.
            PumpingFeed feed = new PumpingFeed(queryName, pumps, resources, description, publish);
            feed.start();
            return feed;
        }
        // A feed thread only for the streams that kept a reader of their own. A query whose sources
        // are all shared has none at all, which is the other half of what sharing buys.
        PumpingFeed unshared =
                pumps.isEmpty() ? null : new PumpingFeed(queryName, pumps, resources, description, publish);
        List<AutoCloseable> owned = new ArrayList<>(sharedResources);
        List<SharedSourceGroup> held = List.copyOf(joined);
        SharedFeed feed = new SharedFeed(members, held, () -> release(held), unshared, owned, description);
        feed.start();
        return feed;
    }

    /**
     * The group of queries reading one binding, or null when this one keeps a reader of its own.
     *
     * <p>Null for three reasons, and they are different: the deployment turned sharing off for this
     * binding, the plan's scan emits a layout no existing group is fanning out, or the plugin
     * declares a guarantee sharing cannot keep ({@link SharedSourceGroup#whyNotShared}).
     */
    private SharedSourceGroup groupFor(String stream, SourceBinding binding, QueryExecution execution) {
        // The escape hatch. Sharing trades a query's own pushdown for the store's scan count once a
        // second query with a different WHERE clause joins, and a deployment running one query
        // against a set it cares about may want the filter more than the sharing.
        if (!Boolean.parseBoolean(binding.options().getOrDefault("share.reader", "true"))) {
            return null;
        }
        StreamSchema scanned = scanSchemaOf(execution.plan(), stream);
        if (scanned == null) {
            return null;
        }
        SharedSourceGroup.Key key = new SharedSourceGroup.Key(stream, binding, scanned);
        synchronized (sharing) {
            if (unshareable.containsKey(binding)) {
                return null;
            }
            SharedSourceGroup group = groups.get(key);
            if (group == null) {
                // Configured but not yet opened: what a plugin can promise is a property of its
                // configuration, and asking before connecting means a source that cannot share does
                // not pay for a connection this throws away.
                StreamSourcePlugin plugin = configure(binding);
                String why = SharedSourceGroup.whyNotShared(plugin.capabilities());
                if (why != null) {
                    unshareable.put(binding, why);
                    closeQuietly(List.of(plugin));
                    return null;
                }
                openConfigured(plugin, binding);
                group = new SharedSourceGroup(key, plugin, plugin.partitions(stream));
                groups.put(key, group);
            }
            group.retain();
            return group;
        }
    }

    /** Gives back one query's hold on the groups it joined, closing any nobody is left reading. */
    private void release(List<SharedSourceGroup> held) {
        synchronized (sharing) {
            for (SharedSourceGroup group : held) {
                if (group.release()) {
                    groups.remove(group.key());
                    group.close();
                }
            }
        }
    }

    /**
     * The layout this plan's scan of {@code stream} emits, or null when it does not scan it.
     *
     * <p>Part of the sharing key rather than an assumption, because a pushed projection makes two
     * scans of one set emit different rows -- see {@link SharedSourceGroup.Key}.
     */
    private static StreamSchema scanSchemaOf(
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
     * of saying so here is an uncontended monitor fifty times a second.
     */
    private static Runnable serialised(Runnable afterDelivery) {
        Runnable delegate = afterDelivery == null ? () -> {} : afterDelivery;
        return new Runnable() {
            @Override
            public synchronized void run() {
                delegate.run();
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
        List<String> available = new ArrayList<>();
        for (StreamSourcePlugin candidate : ServiceLoader.load(StreamSourcePlugin.class)) {
            if (candidate.name().equalsIgnoreCase(binding.plugin())) {
                return candidate;
            }
            available.add(candidate.name());
            closeQuietly(List.of(candidate));
        }
        throw new PravahaException(
                IngestErrors.NO_SUCH_PLUGIN,
                "no source plugin named '" + binding.plugin() + "' is on the classpath, so stream '"
                        + binding.streamName() + "' cannot be fed. Available: "
                        + (available.isEmpty() ? "none -- no source plugin jar is on the classpath" : available));
    }

    private static String describe(Map<String, Integer> partitionCounts) {
        StringBuilder text = new StringBuilder("reading ");
        partitionCounts.forEach((stream, count) -> text.append(stream)
                .append(" (")
                .append(count)
                .append(count == 1 ? " partition" : " partitions")
                .append("), "));
        text.setLength(text.length() - 2);
        return text.toString();
    }

    /** Gives one pump a dead-letter file, when a directory is configured. */
    private void attachDeadLetters(
            com.ash.messaging.pravaha.runtime.ingest.IngestPump pump, String queryName, List<AutoCloseable> resources) {
        java.nio.file.Path directory = deadLetterDirectory;
        if (directory == null) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(directory);
            // One file per query, named for it: a shared file would make "which query rejected
            // this" a question you answer by reading, and the queryId is already on every entry.
            java.nio.file.Path file = directory.resolve(queryName + ".dlq");
            com.ash.messaging.pravaha.runtime.dlq.FileDeadLetterQueue queue =
                    new com.ash.messaging.pravaha.runtime.dlq.FileDeadLetterQueue(file);
            resources.add(queue);
            pump.deadLetteringTo(queue, queryName);
        } catch (java.io.IOException | RuntimeException cannot) {
            // Refused rather than degraded. An operator who set pravaha.dlq.directory asked for
            // records to be kept; carrying on without one would silently give them the behaviour
            // they were trying to leave, which is the failure this whole finding is about.
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    com.ash.messaging.pravaha.state.StateErrors.DLQ_UNUSABLE,
                    "pravaha.dlq.directory is " + directory + " and this node cannot write there: "
                            + cannot.getMessage() + ". Fix the path or unset the key -- starting without the "
                            + "queue would give you the behaviour you configured it to avoid.",
                    cannot);
        }
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
