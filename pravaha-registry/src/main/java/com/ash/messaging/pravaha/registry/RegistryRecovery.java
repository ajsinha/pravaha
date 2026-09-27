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
package com.ash.messaging.pravaha.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;

/**
 * The replay of a registry's journal at startup: each live entry registered again under its owner,
 * as the policy and the tenants' quotas stand now, and each one that cannot be recorded with its
 * code.
 *
 * <p>Lives beside {@link QueryRegistry} rather than inside it because that class had reached the
 * project's 1500-line ceiling, and because this is one piece with one job: turning a journal back
 * into registrations, one entry's failure never stopping the rest. The registry calls it under its
 * own monitor.
 */
final class RegistryRecovery {

    private RegistryRecovery() {}

    /** See {@link QueryRegistry#recover}; called with the registry's monitor held. */
    static QueryRegistry.Recovery replay(
            QueryRegistry registry, RegistryJournal journal, Function<String, Optional<Principal>> principals) {
        List<String> recovered = new ArrayList<>();
        List<QueryRegistry.Recovery.Refusal> refused = new ArrayList<>();
        RegistryJournal.Replayed replayed = journal.replayAll();
        for (RegistryJournal.Entry entry : replayed.live()) {
            Optional<Principal> owner = principals.apply(entry.owner());
            if (owner.isEmpty()) {
                refused.add(new QueryRegistry.Recovery.Refusal(
                        entry.name(),
                        Optional.of(RegistryErrors.REPLAY_UNAUTHORIZED),
                        "its owner '" + entry.owner()
                                + "' is not a principal this deployment knows, so there is nobody to authorize it "
                                + "as"));
                continue;
            }
            try {
                List<Object> values = entry.parameters().stream()
                        .map(RegistryJournal::decodeParameter)
                        .toList();
                registry.registerWithoutJournalling(
                        entry.name(),
                        entry.sql(),
                        entry.keyColumns(),
                        owner.get(),
                        entry.retention(),
                        values.isEmpty() ? BoundParameters.none() : BoundParameters.of(values),
                        entry.sink(),
                        entry.checkpointDirectory(),
                        entry.indexed());
                recovered.add(entry.name());
            } catch (RuntimeException failure) {
                // One bad entry must not stop the rest. A deployment recovering forty queries should
                // not lose thirty-nine because the fortieth names a stream that has since been removed.
                refused.add(new QueryRegistry.Recovery.Refusal(
                        entry.name(), replayRefusalCode(failure), failure.getMessage()));
            }
        }
        // Every name the journal knows has now been registered or refused, so a sink a restored
        // checkpoint recorded and nobody claimed belongs to a name that is not coming back.
        registry.queries().forEach(RegisteredQuery::forgetUnclaimedSinks);
        // And then the replacements that were in flight, which need their names back first: a
        // candidate is started beside the version serving the name, and there is no name to be
        // beside until the registrations above have been replayed (ADR-046).
        if (!replayed.pending().isEmpty()) {
            refused.addAll(registry.replacements().recover(replayed.pending(), principals));
        }
        return new QueryRegistry.Recovery(recovered, refused);
    }

    /**
     * The code to record for a refusal raised while replaying one journalled entry.
     *
     * <p>{@code register} refuses a principal who no longer holds what the journal recorded by
     * throwing {@link SecurityErrors#FORBIDDEN} -- the same code a live registration would raise for
     * an unrelated caller. During replay that is not a caller being refused; it is the exact
     * condition {@link RegistryErrors#REPLAY_UNAUTHORIZED} documents, so it is relabelled here rather
     * than surfaced under the generic code. Any other coded failure (an unusable name, a stream the
     * deployment no longer has) keeps its own code, and a bare {@link RuntimeException} carrying none
     * is recorded without one rather than inventing a code it never raised.
     */
    private static Optional<ErrorCode> replayRefusalCode(RuntimeException failure) {
        if (!(failure instanceof PravahaException coded)) {
            return Optional.empty();
        }
        return Optional.of(
                coded.errorCode().equals(SecurityErrors.FORBIDDEN)
                        ? RegistryErrors.REPLAY_UNAUTHORIZED
                        : coded.errorCode());
    }
}
