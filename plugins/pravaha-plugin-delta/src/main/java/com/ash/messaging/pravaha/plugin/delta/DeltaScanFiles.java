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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.delta.kernel.Scan;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.data.ScanStateRow;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * The one place that reaches into Delta Kernel's internals, quarantined on purpose.
 *
 * <p>Turning a scan file row into a {@code FileStatus}, and a scan state into the physical read
 * schema, has no public API in Kernel 4.0 -- Delta's own connector examples use
 * {@code InternalScanFileUtils} and {@code ScanStateRow} for exactly this. Rather than sprinkle
 * internal imports through the plugin, they live here, so the day Kernel publishes an API (or
 * changes these) there is one file to fix and a test that will say so.
 */
final class DeltaScanFiles {

    private DeltaScanFiles() {}

    /**
     * One file of a scan, with the log row that describes it.
     *
     * <p>The row is carried rather than reconstructed. It holds the file's partition values, and
     * Kernel needs those to rebuild the logical row -- a partition column lives in the directory
     * name, not in the Parquet file. A synthesised row loses them, and the failure is not subtle:
     * the transform dereferences a null map.
     */
    record ScanFile(String path, FileStatus status, Row row) {}

    /**
     * The files a scan will read, in a stable order.
     *
     * <p>Sorted by path. Kernel's iteration order is not promised to be stable across calls, and an
     * offset that names "file 3 of version 7" is worthless unless "file 3" means the same file every
     * time -- including after a restart, in a different JVM.
     */
    static List<ScanFile> listFiles(Engine engine, Scan scan) {
        List<ScanFile> files = new ArrayList<>();
        try (CloseableIterator<FilteredColumnarBatch> batches = scan.getScanFiles(engine)) {
            while (batches.hasNext()) {
                try (CloseableIterator<Row> rows = batches.next().getRows()) {
                    while (rows.hasNext()) {
                        Row scanFileRow = rows.next();
                        rejectDeletionVector(scanFileRow);
                        FileStatus status = InternalScanFileUtils.getAddFileStatus(scanFileRow);
                        files.add(new ScanFile(status.getPath(), status, scanFileRow));
                    }
                }
            }
        } catch (IOException e) {
            throw new PravahaException(DeltaErrors.READ_FAILED, "cannot list the table's data files: " + e, e);
        }
        files.sort(java.util.Comparator.comparing(ScanFile::path));
        return files;
    }

    /**
     * Refuses a file carrying a deletion vector.
     *
     * <p>A deletion vector marks individual rows as deleted <em>without rewriting the file</em>.
     * This plugin derives changes by diffing file lists between versions, so a deletion vector is
     * invisible to it: the file is neither added nor removed, and the rows it hides would keep being
     * emitted as though they were still there. Refusing is the only honest response -- a query would
     * otherwise return rows the table says are deleted, indefinitely, with nothing to indicate it.
     */
    private static void rejectDeletionVector(Row scanFileRow) {
        Row addFile = scanFileRow.getStruct(InternalScanFileUtils.ADD_FILE_ORDINAL);
        int ordinal = addFile.getSchema().indexOf("deletionVector");
        if (ordinal >= 0 && !addFile.isNullAt(ordinal)) {
            throw new PravahaException(
                    DeltaErrors.UNSUPPORTED_FEATURE,
                    "this table uses deletion vectors, which this plugin cannot read. Changes here are "
                            + "derived by diffing each version's file list, and a deletion vector deletes rows "
                            + "without rewriting the file -- so the deleted rows would keep being served as "
                            + "live, silently. Set delta.enableDeletionVectors=false on the table, or wait for "
                            + "the change-data-feed reader.");
        }
    }

    /**
     * Reads one data file, already mapped back to the table's logical schema.
     *
     * <p>The retraction path reads a file the current version has <em>removed</em>, using the scan
     * file row recorded by the version before it. That works because Delta keeps removed files on
     * disk until {@code VACUUM}; when it has not, this fails loudly rather than dropping the
     * retractions, because a lost retraction leaves deleted rows alive in a maintained view
     * permanently.
     */
    static CloseableIterator<FilteredColumnarBatch> readFile(Engine engine, Row scanState, ScanFile file) {
        try {
            StructType physicalSchema = ScanStateRow.getPhysicalDataReadSchema(engine, scanState);
            CloseableIterator<ColumnarBatch> physical = engine.getParquetHandler()
                    .readParquetFiles(
                            Utils.singletonCloseableIterator(file.status()),
                            physicalSchema,
                            java.util.Optional.empty());
            // Applies column mapping and partition-value injection: what comes back is the logical
            // row the table's schema describes, not the file's physical layout.
            return Scan.transformPhysicalData(engine, scanState, file.row(), physical);
        } catch (IOException e) {
            throw new PravahaException(
                    DeltaErrors.READ_FAILED,
                    "cannot read data file " + file.path() + ": " + e
                            + ". If a later commit removed this file and it has since been vacuumed, its "
                            + "retractions cannot be reconstructed and deleted rows would stay alive.",
                    e);
        }
    }
}
