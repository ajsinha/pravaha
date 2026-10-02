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
package com.ash.messaging.pravaha.embedded;

import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * What a push reports when a query on the stream could not take it (PUSHPARTIAL-1).
 *
 * <p>Each running query takes a push independently, and every one that could apply it has committed
 * it by the time this is thrown. So the answer depends on whether anyone did. Nobody: the first
 * failure, unchanged -- the push reached no view, and retrying it is right once the cause is fixed.
 * Somebody: {@code PRV-8105}, naming the queries that have the rows and the ones that do not, with the
 * first failure as its cause -- retrying the push would count it twice in the first, which is exactly
 * what the old behaviour invited.
 */
final class PushOutcome {

    private PushOutcome() {}

    static RuntimeException failure(
            String stream, List<String> committed, Map<DefaultPravahaEngine.Target, RuntimeException> failed) {
        RuntimeException first = failed.values().iterator().next();
        if (committed.isEmpty()) {
            return first;
        }
        StringBuilder failures = new StringBuilder();
        for (Map.Entry<DefaultPravahaEngine.Target, RuntimeException> failure : failed.entrySet()) {
            if (failures.length() > 0) {
                failures.append("; ");
            }
            failures.append('\'')
                    .append(failure.getKey().query().name())
                    .append("': ")
                    .append(failure.getValue().getMessage());
        }
        return new PravahaException(
                EmbeddedErrors.PUSH_PARTLY_APPLIED,
                "this push to '" + stream + "' was applied and committed by " + committed + ", and not by "
                        + failures + ". Do not retry it: the queries that took it would count it twice. The "
                        + "ones that could not have stopped or are backed up; see their state.",
                first);
    }
}
