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
import java.util.Optional;

import io.delta.kernel.Scan;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.actions.DeletionVectorDescriptor;
import io.delta.kernel.internal.data.ScanStateRow;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * The one place that reaches into Delta Kernel's internals, quarantined on purpose.
 *
 * <p>Turning a scan file row into a {@code FileStatus}, reading its deletion vector's descriptor,
 * and turning a scan state into the physical read schema, have no public API in Kernel 4.0 -- Delta's own connector examples use
 * {@code InternalScanFileUtils} and {@code ScanStateRow} for exactly this. Rather than sprinkle
 * internal imports through the plugin, they live here, so the day Kernel publishes an API (or
 * changes these) there is one file to fix and a test that will say so.
 */
final class DeltaScanFiles {

    private DeltaScanFiles() {}

    /**
     * One file of a scan, with the log row that describes it.
     *
     * <p>The row is carried rather than reconstructed. It holds the file's partition values and its
     * deletion vector, and Kernel needs both to rebuild the logical rows -- a partition column lives
     * in the directory name, not in the Parquet file, and a deleted row is only marked deleted in the
     * vector. A synthesised row loses them, and the failure is not subtle: the transform
     * dereferences a null map.
     *
     * @param identity what makes two versions' entries the same entry: the path, and the deletion
     *     vector's unique id when the file has one. A {@code DELETE} on a table with deletion vectors
     *     leaves the file where it is and replaces its entry with one naming a new vector, so the
     *     path alone would see no change at all.
     */
    record ScanFile(String path, FileStatus status, Row row, String identity) {}

    /**
     * The files a scan will read, in a stable order.
     *
     * <p>Sorted by path. Kernel's iteration order is not promised to be stable across calls, and an
     * offset that names "file 3 of version 7" is worthless unless "file 3" means the same file every
     * time -- including after a restart, in a different JVM.
     */
    static List<ScanFile> listFiles(Engine engine, Scan scan) {
        return listFiles(engine, scan, true);
    }

    /**
     * The files a scan will read, refusing any that carries a deletion vector.
     *
     * <p>For {@code delta-sink}, whose copy-on-write merge rewrites whole files: it would have to
     * carry a file's deletion vector through the rewrite and record it on the removal, and it does
     * neither, so the rows the vector deletes would come back.
     */
    static List<ScanFile> listFilesWithoutDeletionVectors(Engine engine, Scan scan) {
        return listFiles(engine, scan, false);
    }

    private static List<ScanFile> listFiles(Engine engine, Scan scan, boolean deletionVectorsRead) {
        List<ScanFile> files = new ArrayList<>();
        try (CloseableIterator<FilteredColumnarBatch> batches = scan.getScanFiles(engine)) {
            while (batches.hasNext()) {
                try (CloseableIterator<Row> rows = batches.next().getRows()) {
                    while (rows.hasNext()) {
                        Row scanFileRow = rows.next();
                        Optional<DeletionVectorDescriptor> vector = deletionVectorOf(scanFileRow);
                        if (vector.isPresent() && !deletionVectorsRead) {
                            throw refusal();
                        }
                        FileStatus status = InternalScanFileUtils.getAddFileStatus(scanFileRow);
                        String identity = vector.map(dv -> status.getPath() + "#" + dv.getUniqueId())
                                .orElse(status.getPath());
                        files.add(new ScanFile(status.getPath(), status, scanFileRow, identity));
                    }
                }
            }
        } catch (IOException e) {
            throw new PravahaException(DeltaErrors.READ_FAILED, "cannot list the table's data files: " + e, e);
        }
        files.sort(java.util.Comparator.comparing(ScanFile::path));
        return files;
    }

    /** The file's deletion vector, when its {@code add} action names one. */
    private static Optional<DeletionVectorDescriptor> deletionVectorOf(Row scanFileRow) {
        Row addFile = scanFileRow.getStruct(InternalScanFileUtils.ADD_FILE_ORDINAL);
        int ordinal = addFile.getSchema().indexOf("deletionVector");
        if (ordinal < 0 || addFile.isNullAt(ordinal)) {
            return Optional.empty();
        }
        return Optional.of(DeletionVectorDescriptor.fromRow(addFile.getStruct(ordinal)));
    }

    private static PravahaException refusal() {
        return new PravahaException(
                DeltaErrors.UNSUPPORTED_FEATURE,
                "this table's data files carry deletion vectors, and delta-sink does not write such a table. "
                        + "Its upsert mode rewrites a file to remove rows from it, and it neither carries the file's "
                        + "deletion vector through the rewrite nor records it on the removal, so the rows the vector "
                        + "deletes would come back. Point the sink at a table without deletion vectors "
                        + "(delta.enableDeletionVectors=false, with the existing vectors purged by REORG).");
    }

    /**
     * Reads one data file, already mapped back to the table's logical schema.
     *
     * <p>A file with a deletion vector comes back with a selection vector: Kernel loads the deletion
     * vector named in the scan file row and marks the rows it deletes unselected. A reader must skip
     * those rows; the batch still holds them.
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
                            Utils.singletonCloseableIterator(file.status()), physicalSchema, Optional.empty());
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
