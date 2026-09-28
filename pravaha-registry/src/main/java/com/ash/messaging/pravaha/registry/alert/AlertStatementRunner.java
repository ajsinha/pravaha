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

import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.AlertStatement;

/**
 * Runs an alert statement against {@link AlertService}, answering with rows: every surface that carries
 * SQL here carries result sets, as {@code ContinuousQueryStatements} explains.
 */
public final class AlertStatementRunner {

    /** What a statement that changes an alert answers: its name, its state now, and what was done. */
    public static final StreamSchema CHANGED = StreamSchema.builder("alert_changed")
            .field("name", Types.string())
            .field("state", Types.string())
            .field("detail", Types.string().withNullable(true))
            .build();

    /** What {@code SHOW ALERTS} answers: a row per alert the caller may see. */
    public static final StreamSchema LISTING = StreamSchema.builder("alerts")
            .field("name", Types.string())
            .field("view", Types.string())
            .field("state", Types.string())
            .field("following", Types.string())
            .field("condition", Types.string())
            .field("channels", Types.string())
            .field("severity", Types.string())
            .field("firing", Types.int64())
            .field("pending", Types.int64())
            .field("owner", Types.string())
            .field("delivery_error", Types.string().withNullable(true))
            .build();

    private final AlertService service;

    AlertStatementRunner(AlertService service) {
        this.service = service;
    }

    /** The columns {@code statement} answers with, known before it runs. */
    public static StreamSchema schemaOf(AlertStatement statement) {
        return statement instanceof AlertStatement.Show ? LISTING : CHANGED;
    }

    public ViewQuery.Result execute(AlertStatement statement, Principal principal) {
        return switch (statement) {
            case AlertStatement.Create create -> {
                AlertStatus.Summary created = service.create(principal, create);
                yield changed(created, "watches " + created.view() + " and notifies " + created.channels());
            }
            case AlertStatement.Alter alter -> changed(service.alter(principal, alter), "altered");
            case AlertStatement.Drop drop -> {
                boolean dropped = service.drop(principal, drop.name(), drop.ifExists());
                yield row(drop.name(), dropped ? "DROPPED" : "ABSENT", null);
            }
            case AlertStatement.Pause pause -> changed(service.pause(principal, pause.name()), "paused");
            case AlertStatement.Resume resume -> changed(service.resume(principal, resume.name()), "resumed");
            case AlertStatement.Snooze snooze -> {
                AlertStatus.Summary snoozed =
                        service.snooze(principal, snooze.name(), AlertOptions.duration("SNOOZE", snooze.duration()));
                yield changed(snoozed, "snoozed until " + snoozed.snoozedUntil());
            }
            case AlertStatement.Ack ack -> {
                int count = service.acknowledge(principal, ack.name(), null);
                yield row(
                        ack.name(), "ACKNOWLEDGED", count + " firing key" + (count == 1 ? "" : "s") + " acknowledged");
            }
            case AlertStatement.Show show -> {
                List<Object[]> rows = new ArrayList<>();
                for (AlertStatus.Summary s : service.list(principal)) {
                    rows.add(new Object[] {
                        s.name(),
                        s.view(),
                        s.state(),
                        s.following(),
                        s.condition(),
                        String.join(",", s.channels()),
                        s.severity(),
                        (long) s.firing(),
                        (long) s.pending(),
                        s.owner(),
                        s.deliveryError()
                    });
                }
                yield new ViewQuery.Result(LISTING, rows);
            }
        };
    }

    private static ViewQuery.Result changed(AlertStatus.Summary summary, String detail) {
        return row(summary.name(), summary.state(), detail);
    }

    private static ViewQuery.Result row(String name, String state, String detail) {
        return new ViewQuery.Result(CHANGED, List.<Object[]>of(new Object[] {name, state, detail}));
    }
}
