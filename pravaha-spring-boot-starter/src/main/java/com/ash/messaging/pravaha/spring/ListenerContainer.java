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
package com.ash.messaging.pravaha.spring;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.ReflectionUtils;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.embedded.RowChange;
import com.ash.messaging.pravaha.embedded.RowMapping;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.Subscription;
import com.ash.messaging.pravaha.registry.SubscriptionOptions;

/**
 * One {@link PravahaListener} method, subscribed: the subscription, the threads that call the method,
 * and what it has done.
 *
 * <p>The subscription's callback runs on the engine's committing thread and must not block it, so it
 * only sorts each commit's changes by key onto a worker and returns. Each worker is one thread with a
 * bounded queue: one thread per worker is what keeps a key's changes in order, and the bound is what
 * keeps a stuck listener from holding unbounded memory. A queue that fills detaches the listener,
 * loudly -- it is the listener that has fallen behind, and a stream with a silent gap in it is worse
 * than a stream that stopped and said so.
 *
 * <p>The subscription itself is asked never to conflate: a listener sees every change in every
 * commit, which is the difference between it and a dashboard.
 */
public final class ListenerContainer implements AutoCloseable {

    private static final Log LOG = LogFactory.getLog(ListenerContainer.class);

    private final String beanName;
    private final Object bean;
    private final Method method;
    private final String queryName;
    private final int concurrency;
    private final Shape shape;

    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicBoolean detached = new AtomicBoolean();

    private volatile Subscription subscription;
    private ThreadPoolExecutor[] workers = new ThreadPoolExecutor[0];
    private int[] keyOrdinals = new int[0];
    private Function<RowChange, Object> rowReader;

    /** The three method shapes {@link PravahaListener} documents. */
    private enum Shape {
        CHANGE,
        COMMIT,
        ROW_AND_RETRACTION
    }

    ListenerContainer(String beanName, Object bean, Method method, PravahaListener listener) {
        this.beanName = beanName;
        this.bean = bean;
        this.method = method;
        this.queryName = listener.query();
        this.concurrency = listener.concurrency();
        if (queryName == null || queryName.isBlank()) {
            throw refusal("names no query");
        }
        if (concurrency < 1) {
            throw refusal("asks for concurrency " + concurrency + "; it needs at least one thread");
        }
        this.shape = shapeOf(method);
        ReflectionUtils.makeAccessible(method);
    }

    private Shape shapeOf(Method candidate) {
        Class<?>[] parameters = candidate.getParameterTypes();
        if (parameters.length == 1 && parameters[0] == RowChange.class) {
            return Shape.CHANGE;
        }
        if (parameters.length == 1 && parameters[0] == List.class && listOfRowChange(candidate)) {
            return Shape.COMMIT;
        }
        if (parameters.length == 2
                && (parameters[1] == boolean.class || parameters[1] == Boolean.class)
                && (parameters[0] == Map.class || parameters[0].isRecord())) {
            return Shape.ROW_AND_RETRACTION;
        }
        throw refusal("takes " + Arrays.toString(parameters) + ". A listener takes (RowChange), "
                + "(List<RowChange>), or (SomeRecord row, boolean retraction) / (Map<String, Object> row, "
                + "boolean retraction). The boolean is not optional: an update arrives as the old row "
                + "withdrawn and the new one added, and a method that could not tell them apart would "
                + "count every update twice.");
    }

    private static boolean listOfRowChange(Method candidate) {
        Type type = candidate.getGenericParameterTypes()[0];
        return type instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length == 1
                && parameterized.getActualTypeArguments()[0] == RowChange.class;
    }

