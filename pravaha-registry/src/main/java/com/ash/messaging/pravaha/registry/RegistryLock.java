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
package com.ash.messaging.pravaha.registry;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * The registry's lock: what {@link QueryRegistry} held as its own monitor until ADR-062.
 *
 * <p>A registration holds it across network I/O -- opening a sink, opening or joining a source --
 * and across waits for lanes, and requests arrive on virtual threads (HTTP and Flight both). On JDK
 * 21 a virtual thread that blocks inside a monitor pins its carrier, and one that waits to enter a
 * monitor does too, so a slow registration and a handful of readers could take every carrier the
 * node has; JDK 24 fixed that (JEP 491) and Pravaha runs from 21. A {@link ReentrantLock} parks a
 * virtual thread on any JDK. Reentrant, unfair, and taken and released where the monitor was, so
 * the registry's ordering is unchanged: a replacement takes its own lock and then this one.
 *
 * <p>{@link #call} and {@link #run} are the registry's {@code synchronized} methods in another
 * spelling; {@link #lock()} and {@link #unlock()} are for the classes that synchronized on the
 * registry from outside it.
 */
final class RegistryLock {

    private final ReentrantLock lock = new ReentrantLock();

    /** Runs {@code body} holding the lock and returns what it returns. */
    <T extends @Nullable Object> T call(Supplier<T> body) {
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    /** Runs {@code body} holding the lock. */
    void run(Runnable body) {
        lock.lock();
        try {
            body.run();
        } finally {
            lock.unlock();
        }
    }

    void lock() {
        lock.lock();
    }

    void unlock() {
        lock.unlock();
    }
}
