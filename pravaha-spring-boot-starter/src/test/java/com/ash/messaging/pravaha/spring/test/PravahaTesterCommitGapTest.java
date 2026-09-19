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
package com.ash.messaging.pravaha.spring.test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.registry.RegisteredQuery;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one moment {@link PravahaTester#awaitView} could not learn about from a plain subscription,
 * made to happen every time rather than when the timing allows (SUB-1).
 *
 * <p>A commit is delivered to the listeners attached when its first change was staged (STRM-11). So
 * when a source has handed the query rows that are applied but not yet committed, a wait that
 * subscribes plainly and reads sees an empty view, and is then not told about the commit that
 * publishes them. In a real engine that window is up to the feed's twenty-millisecond publishing
 * tick, and a full test run hit it three times in three.
 *
 * <p>Here the engine is real and the window is held open: the row is applied and uncommitted when
 * the wait begins, and the commit that publishes it happens only after the wait's first read, as the
 * feed's tick would. A wait on a plain subscription sleeps through it and times out; a wait on the
 * engine's snapshot subscription cannot, and forces no commit of its own to get there.
 */
@Timeout(60)
class PravahaTesterCommitGapTest {

    record Row(String id) {}

    private static final StreamSchema EVENTS =
            StreamSchema.builder("events").field("id", Types.string()).build();

    @Test
    void rowsAppliedBeforeTheWaitSubscribedAreSeenWithoutTheWaitForcingACommit() {
        try (PravahaEngine real = PravahaEngine.createDefault();
                RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 1)) {
            real.declareStream(EVENTS);
            real.start();
            RegisteredQuery query = real.register("gap", "SELECT id FROM events", "id");
            assertThat(query.accept(row(arena, "r1"))).isTrue();
            assertThat(query.awaitApplied(Duration.ofSeconds(10))).isTrue();
            assertThat(real.query("SELECT * FROM gap").rows())
                    .as("applied and not committed")
                    .isEmpty();

            // The feed's tick, after the wait has read: a commit the wait must hear about.
            AtomicBoolean ticked = new AtomicBoolean();
            PravahaEngine engine = afterFirstRead(real, () -> {
                if (ticked.compareAndSet(false, true)) {
                    query.commit();
                }
            });

            List<Row> answer = new PravahaTester(engine, null)
                    .withTimeout(Duration.ofSeconds(3))
                    .awaitView("gap", Row.class, rows -> !rows.isEmpty());

            assertThat(answer).containsExactly(new Row("r1"));
        }
    }

    private static BinaryRowView row(RowArena arena, String id) {
        RowLayout layout = RowLayout.of(EVENTS);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, id);
        writer.weight(1L).eventTimestampNanos(0).sequence(1).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        return new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));
    }

    /** The engine, running {@code tick} each time a query returns -- after the read, before the wait. */
    private static PravahaEngine afterFirstRead(PravahaEngine engine, Runnable tick) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            Object result;
            try {
                result = method.invoke(engine, arguments);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if (method.getName().equals("query")) {
                tick.run();
            }
            return result;
        };
        return (PravahaEngine) Proxy.newProxyInstance(
                PravahaEngine.class.getClassLoader(), new Class<?>[] {PravahaEngine.class}, handler);
    }
}
