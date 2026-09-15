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
package com.ash.messaging.pravaha.plugin.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilesystemPluginTest {

    private static final String SCHEMA = "id:INT64,user:STRING,amount:FLOAT64,active:BOOLEAN,note:STRING?";

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    private static PluginContext ctx(Map<String, String> config) {
        return new Ctx("txn", config);
    }

    // ------------------------------------------------------------------ what went wrong

    /**
     * A failure to open says what the operating system said about it.
     *
     * <p>It did not. Every {@code IOException} on the way in became {@code "cannot open <path>"},
     * and the reason -- the only part of the diagnostic that is about anything other than the path
     * the caller already knows -- went into the cause, where the person reading a one-line error
     * never sees it.
     *
     * <p>Driven through the message builder rather than by causing a real open failure, and that is
     * not laziness: the open failures a test can cause on demand are the ones whose {@code
     * IOException} carries no reason at all (a missing file's message is the path), and a directory
     * -- the obvious candidate -- does not fail on open on Linux at all, which is finding I-8. The
     * failures that carry a reason are the ones a test cannot arrange, which is precisely why the
     * reason was being thrown away without anybody noticing.
     */
    @Test
    void aFailureToOpenNamesTheOperatingSystemsReason() {
        assertThat(FilesystemPartitionReader.why(
                        new java.nio.file.FileSystemException("/data/events.csv", null, "Input/output error")))
                .as("the reason, which used to reach only the cause")
                .isEqualTo("/data/events.csv: Input/output error");

        assertThat(FilesystemPartitionReader.why(new IOException()))
                .as("an exception with nothing to say is named rather than rendered as 'null'")
                .isEqualTo("IOException");
    }

    /**
     * The descriptor ceiling explains itself rather than blaming the file it happened to be opening.
     *
     * <p>Measured at {@code ulimit -n 300}: the 276th bound filesystem source fails, and before this
     * the message was {@code PRV-5040 cannot open <path>} -- a decode error code, naming a file
     * whose permissions, encoding and schema are all correct. The person reading it inspects that
     * file. The limit is the process's and the file is innocent, and the sentence has to say so
     * because the error code cannot: 5040 is published and is not changed here.
     *
     * <p>Driven through the message builder rather than by exhausting descriptors: a test JVM's
     * {@code ulimit -n} is whatever the machine sets, this one's is half a million, and a test that
     * opened half a million files to prove a sentence would be a worse test.
     */
    @Test
    void tooManyOpenFilesSaysSoRatherThanBlamingTheFile() {
        String message = FilesystemPartitionReader.why(
                new java.nio.file.FileSystemException("/data/events.csv", null, "Too many open files"));

        assertThat(message)
                .as("the operating system's own words, kept")
                .contains("Too many open files")
                .as("and what they mean for a node holding one descriptor per bound source")
                .contains("file-descriptor limit")
                .contains("ulimit -n")
                .as("and an explicit acquittal of the file, because that is where the reader will go first")
                .contains("almost certainly fine");
    }

    // ------------------------------------------------------------------ schema parsing

    @Test
    void parsesADeclaredSchema() {
        // Declared rather than sniffed: inferring types from sample lines guesses wrong on exactly
        // the columns that matter, and an all-digit identifier becomes an integer until the first
        // row containing a letter arrives.
        StreamSchema s = FilesystemSourcePlugin.parseSchema("txn", SCHEMA);
        assertThat(s.fieldCount()).isEqualTo(5);
        assertThat(s.field(0).type().sqlName()).isEqualTo("INT64 NOT NULL");
        assertThat(s.field(4).type().nullable())
                .as("the ? suffix marks a column nullable")
                .isTrue();
    }

    @Test
    void rejectsAMalformedSchemaWithAnExample() {
        assertThatThrownBy(() -> FilesystemSourcePlugin.parseSchema("s", "id"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("name:TYPE")
                .hasMessageContaining("Example");
        assertThatThrownBy(() -> FilesystemSourcePlugin.parseSchema("s", "id:WIDGET"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Supported:");
    }

    // ------------------------------------------------------------------ splitting

    @Test
    void splittingPreservesEmptyTrailingFields() {
        // String.split drops them, which silently turns a row with a null last column into a short
        // row and produces a field-count error pointing at the wrong problem.
        assertThat(DelimitedCodec.split("a,b,", ',')).containsExactly("a", "b", "");
        assertThat(DelimitedCodec.split("a,,c", ',')).containsExactly("a", "", "c");
        assertThat(DelimitedCodec.split(",", ',')).containsExactly("", "");
        assertThat(DelimitedCodec.split("solo", ',')).containsExactly("solo");
    }

    // ------------------------------------------------------------------ end to end

    @Test
    void readsWritesAndReadsBackIdentically(@TempDir Path dir) throws IOException {
        // The property that matters for the reference plugin: it must be able to read back exactly
        // what it wrote. Two implementations that disagree by one edge case produce a plugin that
        // cannot, and each half looks correct alone.
        Path input = dir.resolve("in.csv");
        Files.writeString(input, """
                1,alice,10.5,true,hello
                2,bob,20.25,false,
                3,carol,0.0,true,note with spaces
                """);

        List<String> roundTripped = new ArrayList<>();
        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            source.configure(ctx(Map.of("path", input.toString(), "schema", SCHEMA)));
            source.open();

            Path output = dir.resolve("out.csv");
            try (FilesystemSinkPlugin sink = new FilesystemSinkPlugin()) {
                sink.configure(ctx(Map.of("path", output.toString(), "schema", SCHEMA)));
                sink.open();

                withRows(source, rows -> {
                    assertThat(rows).hasSize(3);
                    assertThat(sink.write(rows)).isEqualTo(3);
                    sink.flush();
                });
                roundTripped.addAll(Files.readAllLines(output));
            }
        }
        assertThat(roundTripped)
                .containsExactly("1,alice,10.5,true,hello", "2,bob,20.25,false,", "3,carol,0.0,true,note with spaces");
    }

    @Test
    void nullsSurviveTheRoundTrip(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("nulls.csv");
        Files.writeString(input, "1,a,1.0,true,\n");
        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            source.configure(ctx(Map.of("path", input.toString(), "schema", SCHEMA)));
            source.open();
            withRows(source, rows -> {
                assertThat(rows).hasSize(1);
                assertThat(rows.get(0).isNull(4)).isTrue();
            });
        }
    }

    @Test
    void everyRowFromAFileIsAnInsertAtWeightOne(@TempDir Path dir) throws IOException {
        // A file is an append-only log of insertions. If this ever changed silently, downstream
        // aggregates would be wrong in a way that looks like a data problem.
        Path input = dir.resolve("in.csv");
        Files.writeString(input, "1,a,1.0,true,x\n2,b,2.0,false,y\n");
        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            source.configure(ctx(Map.of("path", input.toString(), "schema", SCHEMA)));
            source.open();
            withRows(source, rows -> {
                for (RowView row : rows) {
                    assertThat(row.weight()).isEqualTo(1L);
                    assertThat(row.rowKind()).isEqualTo(com.ash.messaging.pravaha.api.data.RowKind.INSERT);
                }
            });
        }
    }

    @Test
    void resumesFromARecordedOffset(@TempDir Path dir) throws IOException {
        // The claim behind declaring EXACTLY_ONCE. The TCK checks the round trip rather than
        // trusting the declaration, and so does this.
        Path input = dir.resolve("in.csv");
        Files.writeString(input, "1,a,1.0,true,x\n2,b,2.0,true,y\n3,c,3.0,true,z\n");

        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            source.configure(ctx(Map.of("path", input.toString(), "schema", SCHEMA)));
            source.open();

            SourceOffset afterTwo;
            try (PartitionReader reader =
                    source.createReader(source.partitions("txn").get(0), null)) {
                Collector first = new Collector(source.schema());
                assertThat(reader.poll(first, 2)).isEqualTo(2);
                afterTwo = reader.position();
                first.close();
            }
            assertThat(afterTwo.token()).isEqualTo("2");

            try (PartitionReader resumed =
                    source.createReader(source.partitions("txn").get(0), afterTwo)) {
                Collector rest = new Collector(source.schema());
                assertThat(resumed.poll(rest, 10))
                        .as("only the third row remains")
                        .isEqualTo(1);
                assertThat(rest.rows.get(0).getLong(0)).isEqualTo(3L);
                rest.close();
            }
        }
    }

    @Test
    void pauseStopsProducingAndResumeContinues(@TempDir Path dir) throws IOException {
        // A reader that ignores pause turns flow control into an out-of-memory error further along.
        Path input = dir.resolve("in.csv");
        Files.writeString(input, "1,a,1.0,true,x\n2,b,2.0,true,y\n");
        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            source.configure(ctx(Map.of("path", input.toString(), "schema", SCHEMA)));
            source.open();
            try (PartitionReader reader =
                            source.createReader(source.partitions("txn").get(0), null);
                    Collector c = new Collector(source.schema())) {
                reader.pause();
                assertThat(reader.poll(c, 10)).isZero();
                reader.resume();
                assertThat(reader.poll(c, 10)).isEqualTo(2);
            }
        }
    }

    @Test
    void skipsAHeaderWhenAsked(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("in.csv");
        Files.writeString(input, "id,user,amount,active,note\n1,a,1.0,true,x\n");
        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            source.configure(ctx(Map.of("path", input.toString(), "schema", SCHEMA, "skip.header", "true")));
            source.open();
            assertThat(countRows(source)).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ failure modes

    @Test
    void aWrongFieldCountNamesTheLineAndTheCounts(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("bad.csv");
        Files.writeString(input, "1,a,1.0,true,x\n2,b\n");
        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            source.configure(ctx(Map.of("path", input.toString(), "schema", SCHEMA)));
            source.open();
            assertThatThrownBy(() -> countRows(source))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("line 2")
                    .hasMessageContaining("2 fields")
                    .hasMessageContaining("5");
        }
    }

    @Test
    void aNonNumericValueNamesTheColumnAndTheType(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("bad.csv");
        Files.writeString(input, "notanumber,a,1.0,true,x\n");
        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            source.configure(ctx(Map.of("path", input.toString(), "schema", SCHEMA)));
            source.open();
            assertThatThrownBy(() -> countRows(source))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("'id'")
                    .hasMessageContaining("INT64")
                    .hasMessageContaining("notanumber");
        }
    }

    @Test
    void aNullInANotNullColumnIsRefused(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("bad.csv");
        Files.writeString(input, "1,,1.0,true,x\n");
        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            source.configure(ctx(Map.of("path", input.toString(), "schema", SCHEMA, "null.literal", "")));
            source.open();
            assertThatThrownBy(() -> countRows(source))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("NOT NULL")
                    .hasMessageContaining("user");
        }
    }

    @Test
    void anUnreadableFileFailsAtOpenNotAtFirstPoll(@TempDir Path dir) {
        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            source.configure(ctx(Map.of("path", dir.resolve("absent.csv").toString(), "schema", SCHEMA)));
            assertThatThrownBy(source::open)
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("cannot read");
        }
    }

    @Test
    void aMultiCharacterDelimiterIsRejected(@TempDir Path dir) {
        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            assertThatThrownBy(() -> source.configure(
                            ctx(Map.of("path", dir.resolve("x").toString(), "schema", SCHEMA, "delimiter", "||"))))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("single character");
        }
    }

    @Test
    void aMissingRequiredSettingListsWhatWasProvided(@TempDir Path dir) {
        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            assertThatThrownBy(() -> source.configure(ctx(Map.of("schema", SCHEMA))))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("PRV-5001")
                    .hasMessageContaining("'path'")
                    .hasMessageContaining("schema");
        }
    }

    // ------------------------------------------------------------------ capabilities

    @Test
    void capabilitiesAreDeclaredHonestly(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("in.csv");
        Files.writeString(input, "1,a,1.0,true,x\n");
        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            source.configure(ctx(Map.of("path", input.toString(), "schema", SCHEMA)));
            var caps = source.capabilities();
            // A byte position genuinely resumes, so exactly-once is honest.
            assertThat(caps.replayableOffsets()).isTrue();
            assertThat(caps.guarantee()).isEqualTo(DeliveryGuarantee.EXACTLY_ONCE);
            // A file cannot express a delete or a before-image, and saying so lets the planner
            // refuse a query that needs one instead of producing a view holding dead rows.
            assertThat(caps.emitsDeletes()).isFalse();
            assertThat(caps.emitsBeforeImage()).isFalse();
            assertThat(caps.pushdown()).isEmpty();
        }

        try (FilesystemSinkPlugin sink = new FilesystemSinkPlugin()) {
            sink.configure(ctx(Map.of("path", dir.resolve("o.csv").toString(), "schema", SCHEMA)));
            var caps = sink.capabilities();
            assertThat(caps.accepts(EmitMode.APPEND)).isTrue();
            assertThat(caps.accepts(EmitMode.UPSERT)).as("a file cannot upsert").isFalse();
            assertThat(caps.idempotentUpsert())
                    .as("a replay appends the rows again")
                    .isFalse();
            assertThat(caps.guarantee()).isEqualTo(DeliveryGuarantee.AT_LEAST_ONCE);
        }
    }

    @Test
    void appendModeAddsToAnExistingFile(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("out.csv");
        Files.writeString(out, "existing,line,here,now,ok\n");
        try (FilesystemSinkPlugin sink = new FilesystemSinkPlugin()) {
            sink.configure(ctx(Map.of("path", out.toString(), "schema", SCHEMA, "append", "true")));
            sink.open();
            sink.flush();
        }
        assertThat(Files.readAllLines(out)).hasSize(1).first().asString().startsWith("existing");
    }

    // ------------------------------------------------------------------ helpers

    /** Reads every row into arena-backed views, mirroring how the engine will drive a reader. */

    /**
     * Reads every row and hands them to {@code body} <em>while the arena is still open</em>.
     *
     * <p>Rows are flyweights into arena memory, so they are valid only for as long as the arena
     * lives (design section 8.5). An earlier version of this helper returned the list and closed the
     * arena first; the arena's use-after-close guard caught it, which is exactly what that guard is
     * for. Anything needing to outlive the arena must copy.
     */
    private static void withRows(FilesystemSourcePlugin source, java.util.function.Consumer<List<RowView>> body) {
        try (PartitionReader reader =
                        source.createReader(source.partitions("txn").get(0), null);
                Collector collector = new Collector(source.schema())) {
            while (reader.poll(collector, 64) > 0) {
                // keep polling until the file is exhausted
            }
            body.accept(List.copyOf(collector.rows));
        }
    }

    /** Counts rows without holding on to them past the arena's lifetime. */
    private static int countRows(FilesystemSourcePlugin source) {
        int[] count = {0};
        withRows(source, rows -> count[0] = rows.size());
        return count[0];
    }

    /** Collects decoded rows into an arena, as a lane would. */
    // ------------------------------------------------------------------ written dates and times

    @Test
    void aTimeWrittenTheWayAPersonWritesItIsStoredInTheEnginesUnits(@TempDir Path dir) throws IOException {
        // A TIME column read a bare number and stored it unscaled, and the engine holds a time as
        // nanoseconds of day. So the natural spelling -- 3600000 milliseconds for an hour -- became
        // 3.6 milliseconds, and every `WHERE tm < TIME '00:00:01'` over that data returned the
        // wrong rows with a success status. There was no spelling that worked: comparing the column
        // to a bare integer is separately refused.
        Path input = dir.resolve("times.csv");
        Files.writeString(input, "1,01:00:00\n2,00:00:01\n3,23:59:59.999\n");

        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            source.configure(ctx(Map.of("path", input.toString(), "schema", "id:INT64,tm:TIME")));
            source.open();
            try (PartitionReader reader =
                            source.createReader(source.partitions("txn").get(0), null);
                    Collector out = new Collector(source.schema())) {
                while (reader.poll(out, 100) > 0) {
                    // drain
                }
                assertThat(out.rows).hasSize(3);
                assertThat(out.rows.get(0).getLong(1))
                        .as("an hour is 3,600,000,000,000 nanoseconds, not 3,600,000")
                        .isEqualTo(3_600_000_000_000L);
                assertThat(out.rows.get(1).getLong(1)).isEqualTo(1_000_000_000L);
                assertThat(out.rows.get(2).getLong(1)).isEqualTo(86_399_999_000_000L);
            }
        }
    }

    @Test
    void aDateAndATimestampMayBeWrittenOrGivenInTheEnginesUnits(@TempDir Path dir) throws IOException {
        // Both spellings, because a file this codec wrote uses the engine's units and must read
        // back identically, while a file a person wrote uses ISO-8601 and must mean what it says.
        Path input = dir.resolve("stamps.csv");
        Files.writeString(input, "1,2026-09-14,2026-09-14T00:00:01Z\n2,20345,1000000000\n");

        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            source.configure(ctx(Map.of("path", input.toString(), "schema", "id:INT64,d:DATE,ts:TIMESTAMP")));
            source.open();
            try (PartitionReader reader =
                            source.createReader(source.partitions("txn").get(0), null);
                    Collector out = new Collector(source.schema())) {
                while (reader.poll(out, 100) > 0) {
                    // drain
                }
                assertThat(out.rows.get(0).getInt(1))
                        .as("2026-09-14 as days since the epoch")
                        .isEqualTo((int) java.time.LocalDate.of(2026, 9, 14).toEpochDay());
                assertThat(out.rows.get(0).getLong(2))
                        .as("one second past the epoch day, in nanoseconds")
                        .isEqualTo(
                                java.time.Instant.parse("2026-09-14T00:00:01Z").getEpochSecond() * 1_000_000_000L);
                assertThat(out.rows.get(1).getInt(1))
                        .as("a bare number stays days")
                        .isEqualTo(20345);
                assertThat(out.rows.get(1).getLong(2))
                        .as("a bare number stays nanoseconds")
                        .isEqualTo(1_000_000_000L);
            }
        }
    }

    // ------------------------------------------------------------------ follow (tail -f)

    private static final String SMALL = "id:INT64,user:STRING";

    /** Drains everything available right now, returning the ids read. */
    private static List<Long> drainIds(PartitionReader reader, StreamSchema schema) {
        List<Long> ids = new ArrayList<>();
        try (Collector out = new Collector(schema)) {
            while (reader.poll(out, 100) > 0) {
                // keep draining
            }
            out.rows.forEach(row -> ids.add(row.getLong(0)));
        }
        return ids;
    }

    @Test
    void followingSeesRowsAppendedAfterTheReaderCaughtUp(@TempDir Path dir) throws IOException {
        // The whole point. Without follow the reader latches exhausted at the first null read, so a
        // continuous query over a file kept a view it would never update again -- and reported
        // RUNNING while doing it.
        Path input = dir.resolve("live.csv");
        Files.writeString(input, "1,ann\n2,bob\n");

        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            source.configure(ctx(Map.of("path", input.toString(), "schema", SMALL, "follow", "true")));
            source.open();
            try (PartitionReader reader =
                    source.createReader(source.partitions("txn").get(0), null)) {
                assertThat(drainIds(reader, source.schema())).containsExactly(1L, 2L);

                // Caught up. A non-following reader is finished for ever at this point.
                assertThat(drainIds(reader, source.schema())).isEmpty();

                Files.writeString(input, "3,cat\n", java.nio.file.StandardOpenOption.APPEND);
                assertThat(drainIds(reader, source.schema()))
                        .as("appended after the reader caught up, and still delivered")
                        .containsExactly(3L);
            }
        }
    }

    @Test
    void followingWaitsForTheNewlineRatherThanReadingHalfALine(@TempDir Path dir) throws IOException {
        // readLine returns whatever is there at end of file, terminated or not. Tailing a file
        // being appended to would therefore read half a line as a record, decode it against the
        // schema, and read the other half as a second record -- both wrong, neither looking it.
        Path input = dir.resolve("live.csv");
        Files.writeString(input, "1,ann\n");

        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            source.configure(ctx(Map.of("path", input.toString(), "schema", SMALL, "follow", "true")));
            source.open();
            try (PartitionReader reader =
                    source.createReader(source.partitions("txn").get(0), null)) {
                assertThat(drainIds(reader, source.schema())).containsExactly(1L);

                // A writer mid-line: the row is not finished, so it is not a row yet.
                Files.writeString(input, "2,bo", java.nio.file.StandardOpenOption.APPEND);
                assertThat(drainIds(reader, source.schema()))
                        .as("half a line is not a record")
                        .isEmpty();

                Files.writeString(input, "b\n", java.nio.file.StandardOpenOption.APPEND);
                assertThat(drainIds(reader, source.schema()))
                        .as("and the line arrives whole, once, when its newline does")
                        .containsExactly(2L);
            }
        }
    }

    @Test
    void followingPicksUpAFileThatWasRotatedUnderneathIt(@TempDir Path dir) throws IOException {
        // Log rotation and an atomic rewrite look the same from here: the path now names a
        // different file. A reader holding the old handle would sit on something nobody can see any
        // more and deliver nothing for ever, which is the failure mode tailing exists to avoid.
        Path input = dir.resolve("live.csv");
        Files.writeString(input, "1,ann\n2,bob\n");

        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            source.configure(ctx(Map.of("path", input.toString(), "schema", SMALL, "follow", "true")));
            source.open();
            try (PartitionReader reader =
                    source.createReader(source.partitions("txn").get(0), null)) {
                assertThat(drainIds(reader, source.schema())).containsExactly(1L, 2L);

                Files.move(input, dir.resolve("live.csv.1"));
                Files.writeString(input, "7,zoe\n");

                assertThat(drainIds(reader, source.schema()))
                        .as("the replacement is read from its start, not from the old file's offset")
                        .containsExactly(7L);
            }
        }
    }

    @Test
    void followingIsOffByDefaultSoABoundedReadStillEnds(@TempDir Path dir) throws IOException {
        // Every existing binding means a bounded read by "a file", and a source that stopped ending
        // would change what those queries do. Opt in, or nothing changes.
        Path input = dir.resolve("once.csv");
        Files.writeString(input, "1,ann\n");

        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            source.configure(ctx(Map.of("path", input.toString(), "schema", SMALL)));
            source.open();
            try (PartitionReader reader =
                    source.createReader(source.partitions("txn").get(0), null)) {
                assertThat(drainIds(reader, source.schema())).containsExactly(1L);

                Files.writeString(input, "2,bob\n", java.nio.file.StandardOpenOption.APPEND);
                assertThat(drainIds(reader, source.schema()))
                        .as("a bounded read is finished when the file ends, and stays finished")
                        .isEmpty();
            }
        }
    }

    private static final class Collector implements PartitionReader.RecordSink, AutoCloseable {
        private final RowLayout layout;
        private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 32);
        private final BinaryRowWriter writer;
        final List<RowView> rows = new ArrayList<>();
        private long pending = ArenaHandle.NULL;

        Collector(StreamSchema schema) {
            this.layout = RowLayout.of(schema);
            this.writer = new BinaryRowWriter(layout);
        }

        @Override
        public RowWriter beginRow() {
            pending = arena.allocate(layout.rowSize(512));
            writer.begin(arena.regionOf(pending), arena.offsetOf(pending));
            long handle = pending;
            return new com.ash.messaging.pravaha.plugin.filesystem.CollectingWriter(
                    writer,
                    () -> rows.add(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))));
        }

        @Override
        public void close() {
            arena.close();
        }
    }
}
