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
package com.ash.messaging.pravaha.server.api;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditTrail;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

/**
 * The audit trail, read back: who asked for what, when, and what the policy said.
 *
 * <p><strong>Its own permission.</strong> {@code SecurityPolicy.mayReadAudit}, not {@code mayRead}
 * on some name: the trail carries every principal that read anything and the SQL they read it with,
 * so being entitled to every view is not being entitled to this. The default answer is no; the
 * {@code authenticated} policy grants it to the roles in {@code pravaha.security.audit-readers}; the
 * {@code permissive} policy, which already lets every caller read and drop everything, grants it to
 * every caller.
 *
 * <p><strong>Reading it is audited</strong>, allowed or denied, before anything is read and with the
 * filter as the event's detail.
 *
 * <p><strong>What it reads.</strong> The node's bounded in-memory ring of recent decisions, recorded
 * on the same call as the durable sink ({@link AuditTrail}); the response names the ring's capacity,
 * how much it has evicted, and the oldest decision it still holds. The file, when there is one, is
 * the record of everything before that.
 */
@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit", description = "The recorded authorization decisions, for principals allowed to read them")
public class AuditController {

    private static final int DEFAULT_LIMIT = 100;

    private final HttpAuthorizer authorizer;
    private final Supplier<Optional<AuditTrail>> trail;

    @Autowired
    public AuditController(HttpAuthorizer authorizer, PravahaNode node) {
        this(authorizer, node::auditTrail);
    }

    /** For a test, or anything holding a trail directly; {@code null} means the node does not audit. */
    public AuditController(HttpAuthorizer authorizer, AuditTrail trail) {
        this(authorizer, () -> Optional.ofNullable(trail));
    }

    private AuditController(HttpAuthorizer authorizer, Supplier<Optional<AuditTrail>> trail) {
        this.authorizer = authorizer;
        this.trail = trail;
    }

    @GetMapping
    @Operation(summary = "Read recorded authorization decisions, newest first, one page at a time")
    public AdminDtos.AuditPage read(
            @RequestParam(required = false) @Nullable String since,
            @RequestParam(required = false) @Nullable String until,
            @RequestParam(required = false) @Nullable String principal,
            @RequestParam(required = false) @Nullable String view,
            @RequestParam(required = false) @Nullable String action,
            @RequestParam(required = false) @Nullable String decision,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor,
            HttpServletRequest http) {
        // Parsed before the permission is checked so a malformed request is a 400 for everyone --
        // but recorded and refused before anything is read.
        AuditTrail.Filter filter = new AuditTrail.Filter(
                instant("since", since), instant("until", until), principal, view, action, decision(decision));
        long before = cursor(cursor);
        authorizer.requireAuditRead(http, describe(filter, before));

        Optional<AuditTrail> found = trail.get();
        if (found.isEmpty()) {
            return new AdminDtos.AuditPage(
                    false,
                    "none",
                    0,
                    0,
                    0,
                    null,
                    List.of(),
                    List.of(),
                    null,
                    "This node is configured pravaha.security.audit=none, so no decision was recorded and "
                            + "there is nothing to read. Set it to file for a durable trail.");
        }
        AuditTrail.Page page = found.get().read(filter, limit == null ? DEFAULT_LIMIT : limit, before);
        List<AdminDtos.AuditEntry> events = page.entries().stream()
                .map(entry -> entry(entry.sequence(), entry.event()))
                .toList();
        return new AdminDtos.AuditPage(
                true,
                found.get().durable(),
                page.capacity(),
                page.retained(),
                page.evicted(),
                page.oldest(),
                page.actions(),
                events,
                page.nextCursor() == 0 ? null : Long.toString(page.nextCursor()),
                note(found.get(), page));
    }

    private static AdminDtos.AuditEntry entry(long sequence, AuditEvent event) {
        return new AdminDtos.AuditEntry(
                sequence,
                event.at(),
                event.principal().id(),
                event.principal().tenant(),
                event.principal().roles().stream().sorted().toList(),
                event.action(),
                event.target(),
                event.allowed() ? "ALLOW" : "DENY",
                event.reason(),
                event.detail().filter(text -> !text.isEmpty()).orElse(null));
    }

    private static String note(AuditTrail trail, AuditTrail.Page page) {
        String window = "The most recent " + page.capacity() + " decisions on this node are readable here";
        if (page.evicted() == 0) {
            return window + "; none has been evicted since it started.";
        }
        return window + "; " + page.evicted() + " older ones have been evicted"
                + ("file".equals(trail.durable())
                        ? " and are in the audit file (pravaha.security.audit-file)."
                        : " and are not readable anywhere, because this node does not audit to a file.");
    }

    private static @Nullable Instant instant(String name, @Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text.strip());
        } catch (DateTimeParseException e) {
            throw new PravahaException(
                    ApiErrors.INVALID_PARAMETER,
                    "'" + name + "' must be an ISO-8601 instant such as 2026-09-19T08:00:00Z; got '" + text + "'.");
        }
    }

    private static @Nullable Boolean decision(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "allow", "allowed" -> Boolean.TRUE;
            case "deny", "denied" -> Boolean.FALSE;
            default ->
                throw new PravahaException(
                        ApiErrors.INVALID_PARAMETER, "'decision' is 'allow' or 'deny'; got '" + text + "'.");
        };
    }

    private static long cursor(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            long value = Long.parseLong(text.strip());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            // Falls through to the refusal below.
        }
        throw new PravahaException(
                ApiErrors.INVALID_PARAMETER,
                "'cursor' is the nextCursor of a previous page; '" + text + "' is not one this node issued.");
    }

    private static String describe(AuditTrail.Filter filter, long before) {
        StringBuilder text = new StringBuilder();
        append(text, "since", filter.since());
        append(text, "until", filter.until());
        append(text, "principal", filter.principal());
        append(text, "view", filter.target());
        append(text, "action", filter.action());
        append(text, "decision", filter.allowed() == null ? null : filter.allowed() ? "allow" : "deny");
        append(text, "cursor", before == 0 ? null : before);
        return text.toString();
    }

    private static void append(StringBuilder text, String name, @Nullable Object value) {
        if (value != null) {
            text.append(text.isEmpty() ? "" : "&").append(name).append('=').append(value);
        }
    }
}
