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
package com.ash.messaging.pravaha.flight;

import java.util.List;

import org.apache.arrow.flight.FlightProducer.StreamListener;
import org.apache.arrow.flight.Result;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.registry.DeadLetters;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;

/**
 * The dead-letter actions as Flight carries them, lifted out of the producer at its 1500-line
 * ceiling.
 *
 * <p>No rule lives here. Who may see that a queue exists, whose entries have their bytes removed
 * and who may replay all come from {@link DeadLetters}, which is what the HTTP endpoints use too:
 * a second copy of an authorization rule diverges in the direction of whichever one somebody
 * forgot to change. What this class holds is the wire -- which fields, in which order, and the
 * trailer that lets a client tell totals from an entry.
 */
final class DeadLetterActions {

    private DeadLetterActions() {}

    /**
     * The three dead-letter actions (B5).
     *
     * <p>Every rule -- who may see that the queue exists, whose entries have their bytes removed,
     * who may replay -- comes from {@link DeadLetters}, which is also what the HTTP endpoints use.
     * A second copy of an authorization rule diverges in the direction of whichever one somebody
     * forgot to change, which is the finding that put the listing rules in one class to begin with.
     */
    static void deadLetterAction(
            String type,
            QueryRegistry required,
            Principal principal,
            List<String> fields,
            StreamListener<Result> listener,
            SecurityPolicy policy,
            AuditSink audit,
            com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore deadLetters) {
        if (fields.isEmpty() || fields.get(0).isBlank()) {
            throw new PravahaException(FlightErrors.BAD_HANDLE, type + " needs the name of a query as its first field");
        }
        String name = fields.get(0);
        DeadLetters access = new DeadLetters(required, policy, audit, deadLetters);
        switch (type) {
            case ControlWire.DLQ_LIST -> {
                int offset = intField(fields, 1, 0);
                int limit = intField(fields, 2, com.ash.messaging.pravaha.runtime.dlq.DeadLetterPage.DEFAULT_LIMIT);
                DeadLetters.View view = access.page(principal, name, offset, limit);
                for (DeadLetters.Visible visible : view.entries()) {
                    listener.onNext(new Result(ControlWire.encode(entryFields(visible))));
                }
                // The trailer. '#' as the first field cannot be an id -- ids are UUIDs -- so a
                // client tells the totals from an entry without being told how many to expect.
                com.ash.messaging.pravaha.runtime.dlq.DeadLetterCounts counts = view.counts();
                listener.onNext(new Result(ControlWire.encode(
                        "#",
                        Long.toString(view.page().total()),
                        Long.toString(counts.bytes()),
                        Long.toString(counts.evicted()),
                        Long.toString(counts.evictedBytes()),
                        Long.toString(counts.replayed()),
                        Long.toString(counts.failedAgain()),
                        deadLetters.retention().describe(),
                        Boolean.toString(deadLetters.configured()))));
            }
            case ControlWire.DLQ_SHOW -> {
                if (fields.size() < 2 || fields.get(1).isBlank()) {
                    throw new PravahaException(
                            FlightErrors.BAD_HANDLE, "show needs the id of a dead letter as its second field");
                }
                listener.onNext(
                        new Result(ControlWire.encode(entryFields(access.show(principal, name, fields.get(1))))));
            }
            default -> {
                if (fields.size() < 2) {
                    throw new PravahaException(
                            FlightErrors.BAD_HANDLE,
                            "replay needs at least one dead letter's id after the query's name. Replaying a "
                                    + "whole queue by omission is not offered -- a queue is usually a mix of "
                                    + "causes, and most of it is still malformed.");
                }
                for (String id : fields.subList(1, fields.size())) {
                    if (id.isBlank()) {
                        continue;
                    }
                    DeadLetters.Replayed one = access.replay(principal, name, id);
                    listener.onNext(new Result(
                            ControlWire.encode(one.id(), one.outcome().name(), one.detail(), one.newId())));
                }
            }
        }
    }

    /**
     * One entry as the wire carries it.
     *
     * <p>The record is Base64 like the file's own, and empty when it is withheld -- with field 8
     * saying why, so a client shows "you may not see this record" rather than an empty row.
     */
    private static List<String> entryFields(DeadLetters.Visible visible) {
        com.ash.messaging.pravaha.runtime.dlq.DeadLetterEntry entry = visible.entry();
        return List.of(
                entry.id(),
                Long.toString(entry.sequence()),
                entry.letter().stream(),
                entry.letter().sourceOffset(),
                entry.letter().code(),
                visible.reason(),
                entry.letter().at().map(java.time.Instant::toString).orElse(""),
                Integer.toString(visible.size()),
                java.util.Base64.getEncoder().encodeToString(visible.raw()),
                visible.withheld(),
                entry.replay().name(),
                entry.replayedAt() == null ? "" : entry.replayedAt().toString());
    }

    static int intField(List<String> fields, int at, int fallback) {
        if (fields.size() <= at || fields.get(at).isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(fields.get(at).strip());
        } catch (NumberFormatException e) {
            throw new PravahaException(
                    FlightErrors.BAD_HANDLE, "'" + fields.get(at) + "' is not a number; field " + at + " must be one");
        }
    }
}
