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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import com.ash.messaging.pravaha.embedded.ContinuousQuery;
import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.embedded.RowChange;
import com.ash.messaging.pravaha.embedded.RowMapping;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.Subscription;
import com.ash.messaging.pravaha.serving.ViewQuery;

/**
 * The Spring-side handle on the application's engine: register, read, subscribe, push.
 *
 * <p>Deliberately thin. Every call is the {@link PravahaEngine} call of the same name, and the engine
 * is one {@link #engine()} away for anything this does not say; what the template adds is the
 * shapes a Spring application usually wants back -- rows as maps, rows as records, one change at a
 * time -- so that application code does not handle {@code Object[]} and column ordinals.
 */
public class PravahaTemplate {

    private final PravahaEngine engine;

    public PravahaTemplate(PravahaEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** The engine underneath. */
    public PravahaEngine engine() {
        return engine;
    }

    /** Registers {@code sql} as a continuous query named {@code name}, keyed by the named columns. */
    public RegisteredQuery register(String name, String sql, String... keyColumns) {
        return engine.register(name, sql, keyColumns);
    }

    public RegisteredQuery register(ContinuousQuery query) {
        return engine.register(query);
    }

    /** Rows of a query over the views, each as column name to value. */
    public List<Map<String, Object>> queryForList(String sql, Object... parameters) {
        ViewQuery.Result result = engine.query(sql, parameters);
        return result.rows().stream()
                .map(row -> RowMapping.toMap(result.schema(), row))
                .toList();
    }

    /** Rows of a query over the views, each read into a record by column name. */
    public <R> List<R> query(Class<R> rowType, String sql, Object... parameters) {
        return engine.query(rowType, sql, parameters);
    }

    /** The raw result: its schema and its rows in column order. */
    public ViewQuery.Result query(String sql, Object... parameters) {
        return engine.query(sql, parameters);
    }

    /**
     * Calls {@code onChange} for each committed change to {@code queryName}'s view, on the thread
     * that committed it -- keep it short, or use {@link PravahaListener} for delivery off that thread.
     */
    public Subscription subscribe(String queryName, Consumer<RowChange> onChange) {
        Objects.requireNonNull(onChange, "onChange");
        return engine.subscribe(queryName, changes -> changes.forEach(onChange));
    }

    /** Pushes rows, one {@code Object[]} per row in column order; see {@link PravahaEngine#push}. */
    public int push(String stream, Object[]... rows) {
        return engine.push(stream, rows);
    }

    /** Pushes one row given by column name. */
    public int push(String stream, Map<String, ?> row) {
        return engine.push(stream, row);
    }

    public void pause(String queryName) {
        engine.pause(queryName);
    }

    public void resume(String queryName) {
        engine.resume(queryName);
    }

    public void drop(String queryName) {
        engine.drop(queryName);
    }
}
