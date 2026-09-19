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

import com.ash.messaging.pravaha.api.ErrorCode;

/** Kafka plugin error codes. Stable, documented, and never renumbered. */
public final class KafkaErrors {

    /**
     * A configuration that cannot be honoured: a missing or malformed option, a {@code kafka.*}
     * property that would break the delivery guarantee or that the sink or the source sets itself, a
     * compression codec whose library is not on the classpath, or TLS and SASL options that do not fit
     * together.
     */
    public static final ErrorCode BAD_CONFIGURATION = new ErrorCode(5100, "KAFKA_BAD_CONFIGURATION");

    /**
     * The brokers could not be reached, the topic does not exist, or the credentials or ACLs were
     * refused -- found when the sink or the source opens, before a row moves.
     */
    public static final ErrorCode CONNECT_FAILED = new ErrorCode(5101, "KAFKA_CONNECT_FAILED");

    /**
     * A send, a staging read, or a transaction's commit failed at the broker -- including a producer
     * fenced because another sink opened with the same {@code transactional.id}.
     */
    public static final ErrorCode WRITE_FAILED = new ErrorCode(5102, "KAFKA_WRITE_FAILED");

    /**
     * The staging topic cannot hold what a checkpoint needs: it is compacted, cannot be created, or
     * the records a recorded checkpoint names have already been deleted by its retention.
     */
    public static final ErrorCode STAGING_UNUSABLE = new ErrorCode(5103, "KAFKA_STAGING_UNUSABLE");

    /**
     * A stored source offset this plugin did not write, or one written for another topic or
     * partition -- a binding whose {@code topic} was changed under a checkpoint that still holds the
     * old topic's positions.
     */
    public static final ErrorCode MALFORMED_OFFSET = new ErrorCode(5104, "KAFKA_MALFORMED_OFFSET");

    /**
     * A record the {@code kafka} source cannot turn into a row -- not JSON, not the Avro or protobuf
     * the schema describes, a column of the wrong type, a required column missing, a tombstone where
     * none is expected -- and no dead-letter queue to set it aside in.
     */
    public static final ErrorCode UNDECODABLE_RECORD = new ErrorCode(5105, "KAFKA_UNDECODABLE_RECORD");

    /**
     * The position a {@code kafka} source must read from is no longer in the partition: retention
     * deleted records the checkpoint had not yet seen, or the topic was recreated and the position is
     * past its end. Resuming anywhere else would lose or repeat records, so the source stops.
     */
    public static final ErrorCode RESUME_POINT_GONE = new ErrorCode(5106, "KAFKA_RESUME_POINT_GONE");

    /** Fetching from the brokers failed in a way retrying will not fix: authorization, a deleted topic. */
    public static final ErrorCode READ_FAILED = new ErrorCode(5107, "KAFKA_READ_FAILED");

    /**
     * A writer schema that cannot become rows of the stream the binding feeds: an Avro {@code
     * schema.file} or a protobuf {@code schema.descriptor} that is not one, has no field for a
     * column, or types a column cannot hold -- refused when the binding is configured, before a
     * record moves. A schema that arrives <em>with</em> a record (the registry's) cannot be refused
     * then: the record is a dead letter, or {@code PRV-5105}.
     */
    public static final ErrorCode SCHEMA_UNMAPPABLE = new ErrorCode(5108, "KAFKA_SCHEMA_UNMAPPABLE");

    /**
     * The schema registry could not be reached, refused the credentials, has no schema with the id a
     * record names, or answered with something that is not the documented shape of {@code GET
     * /schemas/ids/{id}}. Not the record's fault, so the reader stops rather than setting good
     * records aside.
     */
    public static final ErrorCode REGISTRY_UNAVAILABLE = new ErrorCode(5109, "KAFKA_REGISTRY_UNAVAILABLE");

    private KafkaErrors() {}
}