    /** Subscribes, and starts the threads that call the method. */
    void start(PravahaEngine engine, int maxPending) {
        if (maxPending < 1) {
            throw refusal("cannot start with pravaha.listener.max-pending=" + maxPending);
        }
        RegisteredQuery query = engine.find(queryName)
                .orElseThrow(() -> refusal("listens to '" + queryName + "', and no query of that name is "
                        + "registered on engine " + engine.instanceId() + " (it has " + engine.queries()
                        + "). Declare it under pravaha.queries, or register it through PravahaTemplate "
                        + "from a bean's initialisation, so it exists before listeners subscribe."));
        StreamSchema schema = query.outputSchema();
        if (shape == Shape.ROW_AND_RETRACTION) {
            Class<?> rowType = method.getParameterTypes()[0];
            if (rowType == Map.class) {
                rowReader = RowChange::values;
            } else {
                // Resolved now, so a record with a component the view cannot fill fails the startup
                // rather than the first change.
                Function<Object[], ?> reader = RowMapping.reader(rowType, schema);
                rowReader = change -> reader.apply(change.change().values());
            }
        }
        keyOrdinals =
                query.view().keyOrdinals().stream().mapToInt(Integer::intValue).toArray();
        workers = new ThreadPoolExecutor[concurrency];
        for (int i = 0; i < concurrency; i++) {
            String threadName = "pravaha-listener-" + queryName + "-" + i;
            workers[i] = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(maxPending), task -> {
                        Thread thread = new Thread(task, threadName);
                        thread.setDaemon(true);
                        return thread;
                    });
        }
        subscription = engine.subscribe(
                queryName,
                SubscriptionOptions.of(Integer.MAX_VALUE, SubscriptionOptions.Overflow.FAIL),
                this::dispatch);
    }

    /** On the engine's committing thread: sort by key onto workers, and return. */
    private void dispatch(List<RowChange> changes) {
        if (detached.get() || changes.isEmpty()) {
            return;
        }
        if (workers.length == 1) {
            submit(0, changes);
            return;
        }
        List<List<RowChange>> byWorker = new ArrayList<>(workers.length);
        for (int i = 0; i < workers.length; i++) {
            byWorker.add(new ArrayList<>());
        }
        for (RowChange change : changes) {
            byWorker.get(workerFor(change)).add(change);
        }
        for (int i = 0; i < workers.length; i++) {
            if (!byWorker.get(i).isEmpty()) {
                submit(i, byWorker.get(i));
            }
        }
    }

    private int workerFor(RowChange change) {
        Object[] values = change.change().values();
        int hash = 1;
        for (int ordinal : keyOrdinals) {
            hash = 31 * hash + (ordinal < values.length ? java.util.Objects.hashCode(values[ordinal]) : 0);
        }
        return Math.floorMod(hash, workers.length);
    }

    private void submit(int worker, List<RowChange> batch) {
        try {
            workers[worker].execute(() -> deliver(batch));
        } catch (RejectedExecutionException e) {
            if (workers[worker].isShutdown()) {
                return;
            }
            if (detached.compareAndSet(false, true)) {
                LOG.error("@PravahaListener " + describe() + " fell more than "
                        + workers[worker].getQueue().size()
                        + " commits behind and has been detached rather than handed a stream with a gap in it. "
                        + "Make the method faster, raise its concurrency, or give pravaha.listener.max-pending (a "
                        + "starter property) a larger value.");
                // Not closed here: this runs inside the subscription's own delivery, and the close
                // happens with the rest of the container when the context stops.
            }
        }
    }

    private void deliver(List<RowChange> batch) {
        if (shape == Shape.COMMIT) {
            invoke(batch);
            return;
        }
        for (RowChange change : batch) {
            if (shape == Shape.CHANGE) {
                invoke(change);
            } else {
                invoke(rowReader.apply(change), change.isRetraction());
            }
        }
    }

    private void invoke(Object... arguments) {
        try {
            method.invoke(bean, arguments);
            delivered.incrementAndGet();
        } catch (InvocationTargetException e) {
            failures.incrementAndGet();
            LOG.error(
                    "@PravahaListener " + describe() + " threw; the change is not redelivered and the "
                            + "listener stays subscribed",
                    e.getCause());
        } catch (IllegalAccessException | RuntimeException e) {
            failures.incrementAndGet();
            LOG.error("@PravahaListener " + describe() + " could not be called", e);
        }
    }

    /** Detaches, then lets each worker finish what it was already handed. */
    @Override
    public void close() {
        Subscription current = subscription;
        subscription = null;
        if (current != null) {
            current.close();
        }
        for (ThreadPoolExecutor worker : workers) {
            worker.shutdown();
        }
        for (ThreadPoolExecutor worker : workers) {
            try {
                if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
                    LOG.warn("@PravahaListener " + describe() + " did not finish within 10s of shutdown; "
                            + worker.getQueue().size() + " commits were not delivered");
                    worker.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                worker.shutdownNow();
            }
        }
    }

    /** The query this listener follows. */
    public String queryName() {
        return queryName;
    }

    /** Calls to the method that returned normally. */
    public long delivered() {
        return delivered.get();
    }

    /** Calls to the method that threw. */
    public long failures() {
        return failures.get();
    }

    /** Whether it fell too far behind and was detached. */
    public boolean isDetached() {
        return detached.get();
    }

    /** Whether it is subscribed and its threads are running. */
    public boolean isRunning() {
        return subscription != null && !subscription.isClosed() && !detached.get();
    }

    private String describe() {
        return beanName + "." + method.getName() + " on '" + queryName + "'";
    }

    private IllegalStateException refusal(String what) {
        return new IllegalStateException("@PravahaListener " + beanName + "." + method.getName() + " " + what);
    }

    @Override
    public String toString() {
        return "ListenerContainer[" + describe() + ", " + concurrency + " thread(s)]";
    }
}
