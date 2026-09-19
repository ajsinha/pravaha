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

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;

/**
 * Where the sink's clients come from: a seam, so the write path can be tested against Kafka's own
 * {@code MockProducer} and {@code MockConsumer} without a broker.
 */
interface KafkaClients {

    Producer<byte[], byte[]> producer(Map<String, Object> config);

    Consumer<byte[], byte[]> consumer(Map<String, Object> config);

    /** Checks the target topic exists and the staging topic is usable, creating it if missing. */
    void prepareTopics(KafkaSinkOptions options);

    /** The real clients. */
    KafkaClients REAL = new KafkaClients() {
        @Override
        public Producer<byte[], byte[]> producer(Map<String, Object> config) {
            return new KafkaProducer<>(config);
        }

        @Override
        public Consumer<byte[], byte[]> consumer(Map<String, Object> config) {
            return new KafkaConsumer<>(config);
        }

        @Override
        public void prepareTopics(KafkaSinkOptions options) {
            KafkaTopics.prepare(options);
        }
    };
}
