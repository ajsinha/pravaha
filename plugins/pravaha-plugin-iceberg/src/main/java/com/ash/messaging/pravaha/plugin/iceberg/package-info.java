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
 * Apache Iceberg sink, {@code iceberg-sink}.
 *
 * <p>Built on iceberg-core and iceberg-parquet rather than Spark: a table on the local filesystem,
 * one Iceberg snapshot per checkpoint, with equality deletes (format version 2) for upsert mode.
 */
package com.ash.messaging.pravaha.plugin.iceberg;
