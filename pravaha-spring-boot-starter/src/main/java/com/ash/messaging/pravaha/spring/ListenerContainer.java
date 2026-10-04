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
import org.jspecify.annotations.Nullable;
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
 * <p>The subscription's callback runs on the subscription's own delivery thread, one commit at a
 * time, so blocking it would stall this listener and nothing else (STRM-8). It still only sorts
 * each commit's changes by key onto a worker and returns, because a listener method that took a
 * second would otherwise hold up the key ordering of every other key. Each worker is one thread with a
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
    private final String errorHandlerName;
    private final Shape shape;

    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicBoolean detached = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicLong undelivered = new AtomicLong();

    private volatile PravahaListenerErrorHandler errorHandler = PravahaListenerErrorHandler.logAndContinue();
    private volatile PravahaListenerErrorHandler.@Nullable Failure lastFailure;

    private volatile @Nullable Subscription subscription;
    private ThreadPoolExecutor[] workers = new ThreadPoolExecutor[0];
    private int[] keyOrdinals = new int[0];
    /** For the row-and-retraction shape only: how a change becomes the method's row argument. */
    private @Nullable Function<RowChange, Object> rowReader;

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
        this.errorHandlerName = listener.errorHandler();
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

    /** The bean name {@link PravahaListener#errorHandler()} gives, or empty for the application's. */
    String errorHandlerName() {
        return errorHandlerName;
    }

    /** Subscribes, and starts the threads that call the method. */
    void start(PravahaEngine engine, int maxPending, PravahaListenerErrorHandler handler) {
        this.errorHandler = java.util.Objects.requireNonNull(handler, "errorHandler");
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

    /** On the subscription's delivery thread: sort by key onto workers, and return. */
    private void dispatch(List<RowChange> changes) {
        if (detached.get() || stopped.get() || changes.isEmpty()) {
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
            call(batch, () -> new Object[] {batch});
            return;
        }
        for (RowChange change : batch) {
            if (shape == Shape.CHANGE) {
                call(List.of(change), () -> new Object[] {change});
            } else {
                // Read inside the call, so a row the record cannot take is a failure the handler
                // hears about rather than an exception lost on a pool thread.
                call(List.of(change), () -> new Object[] {
                    java.util.Objects.requireNonNull(rowReader, "set at subscribe for this shape")
                            .apply(change),
                    change.isRetraction()
                });
            }
        }
    }

    /** One call to the method, and what the error handler decides if it throws. */
    private void call(List<RowChange> changes, java.util.function.Supplier<Object[]> arguments) {
        if (stopped.get()) {
            undelivered.addAndGet(changes.size());
            return;
        }
        Throwable thrown;
        try {
            method.invoke(bean, arguments.get());
            delivered.incrementAndGet();
            return;
        } catch (InvocationTargetException e) {
            thrown = e.getCause() == null ? e : e.getCause();
        } catch (IllegalAccessException | RuntimeException e) {
            thrown = e;
        }
        failures.incrementAndGet();
        PravahaListenerErrorHandler.Failure failure =
                new PravahaListenerErrorHandler.Failure(listenerName(), queryName, changes, thrown);
        lastFailure = failure;
        PravahaListenerErrorHandler.Decision decision;
        try {
            decision = errorHandler.handle(failure);
        } catch (RuntimeException handlerFailure) {
            handlerFailure.addSuppressed(thrown);
            LOG.error(
                    "@PravahaListener " + describe() + " threw on " + failure.describeChanges()
                            + ", and its error handler threw too; the listener is stopped",
                    handlerFailure);
            decision = PravahaListenerErrorHandler.Decision.STOP;
        }
        if (decision == null) {
            LOG.error(
                    "@PravahaListener " + describe() + " threw on " + failure.describeChanges()
                            + ", and its error handler returned no decision; the listener is stopped",
                    thrown);
            decision = PravahaListenerErrorHandler.Decision.STOP;
        }
        if (decision == PravahaListenerErrorHandler.Decision.STOP) {
            stopDelivering();
        }
    }

    /**
     * Stops at the error handler's word: detaches from the query so nothing more is dispatched, and
     * lets what is already queued drain as undelivered rather than calling the method again.
     */
    private void stopDelivering() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        Subscription current = subscription;
        if (current != null) {
            current.close();
        }
        LOG.warn("@PravahaListener " + describe() + " is stopped by its error handler and receives nothing more; "
                + "the context must be restarted to deliver to it again");
    }

    /**
     * Waits until every change dispatched to this listener so far has been handed to its method.
     *
     * <p>Each worker is one thread taking from one queue in order, so a marker queued behind what is
     * already waiting runs only after all of it -- no polling, and no guess at how long is enough.
     *
     * <p>Two waits, and the first one is why the second is sound. A commit no longer dispatches:
     * it hands its changes to the subscription's buffer and returns, and the subscription's own
     * thread dispatches them into these queues (STRM-8). So the subscription is waited out first,
     * and only then is the marker queued -- behind everything that commit produced. Queueing the
     * marker first would let it run before the dispatch it is supposed to follow.
     *
     * @return whether it finished within {@code timeout}
     */
    public boolean awaitDelivered(java.time.Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Subscription attached = subscription;
        if (attached != null && !attached.awaitQuiet(timeout)) {
            return false;
        }
        ThreadPoolExecutor[] current = workers;
        java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(current.length);
        for (ThreadPoolExecutor worker : current) {
            try {
                worker.execute(drained::countDown);
            } catch (RejectedExecutionException e) {
                if (!worker.isShutdown()) {
                    throw new IllegalStateException(
                            "@PravahaListener " + describe() + " has pravaha.listener.max-pending commits waiting; "
                                    + "cannot queue behind them",
                            e);
                }
                // Shut down: nothing more will run on it, so there is nothing left to wait for.
                drained.countDown();
            }
        }
        return drained.await(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
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
        return subscription != null && !subscription.isClosed() && !detached.get() && !stopped.get();
    }

    /** Whether its error handler stopped it. */
    public boolean isStopped() {
        return stopped.get();
    }

    /** Changes that were waiting when its error handler stopped it, and so never reached the method. */
    public long undelivered() {
        return undelivered.get();
    }

    /** Commits handed to its threads and not yet delivered. */
    public int pending() {
        int waiting = 0;
        for (ThreadPoolExecutor worker : workers) {
            waiting += worker.getQueue().size();
        }
        return waiting;
    }

    /** The most recent call that threw, if any has. */
    public java.util.Optional<PravahaListenerErrorHandler.Failure> lastFailure() {
        return java.util.Optional.ofNullable(lastFailure);
    }

    /** The bean and method, as {@code bean.method}. */
    public String listenerName() {
        return beanName + "." + method.getName();
    }

    private String describe() {
        return listenerName() + " on '" + queryName + "'";
    }

    private IllegalStateException refusal(String what) {
        return new IllegalStateException("@PravahaListener " + beanName + "." + method.getName() + " " + what);
    }

    @Override
    public String toString() {
        return "ListenerContainer[" + describe() + ", " + concurrency + " thread(s)]";
    }
}
