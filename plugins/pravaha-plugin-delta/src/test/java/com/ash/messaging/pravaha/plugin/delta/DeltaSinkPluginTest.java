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
package com.ash.messaging.pravaha.plugin.delta;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.delta.kernel.Operation;
import io.delta.kernel.Table;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.utils.CloseableIterable;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PluginContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Delta sink against real Delta tables on the local filesystem.
 *
 * <p>Real tables, written by Delta Kernel and read back by Delta Kernel, for the reason {@code
 * DeltaTableFixture} gives on the source's side: a checked-in table is a snapshot of one protocol
 * version, and a table Kernel writes is whatever Kernel currently produces. No cluster is involved --
 * Kernel writes Parquet and a transaction log, and a directory is all a Delta table is.
 *
 * <p>The engine's delivery protocol is driven by hand here, one call at a time, exactly as {@code
 * SinkDelivery} drives it: begin, write, flush, prepare, commit. {@link DeltaSinkRegistrationTest}
 * is the same sink under the real registry.
 */
class DeltaSinkPluginTest {

    private static final StreamSchema SPEND = StreamSchema.builder("spend")
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    @TempDir
    Path root;

    private final SinkTestRows rows = new SinkTestRows();
    private final java.util.List<DeltaSinkPlugin> opened = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() {
        opened.forEach(DeltaSinkPlugin::close);
        rows.close();
    }

    // ---- upsert -------------------------------------------------------------------------

    @Test
    void aRowIsWrittenAndThenReplacedByItsKey() {
        String path = table("spend");
        DeltaSinkPlugin sink = upsertSink(path, Map.of());

        cycle(sink, 1, rows.row(SPEND, 1, "u1", 300L), rows.row(SPEND, 1, "u2", 50L));
        assertThat(DeltaTableReader.read(path)).containsExactly("u1|300", "u2|50");

        // The engine revises u1: the old row retracted and the new one inserted, in one commit.
        cycle(sink, 2, rows.row(SPEND, -1, "u1", 300L), rows.row(SPEND, 1, "u1", 375L));
        assertThat(DeltaTableReader.read(path)).containsExactly("u1|375", "u2|50");
    }

    @Test
    void aRetractionRemovesTheRowItsKeyNames() {
        String path = table("spend");
        DeltaSinkPlugin sink = upsertSink(path, Map.of());
        cycle(sink, 1, rows.row(SPEND, 1, "u1", 300L), rows.row(SPEND, 1, "u2", 50L));

        cycle(sink, 2, rows.row(SPEND, -1, "u1", 300L));

        assertThat(DeltaTableReader.read(path))
                .as("a Delta table has no delete; the file holding u1 was rewritten without it")
                .containsExactly("u2|50");
        assertThat(sink.filesRewritten()).isEqualTo(1L);
    }

    @Test
    void aKeyChangedManyTimesInOneCheckpointIsWrittenOnce() {
        String path = table("spend");
        DeltaSinkPlugin sink = upsertSink(path, Map.of());

        sink.beginTransaction(1);
        for (long total = 1; total <= 20; total++) {
            sink.write(List.of(rows.row(SPEND, 1, "u1", total)));
        }
        sink.flush();
        sink.commit(sink.prepare(1));

        assertThat(DeltaTableReader.read(path)).containsExactly("u1|20");
        assertThat(DeltaTableReader.version(path))
                .as("twenty writes, one Delta commit after the table's creation")
                .isEqualTo(1L);
    }

    @Test
    void onlyTheFilesHoldingAnAffectedKeyAreRewritten() {
        String path = table("spend");
        DeltaSinkPlugin sink = upsertSink(path, Map.of());
        cycle(sink, 1, rows.row(SPEND, 1, "u1", 1L));
        cycle(sink, 2, rows.row(SPEND, 1, "u2", 2L));
        cycle(sink, 3, rows.row(SPEND, 1, "u3", 3L));
        assertThat(DeltaTableReader.dataFiles(path)).isEqualTo(3);

        cycle(sink, 4, rows.row(SPEND, -1, "u2", 2L));

        assertThat(sink.filesRewritten())
                .as("u1's and u3's files hold no key this commit changes, so they are left alone")
                .isEqualTo(1L);
        assertThat(DeltaTableReader.read(path)).containsExactly("u1|1", "u3|3");
    }

    // ---- what a checkpoint makes visible, and when ---------------------------------------

