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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.delta.kernel.Scan;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.data.ColumnVector;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.types.BinaryType;
import io.delta.kernel.types.BooleanType;
import io.delta.kernel.types.ByteType;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.DateType;
import io.delta.kernel.types.DecimalType;
import io.delta.kernel.types.DoubleType;
import io.delta.kernel.types.FloatType;
import io.delta.kernel.types.IntegerType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.ShortType;
import io.delta.kernel.types.TimestampType;
import org.apache.hadoop.conf.Configuration;

/**
 * Reads a Delta table back, as the assertions want to see it: one string per row, columns joined by
 * {@code |}, sorted.
 *
 * <p>Through Kernel's own reader rather than through this plugin's source, so a test of the sink
 * fails when the sink is wrong and not when both halves are wrong in the same way.
 */
final class DeltaTableReader {

    private DeltaTableReader() {}

    /** The table's rows, sorted. Empty when there is no table there yet. */
    static List<String> read(String path) {
        Engine engine = DefaultEngine.create(new Configuration());
        Snapshot snapshot;
        try {
            snapshot = Table.forPath(engine, path).getLatestSnapshot(engine);
        } catch (TableNotFoundException absent) {
            return List.of();
        }
        Scan scan = snapshot.getScanBuilder().build();
        Row scanState = scan.getScanState(engine);
        List<DataType> types = DeltaTypes.columnTypes(snapshot.getSchema());
        List<String> rows = new ArrayList<>();
        for (DeltaScanFiles.ScanFile file : DeltaScanFiles.listFiles(engine, scan)) {
            try (io.delta.kernel.utils.CloseableIterator<FilteredColumnarBatch> batches =
                    DeltaScanFiles.readFile(engine, scanState, file)) {
                while (batches.hasNext()) {
                    append(batches.next(), types, rows);
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("cannot read " + file.path(), e);
            }
        }
        Collections.sort(rows);
        return rows;
    }

    /** How many commits the table's log holds, which is how many files a small-file count starts from. */
    static long version(String path) {
        Engine engine = DefaultEngine.create(new Configuration());
        try {
            return Table.forPath(engine, path).getLatestSnapshot(engine).getVersion();
        } catch (TableNotFoundException absent) {
            return -1L;
        }
    }

    /** How many data files the table's current snapshot references. */
    static int dataFiles(String path) {
        Engine engine = DefaultEngine.create(new Configuration());
        Snapshot snapshot = Table.forPath(engine, path).getLatestSnapshot(engine);
        return DeltaScanFiles.listFiles(engine, snapshot.getScanBuilder().build())
                .size();
    }

    private static void append(FilteredColumnarBatch filtered, List<DataType> types, List<String> rows) {
        ColumnarBatch batch = filtered.getData();
        for (int rowId = 0; rowId < batch.getSize(); rowId++) {
            if (filtered.getSelectionVector().isPresent()
                    && !filtered.getSelectionVector().get().getBoolean(rowId)) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            for (int ordinal = 0; ordinal < types.size(); ordinal++) {
                if (ordinal > 0) {
                    text.append('|');
                }
                text.append(value(batch.getColumnVector(ordinal), rowId, types.get(ordinal)));
            }
            rows.add(text.toString());
        }
    }

    private static Object value(ColumnVector vector, int rowId, DataType type) {
        if (vector.isNullAt(rowId)) {
            return "null";
        }
        if (type instanceof BooleanType) {
            return vector.getBoolean(rowId);
        } else if (type instanceof ByteType) {
            return vector.getByte(rowId);
        } else if (type instanceof ShortType) {
            return vector.getShort(rowId);
        } else if (type instanceof IntegerType || type instanceof DateType) {
            return vector.getInt(rowId);
        } else if (type instanceof LongType || type instanceof TimestampType) {
            return vector.getLong(rowId);
        } else if (type instanceof FloatType) {
            return vector.getFloat(rowId);
        } else if (type instanceof DoubleType) {
            return vector.getDouble(rowId);
        } else if (type instanceof DecimalType) {
            return vector.getDecimal(rowId);
        } else if (type instanceof BinaryType) {
            return java.util.Base64.getEncoder().encodeToString(vector.getBinary(rowId));
        }
        return vector.getString(rowId);
    }
}
