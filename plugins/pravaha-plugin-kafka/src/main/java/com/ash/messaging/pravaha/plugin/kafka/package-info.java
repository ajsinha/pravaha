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
 * A Kafka sink, {@code kafka-sink} ({@code KafkaSinkPlugin}): a continuous query's changes written
 * to a topic as keyed upserts with tombstones, or as an explicit changelog, in JSON -- exactly once to
 * a {@code read_committed} consumer, through a staging topic and Kafka transactions tied to the
 * engine's checkpoints.
 *
 * <p>No source: a Kafka <em>source</em> is not built (see {@code docs/CONNECTORS.md} section 7).
 */
package com.ash.messaging.pravaha.plugin.kafka;
