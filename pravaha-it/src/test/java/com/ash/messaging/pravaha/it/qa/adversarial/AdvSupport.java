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
package com.ash.messaging.pravaha.it.qa.adversarial;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.common.config.ConfigurationBuilder;
import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.serving.ViewQuery;

/**
 * Shared plumbing for the QE- adversarial engine cases ({@code docs/project/qa/cases/ADV-ENGINE.md}):
 * an embedded engine, a read rendered as sorted text, and an attempt rendered as its outcome.
 *
 * <p>The adversarial suites are opt-in: {@code -Dpravaha.qa.adversarial=true}. A defect they
 * reproduce is kept as a {@code @Disabled("QE-xxx: ...")} test in the class that found it, so the
 * lead can switch it on with the fix.
 */
final class AdvSupport {

    static final String SWITCH = "pravaha.qa.adversarial";

    private AdvSupport() {}

    /** An engine with the given settings, the declarations applied by {@code declare}, started. */
    static PravahaEngine engine(Map<String, String> settings, Consumer<PravahaEngine> declare) {
        ConfigurationBuilder builder = Configuration.builder();
        settings.forEach(builder::set);
        PravahaEngine engine = PravahaEngine.create(builder.build());
        declare.accept(engine);
        engine.start();
        return engine;
    }

    static PravahaEngine engine(Consumer<PravahaEngine> declare) {
        return engine(Map.of(), declare);
    }

    /** Settings for an engine that journals and checkpoints under {@code dir}. */
    static Map<String, String> durable(Path dir) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("pravaha.registry.journal", dir.resolve("registry.journal").toString());
        settings.put("pravaha.checkpoint.directory", dir.resolve("checkpoints").toString());
        settings.put("pravaha.checkpoint.interval", "200ms");
        settings.put("pravaha.dlq.directory", dir.resolve("dlq").toString());
        return settings;
    }

    /** Every row of a read, each as {@code a|b|c}, sorted. */
    static List<String> rows(PravahaEngine engine, String sql) {
        ViewQuery.Result result = engine.query(sql);
        List<String> out = new ArrayList<>();
        for (Object[] row : result.rows()) {
            out.add(render(row));
        }
        out.sort(null);
        return out;
    }

    static String render(Object[] row) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < row.length; i++) {
            if (i > 0) {
                text.append('|');
            }
            Object value = row[i];
            text.append(
                    value instanceof BigDecimal d
                            ? d.toPlainString()
                            : value instanceof Object[] nested ? Arrays.deepToString(nested) : String.valueOf(value));
        }
        return text.toString();
    }

    /** What an action did: {@code OK}, or the code and message it was refused with. */
    static String attempt(Runnable action) {
        try {
            action.run();
            return "OK";
        } catch (PravahaException refused) {
            return refused.errorCode().code() + " " + refused.getMessage();
        } catch (RuntimeException | Error other) {
            return "UNCODED " + other.getClass().getName() + ": " + other.getMessage();
        }
    }

    /** The query's state and failure, as text. */
    static String state(PravahaEngine engine, String name) {
        RegisteredQuery query = engine.find(name).orElseThrow();
        return query.state()
                + query.failure()
                        .map(f -> " " + f.errorCode().code() + " " + f.getMessage())
                        .orElse("");
    }
}
