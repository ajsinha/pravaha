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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SINKKEYROWS-1: an upsert sink holds, for each key, the row the view shows.
 *
 * <p>A keyed view keeps every distinct row of a key with its own weight and shows the one that most
 * recently gained weight (VIEWW-1). Retracting the shown row brings the one behind it back. The
 * changelog of that commit is only the retraction, and an upsert sink -- which deletes the record a
 * retraction's key names -- deleted a key the view still showed. An upsert sink is now handed how the
 * answer changed at each commit, as an answer-following subscription is (KEYEDWT-1): the row that
 * left, then the row that entered, so its last word on each key is the view's.
 *
 * <p>The recording sink here is replayed as a keyed table would apply it -- an insert replaces the
 * key's record, a retraction deletes it -- and compared with the view.
 */
class UpsertSinkKeyRowsTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private static final SinkCapabilities UPSERT =
            new SinkCapabilities(EnumSet.of(EmitMode.UPSERT, EmitMode.RETRACT), false, true, 0);

    /** A changelog sink: takes retractions as rows, and never collapses by key. */
    private static final SinkCapabilities CHANGELOG =
            new SinkCapabilities(EnumSet.of(EmitMode.APPEND, EmitMode.RETRACT), false, false, 0);

    private static final String LATEST = "SELECT user_id, amount FROM txn";

    private SinkDeliveryTest.RecordingSinks sinks;
    private QueryRegistry registry;
    private RowArena arena;

    @BeforeEach
    void setUp() {
        sinks = new SinkDeliveryTest.RecordingSinks();
        sinks.bind("table", UPSERT, TXN, List.of("user_id"));
        sinks.bind("log", CHANGELOG, TXN, List.of("user_id"));
        registry = new QueryRegistry(new ViewCatalog(), TXN).writingTo(sinks);
        arena = new RowArena(MemoryAccess.best(), 1 << 22, 8);
    }

    @AfterEach
    void tearDown() {
        registry.close();
        arena.close();
    }

    @Test
    void retractingTheShownRowOfAKeyWithTwoRowsLeavesTheSinkHoldingTheRowBehindIt() {
        RegisteredQuery query = registry.registerWritingTo("latest", LATEST, List.of(0), DANA, "table");

        feed(query, "u1", 10, 1, 0);
        query.commit();
        feed(query, "u1", 20, 1, 0);
        query.commit();
        assertThat(table("table")).containsExactly(Map.entry("u1", "[u1, 20]"));

        // The view holds u1 twice and shows 20; taking 20 away shows 10 again.
        feed(query, "u1", 20, -1, 0);
        query.commit();

        assertThat(shown(query)).containsExactly(Map.entry("u1", "[u1, 10]"));
        assertThat(table("table"))
                .as("the sink holds the row the view shows, not nothing")
                .isEqualTo(shown(query));
    }

    @Test
    void theSinkFollowsTheViewThroughUpsertsRetractionsAndDeletes() {
        RegisteredQuery query = registry.registerWritingTo("latest", LATEST, List.of(0), DANA, "table");

        feed(query, "u1", 10, 1, 0);
        feed(query, "u2", 5, 1, 0);
        query.commit();
        feed(query, "u1", 20, 1, 0);
        feed(query, "u1", 30, 1, 0);
        query.commit();
        feed(query, "u1", 30, -1, 0);
        feed(query, "u1", 20, -1, 0);
        feed(query, "u2", 5, -1, 0);
        query.commit();
        assertThat(table("table")).isEqualTo(shown(query)).containsExactly(Map.entry("u1", "[u1, 10]"));

        feed(query, "u1", 10, -1, 0);
        query.commit();
        assertThat(shown(query)).isEmpty();
        assertThat(table("table"))
                .as("the last row of a key withdrawn deletes the record")
                .isEmpty();
    }

    /**
     * SINKKEYROWS-2: a key whose row is replaced within a commit arrives as the new row alone, an
     * upsert over the key's record. The old row's withdrawal would delete the record on the way --
     * a tombstone on a Kafka topic, a DELETE a reader can see -- and adds nothing to where it ends.
     */
    @Test
    void anUpsertThatReplacesAKeysRowArrivesAsTheNewRowAlone() {
        RegisteredQuery query = registry.registerWritingTo("latest", LATEST, List.of(0), DANA, "table");
        feed(query, "u1", 10, 1, 0);
        query.commit();
        feed(query, "u1", 20, 1, 0);
        query.commit();

        assertThat(sinks.sink("table").rows()).containsExactly("+[u1, 10]", "+[u1, 20]");
    }

    @Test
    void aKeyThatLeavesAndDoesNotReturnIsStillWithdrawnInTheCommitThatReplacesAnother() {
        RegisteredQuery query = registry.registerWritingTo("latest", LATEST, List.of(0), DANA, "table");
        feed(query, "u1", 10, 1, 0);
        feed(query, "u2", 5, 1, 0);
        query.commit();
        feed(query, "u1", 20, 1, 0);
        feed(query, "u2", 5, -1, 0);
        query.commit();

        assertThat(sinks.sink("table").rows())
                .as("u1 replaced by an upsert, u2 deleted")
                .containsExactlyInAnyOrder("+[u1, 10]", "+[u2, 5]", "+[u1, 20]", "-[u2, 5]");
        assertThat(table("table")).isEqualTo(shown(query)).containsExactly(Map.entry("u1", "[u1, 20]"));
    }

    @Test
    void aChangelogSinkStillReceivesTheReplacementAsTheChangelogHasIt() {
        RegisteredQuery query = registry.registerWritingTo("latest", LATEST, List.of(0), DANA, "log");
        feed(query, "u1", 10, 1, 0);
        query.commit();
        feed(query, "u1", 10, -1, 0);
        feed(query, "u1", 20, 1, 0);
        query.commit();

        assertThat(sinks.sink("log").rows()).containsExactly("+[u1, 10]", "-[u1, 10]", "+[u1, 20]");
    }

    @Test
    void aChangelogSinkStillReceivesTheChangelogVerbatim() {
        RegisteredQuery query = registry.registerWritingTo("latest", LATEST, List.of(0), DANA, "log");
        feed(query, "u1", 10, 1, 0);
        query.commit();
        feed(query, "u1", 20, 1, 0);
        query.commit();
        feed(query, "u1", 20, -1, 0);
        query.commit();

        // STRM-008 to STRM-012: the weights applied, passed through. A consumer of a changelog
        // applies them itself.
        assertThat(sinks.sink("log").rows()).containsExactly("+[u1, 10]", "+[u1, 20]", "-[u1, 20]");
    }

    @Test
    void aRowRetentionAgesOutIsNotDeletedFromTheSink() {
        RegisteredQuery query = registry.registerWritingTo(
                "latest", LATEST, List.of(0), DANA, "table", Retention.ofAge(Duration.ofSeconds(1)));
        long second = 1_000_000_000L;
        feed(query, "u1", 10, 1, second);
        query.commit();
        feed(query, "u2", 5, 1, 60 * second);
        query.commit();

        assertThat(shown(query)).as("u1 aged out of the view").containsExactly(Map.entry("u2", "[u2, 5]"));
        // Eviction is silent to a sink, as it always was: a row that aged out was not withdrawn, and
        // a table the view feeds is where it is kept after the view has let it go.
        assertThat(table("table")).containsOnlyKeys("u1", "u2");
    }

    @Test
    void aSinkJoiningARunningComputationIsSeededWithTheRowsTheViewShows() {
        RegisteredQuery first = registry.register("latest_view", LATEST, List.of(0), DANA);
        feed(first, "u1", 10, 1, 0);
        feed(first, "u1", 20, 1, 0);
        first.commit();

        RegisteredQuery second = registry.registerWritingTo("latest", LATEST, List.of(0), DANA, "table");
        feed(second, "u1", 20, -1, 0);
        second.commit();
        assertThat(table("table")).isEqualTo(shown(second)).containsExactly(Map.entry("u1", "[u1, 10]"));

        // After the seed, commits are the answer's changes.
        feed(second, "u1", 30, 1, 0);
        second.commit();
        feed(second, "u1", 30, -1, 0);
        second.commit();
        assertThat(table("table")).isEqualTo(shown(second)).containsExactly(Map.entry("u1", "[u1, 10]"));
    }

    // ---------------------------------------------------------------------------------------

    /** The recording sink's rows, applied as a table keyed by the first column applies them. */
    private Map<String, String> table(String sinkName) {
        Map<String, String> records = new LinkedHashMap<>();
        for (String row : sinks.sink(sinkName).rows()) {
            String values = row.substring(1);
            String key = values.substring(1, values.indexOf(','));
            if (row.startsWith("-")) {
                records.remove(key);
            } else {
                records.put(key, values);
            }
        }
        return records;
    }

    private static Map<String, String> shown(RegisteredQuery query) {
        Map<String, String> rows = new LinkedHashMap<>();
        for (Object[] row : query.view().scan()) {
            rows.put((String) row[0], java.util.Arrays.asList(row).toString());
        }
        return rows;
    }

    private void feed(RegisteredQuery query, String user, long amount, long weight, long eventTime) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setLong(1, amount);
        writer.weight(weight).eventTimestampNanos(eventTime).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        assertThat(query.accept(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))))
                .isTrue();
        assertThat(query.awaitApplied(Duration.ofSeconds(10))).isTrue();
    }
}
