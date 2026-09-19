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
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.testkit.tck.SourcePluginTck;

/**
 * The {@code kafka} source run against the conformance suite every source must pass, on a real broker.
 *
 * <p>Each case gets a fresh one-partition topic holding five records, written in one Kafka transaction
 * so the log carries a commit marker after them: "every record" is five rows and nothing for the
 * marker, and "resume from the midpoint" is an offset the source must seek to exactly. The source
 * declares {@code EXACTLY_ONCE}, so the kit holds it to no duplicates on resume as well as no loss.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class KafkaSourceTckTest extends SourcePluginTck {

    private static final int RECORDS = 5;

    @Override
    protected StreamSourcePlugin createPlugin() {
        String topic = KafkaBroker.topic("tck", 1, false);
        try (KafkaProducer<byte[], byte[]> producer = KafkaBroker.producer("tck-" + topic)) {
            producer.beginTransaction();
            for (int id = 1; id <= RECORDS; id++) {
                KafkaBroker.send(
                        producer,
                        topic,
                        0,
                        "{\"order_id\":" + id + "}",
                        "{\"order_id\":" + id + ",\"name\":\"row-" + id + "\"}");
            }
            producer.commitTransaction();
        }
        KafkaSourcePlugin plugin = new KafkaSourcePlugin();
        plugin.configure(new KafkaSourcePluginTest.Ctx(
                "tck",
                Map.of(
                        "bootstrap.servers",
                        KafkaBroker.bootstrap(),
                        "topic",
                        topic,
                        "schema",
                        "order_id:INT64,name:STRING")));
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
