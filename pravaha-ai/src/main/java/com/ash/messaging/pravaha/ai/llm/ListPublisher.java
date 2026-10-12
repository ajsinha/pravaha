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
package com.ash.messaging.pravaha.ai.llm;

import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** A {@link Flow.Publisher} over a fixed list, delivered on the subscriber's demand: how an emulated stream is offered as the async twin. */
final class ListPublisher<T> implements Flow.Publisher<T> {

    private final List<T> items;

    ListPublisher(List<T> items) {
        this.items = List.copyOf(items);
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> s) {
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger next = new AtomicInteger();
        s.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                for (long i = 0; i < n && next.get() < items.size() && !done.get(); i++) {
                    s.onNext(items.get(next.getAndIncrement()));
                }
                if (next.get() >= items.size() && done.compareAndSet(false, true)) {
                    s.onComplete();
                }
            }

            @Override
            public void cancel() {
                done.set(true);
            }
        });
    }
}
