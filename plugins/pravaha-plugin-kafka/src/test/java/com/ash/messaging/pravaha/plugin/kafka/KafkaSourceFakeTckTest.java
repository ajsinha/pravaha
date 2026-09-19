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
package com.ash.messaging.pravaha.plugin.kafka;

import java.util.Map;

import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.testkit.tck.SourcePluginTck;

/**
 * The source conformance suite over an in-memory topic, so it runs on every build, Docker or not.
 * {@link KafkaSourceTckTest} runs the same suite against a real broker.
 */
class KafkaSourceFakeTckTest extends SourcePluginTck {

    private static final int RECORDS = 5;

    @Override
    protected StreamSourcePlugin createPlugin() {
        FakeTopic topic = new FakeTopic("tck", 1);
        for (int id = 1; id <= RECORDS; id++) {
            topic.append(0, "{\"order_id\":" + id + "}", "{\"order_id\":" + id + ",\"name\":\"row-" + id + "\"}");
        }
        KafkaSourcePlugin plugin = new KafkaSourcePlugin(topic);
        plugin.configure(new KafkaSourcePluginTest.Ctx(
                "tck",
                Map.of(
                        "bootstrap.servers", "localhost:9",
                        "topic", "tck",
                        "schema", "order_id:INT64,name:STRING")));
        plugin.open();
        return plugin;
    }

    @Override
    protected String streamName() {
        return "tck";
    }

    @Override
    protected int expectedRecordCount() {
        return RECORDS;
    }

    @Override
    protected RowCollector newCollector(StreamSourcePlugin plugin) {
        return new Collected(plugin.discoverSchemas().get(0));
    }
}
