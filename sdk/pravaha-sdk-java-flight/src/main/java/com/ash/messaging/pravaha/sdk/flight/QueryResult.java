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
package com.ash.messaging.pravaha.sdk.flight;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.vector.VectorSchemaRoot;

import com.ash.messaging.pravaha.sdk.ClientErrors;
import com.ash.messaging.pravaha.sdk.PravahaClientException;

/**
 * An answer, iterated as it arrives.
 *
 * <p>Streamed, not materialised: the server sends bounded batches and this walks them, so a client
 * reading a million rows holds one batch rather than a million rows. That is the whole reason the
 * result is {@code AutoCloseable} and the reason a {@link Row} is valid only until the iteration
 * moves on -- both are the cost of not copying, and both are worth it.
 *
 * <p>Iterating twice is refused rather than silently returning nothing. A stream is consumed once,
 * and a second {@code for} loop over the same result quietly seeing zero rows is a bug that looks
 * like missing data.
 */
public final class QueryResult implements Iterable<Row>, AutoCloseable {

    private final FlightStream stream;
    private final List<String> columns;

    /**
     * The client that opened this stream.
     *
     * <p>Held so that a failure raised part way through reading can be diagnosed the same way one
     * raised at call time is -- S-4: a stream that dies mid-result is the one place a server failure
     * was not merely re-stamped but not caught at all. See {@code advanceBatch}.
     */
    private final PravahaFlightClient owner;

    private boolean iterated;

    QueryResult(FlightStream stream, PravahaFlightClient owner) {
        this.stream = stream;
        this.owner = owner;
        this.columns = new ArrayList<>();
        stream.getSchema().getFields().forEach(field -> columns.add(field.getName()));
    }

    /** The column names, in order, known before any row arrives. */
    public List<String> columns() {
        return List.copyOf(columns);
    }

    @Override
    public Iterator<Row> iterator() {
        if (iterated) {
            throw new PravahaClientException(
                    ClientErrors.READ_FAILED,
                    "this result has already been iterated. A stream is consumed once; collect the rows if "
                            + "you need them twice.",
                    false);
        }
        iterated = true;
        return new BatchIterator();
    }

    /** Everything, as value arrays. For a caller that knows the answer is small. */
    public List<Object[]> toList() {
        List<Object[]> rows = new ArrayList<>();
        for (Row row : this) {
            rows.add(row.toArray());
        }
        return rows;
    }

    @Override
    public void close() {
        // Cancel first, then close, and both matter for the *server*. Closing a stream the server
        // is still writing to leaves it writing: it finds out that nobody is listening from the
        // cancellation, and until it does it holds Arrow buffers and, for a subscription, an
        // attached listener on the query. Closing alone releases this side and leaves that behind.
        try {
            stream.cancel("client finished reading", null);
        } catch (Exception e) {
            // Already complete. A stream that was fully drained has nothing to cancel, and saying
            // so is not an error worth propagating.
        }
        try {
            stream.close();
        } catch (Exception e) {
            // Teardown noise is not a failure. A fully-read stream reports its own cancellation as
            // RST_STREAM, and turning that into an exception meant a query that had already
            // returned every row then failed on the way out -- indistinguishable, to a script, from
            // the query itself failing.
        }
    }

    /** Walks rows within a batch, and batches within the stream. */
    private final class BatchIterator implements Iterator<Row> {

        private VectorSchemaRoot root;
        private Row cursor;
        private int index;
        private int rowsInBatch;

        @Override
        public boolean hasNext() {
            while (index >= rowsInBatch) {
                if (!advanceBatch()) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Pulls the next batch.
         *
         * <p>A loop rather than a single step, because an empty batch is legal: the server sends one
         * for a result with no rows, and treating that as end-of-stream would be right by accident
         * and wrong the moment a non-final batch is empty.
         */
        private boolean advanceBatch() {
            boolean more;
            try {
                more = stream.next();
            } catch (FlightRuntimeException e) {
                // S-4, the half that was not even a wrong code: a failure part way through a result
                // -- the lane behind the view dying, the credential expiring under a long read, the
                // node going away -- escaped this iterator as Arrow's own FlightRuntimeException,
                // with no PRV code at all and nothing a caller could catch that was Pravaha's. The
                // server's diagnosis is decoded here by the same route as at call time, so a
                // mid-stream failure and an up-front refusal look the same to the code handling
                // them, which is the only way a caller can handle them in one place.
                throw owner.failureOf(e, ClientErrors.READ_FAILED);
            }
            if (!more) {
                return false;
            }
            root = stream.getRoot();
            rowsInBatch = root.getRowCount();
            index = 0;
            cursor = new Row(root, columns);
            return true;
        }

        @Override
        public Row next() {
            if (!hasNext()) {
                throw new NoSuchElementException("the result has no more rows");
            }
            return cursor.at(index++);
        }
    }
}
