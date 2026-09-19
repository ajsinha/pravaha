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
package com.ash.messaging.pravaha.spring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pravaha.*} for an engine embedded in a Spring Boot application.
 *
 * <p>The same blocks, with the same spelling, as the server's {@code application.yaml} -- {@code
 * streams}, {@code sources}, {@code lookups}, {@code sinks}, {@code registry.journal}, {@code
 * checkpoint.*}, {@code dlq.directory}, {@code watermark.*} -- plus {@code queries}, which the
 * embedded engine registers at start. One file describes a deployment whichever mode runs it.
 *
 * <pre>
 * pravaha:
 *   streams:
 *     txn:
 *       schema: "user_id:STRING,amount:INT64,ts:TIMESTAMP"
 *       event-time: ts
 *   queries:
 *     big_txn:
 *       sql: "SELECT user_id, amount FROM txn WHERE amount &gt; 100"
 *       keys: [user_id]
 * </pre>
 *
 * <p>The prefix is {@code pravaha} and each block is a field, for the reason the server's {@code
 * SourceBindingProperties} records: with {@code prefix = "pravaha.sources"} a map field would bind
 * {@code pravaha.sources.<field>.<name>}, and the documented {@code pravaha.sources.<name>} would
 * bind nothing at all.
 */
@ConfigurationProperties(prefix = "pravaha")
public class PravahaProperties {

    /** Whether to create an engine at all. */
    private boolean enabled = true;

    private final Node node = new Node();
    private final Map<String, Stream> streams = new LinkedHashMap<>();
    private final Map<String, Binding> sources = new LinkedHashMap<>();
    private final Map<String, Binding> lookups = new LinkedHashMap<>();
    private final Map<String, Binding> sinks = new LinkedHashMap<>();
    private final Map<String, Query> queries = new LinkedHashMap<>();
    private final Registry registry = new Registry();
    private final Checkpoint checkpoint = new Checkpoint();
    private final Dlq dlq = new Dlq();
    private final Watermark watermark = new Watermark();
    private final Listener listener = new Listener();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Node getNode() {
        return node;
    }

    public Map<String, Stream> getStreams() {
        return streams;
    }

    public Map<String, Binding> getSources() {
        return sources;
    }

    public Map<String, Binding> getLookups() {
        return lookups;
    }

    public Map<String, Binding> getSinks() {
        return sinks;
    }

    public Map<String, Query> getQueries() {
        return queries;
    }

    public Registry getRegistry() {
        return registry;
    }

    public Checkpoint getCheckpoint() {
        return checkpoint;
    }

    public Dlq getDlq() {
        return dlq;
    }

    public Watermark getWatermark() {
        return watermark;
    }

    public Listener getListener() {
        return listener;
    }

    /** {@code pravaha.node.*}. */
    public static class Node {

        /** Names this engine in logs and in the ownership marker of any directory it writes. */
        private String id = "pravaha-embedded";

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }

    /** {@code pravaha.streams.<name>.*}: a stream's shape. */
    public static class Stream {

        /** {@code name:TYPE,...}, the spelling the CLI and the server use. */
        private String schema;

        /** The column carrying event time; without one no window over this stream ever closes. */
        private String eventTime;

        /** How late this stream's rows may be. */
        private Duration outOfOrderness;

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public String getEventTime() {
            return eventTime;
        }

        public void setEventTime(String eventTime) {
            this.eventTime = eventTime;
        }

        public Duration getOutOfOrderness() {
            return outOfOrderness;
        }

        public void setOutOfOrderness(Duration outOfOrderness) {
            this.outOfOrderness = outOfOrderness;
        }
    }

    /** {@code pravaha.sources|lookups|sinks.<name>.*}: a plugin, by the name it reports, and its options. */
    public static class Binding {

        private String plugin;
        private Map<String, String> options = new LinkedHashMap<>();

        public String getPlugin() {
            return plugin;
        }

        public void setPlugin(String plugin) {
            this.plugin = plugin;
        }

        public Map<String, String> getOptions() {
            return options;
        }

        public void setOptions(Map<String, String> options) {
            this.options = options == null ? new LinkedHashMap<>() : options;
        }
    }

    /** {@code pravaha.queries.<name>.*}: a continuous query registered when the engine starts. */
    public static class Query {

        private String sql;

        /** The output columns the view is keyed by. */
        private List<String> keys = new ArrayList<>();

        /** A bound sink the changelog is also written to. */
        private String sink;

        /** How long the view remembers; unset for the engine's default. */
        private Duration retention;

        public String getSql() {
            return sql;
        }

        public void setSql(String sql) {
            this.sql = sql;
        }

        public List<String> getKeys() {
            return keys;
        }

        public void setKeys(List<String> keys) {
            this.keys = keys == null ? new ArrayList<>() : keys;
        }

        public String getSink() {
            return sink;
        }

        public void setSink(String sink) {
            this.sink = sink;
        }

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }
    }

    /** {@code pravaha.registry.*}. */
    public static class Registry {

        /** A file: registrations written here come back after a restart. Unset keeps them in memory. */
        private String journal;

        public String getJournal() {
            return journal;
        }

        public void setJournal(String journal) {
            this.journal = journal;
        }
    }

    /** {@code pravaha.checkpoint.*}. */
    public static class Checkpoint {

        /** A directory: each query's state is checkpointed under it. Unset keeps no checkpoints. */
        private String directory;

        private Duration interval = Duration.ofMinutes(1);
        private int keep = 3;
        private Duration timeout = Duration.ofSeconds(30);

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public int getKeep() {
            return keep;
        }

        public void setKeep(int keep) {
            this.keep = keep;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    /** {@code pravaha.dlq.*}. */
    public static class Dlq {

        /** Where records a source cannot decode are written, instead of stopping the source. */
        private String directory;

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }
    }

    /** {@code pravaha.watermark.*}, for queries fed by a bound source. */
    public static class Watermark {

        private Duration idleAfter = Duration.ofSeconds(30);
        private Duration tick = Duration.ofSeconds(1);

        public Duration getIdleAfter() {
            return idleAfter;
        }

        public void setIdleAfter(Duration idleAfter) {
            this.idleAfter = idleAfter;
        }

        public Duration getTick() {
            return tick;
        }

        public void setTick(Duration tick) {
            this.tick = tick;
        }
    }

    /** {@code pravaha.listener.*}, for {@code @PravahaListener} methods. */
    public static class Listener {

        /**
         * Commits a listener thread may have waiting before the listener is detached. Bounded so a
         * stuck listener costs a known amount of memory and says so, rather than growing until the
         * application falls over.
         */
        private int maxPending = 10_000;

        /**
         * What the auto-configured {@link PravahaListenerErrorHandler} does after a listener method
         * throws: log and go on to the next change, or log and stop the listener. Either way the
         * failure is logged at error with the query and the change, and counted.
         */
        private PravahaListenerErrorHandler.Decision onError = PravahaListenerErrorHandler.Decision.CONTINUE;

        public int getMaxPending() {
            return maxPending;
        }

        public void setMaxPending(int maxPending) {
            this.maxPending = maxPending;
        }

        public PravahaListenerErrorHandler.Decision getOnError() {
            return onError;
        }

        public void setOnError(PravahaListenerErrorHandler.Decision onError) {
            this.onError = onError;
        }
    }
}
