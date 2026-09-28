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
package com.ash.messaging.pravaha.registry.alert;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.sql.AlertStatement;

/**
 * What an alert is (ADR-057): the view it follows, its condition, where it notifies and how, and the
 * two switches a person throws on it -- paused, and snoozed until a time.
 *
 * @param id this alert's identity for its whole life: a new alert of the same name gets a new one, so
 *     its notifications' idempotency keys never collide with the old one's
 * @param view the view's engine name
 * @param owner who created it
 * @param snoozedUntil null when not snoozed
 */
public record AlertDefinition(
        String id,
        String name,
        String view,
        String tenant,
        String owner,
        Instant createdAt,
        List<AlertStatement.Condition> where,
        List<String> channels,
        AlertOptions options,
        boolean paused,
        Instant snoozedUntil,
        Instant updatedAt,
        String updatedBy) {

    public AlertDefinition {
        where = List.copyOf(where);
        channels = List.copyOf(channels);
    }

    /** The condition as written, or empty when every row of the view counts. */
    public String condition() {
        return String.join(
                " AND ", where.stream().map(AlertStatement.Condition::toString).toList());
    }

    /** Whether it is snoozed at {@code now}. */
    public boolean snoozed(Instant now) {
        return snoozedUntil != null && now.isBefore(snoozedUntil);
    }

    /** {@code ACTIVE}, {@code PAUSED} or {@code SNOOZED}, as shown. */
    public String state(Instant now) {
        return paused ? "PAUSED" : snoozed(now) ? "SNOOZED" : "ACTIVE";
    }

    AlertDefinition withChannels(List<String> next, Instant at, String by) {
        return new AlertDefinition(
                id, name, view, tenant, owner, createdAt, where, next, options, paused, snoozedUntil, at, by);
    }

    AlertDefinition withOptions(AlertOptions next, Instant at, String by) {
        return new AlertDefinition(
                id, name, view, tenant, owner, createdAt, where, channels, next, paused, snoozedUntil, at, by);
    }

    AlertDefinition withPaused(boolean next, Instant at, String by) {
        return new AlertDefinition(
                id,
                name,
                view,
                tenant,
                owner,
                createdAt,
                where,
                channels,
                options,
                next,
                next ? snoozedUntil : null,
                at,
                by);
    }

    AlertDefinition withSnooze(Instant until, Instant at, String by) {
        return new AlertDefinition(
                id, name, view, tenant, owner, createdAt, where, channels, options, paused, until, at, by);
    }

    // --------------------------------------------------------------------------------- journal

    List<String> encode() {
        List<String> fields = new ArrayList<>(List.of(
                "A",
                id,
                name,
                view,
                tenant,
                owner,
                createdAt.toString(),
                updatedAt.toString(),
                updatedBy,
                String.join(",", channels),
                Boolean.toString(paused),
                snoozedUntil == null ? "" : snoozedUntil.toString(),
                Integer.toString(where.size())));
        for (AlertStatement.Condition condition : where) {
            fields.add(condition.column());
            fields.add(condition.operator());
            fields.add(condition.literal() == null ? "" : condition.literal());
            fields.add(condition.quoted() ? "1" : "0");
        }
        options.written().forEach((key, value) -> {
            fields.add(key);
            fields.add(value);
        });
        return fields;
    }

    static AlertDefinition decode(List<String> f) {
        int conditions = Integer.parseInt(f.get(12));
        List<AlertStatement.Condition> where = new ArrayList<>();
        int at = 13;
        for (int i = 0; i < conditions; i++, at += 4) {
            String operator = f.get(at + 1);
            where.add(new AlertStatement.Condition(
                    f.get(at),
                    operator,
                    operator.startsWith("IS_") ? null : f.get(at + 2),
                    f.get(at + 3).equals("1")));
        }
        Map<String, String> written = new LinkedHashMap<>();
        for (; at + 1 < f.size(); at += 2) {
            written.put(f.get(at), f.get(at + 1));
        }
        List<String> channels =
                f.get(9).isEmpty() ? List.of() : List.of(f.get(9).split(","));
        return new AlertDefinition(
                f.get(1),
                f.get(2),
                f.get(3),
                f.get(4),
                f.get(5),
                Instant.parse(f.get(6)),
                where,
                channels,
                AlertOptions.of(AlertOptions.defaults(), written),
                Boolean.parseBoolean(f.get(10)),
                f.get(11).isEmpty() ? null : Instant.parse(f.get(11)),
                Instant.parse(f.get(7)),
                f.get(8));
    }
}
