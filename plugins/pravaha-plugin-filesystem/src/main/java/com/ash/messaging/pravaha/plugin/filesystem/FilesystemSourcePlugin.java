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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * Reads delimited files.
 *
 * <p>The reference source plugin. It has no external dependency, so it exercises the SPI without a
 * store getting in the way, and it is what every integration test uses as a known-good input.
 *
 * <p>Its capabilities are worth reading as an example of honest declaration: a file <em>is</em>
 * replayable, because a byte offset genuinely resumes, so this source can support exactly-once. It
 * cannot express a delete or carry a before-image, so it says so, and a query needing either is
 * refused rather than silently producing a view that keeps rows which no longer exist.
 *
 * <p>Configuration: {@code path} (required), {@code schema} (required, {@code name:TYPE,...}),
 * {@code delimiter} (default {@code ,}), {@code null.literal} (default empty), {@code skip.header}
 * (default {@code false}).
 */
public final class FilesystemSourcePlugin implements StreamSourcePlugin {

    private Path path;
    private String opColumn = "";
    private java.util.Set<String> deleteMarkers = java.util.Set.of();
    private StreamSchema schema;
    private char delimiter;
    private String nullLiteral;
    private boolean skipHeader;

    /** Whether end of file means end of stream. See configure's `follow`. */
    private boolean follow;

    private String instanceName = "filesystem";

    @Override
    public String name() {
        return "filesystem";
    }

    @Override
    public Version version() {
        return new Version(0, 1, 0);
    }

    @Override
    public void configure(PluginContext context) {
        this.instanceName = context.instanceName();
        this.path = Path.of(context.require("path"));
        StreamSchema parsed = parseSchema(context.instanceName(), context.require("schema"));
        // The column carrying event time, if the deployment named one.
        //
        // The `name:TYPE` grammar has no way to mark a column, and this plugin decoded with a schema
        // that had no event-time ordinal -- so every row it produced carried event time zero even
        // when the engine's own copy of the schema knew better. A watermark derived from zero never
        // reaches a window in the present, which is why a windowed query ingested every row and
        // emitted nothing.
        // Which column says whether a row is an insertion or a retraction, if any does.
        //
        // Without this no configurable source could deliver a negative weight: four of five plugins
        // hard-code +1 and the name:TYPE grammar has no operation column. The whole Z-set model --
        // retraction, update as retract-plus-insert, a weight netting to zero -- had no route into a
        // configured deployment, which is why every defect in it survived so long.
        this.opColumn = context.get("op.column", "");
        this.deleteMarkers = java.util.Set.of(
                context.get("op.delete.values", "D,DELETE,-,-1").split(","));
        String eventTime = context.get("event.time", "");
        if (!eventTime.isBlank()) {
            if (!parsed.hasField(eventTime)) {
                throw new ConfigurationException(
                        DelimitedCodec.DECODE_FAILED,
                        "event.time names '" + eventTime + "', which is not a column of stream '"
                                + context.instanceName() + "'");
            }
            StreamSchema.Builder builder = StreamSchema.builder(context.instanceName());
            parsed.fields().forEach(field -> builder.field(field.name(), field.type()));
            parsed = builder.eventTime(eventTime).build();
        }
        this.schema = parsed;
        String d = context.get("delimiter", ",");
        if (d.length() != 1) {
            throw new ConfigurationException(
                    DelimitedCodec.DECODE_FAILED,
                    "plugin '" + context.instanceName() + "' delimiter must be a single character, got '" + d + "'");
        }
        this.delimiter = d.charAt(0);
        this.nullLiteral = context.get("null.literal", "");
        this.skipHeader = Boolean.parseBoolean(context.get("skip.header", "false"));
        // tail -f. Off by default, because a bounded read is what every existing binding means by a
        // file and a source that stopped ending would change what those queries do.
        //
        // With it on, end of file stops being end of stream: the reader returns what it has, is
        // polled again, and picks up whatever has been appended since -- which is what a continuous
        // query over a file needs and did not have. Without it the reader latched exhausted at the
        // first null read and the query kept a view it would never update again.
        this.follow = Boolean.parseBoolean(context.get("follow", "false"));
    }

