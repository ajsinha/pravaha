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
package com.ash.messaging.pravaha.bindings.ingest;

/**
 * What one pump of a feed reads: a bound stream and one of its partitions.
 *
 * <p>Kept beside each pump so that a feed which stops can say where (FEED-1). The pump itself does
 * not know: it holds a reader and a lane, and the stream name lives in the binding that made it.
 */
record FeedInput(String stream, int partition) {

    /** {@code txn#0}, as {@link com.ash.messaging.pravaha.registry.FeedStatus.Source#where()} says it. */
    String where() {
        return stream + "#" + partition;
    }
}
