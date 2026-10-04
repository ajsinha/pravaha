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
package com.ash.messaging.pravaha.registry;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HLP-3: a join over a source that deletes revises its answer, and {@code PRV-2041} has to know.
 *
 * <p>A stream-to-stream join is bilinear: a retraction on either side retracts exactly the pairs
 * its insert produced. Over two append-only sources it only ever appends, which is why {@code
 * ChangelogAnalysis} passed it. But a source such as {@code postgres-cdc} delivers a delete as a
 * row at weight -1, the join passes that on as a retracted pair, and an append-only sink writes it
 * as one more row -- with nothing refused at registration and nothing failing afterwards.
 */
class RetractingSourceSinkTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("ts", Types.int64())
            .build();

    private static final StreamSchema PROFILES = StreamSchema.builder("profiles")
            .field("user_id", Types.string())
            .field("tier", Types.string())
            .field("ts", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    private static final String JOIN =
            "SELECT t.user_id, t.amount, p.tier FROM txn t JOIN profiles p ON t.user_id = p.user_id";

    private SinkDeliveryTest.RecordingSinks sinks;
    private RowArena arena;

    @SuppressWarnings("NullAway.Init") // a test sets it before reading it
    private QueryRegistry registry;

    @BeforeEach
    void setUp() {
        sinks = new SinkDeliveryTest.RecordingSinks();
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.close();
        }
        arena.close();
    }

    /** A feed factory that attaches nothing and says which of its streams can delete. */
    private static SourceFeedFactory retracting(String... streams) {
        Set<String> deleting = Set.of(streams);
        return new SourceFeedFactory() {
            @Override
            public SourceFeed open(
                    String queryName,
                    com.ash.messaging.pravaha.runtime.exec.QueryExecution execution,
                    List<String> sourceStreams,
                    Runnable afterDelivery,
                    Map<String, String> resumeFrom) {
                return SourceFeed.NONE;
            }

            @Override
            public boolean retracts(String stream) {
                return deleting.contains(stream);
            }
        };
    }

    @Test
    void aJoinOverASourceThatDeletesIsRefusedAgainstAnAppendOnlySink() {
        registry = new QueryRegistry(new ViewCatalog(), TXN, PROFILES)
                .writingTo(sinks)
                .feedingFrom(retracting("profiles"));
        sinks.bind("log", SinkCapabilities.appendOnly());

        assertThatThrownBy(() -> registry.registerWritingTo("tiers", JOIN, List.of(0), DANA, "log"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2041")
                .as("the reason names the stream whose deletes the join passes on")
                .hasMessageContaining("profiles");
        assertThat(sinks.opened()).isZero();
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void theSameJoinOverAppendOnlySourcesIsAccepted() {
        registry = new QueryRegistry(new ViewCatalog(), TXN, PROFILES)
                .writingTo(sinks)
                .feedingFrom(retracting());
        sinks.bind("log", SinkCapabilities.appendOnly());

        registry.registerWritingTo("tiers", JOIN, List.of(0), DANA, "log");

        assertThat(registry.names()).contains("tiers");
    }

    @Test
    void aSinkThatTakesRetractionsReceivesTheJoinsRetractedPair() {
        // What the refusal protects an append-only sink from: the delete arrives as a retraction of
        // the pair, which a sink that can take one applies and a file would write as a new line.
        registry = new QueryRegistry(new ViewCatalog(), TXN, PROFILES)
                .writingTo(sinks)
                .feedingFrom(retracting("profiles"));
        sinks.bind("totals", new SinkCapabilities(EnumSet.of(EmitMode.UPSERT, EmitMode.RETRACT), false, true, 0));
        RegisteredQuery query = registry.registerWritingTo("tiers", JOIN, List.of(0), DANA, "totals");

        push(query, TXN, "txn", "u1", 300L, 1L);
        push(query, PROFILES, "profiles", "u1", "gold", 1L);
        query.commit();
        push(query, PROFILES, "profiles", "u1", "gold", -1L);
        query.commit();

        assertThat(sinks.sink("totals").rows()).containsExactly("+[u1, 300, gold]", "-[u1, 300, gold]");
    }

    private void push(
            RegisteredQuery query, StreamSchema schema, String stream, String user, Object value, long weight) {
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user);
        if (value instanceof Long amount) {
            writer.setLong(1, amount);
        } else {
            writer.setString(1, (String) value);
        }
        writer.setLong(2, 1L);
        writer.weight(weight).eventTimestampNanos(1L).sequence(1L).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(stream, new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }
}
