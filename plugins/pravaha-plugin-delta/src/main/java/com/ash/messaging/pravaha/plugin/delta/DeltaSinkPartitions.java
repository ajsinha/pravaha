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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.delta.kernel.data.ColumnVector;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.expressions.Literal;
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
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructType;
import io.delta.kernel.types.TimestampType;
import io.delta.kernel.utils.CloseableIterator;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.plugin.delta.DeltaSinkRows.Change;

/**
 * Which partition of a Delta table each row a sink writes belongs to.
 *
 * <p>Delta keeps a partition column's value out of the data file: the file lives in a directory
 * named for its partition, and the {@code add} action that names it records the value in its
 * {@code partitionValues}. So one data file holds rows of one partition only, and a commit that
 * touches three partitions writes at least three files. This class groups a commit's rows by their
 * partition values and turns those values into the Kernel literals that Kernel's write path wants;
 * Kernel then chooses the directory, strips the columns from the Parquet and records the values in
 * the log.
 *
 * <p>An unpartitioned sink is the degenerate case: one group, with no values.
 */
final class DeltaSinkPartitions {

    private final StructType deltaSchema;
    private final int[] ordinals;

    /**
     * @param deltaSchema the table shape the sink writes
     * @param ordinals the partition columns' ordinals in {@code deltaSchema}, in partition order
     */
    DeltaSinkPartitions(StructType deltaSchema, int[] ordinals) {
        this.deltaSchema = deltaSchema;
        this.ordinals = ordinals.clone();
    }

    /** Whether the table is partitioned at all. */
    boolean partitioned() {
        return ordinals.length > 0;
    }

    /** The partition columns' names as the table spells them, in partition order. */
    List<String> names() {
        List<String> names = new ArrayList<>(ordinals.length);
        for (int ordinal : ordinals) {
            names.add(deltaSchema.at(ordinal).getName());
        }
        return names;
    }

    /**
     * Refuses a partition column that the sink cannot write, by name, at registration.
     *
     * <p>A partition column must be one of the query's output columns: its value comes from the row,
     * and a column the query does not produce has no value to put in a directory name. Changelog
     * mode's {@code _op} and {@code _weight} are the sink's own, not the query's, and are refused on
     * the same ground.
     *
     * <p>{@code BYTES} is refused although the Delta protocol lists binary among the partition types.
     * A partition value is a string in the log, and Kernel 4.0 writes a binary value as its bytes read
     * as UTF-8 text: a byte sequence that is not valid UTF-8 comes back as different bytes, so the
     * partition a row is filed under would not be the value the row holds.
     *
     * @return the partition columns' ordinals in the declared schema, in the order named
     */
    static int[] ordinalsOf(String instanceName, StreamSchema schema, List<String> names) {
        int[] ordinals = new int[names.size()];
        for (int p = 0; p < names.size(); p++) {
            String name = names.get(p);
            int ordinal = -1;
            for (int i = 0; i < schema.fieldCount(); i++) {
                if (schema.field(i).name().equalsIgnoreCase(name)) {
                    ordinal = i;
                }
            }
            if (ordinal < 0) {
                throw new ConfigurationException(
                        DeltaErrors.SINK_BAD_CONFIGURATION,
                        "plugin '" + instanceName + "' names partition column '" + name + "', which is not one of "
                                + "the query's output columns "
                                + schema.fields().stream().map(f -> f.name()).toList()
                                + ". A partition value is read from each row, so the column must be in the declared "
                                + "schema; changelog mode's _op and _weight are the sink's, not the query's.");
            }
            for (int q = 0; q < p; q++) {
                if (ordinals[q] == ordinal) {
                    throw new ConfigurationException(
                            DeltaErrors.SINK_BAD_CONFIGURATION,
                            "plugin '" + instanceName + "' names partition column '" + name + "' twice");
                }
            }
            TypeName type = schema.field(ordinal).type().typeName();
            if (type == TypeName.BYTES) {
                throw new ConfigurationException(
                        DeltaErrors.SINK_BAD_CONFIGURATION,
                        "plugin '" + instanceName + "' cannot partition by '" + name + "', which is BYTES. A Delta "
                                + "partition value is text in the log, and Kernel writes a binary value as its bytes "
                                + "read as UTF-8, so bytes that are not UTF-8 would file the row under a value it does "
                                + "not hold. Partition by a column of another type, or derive one in the query.");
            }
            ordinals[p] = ordinal;
        }
        if (ordinals.length > 0 && ordinals.length == schema.fieldCount()) {
            throw new ConfigurationException(
                    DeltaErrors.SINK_BAD_CONFIGURATION,
                    "plugin '" + instanceName + "' partitions by every column " + names + ", which leaves no column "
                            + "for a data file to hold. Partition by fewer columns.");
        }
        return ordinals;
    }

