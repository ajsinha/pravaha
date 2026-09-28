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

import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ash.messaging.pravaha.catalog.CatalogPolicy;
import com.ash.messaging.pravaha.catalog.ObjectKind;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.alert.AlertService;
import com.ash.messaging.pravaha.registry.alert.Notifiers;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.server.alerts.AlertProperties;
import com.ash.messaging.pravaha.server.alerts.NotifierBindingProperties;

/**
 * The node's alerts (ADR-057): the notifier channels opened, the alert journal replayed, and the service
 * attached to the registry once it has recovered its queries -- so every alert finds the view it follows.
 *
 * <p>Kept out of {@link PravahaNode}, which is at the project's file-size ceiling, as {@link NodeCatalog}
 * is. A channel the node cannot open refuses the start ({@code PRV-8046}): a pager that is found broken
 * at the first page is worse than a node that did not start.
 */
final class NodeAlerts implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NodeAlerts.class);

    private AlertProperties properties = new AlertProperties();
    private NotifierBindingProperties notifiers = new NotifierBindingProperties();
    private AlertService service;
    private Notifiers opened;

    void configure(AlertProperties alerts, NotifierBindingProperties channels) {
        this.properties = alerts == null ? new AlertProperties() : alerts;
        this.notifiers = channels == null ? new NotifierBindingProperties() : channels;
    }

    Optional<AlertService> service() {
        return Optional.ofNullable(service);
    }

    /** After the registry has recovered: opens the channels and the alerts, and follows every view. */
    void start(QueryRegistry registry, Optional<Path> registryJournal, AuditSink audit) {
        if (!properties.isEnabled()) {
            log.info("alerts: pravaha.alerts.enabled is false; alert statements answer PRV-8047");
            return;
        }
        opened = notifiers.toNotifiers().open();
        Path journal = properties.getJournal().isEmpty()
                ? registryJournal
                        .map(p -> p.toAbsolutePath().resolveSibling("alerts.journal"))
                        .orElse(null)
                : Path.of(properties.getJournal());
        if (journal == null) {
            log.warn("alerts: pravaha.alerts.journal is not set and there is no registry journal to keep it beside, "
                    + "so alerts and what they have said live only in memory; a restart forgets them");
        }
        if (registry.policy() instanceof CatalogPolicy catalog) {
            // Channels are catalogued as they are configured, so GRANT WRITE ON NOTIFIER names one at once.
            opened.channels().forEach(c -> catalog.service().catalog().ensureInfrastructure(ObjectKind.NOTIFIER, c));
        }
        service = AlertService.open(registry, journal, opened, audit, Clock.systemUTC(), properties.settings());
        log.info("alerts: serving with channels {} from {}", opened.plugins(), journal == null ? "memory" : journal);
    }

    @Override
    public void close() {
        if (service != null) {
            service.close();
            service = null;
        }
        if (opened != null) {
            opened.close();
            opened = null;
        }
    }
}
