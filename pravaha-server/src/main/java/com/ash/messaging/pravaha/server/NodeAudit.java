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
package com.ash.messaging.pravaha.server;

import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.AuditTrail;
import com.ash.messaging.pravaha.security.FileAuditSink;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.server.security.SecurityExtensions;
import com.ash.messaging.pravaha.server.security.SecurityProperties;

/**
 * The node's one audit sink: a deployment's own {@code AuditSink} bean (POLICYPLUG-1), else the one
 * {@code pravaha.security.audit} names, wrapped so recent decisions are readable.
 *
 * <p>One instance for the whole node, because the HTTP half has to get the <em>same</em> object.
 * CFG-5: {@code HttpAuthorizer} took its sink from a Spring bean that returned {@code AuditSink.NONE}
 * unconditionally, so a deployment configured with {@code audit: memory} recorded every Flight read
 * and no HTTP read, no HTTP stream declaration and no HTTP refusal -- with nothing at startup saying so.
 * Resolving the key again in that bean would not have fixed it: {@code memory} builds an {@code
 * InMemory} sink, and a second one is a sink nobody can reach. The cache below is what makes sharing
 * safe to ask for before the node starts.
 */
final class NodeAudit {

    /** The node's logger, so these lines read as the node's, as they always have. */
    private static final Logger log = LoggerFactory.getLogger(PravahaNode.class);

    private final SecurityProperties security;
    private final Supplier<SecurityExtensions> extensions;
    private AuditSink audit;

    NodeAudit(SecurityProperties security, Supplier<SecurityExtensions> extensions) {
        this.security = security;
        this.extensions = extensions;
    }

    /** The sink, made on the first ask and the same object after it. */
    synchronized AuditSink sink() {
        if (audit != null) {
            return audit;
        }
        Optional<AuditSink> custom = extensions.get().audit();
        if (custom.isPresent()) {
            String kind = custom.get().getClass().getName();
            log.info(
                    "audit: the application's own AuditSink ({}) records every decision; "
                            + "pravaha.security.audit={} is not used",
                    kind,
                    security.getAudit());
            audit = readable(custom.get(), kind);
            return audit;
        }
        // CFG-21. Validated by SecurityProperties, so an unknown name is refused while the
        // properties bean is initialising rather than four Caused-by levels under Tomcat.
        audit = switch (security.trimmedAudit()) {
            case "memory" -> readable(memorySink(), "memory");
            // CFG-23. The setting that produces a trail an operator can read after the fact, and a file
            // because an endpoint listing who-read-what would need an authorization this policy SPI
            // cannot express, while a file's readers are the operating system's. See FileAuditSink.
            case "file" -> readable(fileSink(), "file");
            default -> null; // audit: none, asked again each time as it always was
        };
        return audit == null ? AuditSink.NONE : audit;
    }

    /** The readable trail, when this node audits at all; empty under {@code audit: none}. */
    Optional<AuditTrail> trail() {
        return sink() instanceof AuditTrail trail ? Optional.of(trail) : Optional.empty();
    }

    /** What the node closes at stop: the sink itself, when it is closeable, and then it is made again. */
    synchronized Optional<AutoCloseable> takeCloseable() {
        if (audit instanceof AutoCloseable closeable) {
            audit = null;
            return Optional.of(closeable);
        }
        return Optional.empty();
    }

    /**
     * The in-process sink, and the warning that it is not an audit trail.
     *
     * <p>CFG-23: {@code memory} accepts every decision and exposes them to nobody but the read API's
     * ring. A deployment that set it believing otherwise has no lasting record, so the node says so once
     * rather than letting the configuration file look reassuring.
     */
    private AuditSink.InMemory memorySink() {
        log.warn("pravaha.security.audit=memory keeps recent decisions in this process and exposes them to "
                + "nothing: no endpoint, no log, no file. It is for tests and for support reading a heap "
                + "dump. Use audit=file for a trail that outlives the process and that an operator can read.");
        return new AuditSink.InMemory();
    }

    private FileAuditSink fileSink() {
        FileAuditSink sink = new FileAuditSink(
                java.nio.file.Path.of(security.getAuditFile()),
                security.getAuditRotateBytes(),
                security.getAuditKeep(),
                // Through the node's log rather than standard error: a write failure here means
                // decisions are being made and not recorded, which is exactly the state CFG-23 is
                // about, and it belongs where the operator is already looking.
                message -> log.error("audit: {}", message));
        log.info(
                "audit trail: {} (owner-readable only, JSON Lines, rotating at {} bytes, keeping {})",
                sink.path(),
                security.getAuditRotateBytes(),
                security.getAuditKeep());
        return sink;
    }

    /**
     * The durable sink, wrapped so the most recent decisions can be read back over {@code GET
     * /api/v1/audit}.
     *
     * <p>A bounded ring beside the durable sink rather than the durable sink read back: the file is
     * written asynchronously, rotates, and is for the operator's own tools, while the ring is recorded
     * on the same call as the decision and costs O(1). {@link AuditTrail} explains the trade and the
     * read API reports the bound. The wrapper also swallows a failing delegate, so auditing cannot fail
     * the call it audits.
     */
    private AuditSink readable(AuditSink durable, String kind) {
        int capacity = security.getAuditRecent();
        if (capacity < 1) {
            throw new PravahaException(
                    SecurityErrors.MISCONFIGURED,
                    "pravaha.security.audit-recent is " + capacity + "; it is how many recent decisions stay "
                            + "readable over /api/v1/audit and must be at least 1.");
        }
        return new AuditTrail(durable, kind, capacity);
    }
}