    /** One partition's worth of a commit: its values, as Kernel literals, and its rows. */
    record Group(Map<String, Literal> values, List<Change> changes) {}

    /** The changes grouped by partition, in the order each partition first appears. */
    List<Group> group(List<Change> changes) {
        if (!partitioned()) {
            return List.of(new Group(Map.of(), changes));
        }
        Map<List<Object>, Group> groups = new LinkedHashMap<>();
        for (Change change : changes) {
            Map<String, Literal> values = new LinkedHashMap<>();
            Object[] identity = new Object[ordinals.length];
            for (int p = 0; p < ordinals.length; p++) {
                Literal literal = literalOf(change.values()[ordinals[p]], p);
                values.put(deltaSchema.at(ordinals[p]).getName(), literal);
                identity[p] = literal.getValue();
            }
            groups.computeIfAbsent(Arrays.asList(identity), k -> new Group(values, new ArrayList<>()))
                    .changes()
                    .add(change);
        }
        return List.copyOf(groups.values());
    }

    /** One engine value as the literal Delta records for it. */
    private Literal literalOf(Object value, int p) {
        DataType type = deltaSchema.at(ordinals[p]).getDataType();
        String name = deltaSchema.at(ordinals[p]).getName();
        if (value == null) {
            return Literal.ofNull(type);
        }
        return switch (type) {
            case BooleanType ignored -> Literal.ofBoolean((Boolean) value);
            case ByteType ignored -> Literal.ofByte((Byte) value);
            case ShortType ignored -> Literal.ofShort((Short) value);
            case IntegerType ignored -> Literal.ofInt((Integer) value);
            case DateType ignored -> Literal.ofDate((Integer) value);
            case LongType ignored -> Literal.ofLong((Long) value);
            case TimestampType ignored -> Literal.ofTimestamp(DeltaSinkRows.microsOf(name, (Long) value));
            case FloatType ignored -> Literal.ofFloat((Float) value);
            case DoubleType ignored -> Literal.ofDouble((Double) value);
            case StringType ignored -> Literal.ofString((String) value);
            case DecimalType decimal ->
                Literal.ofDecimal(
                        rescale(name, (BigDecimal) value, decimal), decimal.getPrecision(), decimal.getScale());
            default ->
                throw new PravahaException(
                        DeltaErrors.SINK_WRITE_FAILED,
                        "partition column '" + name + "' is " + type + ", which this sink does not partition by");
        };
    }

