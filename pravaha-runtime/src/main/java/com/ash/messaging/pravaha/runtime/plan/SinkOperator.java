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
package com.ash.messaging.pravaha.runtime.plan;

import java.util.List;
import java.util.Objects;

import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * Where results leave the engine.
 *
 * <p>Carries the {@link EmitMode} so the planner can check it against the sink's declared
 * capabilities at registration. A {@code LEFT JOIN} or an unbounded aggregate produces updates; if
 * the sink is append-only, the query is refused with a message naming the operator rather than
 * running for a week and appending contradictory rows (design section 15.5).
 */
public record SinkOperator(PhysicalOperator input, String sinkName, EmitMode emitMode, List<String> keyFields)
        implements PhysicalOperator {

    public SinkOperator {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(sinkName, "sinkName");
        Objects.requireNonNull(emitMode, "emitMode");
        keyFields = keyFields == null ? List.of() : List.copyOf(keyFields);
        if (emitMode == EmitMode.UPSERT && keyFields.isEmpty()) {
            throw new IllegalArgumentException(
                    "sink '" + sinkName + "' uses UPSERT but declares no key fields; there is nothing to "
                            + "upsert on, so rows would accumulate instead of replacing each other");
        }
    }

    @Override
    public StreamSchema outputSchema() {
        return input.outputSchema();
    }

    @Override
    public List<PhysicalOperator> inputs() {
        return List.of(input);
    }

    @Override
    public String label() {
        return "Sink(" + sinkName + ", " + emitMode + ")";
    }

    @Override
    public String identity() {
        return "Sink(" + sinkName + ", " + emitMode + ", keys=" + keyFields + ")";
    }
}
