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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a bean method as a receiver of a continuous query's committed changes -- the {@code
 * @KafkaListener} idiom, pointed at a view instead of a topic (ADR-020, design section 22.4).
 *
 * <p>The method takes one of three shapes, checked when the context starts:
 *
 * <ul>
 *   <li>{@code void on(RowChange change)} -- once per change. {@code change.isRetraction()} says
 *       whether it withdraws a row; {@code change.as(MyRecord.class)} reads it by column name.
 *   <li>{@code void on(List<RowChange> commit)} -- once per commit (per worker, see {@link
 *       #concurrency()}), the changes in the order they were applied.
 *   <li>{@code void on(MyRecord row, boolean retraction)} -- once per change, the row read into a
 *       record by column name ({@code user_id} fills {@code userId}), or into a {@code Map<String,
 *       Object>}. The {@code boolean} is required: an update arrives as the old row withdrawn and the
 *       new one added, and a method that could not tell the two apart would count every update twice.
 * </ul>
 *
 * <p>Changes are delivered after they are committed, never mid-window, and off the engine's thread:
 * a slow listener cannot slow the query. Within one key they arrive in commit order, so a row's
 * withdrawal is always seen before its replacement. A listener that throws is logged and keeps its
 * subscription; one that falls more than {@code pravaha.listener.max-pending} commits behind is
 * detached and logged at error, rather than handed a stream with a silent gap in it.
 *
 * <p>The query must be registered by the time the application context has started -- declared under
 * {@code pravaha.queries}, or registered through {@code PravahaTemplate} from a bean's
 * initialisation. A listener naming a query that does not exist fails the startup.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PravahaListener {

    /** The registered continuous query whose view this method follows. */
    String query();

    /**
     * How many threads deliver to this method. Changes are routed by the view's key, so every change
     * to one key goes to the same thread and keeps its order; different keys run in parallel.
     */
    int concurrency() default 1;
}
