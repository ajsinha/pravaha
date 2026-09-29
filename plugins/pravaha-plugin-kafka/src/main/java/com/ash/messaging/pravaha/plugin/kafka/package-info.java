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
/**
 * Kafka, both ways.
 *
 * <p>A source, {@code kafka} ({@code KafkaSourcePlugin}): a topic read as a stream, one reader per
 * partition, from exact offsets the engine's checkpoints hold -- exactly once, {@code read_committed}
 * by default -- decoding JSON rows by column name, or {@code kafka-sink}'s changelog with its weights.
 *
 * <p>A sink, {@code kafka-sink} ({@code KafkaSinkPlugin}): a continuous query's changes written to a
 * topic as keyed upserts with tombstones -- value and key in JSON, Avro or Protobuf, schema ids
 * checked against a schema registry ({@code KafkaSinkEncoders}) -- or as an explicit JSON changelog,
 * exactly once to a {@code read_committed} consumer, through a staging topic and Kafka transactions
 * tied to the engine's checkpoints.
 */
package com.ash.messaging.pravaha.plugin.kafka;
