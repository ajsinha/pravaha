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

import org.apache.kafka.common.TopicPartition;
import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * A position in one Kafka partition: the offset of the next record to read, which is Kafka's own
 * meaning for a committed offset.
 *
 * <p>Written {@code orders/3@42} -- topic, partition, next offset -- so an operator can read it beside
 * {@code kafka-consumer-groups --describe}, and so a checkpoint cannot be resumed against a partition
 * it was not taken from. Neither {@code /} nor {@code @} can appear in a topic name, so the token parses
 * without ambiguity.
 */
record KafkaSourceOffset(String topic, int partition, long next) {

    static KafkaSourceOffset at(TopicPartition partition, long next) {
        return new KafkaSourceOffset(partition.topic(), partition.partition(), next);
    }

    SourceOffset toSourceOffset() {
        return new SourceOffset(topic + "/" + partition + "@" + next);
    }

    /**
     * The offset in {@code token}, which must be for {@code expected}.
     *
     * @return null for {@code null} or {@link SourceOffset#BEGINNING}: no checkpoint, so the binding's
     *     {@code start.from} decides
     */
    static @Nullable KafkaSourceOffset parse(@Nullable SourceOffset offset, TopicPartition expected) {
        if (offset == null || offset.isBeginning()) {
            return null;
        }
        String token = offset.token();
        KafkaSourceOffset parsed;
        try {
            int at = token.lastIndexOf('@');
            int slash = token.lastIndexOf('/', at);
            if (at < 0 || slash < 1) {
                throw new IllegalArgumentException(token);
            }
            parsed = new KafkaSourceOffset(
                    token.substring(0, slash),
                    Integer.parseInt(token.substring(slash + 1, at)),
                    Long.parseLong(token.substring(at + 1)));
            if (parsed.partition < 0 || parsed.next < 0) {
                throw new IllegalArgumentException(token);
            }
        } catch (RuntimeException e) {
            throw new PravahaException(
                    KafkaErrors.MALFORMED_OFFSET,
                    "'" + token + "' is not a kafka source offset. This source writes 'topic/partition@next'; a "
                            + "token in any other shape came from another source or was edited, and resuming from a "
                            + "guess would lose or repeat records.");
        }
        if (!parsed.topic.equals(expected.topic()) || parsed.partition != expected.partition()) {
            throw new PravahaException(
                    KafkaErrors.MALFORMED_OFFSET,
                    "the checkpoint holds a position in " + parsed.topic + "/" + parsed.partition
                            + " and the reader is for " + expected.topic() + "/" + expected.partition()
                            + ". An offset means nothing in "
                            + "another partition, so this is refused rather than seeked to. If the binding's topic "
                            + "was changed on purpose, register the query afresh so it starts without the old "
                            + "checkpoint.");
        }
        return parsed;
    }

    @Override
    public String toString() {
        return toSourceOffset().token();
    }
}