    /**
     * Parses {@code name:TYPE,name:TYPE} into a schema.
     *
     * <p>Declared rather than inferred. Sniffing types from the first few lines guesses wrong on
     * exactly the columns that matter -- an identifier of all digits becomes an integer, and the
     * first row containing a letter fails at 3 a.m.
     *
     * <p>Public because the spec is part of this plugin's configuration contract: anything binding a
     * query to a filesystem stream needs the same schema the plugin will decode with, and deriving
     * it twice is how the two drift apart.
     */
    /**
     * Splits a schema string on the commas that separate columns, not the ones inside a type.
     *
     * <p>TY-7. {@code spec.split(",")} cut {@code amt:DECIMAL(10,2)} in half and reported
     * {@code unknown type 'DECIMAL(10'} — so {@code DECIMAL(p,s)} was unreachable through every
     * schema-string surface, while {@code typeFor}'s own refusal message went on listing it as
     * supported. The message was right about the engine and wrong about this door, which is the
     * worst combination: it sends the reader to check the type name, which is fine.
     */
    private static List<String> splitColumns(String spec) {
        List<String> columns = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            }
            if (c == ',' && depth == 0) {
                columns.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            columns.add(current.toString());
        }
        return columns;
    }

    public static StreamSchema parseSchema(String streamName, String spec) {
        StreamSchema.Builder builder = StreamSchema.builder(streamName);
        for (String column : splitColumns(spec)) {
            // Limit 2: a type may contain no colon today, but splitting greedily would break the
            // moment one does, and the failure would look like a malformed column rather than a
            // parser that ran out of road.
            String[] parts = column.strip().split(":", 2);
            if (parts.length != 2) {
                throw new ConfigurationException(
                        DelimitedCodec.DECODE_FAILED,
                        "schema entry '" + column.strip() + "' is not 'name:TYPE'. Example: id:INT64,name:STRING");
            }
            builder.field(parts[0].strip(), typeFor(parts[1].strip()));
        }
        return builder.build();
    }

    /**
     * {@code DECIMAL(p,s)}, or a refusal naming what can be written.
     *
     * <p>ARRAY, MAP and ROW are deliberately still refused rather than added. They map to Calcite's
     * {@code ANY}, whose inverse throws an exception naming an internal class, and a nested column
     * can be null-tested but never selected -- so accepting one here would move the failure from a
     * sentence at startup to an internal error at the first row.
     */
    private static PravahaType decimalOrRefusal(String original, String upper) {
        java.util.regex.Matcher decimal = java.util.regex.Pattern.compile("^DECIMAL\\((\\d+),\\s*(\\d+)\\)$")
                .matcher(upper);
        if (decimal.matches()) {
            return Types.decimal(Integer.parseInt(decimal.group(1)), Integer.parseInt(decimal.group(2)));
        }
        throw new ConfigurationException(
                DelimitedCodec.DECODE_FAILED,
                "unknown type '" + original + "'. Supported: BOOLEAN, INT8, INT16, INT32, INT64, FLOAT32, "
                        + "FLOAT64, STRING, BYTES, DATE, TIME, TIMESTAMP, DECIMAL(p,s). Suffix with ? for "
                        + "nullable. ARRAY, MAP and ROW are not supported by this engine at all.");
    }

    private static PravahaType typeFor(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        boolean nullable = upper.endsWith("?");
        if (nullable) {
            upper = upper.substring(0, upper.length() - 1);
        }
        PravahaType type =
                switch (upper) {
                    case "BOOLEAN", "BOOL" -> Types.bool();
                    case "INT8", "BYTE" -> Types.int8();
                    case "INT16", "SHORT" -> Types.int16();
                    case "INT32", "INT" -> Types.int32();
                    case "INT64", "LONG" -> Types.int64();
                    case "FLOAT32", "FLOAT" -> Types.float32();
                    case "FLOAT64", "DOUBLE" -> Types.float64();
                    case "STRING", "VARCHAR", "TEXT" -> Types.string();
                    case "BYTES", "BINARY" -> Types.bytes();
                    case "TIMESTAMP" -> Types.timestamp();
                    // DATE and TIME were absent, and this parser is the only way a schema is
                    // declared -- it is behind pravaha.streams.*.schema, POST /api/v1/streams,
                    // --schema and --out-schema alike. So six of the engine's sixteen types
                    // could not be named anywhere, and every line handling them downstream was
                    // dead code from a configured node's point of view.
                    case "DATE" -> Types.date();
                    case "TIME" -> Types.time();
                    default -> decimalOrRefusal(name, upper);
                };
        return nullable ? type.withNullable(true) : type;
    }

    @Override
    public void open() {
        if (!Files.isReadable(path)) {
            throw new ConfigurationException(
                    DelimitedCodec.DECODE_FAILED, "plugin '" + instanceName + "' cannot read " + path.toAbsolutePath());
        }
    }

    @Override
    public void close() {}

    @Override
    public SourceCapabilities capabilities() {
        return new SourceCapabilities(
                // A byte offset genuinely resumes, so exactly-once is honest here.
                true,
                true,
                // A plain file cannot express a delete, and saying so is what lets the planner
                // refuse a query that needs one. With op.column it can: a row whose operation is a
                // delete value arrives at weight -1, and PRV-2041 must know that before it lets the
                // query reach an append-only sink (HLP-15). Never a before-image: the file says only
                // which row leaves, not what it replaced.
                !opColumn.isEmpty(),
                false,
                DeliveryGuarantee.EXACTLY_ONCE,
                EnumSet.noneOf(com.ash.messaging.pravaha.api.plugin.PushdownKind.class),
                Duration.ZERO,
                // Never repeats: each line is read once, from a byte offset.
                false);
    }

    @Override
    public List<StreamSchema> discoverSchemas() {
        return List.of(schema);
    }

    @Override
    public List<SourcePartition> partitions(String streamName) {
        // One file, one partition. Splitting a delimited file by byte range means finding line
        // boundaries, which is real work for a plugin whose job is to be the simple one.
        List<SourcePartition> partitions = new ArrayList<>();
        partitions.add(new SourcePartition(streamName, 0, java.util.Map.of("path", path.toString())));
        return partitions;
    }

    @Override
    public PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom) {
        DelimitedCodec codec = new DelimitedCodec(schema, delimiter, nullLiteral);
        codec.markOperationColumn(opColumn);
        return new FilesystemPartitionReader(path, codec, skipHeader, resumeFrom, deleteMarkers, follow);
    }

    StreamSchema schema() {
        return schema;
    }
}
