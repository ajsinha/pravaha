/*
 * Tutorial 13 (docs/tutorials/13-writing-a-connector.md): the acme source, a ticker of share prices.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
 */
package com.example.acme;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * The {@code acme} source: Acme Exchange's price ticks, one partition per symbol. Each symbol's ticks
 * are numbered from 1; the offset of a partition is the next tick's number, so a restart resumes where
 * the last checkpoint was. A stand-in for a real feed -- replace {@link AcmeReader#poll} with your
 * client's -- that keeps every part of the contract a real one must keep.
 *
 * <pre>
 * pravaha.sources.ticks.plugin: acme
 * pravaha.sources.ticks.options.symbols: ACME,GLOBEX
 * pravaha.sources.ticks.options.ticks: 6
 * pravaha.sources.ticks.options.start: 2026-10-01T09:00:00Z
 * pravaha.sources.ticks.options.step-seconds: 15
 * </pre>
 */
public final class AcmeSourcePlugin implements StreamSourcePlugin {

    /** A code of the plugin's own, in the plugin range (5000-5999). */
    static final ErrorCode BAD_CONFIGURATION = new ErrorCode(5990, "ACME_BAD_CONFIGURATION");

    private List<String> symbols = List.of();
    private int ticks;
    private Instant start = Instant.EPOCH;
    private long stepSeconds;
    private StreamSchema schema = StreamSchema.builder("unconfigured").field("symbol", Types.string()).build();
    private boolean open;

    @Override
    public String name() {
        return "acme";
    }

    @Override
    public Version version() {
        return new Version(0, 2, 0);
    }

    @Override
    public void configure(PluginContext context) {
        // Every setting is checked here, by name: a bad one must stop the node at start, not the first row.
        String stream = context.get("stream", context.instanceName());
        List<String> named = new ArrayList<>();
        for (String symbol : context.require("symbols").split(",")) {
            if (!symbol.isBlank()) {
                named.add(symbol.strip().toUpperCase(java.util.Locale.ROOT));
            }
        }
        if (named.isEmpty()) {
            throw new ConfigurationException(BAD_CONFIGURATION, "acme.symbols names no symbol; give one or more, comma-separated");
        }
        this.symbols = List.copyOf(named);
        this.ticks = Integer.parseInt(context.get("ticks", "10").strip());
        if (ticks < 1) {
            throw new ConfigurationException(BAD_CONFIGURATION, "acme.ticks is " + ticks + "; it must be at least 1");
        }
        try {
            this.start = Instant.parse(context.get("start", "2026-10-01T09:00:00Z").strip());
        } catch (DateTimeParseException e) {
            throw new ConfigurationException(BAD_CONFIGURATION,
                    "acme.start is not an ISO-8601 instant such as 2026-10-01T09:00:00Z: " + context.get("start", ""));
        }
        this.stepSeconds = Long.parseLong(context.get("step-seconds", "15").strip());
        this.schema = StreamSchema.builder(stream)
                .field("symbol", Types.string())
                .field("seq", Types.int64())
                .field("price", Types.int64())
                .field("event_time", Types.timestamp())
                .build();
    }

    @Override
    public void open() {
        open = true;
    }

    @Override
    public void close() {
        open = false; // idempotent: the engine and the TCK may close twice
    }

    @Override
    public HealthStatus health() {
        return open ? HealthStatus.healthy() : HealthStatus.degraded("not open");
    }

    @Override
    public SourceCapabilities capabilities() {
        // Must answer on an unconfigured instance: `pravaha plugins` asks before configure.
        return new SourceCapabilities(
                true,                          // replayableOffsets: a partition resumes at its next tick
                true,                          // orderedWithinPartition: ticks in order, per symbol
                false,                         // emitsDeletes
                false,                         // emitsBeforeImage
                DeliveryGuarantee.AT_LEAST_ONCE,
                EnumSet.noneOf(PushdownKind.class),
                Duration.ofMillis(1),
                false);                        // repeatsRows: each tick is delivered once
    }

    @Override
    public List<StreamSchema> discoverSchemas() {
        return List.of(schema);
    }

    @Override
    public List<SourcePartition> partitions(String streamName) {
        // One partition per symbol: the engine reads them in parallel and keeps an offset for each.
        List<SourcePartition> partitions = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i++) {
            partitions.add(SourcePartition.of(streamName, i));
        }
        return partitions;
    }

    @Override
    public PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom) {
        // null is "from the configured position" (the first tick); BEGINNING is "".
        long next = resumeFrom == null || resumeFrom.isBeginning() ? 1 : Long.parseLong(resumeFrom.token());
        return new AcmeReader(symbols.get(partition.index()), partition.index(), next, ticks, start, stepSeconds);
    }
}
