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
package com.ash.messaging.pravaha.embedded;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.Configuration;

/**
 * What an engine's {@link Configuration} declares: the same {@code pravaha.streams}, {@code
 * pravaha.sources}, {@code pravaha.lookups}, {@code pravaha.sinks} and {@code pravaha.queries} blocks
 * the server reads, so one configuration file describes a deployment whichever way it is run.
 *
 * <pre>
 * pravaha.streams.txn.schema=user_id:STRING,amount:INT64,ts:TIMESTAMP
 * pravaha.streams.txn.event-time=ts
 * pravaha.sources.txn.plugin=filesystem
 * pravaha.sources.txn.options.path=/var/lib/pravaha/incoming
 * pravaha.sinks.totals.plugin=filesystem
 * pravaha.sinks.totals.options.path=/var/lib/pravaha/out/totals.csv
 * pravaha.queries.spend.sql=SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id
 * pravaha.queries.spend.keys=user_id
 * </pre>
 *
 * <p>An option key is everything after {@code options.}, dots included, because plugin options are
 * dotted ({@code event.time}, {@code key.bin}) and cutting them at the first dot would hand a plugin
 * half a key.
 */
final class Declarations {

    /** A stream as configured: its {@code name:TYPE} spec and, optionally, its event-time column. */
    record StreamSpec(String schema, String eventTime, Duration outOfOrderness) {}

    /** A source, lookup or sink binding as configured. */
    record BindingSpec(String plugin, Map<String, String> options) {}

    final Map<String, StreamSpec> streams = new LinkedHashMap<>();
    final Map<String, BindingSpec> sources = new LinkedHashMap<>();
    final Map<String, BindingSpec> lookups = new LinkedHashMap<>();
    final Map<String, BindingSpec> sinks = new LinkedHashMap<>();
    final List<ContinuousQuery> queries = new ArrayList<>();

    static Declarations from(Configuration configuration) {
        Declarations declared = new Declarations();
        Configuration streams = configuration.subset("pravaha.streams");
        for (String name : names(streams)) {
            Configuration stream = streams.subset(name);
            declared.streams.put(
                    name,
                    new StreamSpec(
                            stream.getString("schema").orElse(null),
                            first(stream, "event-time", "eventTime", "event.time")
                                    .orElse(null),
                            first(stream, "out-of-orderness", "outOfOrderness")
                                    .map(value -> duration(value, "pravaha.streams." + name))
                                    .orElse(null)));
        }
        bindings(configuration.subset("pravaha.sources"), declared.sources);
        bindings(configuration.subset("pravaha.lookups"), declared.lookups);
        bindings(configuration.subset("pravaha.sinks"), declared.sinks);
        Configuration queries = configuration.subset("pravaha.queries");
        for (String name : names(queries)) {
            Configuration query = queries.subset(name);
            ContinuousQuery.Builder builder = ContinuousQuery.named(name)
                    .sql(query.getString("sql").orElse(null))
                    .keyedBy(Arrays.stream(query.getString("keys", "").split(","))
                            .map(String::strip)
                            .filter(key -> !key.isEmpty())
                            .toList());
            query.getString("sink").filter(sink -> !sink.isBlank()).ifPresent(builder::writingTo);
            if (query.has("retention")) {
                builder.retaining(query.getDuration("retention").orElseThrow());
            }
            declared.queries.add(builder.build());
        }
        return declared;
    }

    private static void bindings(Configuration block, Map<String, BindingSpec> into) {
        for (String name : names(block)) {
            Configuration binding = block.subset(name);
            Map<String, String> options = new LinkedHashMap<>();
            Configuration configured = binding.subset("options");
            configured.keys().forEach(key -> options.put(key, configured.getString(key, "")));
            into.put(name, new BindingSpec(binding.getString("plugin").orElse(null), options));
        }
    }

    /** The distinct first segments under a block, in the order they sort. */
    private static List<String> names(Configuration block) {
        List<String> names = new ArrayList<>();
        for (String key : block.keys()) {
            int dot = key.indexOf('.');
            String name = dot < 0 ? key : key.substring(0, dot);
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static Optional<String> first(Configuration block, String... keys) {
        for (String key : keys) {
            Optional<String> value = block.getString(key).filter(v -> !v.isBlank());
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private static Duration duration(String value, String where) {
        try {
            return Configuration.builder()
                    .set("d", value)
                    .build()
                    .getDuration("d")
                    .orElseThrow();
        } catch (RuntimeException e) {
            throw new PravahaException(
                    EmbeddedErrors.MISCONFIGURED,
                    where + ".out-of-orderness is '" + value + "', which is not a duration: " + e.getMessage());
        }
    }
}
