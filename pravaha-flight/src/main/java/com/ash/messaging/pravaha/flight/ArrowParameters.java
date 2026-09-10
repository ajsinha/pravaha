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
package com.ash.messaging.pravaha.flight;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.List;

import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowStreamReader;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;
import com.ash.messaging.pravaha.sql.plan.ParameterMetadata;

/**
 * Bound parameters, between an Arrow batch on the wire and values the planner can use (ADR-032).
 *
 * <p>The values are carried as Arrow IPC -- the same bytes the client sent, stored in the handle and
 * decoded when the rows are fetched. Keeping the client's own encoding rather than translating into
 * a format of our own means one conversion instead of two, and one place for a type to be got wrong
 * instead of two.
 */
final class ArrowParameters {

    private ArrowParameters() {}

    /** Reads the client's parameter batch off the wire and returns it as IPC bytes. */
    static byte[] encode(FlightStream stream) {
        VectorSchemaRoot root = stream.getRoot();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ArrowStreamWriter writer = new ArrowStreamWriter(root, null, Channels.newChannel(bytes))) {
            writer.start();
            while (stream.next()) {
                // Every batch is written, not just the first. A client binding one row will send
                // one, and one that sends more is describing more than one execution -- which this
                // server does not run, and which is refused below rather than silently truncated to
                // the first row.
                writer.writeBatch();
            }
            writer.end();
        } catch (IOException e) {
            throw new PravahaException(
                    FlightErrors.BAD_HANDLE, "could not read the bound parameters: " + e.getMessage(), e);
        }
        return bytes.toByteArray();
    }

    /**
     * Turns the stored batch back into values, checked against the statement's placeholders.
     *
     * <p>Column {@code i} of the batch is placeholder {@code i}; one row, because one execution
     * binds one set of values. A batch with several rows is a request to run the statement several
     * times, which is a different feature (Flight SQL's bulk update path) and is refused here rather
     * than answered with the first row and silence about the rest.
     */
    static BoundParameters decode(byte[] bytes, BufferAllocator allocator, ParameterMetadata metadata) {
        try (ArrowStreamReader reader = new ArrowStreamReader(new ByteArrayInputStream(bytes), allocator)) {
            if (!reader.loadNextBatch()) {
                throw new PravahaException(FlightErrors.BAD_HANDLE, "the bound parameters carried no rows");
            }
            VectorSchemaRoot root = reader.getVectorSchemaRoot();
            if (root.getRowCount() != 1) {
                throw new PravahaException(
                        FlightErrors.BAD_HANDLE,
                        "a binding must carry exactly one row of values and this one has "
                                + root.getRowCount() + ". Binding several rows means running the "
                                + "statement several times, which is a different call");
            }
            if (root.getFieldVectors().size() != metadata.count()) {
                throw new PravahaException(
                        FlightErrors.BAD_HANDLE,
                        "this statement has " + metadata.count() + " placeholder"
                                + (metadata.count() == 1 ? "" : "s") + " and "
                                + root.getFieldVectors().size() + " were bound");
            }

            List<Object> values = new ArrayList<>(metadata.count());
            for (FieldVector vector : root.getFieldVectors()) {
                values.add(vector.isNull(0) ? null : vector.getObject(0));
            }
            return BoundParameters.of(normalise(values));
        } catch (PravahaException e) {
            throw e;
        } catch (IOException e) {
            throw new PravahaException(
                    FlightErrors.BAD_HANDLE, "could not read the bound parameters: " + e.getMessage(), e);
        }
    }

    /**
     * Converts Arrow's own object forms into the ones the planner compares against.
     *
     * <p>A Utf8 vector hands back a {@code Text}, not a {@code String}. It is a {@code CharSequence},
     * so a type check passes and an {@code equals} against a String does not -- which would produce
     * a filter that matches nothing, with no error anywhere. Converting here is the difference
     * between a working query and an empty result nobody can explain.
     */
    private static List<Object> normalise(List<Object> values) {
        List<Object> converted = new ArrayList<>(values.size());
        for (Object value : values) {
            converted.add(value instanceof org.apache.arrow.vector.util.Text text ? text.toString() : value);
        }
        return converted;
    }
}
