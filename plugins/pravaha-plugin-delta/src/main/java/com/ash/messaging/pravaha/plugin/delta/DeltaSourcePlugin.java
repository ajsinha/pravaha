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

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.TableNotFoundException;
import org.apache.hadoop.conf.Configuration;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * Reads a Delta Lake table as a continuous stream.
 *
 * <p>Built on <strong>Delta Kernel, not Spark</strong>. Kernel is the connector-facing library: it
 * understands the transaction log, protocol versions, checkpoints and column mapping, and it brings
 * Hadoop and Parquet with it but not a cluster. Requiring Spark to read a table would mean requiring
 * a second execution engine to feed the first, which is precisely the "extra hop" this product
 * exists to remove (design section 2.1).
 *
 * <p>The interesting property is in {@link DeltaPartitionReader}: the difference between two Delta
 * versions is a set of removed files and a set of added files, which is already a Z-set delta once
 * the removals are weighted {@code -1}. Delta's storage semantics and Pravaha's algebra agree
 * without an adapter in between.
 *
 * <p>Configuration: {@code path} (required, the table root); {@code stream} (optional, defaults to
 * the directory name); {@code start.version} (optional, defaults to the latest snapshot);
 * {@code event.time} (optional, a {@code TIMESTAMP} column whose value stamps each row's event time;
 * a server passes the stream's declared {@code eventTime} as this).
 */
public final class DeltaSourcePlugin implements StreamSourcePlugin {

    private String instanceName = "delta";
    private String path;
    private String streamName;
    private long startVersion = -1L;
    private String eventTimeColumn = "";
    private Engine engine;
    private Table table;
    private StreamSchema schema;

    @Override
    public String name() {
        return "delta";
    }

    @Override
    public Version version() {
        return new Version(0, 1, 0);
    }

    @Override
    public void configure(PluginContext context) {
        this.instanceName = context.instanceName();
        this.path = context.require("path");
        java.nio.file.Path asPath = java.nio.file.Path.of(path);
        this.streamName = context.get(
                "stream",
                asPath.getFileName() == null
                        ? instanceName
                        : asPath.getFileName().toString());
        this.eventTimeColumn = context.get("event.time", "").strip();
        String start = context.get("start.version", "");
        if (!start.isBlank()) {
            try {
                this.startVersion = Long.parseLong(start.strip());
            } catch (NumberFormatException e) {
                throw new com.ash.messaging.pravaha.api.ConfigurationException(
                        DeltaErrors.TABLE_UNREADABLE,
                        "plugin '" + instanceName + "' start.version must be a table version number, got '" + start
                                + "'",
                        e);
            }
        }
    }

    @Override
    public void open() {
        // A Hadoop Configuration with no site XML on the classpath is the local-filesystem default,
        // which is what a local table wants; an S3 or ADLS table supplies its own through the
        // plugin's isolated classpath rather than through the engine's.
        this.engine = DefaultEngine.create(new Configuration());
        this.table = Table.forPath(engine, path);
        try {
            Snapshot snapshot = startVersion < 0
                    ? table.getLatestSnapshot(engine)
                    : table.getSnapshotAsOfVersion(engine, startVersion);
            this.schema = withEventTime(DeltaTypes.toStreamSchema(streamName, snapshot.getSchema()));
        } catch (TableNotFoundException e) {
            throw new PravahaException(
                    DeltaErrors.TABLE_UNREADABLE,
                    "plugin '" + instanceName + "' found no Delta table at " + path
                            + ". A Delta table is a directory containing a _delta_log; a directory of Parquet "
                            + "files is not one.",
                    e);
        }
    }

    /**
     * Marks the {@code event.time} column as the schema's event time (HLP-6). Every row used to be
     * stamped zero whatever the table held, so a watermark never left 1970 and an event-time window
     * over a Delta table never closed.
     */
    private StreamSchema withEventTime(StreamSchema derived) {
        if (eventTimeColumn.isEmpty()) {
            return derived;
        }
        if (!derived.hasField(eventTimeColumn)
                || derived.field(derived.indexOf(eventTimeColumn)).type().typeName()
                        != com.ash.messaging.pravaha.api.data.TypeName.TIMESTAMP_LTZ) {
            throw new com.ash.messaging.pravaha.api.ConfigurationException(
                    DeltaErrors.TABLE_UNREADABLE,
                    "plugin '" + instanceName + "' event.time names '" + eventTimeColumn + "', which is not a "
                            + "TIMESTAMP column of the table at " + path + "; its columns are " + derived.fields());
        }
        StreamSchema.Builder builder = StreamSchema.builder(derived.name());
        derived.fields().forEach(field -> builder.field(field.name(), field.type()));
        return builder.eventTime(eventTimeColumn).build();
    }

    @Override
    public void close() {
        // Kernel's default engine holds no connection to release; the table and snapshot are
        // read-through views over the log.
        engine = null;
        table = null;
    }

    @Override
    public SourceCapabilities capabilities() {
        return new SourceCapabilities(
                // A version and a file index resume exactly: Delta versions are immutable and a
                // version's file list is deterministic.
                true,
                // Within a file, row order is the file's. Across the files of one commit there is no
                // order Delta defines, and claiming one would be inventing it.
                true,
                // Deletes are visible, as retractions: of a removed file's rows, and of the rows a
                // new deletion vector marks deleted in a file that stays.
                true,
                // The previous value arrives as a retraction of the whole old row, which is a
                // before-image in the Z-set sense; it is not a paired before/after image on one
                // record, and claiming that would mislead an operator that expects the pairing.
                false,
                DeliveryGuarantee.EXACTLY_ONCE,
                // Kernel's scan builder does take a predicate, and file-skipping from log statistics
                // is the obvious next step. Declaring pushdown before implementing it would make the
                // planner believe filtering had happened when it had not.
                EnumSet.noneOf(PushdownKind.class),
                Duration.ofSeconds(1),
                // Never repeats: a rewritten file's rows are retracted when its replacement's arrive,
                // so a row that survives a rewrite nets to one.
                false);
    }

    @Override
    public List<StreamSchema> discoverSchemas() {
        return List.of(schema);
    }

    @Override
    public List<SourcePartition> partitions(String stream) {
        // One partition per table in this version. Splitting by file group is the natural
        // parallelism -- Delta hands out a file list and the offsets are already per file -- but a
        // split assignment has to survive rescaling, so it belongs with the exchange work rather
        // than being improvised here.
        return List.of(new SourcePartition(stream, 0, Map.of("path", path)));
    }

    @Override
    public PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom) {
        return new DeltaPartitionReader(engine, table, partition.streamName(), startVersion, resumeFrom)
                .stampingEventTimeFrom(eventTimeColumn);
    }

    /** The stream this plugin exposes. */
    public StreamSchema schema() {
        return schema;
    }
}
