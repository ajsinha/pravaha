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

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ash.messaging.pravaha.embedded.ContinuousQuery;
import com.ash.messaging.pravaha.embedded.PravahaEngine;

/**
 * QE-077: an embedded engine fed by a followed CSV, journalling and checkpointing under a directory,
 * run in a child JVM so the parent can SIGKILL it. Prints {@code READY} once started and then waits
 * to be killed. {@link #engine} is also what the parent starts in-process to check the final answer.
 */
public final class AdvCrashChildMain {

    static final String SCHEMA = "op:STRING,id:INT64,g:STRING,v:INT64,ts:TIMESTAMP";

    static final String WINDOW = "SELECT window_start, window_end, g, SUM(v) AS s, COUNT(*) AS c FROM TABLE(TUMBLE("
            + "TABLE f, DESCRIPTOR(ts), INTERVAL '10' SECOND)) GROUP BY window_start, window_end, g";

    static PravahaEngine engine(Path dir, Path csv) {
        Map<String, String> settings = new LinkedHashMap<>(AdvSupport.durable(dir));
        settings.put("pravaha.node.id", "qe-crash");
        Map<String, String> source = new LinkedHashMap<>();
        source.put("path", csv.toString());
        source.put("schema", SCHEMA);
        source.put("follow", "true");
        source.put("op.column", "op");
        source.put("event.time", "ts");
        PravahaEngine engine = AdvSupport.engine(
                settings,
                e -> e.declareStream("f", SCHEMA, "ts")
                        .bindSource("f", "filesystem", source)
                        .declareQuery(ContinuousQuery.named("proj")
                                .sql("SELECT id, g, v FROM f")
                                .keyedBy("id")
                                .build())
                        .declareQuery(ContinuousQuery.named("win")
                                .sql(WINDOW)
                                .keyedBy("window_start", "window_end", "g")
                                .build()));
        if (engine.find("agg").isEmpty()) {
            engine.query(
                    "CREATE CONTINUOUS QUERY agg KEYED BY (g) AS SELECT g, SUM(v) AS s, COUNT(*) AS c FROM proj GROUP BY g");
        }
        return engine;
    }

    public static void main(String[] args) throws Exception {
        PravahaEngine engine = engine(Path.of(args[0]), Path.of(args[1]));
        System.out.println("READY " + ProcessHandle.current().pid() + " " + engine.queries());
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }

    private AdvCrashChildMain() {}
}
