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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The configured watermarks reach a shared reader, and not only a query with a lane of its own.
 *
 * <p>{@code pravaha.lane.backpressure.*} is bound on the node and handed to {@link
 * PluginSourceFeeds}, which uses it for every pump it opens. A shared reader opens its route
 * separately, and it used {@code BackpressurePolicy.defaults()} -- so the same setting would have
 * held for a query on a lane of its own and quietly stopped holding once the same query shared one.
 * Whether a query shares a lane is {@code pravaha.lane.multiplex.enabled}, a deployment's decision
 * taken elsewhere, so an operator would have had no reason to connect the two and nothing would
 * have told them.
 *
 * <p>This asserts what the feed was built with rather than watching a source pause, because the
 * pausing itself is measured by {@code BackpressureMeasurementTest} against a policy it passes in.
 * What was missing was never the hysteresis; it was the wire from the configuration to this object.
 */
final class SharedFeedBackpressureTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("shared")
            .field("user_id", Types.int64())
            .field("amount", Types.int64())
            .build();

    private static final SourceBinding BINDING = new SourceBinding("shared", "counting-scan", Map.of());

    @Test
    void aSharedFeedOpensItsRouteWithTheNodesConfiguredWatermarks() {
        BackpressurePolicy patient = new BackpressurePolicy(0.95, 0.9);

        SharedSourceGroup group = new SharedSourceGroup(
                new SharedSourceGroup.Key("shared", BINDING, SCHEMA),
                new CountingScanPlugin(),
                List.of(new com.ash.messaging.pravaha.api.plugin.SourcePartition("shared", 0, Map.of())),
                patient);

        assertThat(group.feeds()).isNotEmpty();
        assertThat(group.feeds())
                .allSatisfy(feed -> assertThat(feed.backpressurePolicy())
                        .as("a shared reader pauses where the node said, not where the library did")
                        .isEqualTo(patient));
    }

    @Test
    void aGroupGivenNoPolicyStillHasOneRatherThanNull() {
        SharedSourceGroup group = new SharedSourceGroup(
                new SharedSourceGroup.Key("shared", BINDING, SCHEMA),
                new CountingScanPlugin(),
                List.of(new com.ash.messaging.pravaha.api.plugin.SourcePartition("shared", 0, Map.of())),
                null);

        assertThat(group.feeds())
                .allSatisfy(feed -> assertThat(feed.backpressurePolicy()).isEqualTo(BackpressurePolicy.defaults()));
    }
}
