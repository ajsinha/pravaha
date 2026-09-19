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

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.registry.QueryState;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.SourceFeed;
import com.ash.messaging.pravaha.registry.Subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one moment {@link PravahaTester#awaitView} cannot learn about from its subscription, made to
 * happen every time rather than when the timing allows.
 *
 * <p>A commit is delivered to the listeners attached when its first change was staged (STRM-11). So
 * when a source has handed the query rows that are applied but not yet committed, a wait that
 * subscribes now reads an empty view and is then not told about the commit that publishes them. In a
 * real engine that window is up to the feed's twenty-millisecond publishing tick, and a full test run
 * hit it three times in three; here the engine is a stand-in whose commit publishes the rows and
 * tells no subscriber, which is that window held open.
 */
class PravahaTesterCommitGapTest {

    record Row(String id) {}

    @Test
    void rowsAppliedBeforeTheWaitSubscribedArePublishedRatherThanWaitedFor() {
        AtomicBoolean published = new AtomicBoolean();
        RegisteredQuery query = mock(RegisteredQuery.class);
        when(query.state()).thenReturn(QueryState.RUNNING);
        SourceFeed feed = mock(SourceFeed.class);
        when(feed.describe()).thenReturn("a stand-in feed");
        when(query.feed()).thenReturn(feed);
        doAnswer(invocation -> {
                    published.set(true);
                    return null;
                })
                .when(query)
                .commit();
        PravahaEngine engine = mock(PravahaEngine.class);
        when(engine.find("gap")).thenReturn(Optional.of(query));
        Subscription subscription = mock(Subscription.class);
        // Never called: the staged batch belongs to the subscribers that were there before.
        when(engine.subscribe(eq("gap"), any(Consumer.class))).thenReturn(subscription);
        when(engine.query(eq(Row.class), anyString()))
                .thenAnswer(invocation -> published.get() ? List.of(new Row("r1")) : List.of());

        List<Row> answer = new PravahaTester(engine, null)
                .withTimeout(Duration.ofSeconds(2))
                .awaitView("gap", Row.class, rows -> !rows.isEmpty());

        assertThat(answer).containsExactly(new Row("r1"));
    }
}
