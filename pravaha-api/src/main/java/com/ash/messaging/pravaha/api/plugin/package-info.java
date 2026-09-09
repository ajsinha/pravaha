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
 * The plugin SPI.
 *
 * <p>Storage-specific code lives entirely behind these interfaces (design NFR-4). The engine core
 * carries no Aerospike client, no Cassandra driver and no Kafka dependency, which is what lets a
 * plugin bundle whatever versions it needs without negotiating with anything else on the classpath.
 *
 * <p>The recurring theme here is <strong>capability declaration over assumption</strong>. A source
 * says whether it can rewind, whether it sees deletes, what it can filter server-side; a sink says
 * which changelog modes it accepts and whether its writes are idempotent. The engine then computes
 * what it can honestly promise rather than asserting a guarantee the plumbing cannot support.
 */
package com.ash.messaging.pravaha.api.plugin;
