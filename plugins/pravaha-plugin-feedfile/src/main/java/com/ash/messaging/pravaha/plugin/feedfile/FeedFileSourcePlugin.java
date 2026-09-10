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
package com.ash.messaging.pravaha.plugin.feedfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.ash.messaging.pravaha.api.ConfigurationException;
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
 * Drop-directory feeds: files land in a directory and become a stream.
 *
 * <p>The most common real integration shape there is -- an end-of-day extract, an intraday feed, a
 * partner drop over SFTP, an hourly export -- and the one most likely to be dismissed as a test
 * fixture. It is Tier 1 (design section 19.7) for a reason that is technical rather than
 * sentimental: a file feed is the <em>easiest</em> source to make genuinely replayable, because the
 * bytes do not move and the order is declarable. That makes it the best available proof of the
 * exactly-once path, and its backfill story is free -- a directory of history is a bootstrap using
 * the same reader.
 *
 * <p><strong>Capabilities are computed from configuration, not declared once.</strong> The same
 * plugin is exactly-once with a completion marker and no archiving, and at-least-once with a
 * stability heuristic or with archiving turned on -- because in those configurations replay is
 * either a guess or impossible. Reporting the first number in the second situation is how a system
 * promises a guarantee it loses during the one recovery that matters.
 *
 * <p>Configuration: {@code dir} (required), {@code glob} (default {@code *.csv}), {@code format}
 * ({@code csv} or {@code parquet}, default inferred from the glob), {@code schema} (required),
 * {@code order} ({@code name} or {@code mtime}, default {@code name}), {@code completion}
 * ({@code marker}, {@code stable} or {@code immediate}, default {@code stable}),
 * {@code completion.marker.suffix} (default {@code .done}), {@code completion.quiet.ms} (default
 * {@code 5000}), {@code archive.dir}, {@code quarantine.dir}, {@code delimiter}, {@code skip.header},
 * {@code null.literal}.
 */
public final class FeedFileSourcePlugin implements StreamSourcePlugin {

    private String instanceName = "feedfile";
    private Path directory;
    private String streamName;
    private StreamSchema schema;
    private FeedDirectory feed;
    private Supplier<FeedRecordDecoder> decoders;
    private FeedDirectory.Completion completion;
    private Optional<Path> archiveDir = Optional.empty();
    private Optional<Path> quarantineDir = Optional.empty();

    @Override
    public String name() {
        return "feedfile";
    }

    @Override
    public Version version() {
        return new Version(0, 1, 0);
    }

    @Override
    public void configure(PluginContext context) {
        this.instanceName = context.instanceName();
        this.directory = Path.of(context.require("dir"));
        this.streamName = context.get("stream", instanceName);
        String glob = context.get("glob", "*.csv");
        this.schema = FeedSchemas.parse(streamName, context.require("schema"));

        String format = context.get("format", glob.toLowerCase(Locale.ROOT).endsWith(".parquet") ? "parquet" : "csv");
        this.decoders = decoderFactory(format, context);

        this.completion = switch (context.get("completion", "stable").toLowerCase(Locale.ROOT)) {
            case "marker" -> FeedDirectory.Completion.MARKER;
            case "stable" -> FeedDirectory.Completion.STABLE;
            case "immediate" -> FeedDirectory.Completion.IMMEDIATE;
            default ->
                throw new ConfigurationException(
                        FeedFileErrors.BAD_CONFIGURATION,
                        "plugin '" + instanceName + "' completion must be marker, stable or immediate, got '"
                                + context.get("completion", "") + "'");
        };
        FeedDirectory.Order order =
                switch (context.get("order", "name").toLowerCase(Locale.ROOT)) {
                    case "name" -> FeedDirectory.Order.NAME;
                    case "mtime" -> FeedDirectory.Order.MTIME;
                    default ->
                        throw new ConfigurationException(
                                FeedFileErrors.BAD_CONFIGURATION,
                                "plugin '" + instanceName + "' order must be name or mtime, got '"
                                        + context.get("order", "") + "'");
                };

        this.archiveDir = optionalPath(context.get("archive.dir", ""));
        this.quarantineDir = optionalPath(context.get("quarantine.dir", ""));
        this.feed = new FeedDirectory(
                directory,
                glob,
                completion,
                order,
                context.get("completion.marker.suffix", ".done"),
                Duration.ofMillis(Long.parseLong(context.get("completion.quiet.ms", "5000"))));
    }

    private Supplier<FeedRecordDecoder> decoderFactory(String format, PluginContext context) {
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "csv", "delimited" -> {
                String delimiter = context.get("delimiter", ",");
                if (delimiter.length() != 1) {
                    throw new ConfigurationException(
                            FeedFileErrors.BAD_CONFIGURATION,
                            "plugin '" + instanceName + "' delimiter must be a single character, got '" + delimiter
                                    + "'");
                }
                char separator = delimiter.charAt(0);
                String nullLiteral = context.get("null.literal", "");
                boolean skipHeader = Boolean.parseBoolean(context.get("skip.header", "false"));
                yield () -> new CsvDecoder(separator, nullLiteral, skipHeader);
            }
            case "parquet" -> ParquetDecoder::new;
            default ->
                throw new ConfigurationException(
                        FeedFileErrors.BAD_CONFIGURATION,
                        "plugin '" + instanceName + "' format must be csv or parquet, got '" + format + "'");
        };
    }

    private static Optional<Path> optionalPath(String value) {
        return value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
    }

    @Override
    public void open() {
        if (!Files.isDirectory(directory)) {
            throw new ConfigurationException(
                    FeedFileErrors.DIRECTORY_UNREADABLE,
                    "plugin '" + instanceName + "' feed directory " + directory.toAbsolutePath()
                            + " does not exist or is not a directory");
        }
    }

    @Override
    public void close() {}

    @Override
    public SourceCapabilities capabilities() {
        // Archiving moves a file out of the feed, so an offset naming it can no longer be resumed;
        // the stability heuristic cannot distinguish a finished transfer from a stalled one. Either
        // makes replay unavailable or unsound, and the guarantee follows from the configuration
        // rather than from what would look best in a table.
        boolean replayable = archiveDir.isEmpty() && completion != FeedDirectory.Completion.STABLE;
        return new SourceCapabilities(
                replayable,
                true,
                // A file feed states what is, not what changed: no deletes, no before-images. A
                // query needing either is refused at planning instead of quietly maintaining a view
                // that keeps rows the source has forgotten.
                false,
                false,
                replayable ? DeliveryGuarantee.EXACTLY_ONCE : DeliveryGuarantee.AT_LEAST_ONCE,
                EnumSet.noneOf(PushdownKind.class),
                Duration.ofSeconds(1));
    }

    @Override
    public List<StreamSchema> discoverSchemas() {
        return List.of(schema);
    }

    @Override
    public List<SourcePartition> partitions(String stream) {
        // One reader per directory. Splitting a drop directory across readers means assigning files
        // to readers, which is an assignment that has to survive a restart -- so it belongs with the
        // partition assignment machinery rather than being invented here.
        return List.of(new SourcePartition(stream, 0, Map.of("dir", directory.toString())));
    }

    @Override
    public PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom) {
        return new FeedFilePartitionReader(feed, schema, decoders, archiveDir, quarantineDir, resumeFrom);
    }

    /** The stream this feed exposes. */
    public StreamSchema schema() {
        return schema;
    }
}
