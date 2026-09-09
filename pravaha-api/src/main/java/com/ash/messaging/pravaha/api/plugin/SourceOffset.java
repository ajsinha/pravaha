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
package com.ash.messaging.pravaha.api.plugin;

import java.util.Objects;

/**
 * A durable position in a partition.
 *
 * <p>Opaque to the engine: it stores the token in a checkpoint and hands it back on recovery. The
 * plugin decides what it means -- a Kafka offset, a PostgreSQL LSN, an Aerospike last-update-time.
 *
 * <p>The engine's exactly-once story rests entirely on this being genuinely restartable. A plugin
 * that returns a plausible-looking token it cannot actually resume from will produce silent data
 * loss on the first recovery, which is why the TCK checks the round trip rather than trusting the
 * declaration.
 */
public record SourceOffset(String token) {

    /** Start of the partition. */
    public static final SourceOffset BEGINNING = new SourceOffset("");

    public SourceOffset {
        Objects.requireNonNull(token, "token");
    }

    public boolean isBeginning() {
        return token.isEmpty();
    }

    @Override
    public String toString() {
        return isBeginning() ? "SourceOffset[beginning]" : "SourceOffset[" + token + "]";
    }
}
