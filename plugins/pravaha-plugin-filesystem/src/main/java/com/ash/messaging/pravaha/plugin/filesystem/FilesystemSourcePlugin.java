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
    private StreamSchema schema;
    private char delimiter;
    private String nullLiteral;
    private boolean skipHeader;
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
        this.schema = parseSchema(context.instanceName(), context.require("schema"));
        String d = context.get("delimiter", ",");
        if (d.length() != 1) {
            throw new ConfigurationException(
                    DelimitedCodec.DECODE_FAILED,
                    "plugin '" + context.instanceName() + "' delimiter must be a single character, got '" + d + "'");
        }
        this.delimiter = d.charAt(0);
        this.nullLiteral = context.get("null.literal", "");
        this.skipHeader = Boolean.parseBoolean(context.get("skip.header", "false"));
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
    public static StreamSchema parseSchema(String streamName, String spec) {
        StreamSchema.Builder builder = StreamSchema.builder(streamName);
        for (String column : spec.split(",")) {
            String[] parts = column.strip().split(":");
            if (parts.length != 2) {
                throw new ConfigurationException(
                        DelimitedCodec.DECODE_FAILED,
                        "schema entry '" + column.strip() + "' is not 'name:TYPE'. Example: id:INT64,name:STRING");
            }
            builder.field(parts[0].strip(), typeFor(parts[1].strip()));
        }
        return builder.build();
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
                    default ->
                        throw new ConfigurationException(
                                DelimitedCodec.DECODE_FAILED,
                                "unknown type '" + name + "'. Supported: BOOLEAN, INT8, INT16, INT32, INT64, "
                                        + "FLOAT32, FLOAT64, STRING, BYTES, TIMESTAMP. Suffix with ? for nullable.");
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
                // A file cannot express a delete or a before-image, and saying so is what lets the
                // planner refuse a query that needs one.
                false,
                false,
                DeliveryGuarantee.EXACTLY_ONCE,
                EnumSet.noneOf(com.ash.messaging.pravaha.api.plugin.PushdownKind.class),
                Duration.ZERO);
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
        return new FilesystemPartitionReader(
                path, new DelimitedCodec(schema, delimiter, nullLiteral), skipHeader, resumeFrom);
    }

    StreamSchema schema() {
        return schema;
    }
}
