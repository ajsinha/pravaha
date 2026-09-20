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
package com.ash.messaging.pravaha.runtime.exec;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads an execution's operator state <strong>on the lane that owns it</strong> (ADR-048).
 *
 * <p>This is the whole reason the class exists. Operator state is arena memory written by one
 * thread, and the single-writer principle this runtime is built on means nobody else may touch it
 * -- not even to read. A debugger that read a join's index from its own thread while the lane was
 * mid-batch would show a half-written row, and on a compacting store it would follow a handle that
 * had just moved. Restoring a checkpoint goes through the lane for exactly this reason; so does
 * advancing a watermark; so does this.
 *
 * <p>Submitted as a control task, so it runs between batches rather than inside one.
 */
public final class OperatorStateReader {

    private OperatorStateReader() {}

    /** How long a read waits for the lane to get to it. A checkpoint's own budget. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** What state {@code execution}'s first lane holds, and how much of each. */
    public static List<OperatorState.Slot> slots(QueryExecution execution, Duration timeout) {
        return onLane(execution, timeout, pipeline -> pipeline.stateSlots());
    }

    /** One page of one operator's state, read on the lane. */
    public static OperatorState.Page page(
            QueryExecution execution, String id, String keyFilter, int offset, int limit, Duration timeout) {
        return onLane(execution, timeout, pipeline -> pipeline.inspectState(id, keyFilter, offset, limit));
    }

    /**
     * Runs {@code read} against lane 0's pipeline, on lane 0's thread.
     *
     * <p>Lane 0 because state inspection is a debugger's, and a debug fork runs on one lane -- a
     * keyed aggregate's groups are partitioned across lanes, so "the groups" only means something
     * when there is one.
     */
    private static <T> T onLane(
            QueryExecution execution, Duration timeout, java.util.function.Function<InterpretedPipeline, T> read) {
        Duration budget = timeout == null ? DEFAULT_TIMEOUT : timeout;
        InterpretedPipeline pipeline = execution.pipeline(0);
        AtomicReference<T> answer = new AtomicReference<>();
        AtomicReference<RuntimeException> failed = new AtomicReference<>();
        long ticket = execution.lane(0).submitControlTask(() -> {
            try {
                answer.set(read.apply(pipeline));
            } catch (RuntimeException e) {
                // Carried back rather than thrown on the lane: a lane that throws inside a control
                // task is a dead lane, and an unknown operator id is a caller's mistake, not the
                // query's.
                failed.set(e);
            }
        });
        if (!execution.lane(0).awaitControlTask(ticket, budget)) {
            execution.checkHealth();
            throw new IllegalStateException("the lane did not answer a state read within " + budget
                    + ". It is either working through a batch or stuck; a debug session steps its own lane, "
                    + "so this is the second unless something else is driving it.");
        }
        RuntimeException failure = failed.get();
        if (failure != null) {
            throw failure;
        }
        return answer.get();
    }
}
