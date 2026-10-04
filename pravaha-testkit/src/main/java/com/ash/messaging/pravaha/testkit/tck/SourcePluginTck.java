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
package com.ash.messaging.pravaha.testkit.tck;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The conformance suite every source plugin must pass.
 *
 * <p>A plugin's {@link SourceCapabilities} is a set of promises the engine acts on: it decides what
 * guarantee to offer a query, what to push down, and whether a maintained view can be trusted to
 * reflect deletes. Those promises are cheap to declare and expensive to get wrong -- a source that
 * claims replayable offsets but cannot actually resume produces silent data loss on the first
 * recovery, and nothing before that moment reveals it.
 *
 * <p>So the TCK does not read the declaration and believe it. <strong>It exercises each claim
 * against the plugin's real behaviour</strong> and fails when they disagree. This matters more here
 * than in most plugin systems because the engine is closed source (design section 30.4): a third party
 * writing a connector cannot read how the engine calls them, so the conformance suite has to be the
 * specification.
 *
 * <p>Extend it and supply the plugin:
 *
 * <pre>{@code
 * class MySourceTck extends SourcePluginTck {
 *     protected StreamSourcePlugin createPlugin() { ... }
 *     protected String streamName() { return "my_stream"; }
 *     protected int expectedRecordCount() { return 3; }
 * }
 * }</pre>
 *
 * <p>Rows are collected by an {@link ArenaRowCollector} unless {@link #newCollector} is overridden.
 * A sink has its own suite, {@link SinkPluginTck}.
 */
public abstract class SourcePluginTck {

    /** A configured, opened plugin with a known fixture behind it. */
    protected abstract StreamSourcePlugin createPlugin();

    /** The stream the fixture provides. */
    protected abstract String streamName();

    /** How many records the fixture holds. Must be at least 3 so resume can be tested meaningfully. */
    protected abstract int expectedRecordCount();

    /**
     * Collects rows a reader produces: by default an {@link ArenaRowCollector} over the plugin's first
     * discovered schema, the way a lane does (TCKCOLLECT-1). Override for a stream with another schema,
     * or for rows wider than {@link ArenaRowCollector#DEFAULT_VARIABLE_BYTES}.
     */
    protected RowCollector newCollector(StreamSourcePlugin plugin) {
        return new ArenaRowCollector(plugin.discoverSchemas().get(0));
    }

    /** What a TCK run needs from a collector. */
    public interface RowCollector extends PartitionReader.RecordSink, AutoCloseable {
        List<RowView> rows();

        @Override
        void close();
    }

    @Test
    void declaresANameAndAVersion() {
        try (StreamSourcePlugin plugin = createPlugin()) {
            assertThat(plugin.name())
                    .as("a plugin name is how configuration refers to it")
                    .isNotBlank();
            assertThat(plugin.name())
                    .as("names appear in configuration keys, so no spaces or upper case")
                    .isEqualTo(plugin.name().toLowerCase(java.util.Locale.ROOT).strip());
            assertThat(plugin.version()).isNotNull();
            assertThat(plugin.requiredApiVersion()).isNotNull();
        }
    }

    @Test
    void discoversAtLeastOneSchema() {
        try (StreamSourcePlugin plugin = createPlugin()) {
            assertThat(plugin.discoverSchemas())
                    .as("a source with no schema cannot be bound to a query")
                    .isNotEmpty();
            plugin.discoverSchemas()
                    .forEach(schema -> assertThat(schema.fieldCount())
                            .as("schema '%s' has no fields", schema.name())
                            .isPositive());
        }
    }

    @Test
    void reportsAtLeastOnePartition() {
        try (StreamSourcePlugin plugin = createPlugin()) {
            List<SourcePartition> partitions = plugin.partitions(streamName());
            assertThat(partitions)
                    .as("a source with no partitions can never be read; parallelism starts here")
                    .isNotEmpty();
            assertThat(partitions.stream()
                            .map(SourcePartition::index)
                            .distinct()
                            .count())
                    .as("partition indices must be unique, or offsets collide on recovery")
                    .isEqualTo(partitions.size());
        }
    }

    @Test
    void readsEveryRecordExactlyOnce() {
        try (StreamSourcePlugin plugin = createPlugin();
                RowCollector collector = newCollector(plugin)) {
            int total = 0;
            for (SourcePartition partition : plugin.partitions(streamName())) {
                try (PartitionReader reader = plugin.createReader(partition, null)) {
                    int polled;
                    while ((polled = reader.poll(collector, 64)) > 0) {
                        total += polled;
                    }
                }
            }
            assertThat(total).isEqualTo(expectedRecordCount());
            assertThat(collector.rows()).hasSize(expectedRecordCount());
        }
    }

    @Test
    void pollIsNonBlockingAndReturnsZeroWhenExhausted() {
        // A reader that blocks internally takes the wait-strategy decision away from the engine and
        // makes the CPU-for-latency dial meaningless (design section 13.3).
        try (StreamSourcePlugin plugin = createPlugin();
                RowCollector collector = newCollector(plugin)) {
            SourcePartition partition = plugin.partitions(streamName()).get(0);
            try (PartitionReader reader = plugin.createReader(partition, null)) {
                while (reader.poll(collector, 1024) > 0) {
                    // drain
                }
                long start = System.nanoTime();
                int afterExhaustion = reader.poll(collector, 1024);
                long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

                assertThat(afterExhaustion).isZero();
                assertThat(elapsedMillis)
                        .as("poll() on an exhausted reader must return promptly, not block")
                        .isLessThan(500);
            }
        }
    }

    @Test
    void pauseStopsProductionAndResumeRestartsIt() {
        // A reader that ignores pause turns flow control into an out-of-memory error further along
        // the pipeline (design section 13.5).
        try (StreamSourcePlugin plugin = createPlugin();
                RowCollector collector = newCollector(plugin)) {
            SourcePartition partition = plugin.partitions(streamName()).get(0);
            try (PartitionReader reader = plugin.createReader(partition, null)) {
                reader.pause();
                assertThat(reader.poll(collector, 64))
                        .as("a paused reader must produce nothing")
                        .isZero();
                reader.resume();
                assertThat(reader.poll(collector, 64))
                        .as("a resumed reader must produce again")
                        .isPositive();
            }
        }
    }

    @Test
    void replayableOffsetsActuallyReplay() {
        // The claim the engine's exactly-once story rests on entirely. A plugin returning a
        // plausible-looking token it cannot resume from produces silent data loss on the first
        // recovery, so this exercises the round trip rather than trusting the declaration.
        try (StreamSourcePlugin plugin = createPlugin()) {
            SourceCapabilities caps = plugin.capabilities();
            if (!caps.replayableOffsets()) {
                assertThat(caps.guarantee())
                        .as("a source without replayable offsets must not claim exactly-once")
                        .isNotEqualTo(DeliveryGuarantee.EXACTLY_ONCE);
                return;
            }

            SourcePartition partition = plugin.partitions(streamName()).get(0);
            List<String> firstPass = new ArrayList<>();
            SourceOffset midpoint;

            try (RowCollector collector = newCollector(plugin);
                    PartitionReader reader = plugin.createReader(partition, null)) {
                int taken = reader.poll(collector, 2);
                if (taken < 1) {
                    fail("the fixture must yield at least one record before the offset can be tested");
                }
                midpoint = reader.position();
                collector.rows().forEach(r -> firstPass.add(r.toString()));
            }

            assertThat(midpoint).as("position() must not be null after reading").isNotNull();

            try (RowCollector collector = newCollector(plugin);
                    PartitionReader resumed = plugin.createReader(partition, midpoint)) {
                while (resumed.poll(collector, 64) > 0) {
                    // drain the remainder
                }
                // What "replayable" has to mean, and what it must not be confused with.
                //
                // The load-bearing property is that resuming LOSES NOTHING: every record the first
                // reader did not take must come back. Whether records it DID take come back as well
                // is the delivery guarantee's business, not this one's -- AT_LEAST_ONCE says
                // plainly that they may, and the engine's weights absorb a duplicate where nothing
                // absorbs a loss.
                //
                // Asserting an exact size conflated the two and held an at-least-once source to an
                // exactly-once contract. A source whose offset is a timestamp rather than a
                // position -- Aerospike's last-update-time watermark is one -- cannot express
                // "records three to five of this scan" at all, and would have had to declare
                // replayableOffsets false to pass. That declaration is read by SharedSourceGroup to
                // mean "a late-joining query can be given the records it missed", which is exactly
                // the no-loss reading, so saying false there to satisfy this line would have cost
                // reader sharing (SRC-3) to answer a question this case was asking wrongly.
                int unread = expectedRecordCount() - firstPass.size();
                assertThat(collector.rows())
                        .as("resuming from a recorded offset must yield at least the unread remainder; "
                                + "a source may re-deliver what was already read, but may never skip it")
                        .hasSizeGreaterThanOrEqualTo(unread);
                if (caps.guarantee() == DeliveryGuarantee.EXACTLY_ONCE) {
                    assertThat(collector.rows())
                            .as("an exactly-once source must resume with no duplicates either")
                            .hasSize(unread);
                }
            }
        }
    }

    @Test
    void capabilitiesAreInternallyConsistent() {
        try (StreamSourcePlugin plugin = createPlugin()) {
            SourceCapabilities caps = plugin.capabilities();
            assertThat(caps).isNotNull();
            assertThat(caps.guarantee()).isNotNull();
            assertThat(caps.typicalLatency()).isNotNull();
            assertThat(caps.typicalLatency().isNegative())
                    .as("a negative latency estimate is a configuration mistake")
                    .isFalse();

            if (caps.guarantee() == DeliveryGuarantee.EXACTLY_ONCE) {
                assertThat(caps.replayableOffsets())
                        .as("exactly-once without replayable offsets is not achievable")
                        .isTrue();
            }
            if (caps.emitsBeforeImage()) {
                assertThat(caps.emitsDeletes())
                        .as("a source carrying before-images should also see deletes; carrying the "
                                + "old row but not the removal leaves a view holding dead rows")
                        .isTrue();
            }
        }
    }

    @Test
    void closingIsIdempotent() {
        StreamSourcePlugin plugin = createPlugin();
        plugin.close();
        plugin.close();
    }

    @Test
    void healthIsReported() {
        try (StreamSourcePlugin plugin = createPlugin()) {
            assertThat(plugin.health()).isNotNull();
            assertThat(plugin.health().state()).isNotNull();
        }
    }
}