    @Test
    void nothingIsVisibleUntilTheCheckpointsCommit() {
        String path = table("spend");
        DeltaSinkPlugin sink = upsertSink(path, Map.of());

        sink.beginTransaction(1);
        sink.write(List.of(rows.row(SPEND, 1, "u1", 300L)));
        sink.flush();
        assertThat(DeltaTableReader.read(path))
                .as("staged in _pravaha_sink, and no Delta commit has happened")
                .isEmpty();
        String handle = sink.prepare(1);
        assertThat(DeltaTableReader.read(path))
                .as("prepared is still not visible")
                .isEmpty();

        sink.commit(handle);
        assertThat(DeltaTableReader.read(path)).containsExactly("u1|300");
    }

    @Test
    void aCommitSentTwiceAppliesOnce() {
        String path = table("spend");
        DeltaSinkPlugin sink = changelogSink(path, Map.of());

        sink.beginTransaction(1);
        sink.write(List.of(rows.row(SPEND, 1, "u1", 300L)));
        String handle = sink.prepare(1);
        sink.commit(handle);
        sink.commit(handle);

        assertThat(DeltaTableReader.read(path))
                .as("changelog mode appends, so a commit applied twice would show the change twice")
                .containsExactly("u1|300|insert|1");
    }

    /**
     * The narrow window the Delta {@code txn} action is there for: the commit landed, and the
     * process died before it could forget the staging that produced it. A restore commits the
     * handle again, finds the staging still full, and must <em>not</em> apply it a second time. The
     * table's own record of the label is the only thing that can say so, because the process that
     * knew is gone.
     */
    @Test
    void aCommitThatDiedBeforeForgettingItsStagingAppliesOnceAnyway() throws Exception {
        String path = table("spend");
        DeltaSinkPlugin sink = changelogSink(path, Map.of());
        sink.beginTransaction(1);
        sink.write(List.of(rows.row(SPEND, 1, "u1", 300L)));
        sink.flush();
        String handle = sink.prepare(1);
        Path staged = Path.of(path, DeltaSinkPlugin.DEFAULT_STAGING_DIRECTORY, "spend_table", "0000000000000001");
        Path saved = root.resolve("saved-staging");
        copy(staged, saved);

        sink.commit(handle);
        copy(saved, staged);
        sink.commit(handle);

        assertThat(DeltaTableReader.read(path)).containsExactly("u1|300|insert|1");
    }