    private static BigDecimal rescale(String name, BigDecimal value, DecimalType type) {
        try {
            return value.setScale(type.getScale(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new PravahaException(
                    DeltaErrors.SINK_WRITE_FAILED,
                    "partition column '" + name + "' holds " + value.toPlainString() + ", which has more decimal "
                            + "places than its declared " + type + " and would be filed under a rounded value",
                    e);
        }
    }

    /**
     * The partition values of a data file being rewritten, read from the file's own rows.
     *
     * <p>A file's rows come back from Kernel's reader with the partition columns filled in from the
     * file's {@code partitionValues}, parsed by Kernel. Reading them there, rather than parsing the
     * log's strings here, means the rewrite files its survivors under exactly the values Kernel says
     * the old file held. Every row of one file carries the same values, so the first row decides.
     *
     * <p>Batches before the first surviving row are passed over, since they contribute nothing, and
     * a file with no surviving row at all yields nothing -- the commit removes it and writes no
     * empty file in its place.
     *
     * @return the batches from the first one holding a surviving row, with the file's partition
     *     values; empty when no row survives
     */
    Optional<Peeked> peek(CloseableIterator<FilteredColumnarBatch> batches) {
        while (batches.hasNext()) {
            FilteredColumnarBatch first = batches.next();
            ColumnarBatch data = first.getData();
            if (!anySelected(first)) {
                continue;
            }
            Map<String, Literal> values = new LinkedHashMap<>();
            for (int p = 0; p < ordinals.length; p++) {
                values.put(
                        deltaSchema.at(ordinals[p]).getName(),
                        literalOf(
                                data.getColumnVector(ordinals[p]),
                                deltaSchema.at(ordinals[p]).getDataType()));
            }
            return Optional.of(new Peeked(values, prepend(first, batches)));
        }
        try {
            batches.close();
        } catch (IOException e) {
            throw new PravahaException(DeltaErrors.SINK_WRITE_FAILED, "cannot close a data file being rewritten", e);
        }
        return Optional.empty();
    }

    private static boolean anySelected(FilteredColumnarBatch batch) {
        int size = batch.getData().getSize();
        if (batch.getSelectionVector().isEmpty()) {
            return size > 0;
        }
        ColumnVector selection = batch.getSelectionVector().get();
        for (int rowId = 0; rowId < size; rowId++) {
            if (!selection.isNullAt(rowId) && selection.getBoolean(rowId)) {
                return true;
            }
        }
        return false;
    }

    /** A file's partition values, and its batches with the first one put back. */
    record Peeked(Map<String, Literal> values, CloseableIterator<FilteredColumnarBatch> batches) {}

    private static Literal literalOf(ColumnVector vector, DataType type) {
        if (vector.isNullAt(0)) {
            return Literal.ofNull(type);
        }
        return switch (type) {
            case BooleanType ignored -> Literal.ofBoolean(vector.getBoolean(0));
            case ByteType ignored -> Literal.ofByte(vector.getByte(0));
            case ShortType ignored -> Literal.ofShort(vector.getShort(0));
            case IntegerType ignored -> Literal.ofInt(vector.getInt(0));
            case DateType ignored -> Literal.ofDate(vector.getInt(0));
            case LongType ignored -> Literal.ofLong(vector.getLong(0));
            case TimestampType ignored -> Literal.ofTimestamp(vector.getLong(0));
            case FloatType ignored -> Literal.ofFloat(vector.getFloat(0));
            case DoubleType ignored -> Literal.ofDouble(vector.getDouble(0));
            case StringType ignored -> Literal.ofString(vector.getString(0));
            case DecimalType decimal ->
                Literal.ofDecimal(vector.getDecimal(0), decimal.getPrecision(), decimal.getScale());
            default ->
                throw new PravahaException(
                        DeltaErrors.SINK_WRITE_FAILED, "a partition column of type " + type + " cannot be rewritten");
        };
    }

    private static CloseableIterator<FilteredColumnarBatch> prepend(
            FilteredColumnarBatch first, CloseableIterator<FilteredColumnarBatch> rest) {
        return new CloseableIterator<>() {
            private boolean firstTaken;

            @Override
            public boolean hasNext() {
                return !firstTaken || rest.hasNext();
            }

            @Override
            public FilteredColumnarBatch next() {
                if (!firstTaken) {
                    firstTaken = true;
                    return first;
                }
                return rest.next();
            }

            @Override
            public void close() throws IOException {
                rest.close();
            }
        };
    }
}
