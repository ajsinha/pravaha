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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.List;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * Writes delimited lines to a file.
 *
 * <p>Append-only, and it says so. A file cannot express an upsert or a retraction, so the planner
 * refuses a query whose plan produces updates rather than letting it append contradictory rows that
 * look plausible until someone tries to reconcile them (design section 15.5).
 *
 * <p>Configuration: {@code path} (required), {@code schema} (required), {@code delimiter}
 * (default {@code ,}), {@code null.literal} (default empty), {@code append} (default {@code true}),
 * {@code flush.every.batch} (default {@code true}).
 *
 * <p>{@code append} defaults to true because a server opens its sinks again on every restart, and
 * the restored view does not send what it wrote before the checkpoint a second time: emptying the
 * file on open threw that output away (HLP-2). Appending is what at-least-once means for a file,
 * so a restart may repeat the rows written after the last checkpoint. {@code append: false} starts
 * the file empty on every open, a restart included; a one-shot run ({@code pravaha run}) asks for
 * it, since its whole answer is written in one go.
 */
public final class FilesystemSinkPlugin implements StreamSinkPlugin {

    private Path path;
    private StreamSchema schema;
    private DelimitedCodec codec;
    private boolean append;
    private boolean flushEveryBatch;
    private BufferedWriter writer;
    private long rowsWritten;

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
        this.path = Path.of(context.require("path"));
        this.schema = FilesystemSourcePlugin.parseSchema(context.instanceName(), context.require("schema"));
        this.codec =
                new DelimitedCodec(schema, context.get("delimiter", ",").charAt(0), context.get("null.literal", ""));
        this.append = Boolean.parseBoolean(context.get("append", "true"));
        this.flushEveryBatch = Boolean.parseBoolean(context.get("flush.every.batch", "true"));
    }

    @Override
    public void open() {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            writer = append
                    ? Files.newBufferedWriter(
                            path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                    : Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigurationException(
                    DelimitedCodec.DECODE_FAILED, "cannot open " + path.toAbsolutePath() + " for writing", e);
        }
    }

    /** The columns this file is written with; a query registered against it must produce them. */
    @Override
    public java.util.Optional<StreamSchema> schema() {
        return java.util.Optional.ofNullable(schema);
    }

    @Override
    public SinkCapabilities capabilities() {
        // Append-only and honest about it: no upsert, no transaction, and writes are not idempotent
        // because a replay appends the rows again.
        return new SinkCapabilities(EnumSet.of(EmitMode.APPEND), false, false, 0);
    }

    @Override
    public int write(List<RowView> batch) {
        if (writer == null) {
            throw new IllegalStateException("sink is not open");
        }
        try {
            for (RowView row : batch) {
                writer.write(codec.encode(row));
                writer.newLine();
                rowsWritten++;
            }
            if (flushEveryBatch) {
                // Default on: for the plugin that tests read back, a buffered write that has not
                // reached the file is indistinguishable from a bug in whatever produced it.
                writer.flush();
            }
        } catch (IOException e) {
            throw new ConfigurationException(DelimitedCodec.DECODE_FAILED, "write failed to " + path, e);
        }
        return batch.size();
    }

    @Override
    public void flush() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
        } catch (IOException e) {
            throw new ConfigurationException(DelimitedCodec.DECODE_FAILED, "flush failed on " + path, e);
        }
    }

    public long rowsWritten() {
        return rowsWritten;
    }

    @Override
    public void close() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException e) {
            throw new ConfigurationException(DelimitedCodec.DECODE_FAILED, "close failed on " + path, e);
        } finally {
            writer = null;
        }
    }
}