    private static void copy(Path from, Path to) throws java.io.IOException {
        Files.createDirectories(to);
        try (java.util.stream.Stream<Path> listing = Files.list(from)) {
            for (Path file : listing.toList()) {
                Files.copy(
                        file,
                        to.resolve(file.getFileName().toString()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * The crash exactly-once exists for: the checkpoint is durable, the process dies before its
     * commit is sent, and a new process restores the checkpoint and commits the handle it recorded.
     *
     * <p>Changelog mode, deliberately: an upsert repeated is invisible, and what is under test is
     * that the change is applied <em>once</em>, not that it ends up looking right anyway.
     */
    @Test
    void aRestartCommitsWhatTheCheckpointRecordedAndWritesItOnlyOnce() {
        String path = table("spend");
        DeltaSinkPlugin first = changelogSink(path, Map.of());
        first.beginTransaction(1);
        first.write(List.of(rows.row(SPEND, 1, "u1", 300L)));
        first.flush();
        String recorded = first.prepare(1);
        // The checkpoint is now durable and the process dies: no commit is sent. What it wrote
        // afterwards is in transaction 2, which no checkpoint recorded.
        first.beginTransaction(2);
        first.write(List.of(rows.row(SPEND, 1, "u2", 50L)));
        first.flush();
        first.close();
        assertThat(DeltaTableReader.read(path)).isEmpty();

        DeltaSinkPlugin restarted = changelogSink(path, Map.of());
        restarted.commit(recorded);
        restarted.abortAfter(1);
        assertThat(DeltaTableReader.read(path))
                .as("the recorded transaction committed; what came after it was abandoned for the replay")
                .containsExactly("u1|300|insert|1");

        // The replay writes what followed the checkpoint again.
        restarted.beginTransaction(2);
        restarted.write(List.of(rows.row(SPEND, 1, "u2", 50L)));
        restarted.commit(restarted.prepare(2));
        assertThat(DeltaTableReader.read(path)).containsExactly("u1|300|insert|1", "u2|50|insert|1");
    }

    @Test
    void abortAfterLeavesNothingStagedForAReplayToDuplicate() {
        String path = table("spend");
        DeltaSinkPlugin sink = upsertSink(path, Map.of());
        sink.beginTransaction(4);
        sink.write(List.of(rows.row(SPEND, 1, "u1", 300L)));
        sink.prepare(4);
        sink.beginTransaction(5);
        sink.write(List.of(rows.row(SPEND, 1, "u2", 50L)));

        sink.abortAfter(3);

        assertThat(staging(path)).isEmpty();
        assertThat(DeltaTableReader.read(path)).isEmpty();
    }

    @Test
    void aHandleFromAnotherTransactionIdIsRefused() {
        DeltaSinkPlugin sink = upsertSink(table("spend"), Map.of("transaction.id", "mine"));
        assertThatThrownBy(() -> sink.commit("delta-sink:v1:3:somebody-else"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("belongs to transaction.id 'somebody-else'");
        assertThatThrownBy(() -> sink.commit("not-a-handle"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("is not a handle this sink wrote");
    }

    // ---- changelog ------------------------------------------------------------------------

    @Test
    void changelogModeAppendsEveryChangeWithItsOperationAndWeight() {
        String path = table("changes");
        DeltaSinkPlugin sink = changelogSink(path, Map.of());

        cycle(sink, 1, rows.row(SPEND, 1, "u1", 300L));
        cycle(sink, 2, rows.row(SPEND, -1, "u1", 300L), rows.row(SPEND, 1, "u1", 375L));

        assertThat(DeltaTableReader.read(path))
                .as("the history, not the answer: three rows for two changes to one key")
                .containsExactly("u1|300|delete|-1", "u1|300|insert|1", "u1|375|insert|1");
    }

    @Test
    void changelogModeAcceptsAppendsAndRetractionsAndUpsertModeUpsertsAndRetracts() {
        assertThat(changelogSink(table("a"), Map.of()).capabilities().emitModes())
                .containsExactlyInAnyOrder(EmitMode.APPEND, EmitMode.RETRACT);
        assertThat(upsertSink(table("b"), Map.of()).capabilities().emitModes())
                .containsExactlyInAnyOrder(EmitMode.UPSERT, EmitMode.RETRACT);
        assertThat(upsertSink(table("c"), Map.of()).capabilities().idempotentUpsert())
                .isTrue();
        assertThat(changelogSink(table("d"), Map.of()).capabilities().idempotentUpsert())
                .as("appending the same change again appends it again")
                .isFalse();
    }

    // ---- concurrency ------------------------------------------------------------------------

    /**
     * A second writer inside the commit's own window -- after the snapshot it read, before its log
     * entry. Kernel refuses the commit, and this sink turns that into PRV-5059 and stops, rather
     * than resolving the conflict and trying again over whatever the other writer has just written.
     */
    @Test
    void aWriterThatCommitsInsideTheCommitsWindowMakesItRefuseRatherThanRetry() {
        String path = table("spend");
        DeltaSinkPlugin sink = upsertSink(path, Map.of());
        cycle(sink, 1, rows.row(SPEND, 1, "u1", 300L));

        sink.beginTransaction(2);
        sink.write(List.of(rows.row(SPEND, 1, "u2", 50L)));
        sink.flush();
        String handle = sink.prepare(2);
        sink.commits().openConflictWindow(() -> commitSomethingElse(path));

        assertThatThrownBy(() -> sink.commit(handle))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5059")
                .hasMessageContaining("another writer committed to the table while this commit was being built")
                .hasMessageContaining("does not retry");
        assertThat(DeltaTableReader.read(path))
                .as("the refused commit wrote none of its changes")
                .containsExactly("u1|300");
        assertThat(staging(path))
                .as("the checkpoint's changes are still staged; nothing was written half")
                .isNotEmpty();
    }

    /**
     * The other half of the concurrency story, and the reason the one above is a window rather than
     * the whole gap between prepare and commit: a writer that has finished before this commit begins
     * is simply the table this commit merges onto.
     */
    @Test
    void aWriterThatFinishedBeforeTheCommitBeganIsMergedOntoRatherThanRefused() {
        String path = table("spend");
        DeltaSinkPlugin sink = upsertSink(path, Map.of());
        cycle(sink, 1, rows.row(SPEND, 1, "u1", 300L));

        sink.beginTransaction(2);
        sink.write(List.of(rows.row(SPEND, 1, "u2", 50L)));
        sink.flush();
        String handle = sink.prepare(2);
        commitSomethingElse(path);
        sink.commit(handle);

        assertThat(DeltaTableReader.read(path)).containsExactly("u1|300", "u2|50");
    }

    // ---- what it refuses before a row moves --------------------------------------------------

    @Test
    void aTableWhoseColumnsAreNotTheBindingsIsRefusedWhenTheSinkOpens() {
        String path = table("spend");
        DeltaTableFixture.withRows(path, 1, 1, "a");

        assertThatThrownBy(() -> upsertSink(path, Map.of()))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5057")
                .hasMessageContaining("column 0 of the table is 'id'");
    }

    @Test
    void createFalseRefusesToInventTheTable() {
        assertThatThrownBy(() -> upsertSink(table("absent"), Map.of("create", "false")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5057")
                .hasMessageContaining("create is false");
    }

    @Test
    void aTimestampThatIsNotAWholeMicrosecondIsRefusedRatherThanRounded() {
        StreamSchema timed = StreamSchema.builder("t")
                .field("user_id", Types.string())
                .field("seen", Types.timestamp())
                .build();
        String path = table("timed");
        DeltaSinkPlugin sink = open(path, Map.of("schema", "user_id:STRING,seen:TIMESTAMP", "key.columns", "user_id"));

        sink.beginTransaction(1);
        sink.write(List.of(rows.row(timed, 1, "u1", 1_700_000_000_000_000_500L)));
        String handle = sink.prepare(1);

        assertThatThrownBy(() -> sink.commit(handle))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5058")
                .hasMessageContaining("which Delta cannot store");
    }

    // ---- without transactions ---------------------------------------------------------------

    @Test
    void withoutTransactionsEachBatchIsItsOwnDeltaCommit() {
        String path = table("spend");
        DeltaSinkPlugin sink = upsertSink(path, Map.of("transactional", "false"));

        sink.write(List.of(rows.row(SPEND, 1, "u1", 300L)));
        assertThat(DeltaTableReader.read(path))
                .as("no checkpoint to wait for: visible at once")
                .containsExactly("u1|300");
        sink.write(List.of(rows.row(SPEND, -1, "u1", 300L), rows.row(SPEND, 1, "u1", 375L)));
        assertThat(DeltaTableReader.read(path)).containsExactly("u1|375");
        assertThat(sink.capabilities().transactional()).isFalse();
        assertThat(staging(path)).isEmpty();
    }

    // -----------------------------------------------------------------------------------------

    private String table(String name) {
        return root.resolve(name).toString();
    }

    private DeltaSinkPlugin upsertSink(String path, Map<String, String> extra) {
        Map<String, String> options = new HashMap<>(Map.of("key.columns", "user_id"));
        options.putAll(extra);
        return open(path, options);
    }

    private DeltaSinkPlugin changelogSink(String path, Map<String, String> extra) {
        Map<String, String> options = new HashMap<>(Map.of("mode", "changelog"));
        options.putAll(extra);
        return open(path, options);
    }

    private DeltaSinkPlugin open(String path, Map<String, String> extra) {
        Map<String, String> options = new HashMap<>(Map.of("path", path, "schema", "user_id:STRING,total:INT64"));
        options.putAll(extra);
        DeltaSinkPlugin sink = new DeltaSinkPlugin();
        sink.configure(new Ctx("spend_table", options));
        sink.open();
        opened.add(sink);
        return sink;
    }

    /** One checkpoint's worth of delivery, as {@code SinkDelivery} performs it. */
    private static void cycle(DeltaSinkPlugin sink, long label, RowView... batch) {
        sink.beginTransaction(label);
        sink.write(List.of(batch));
        sink.flush();
        sink.commit(sink.prepare(label));
    }

    /** Another writer, committing to the table between this sink's prepare and its commit. */
    private static void commitSomethingElse(String path) {
        io.delta.kernel.engine.Engine engine = DefaultEngine.create(new Configuration());
        Table.forPath(engine, path)
                .createTransactionBuilder(engine, "somebody-else", Operation.WRITE)
                .build(engine)
                .commit(engine, CloseableIterable.emptyIterable());
    }

    private static List<Path> staging(String path) {
        Path directory = Path.of(path, DeltaSinkPlugin.DEFAULT_STAGING_DIRECTORY);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(".batch"))
                    .toList();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    /** Kept out of the way of the assertions: the configuration refusals are in their own test. */
    @Test
    void theBindingIsCheckedBeforeAnythingIsOpened() {
        assertThatThrownBy(() -> new DeltaSinkPlugin().configure(new Ctx("s", Map.of("path", "/tmp/x"))))
                .isInstanceOf(PravahaException.class);
        assertThatThrownBy(() -> new DeltaSinkPlugin()
                        .configure(new Ctx("s", Map.of("path", "/tmp/x", "schema", "a:INT64", "mode", "sideways"))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("is not upsert or changelog");
    }
}
