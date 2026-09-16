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
package com.ash.messaging.pravaha.plugin.cassandra;

import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Cassandra server, for tests that would otherwise be guesses.
 *
 * <p>Mocking a store is mocking one's own beliefs about it. Two things in this plugin were only
 * discoverable against a real server: that {@code token()} may not appear in a {@code WHERE} clause
 * alongside a plain {@code SELECT *} without also being selected when the driver's simple statement
 * path is used a certain way, and that the token range a query names must include
 * {@code Long.MIN_VALUE} and {@code Long.MAX_VALUE} exactly or the first and last partitions written
 * are silently unreachable. A mock would have agreed with whatever the code assumed.
 *
 * <p>Testcontainers ships a purpose-built module for this store, unlike Aerospike's, so there is no
 * hand-rolled wait strategy or host-networking workaround here -- {@link CassandraContainer} already
 * knows what "ready" looks like for this image.
 */
final class CassandraTestContainer {

    /** The keyspace every test creates, with replication factor 1 -- there is one node. */
    static final String KEYSPACE = "pravaha_test";

    private CassandraTestContainer() {}

    static CassandraContainer create() {
        return new CassandraContainer(DockerImageName.parse("cassandra:4.1"));
    }
}
