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
package com.ash.messaging.pravaha.plugin.mysqlcdc;

import java.io.IOException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * {@code mysql-cdc}: change data capture from one MySQL table through the row-based binary log, on
 * ADR-041's model and without Debezium.
 *
 * <p>The plugin registers with the server as a replica and reads the binary log from a transaction
 * boundary. An insert arrives at {@code +1}, a delete as the whole old row at {@code -1}, an update
 * as both, a transaction whole and only once committed ({@link TransactionAssembler}).
 *
 * <p><strong>What open refuses</strong>, naming the fix ({@code PRV-5152}): binary logging off,
 * {@code binlog_format} other than {@code ROW}, {@code binlog_row_image} other than {@code FULL}
 * (a partial before-image would retract nothing), compressed transactions, and a user without
 * {@code REPLICATION SLAVE} and {@code REPLICATION CLIENT}. A column type with no exact mapping is
 * {@code PRV-5153}; {@code snapshot.mode: initial} is {@code PRV-5150}, not built yet.
 *
 * <p><strong>The guarantee is {@code EXACTLY_ONCE}.</strong> The position is a binlog file and
 * offset at a transaction boundary, and replay from it is deterministic, so a restore re-delivers
 * exactly what the checkpoint does not hold. MySQL keeps binlog files by time ({@code
 * binlog_expire_logs_seconds}), not by what a replica has read, so a restore whose file has been
 * purged is refused ({@code PRV-5155}) rather than resumed from wherever the log now starts.
 *
 * <p>A registration with no checkpoint reads changes from the end of the log as it stood when the
 * plugin opened. One partition, one replica connection per reader.
 */
public final class MySqlCdcSourcePlugin implements StreamSourcePlugin {

    private MySqlCdcOptions options;
    private MySqlSchema.Mapping mapping;
    private BinlogOffset openedAt;
    private final List<MySqlCdcReader> readers = new CopyOnWriteArrayList<>();

    @Override
    public String name() {
        return "mysql-cdc";
    }

    @Override
    public Version version() {
        return new Version(0, 1, 0);
    }

    @Override
    public void configure(PluginContext context) {
        this.options = MySqlCdcOptions.from(context);
    }

    @Override
    public void open() {
        requireConfigured();
        try (MySqlClient client = connect()) {
            MySqlPreflight.check(client, options);
            mapping = MySqlSchema.resolve(options, MySqlSchema.load(client, options));
            openedAt = MySqlPreflight.current(client, options);
        } catch (IOException e) {
            throw new PravahaException(
                    MySqlCdcErrors.CONNECT_FAILED,
                    "plugin '" + options.instanceName() + "' cannot prepare " + options.qualifiedTable() + " at "
                            + options.host() + ":" + options.port() + " for capture: " + e.getMessage(),
                    e);
        }
    }

    private MySqlClient connect() throws IOException {
        return MySqlClient.connect(options);
    }

    @Override
    public SourceCapabilities capabilities() {
        return new SourceCapabilities(
                // Any offset handed out names a binlog file and offset the reader can start from.
                true,
                // One replica connection, commit order.
                true,
                // Deletes, and before-images under binlog_row_image = FULL, which open insists on.
                true,
                true,
                DeliveryGuarantee.EXACTLY_ONCE,
                EnumSet.noneOf(PushdownKind.class),
                Duration.ofMillis(50),
                // Never repeats: an update is a retraction and an insertion.
                false);
    }

    @Override
    public List<StreamSchema> discoverSchemas() {
        requireOpen();
        return List.of(mapping.schema());
    }

    @Override
    public List<SourcePartition> partitions(String stream) {
        requireConfigured();
        return List.of(new SourcePartition(stream, 0, Map.of("table", options.qualifiedTable())));
    }

    @Override
    public PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom) {
        requireOpen();
        BinlogOffset requested = BinlogOffset.parse(resumeFrom);
        BinlogOffset start = requested == null ? openedAt : requested;
        if (requested != null) {
            try (MySqlClient client = connect()) {
                MySqlPreflight.requireRetained(client, options, requested);
            } catch (IOException e) {
                throw new PravahaException(
                        MySqlCdcErrors.CONNECT_FAILED,
                        "plugin '" + options.instanceName() + "' cannot list the server's binlog files: "
                                + e.getMessage(),
                        e);
            }
        }
        BinlogStream stream = new BinlogStream(options, mapping, start);
        stream.start();
        try {
            stream.awaitConnected(options.startTimeout());
        } catch (RuntimeException e) {
            stream.close();
            throw e;
        }
        MySqlCdcReader reader = new MySqlCdcReader(stream, mapping.schema(), start);
        readers.removeIf(MySqlCdcReader::isClosed);
        readers.add(reader);
        return reader;
    }

    /** Healthy while every open reader's binlog connection is up. */
    @Override
    public HealthStatus health() {
        if (mapping == null) {
            return HealthStatus.unhealthy("not open");
        }
        for (MySqlCdcReader reader : readers) {
            if (reader.isClosed()) {
                continue;
            }
            if (reader.stream().failure() != null) {
                return HealthStatus.unhealthy(
                        "the reader has stopped: " + reader.stream().failure().getMessage());
            }
            if (!reader.stream().lastProblem().isEmpty()) {
                return HealthStatus.degraded("the reader is " + reader.stream().lastProblem() + " ("
                        + reader.stream().reconnects() + " reconnects so far)");
            }
        }
        return new HealthStatus(
                HealthStatus.State.HEALTHY, "capturing " + options.qualifiedTable() + " from the binary log");
    }

    @Override
    public void close() {
        for (MySqlCdcReader reader : readers) {
            reader.close();
        }
        readers.clear();
        mapping = null;
    }

    /** The stream this plugin exposes, once open. */
    public StreamSchema schema() {
        requireOpen();
        return mapping.schema();
    }

    private void requireConfigured() {
        if (options == null) {
            throw new ConfigurationException(MySqlCdcErrors.BAD_CONFIGURATION, "mysql-cdc is not configured");
        }
    }

    private void requireOpen() {
        requireConfigured();
        if (mapping == null) {
            throw new IllegalStateException("mysql-cdc plugin '" + options.instanceName() + "' is not open");
        }
    }
}
