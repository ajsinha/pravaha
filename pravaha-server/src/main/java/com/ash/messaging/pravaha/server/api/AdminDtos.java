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
import java.util.List;

/**
 * The wire types of the administrative endpoints: the audit trail, the plugin manifests and a
 * principal's own permissions.
 *
 * <p>Apart from {@link ApiDtos} for the same reason those are apart from the engine's types, and
 * one more: these describe the node's security and its code rather than its data, and keeping them
 * in one place makes it easy to see everything this surface can disclose. Every field name is part
 * of the contract.
 */
public final class AdminDtos {

    private AdminDtos() {}

    /**
     * One page of the audit trail, newest first.
     *
     * @param recording false when the node is configured {@code audit: none}, in which case there
     *     is nothing to read and {@code events} is empty because nothing was recorded, not because
     *     nobody asked for anything
     * @param sink what records durably: {@code file}, {@code memory} or {@code none}
     * @param capacity how many recent decisions the node keeps readable here
     * @param retained how many it holds now
     * @param evicted how many were recorded and have since left the readable window; a {@code file}
     *     sink still has them
     * @param oldestRetained when the oldest readable decision was made, or null
     * @param actions every action among the retained decisions, for a filter to offer
     * @param nextCursor pass as {@code cursor} for the next page; null on the last
     * @param note what the page cannot show, in words
     */
    public record AuditPage(
            boolean recording,
            String sink,
            int capacity,
            int retained,
            long evicted,
            Instant oldestRetained,
            List<String> actions,
            List<AuditEntry> events,
            String nextCursor,
            String note) {}

    /**
     * One recorded decision. Never the principal's claims or credential -- {@code AuditEvent} carries
     * neither.
     *
     * @param sequence increases by one per decision on this node; a gap in a filtered page is a
     *     decision the filter excluded
     * @param decision {@code ALLOW} or {@code DENY}
     * @param detail the SQL or filter the decision was about, when there was one
     */
    public record AuditEntry(
            long sequence,
            Instant at,
            String principal,
            String tenant,
            List<String> roles,
            String action,
            String target,
            String decision,
            String reason,
            String detail) {}

    /**
     * A plugin this node can load, as its manifest and its code declare it.
     *
     * <p>Never a binding's options, which are where a plugin's credentials live; {@code settings}
     * are the names a plugin's manifest declares it understands, not anybody's values for them.
     *
     * @param requiredApiVersion the plugin API it was built against
     * @param compatible whether this engine can host that API
     * @param loaded false for a plugin a binding names that is not on the node's classpath
     * @param kinds {@code source}, {@code sink}, {@code lookup}: what its code can be, not what the
     *     configuration uses it as
     * @param settings the setting names its manifest declares; empty when it declares none or was
     *     discovered without one
     * @param bindings what this caller may see bound to it
     */
    public record PluginInfo(
            String name,
            String version,
            String requiredApiVersion,
            boolean compatible,
            boolean loaded,
            List<String> kinds,
            PluginCapabilities capabilities,
            List<String> settings,
            PluginHealth health,
            List<PluginBinding> bindings) {}

    /**
     * What a plugin declares it can do, before any binding configures it.
     *
     * @param note why a part is missing, or that a binding's configuration can narrow what is here
     */
    public record PluginCapabilities(SourceCapabilities source, SinkCapabilities sink, String note) {}

    public record SourceCapabilities(
            boolean replayableOffsets,
            boolean orderedWithinPartition,
            boolean emitsDeletes,
            boolean emitsBeforeImage,
            String guarantee,
            List<String> pushdown,
            String typicalLatency) {}

    public record SinkCapabilities(
            List<String> emitModes,
            boolean transactional,
            boolean idempotentUpsert,
            int maxBatchRows,
            String guarantee) {}

    /**
     * @param state {@code HEALTHY}, {@code DEGRADED}, {@code UNHEALTHY}, or {@code UNKNOWN} when the
     *     node holds no instance of it that reports
     * @param reported whether {@code state} came from a live instance
     */
    public record PluginHealth(String state, String detail, boolean reported) {}

    /** A stream, lookup table or sink bound to a plugin: its kind and name, nothing else. */
    public record PluginBinding(String kind, String name) {}

    /**
     * What the configured policy lets the calling principal do.
     *
     * <p>Only about the caller, and only about what the caller may already see: a view the listing
     * hides from them is not named here with a "no", because naming it would disclose it.
     *
     * @param policy the configured policy's name
     * @param register may this principal register continuous queries
     * @param readAudit may this principal read the audit trail
     * @param views the registered views this principal can see, with how
     * @param streams the streams this principal can see, with how
     */
    public record Permissions(
            String principal,
            String tenant,
            List<String> roles,
            boolean anonymous,
            String policy,
            Decision register,
            Decision readAudit,
            List<ObjectPermission> views,
            List<ObjectPermission> streams) {}

    public record Decision(boolean allowed, String reason) {}

    /**
     * @param read {@code full} or {@code filtered} (a row filter applies; the predicate itself is not
     *     repeated here)
     * @param administer for a view, may this principal drop, pause or resume it; for a stream, may
     *     they redeclare it ({@code POST /api/v1/streams} asks the same question)
     */
    public record ObjectPermission(String name, String read, Decision administer) {}
}
