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
package com.ash.messaging.pravaha.server.observe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.catalog.CatalogPolicy;
import com.ash.messaging.pravaha.catalog.CatalogStatistics;
import com.ash.messaging.pravaha.catalog.Privilege;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.alert.AlertService;
import com.ash.messaging.pravaha.registry.alert.AlertStatistics;

/**
 * The meters of the newest features: alerts (ADR-057) and the catalogue (ADR-059).
 *
 * <p>Read at scrape time from the engine's own counters ({@link AlertStatistics}, {@link
 * CatalogStatistics}), as every per-query number is: the engine keeps no metrics library, and a meter
 * that reads a counter cannot disagree with it. Reconciled by {@code PravahaMetrics}' sync -- an alert
 * created gains its gauge, one dropped loses it.
 *
 * <p><strong>Labels.</strong> An alert's name and a channel's name (both made by an administrator, so
 * bounded by configuration), a privilege, an outcome and a kind (fixed sets). Never a user, a key, a
 * row, a statement or an object name: those would make every principal and every view a time series.
 */
public final class FeatureMeters {

    private final MeterRegistry meters;
    private final Supplier<Optional<AlertService>> alerts;

    private @Nullable AlertService publishedAlerts;
    private final Map<String, List<Meter.Id>> perAlert = new HashMap<>();
    private final List<Meter.Id> alertNode = new ArrayList<>();
    private final List<Meter.Id> perChannel = new ArrayList<>();
    private Set<String> channelsPublished = Set.of();

    private @Nullable CatalogPolicy publishedCatalog;
    private final List<Meter.Id> catalogMeters = new ArrayList<>();

    public FeatureMeters(MeterRegistry meters, Supplier<Optional<AlertService>> alerts) {
        this.meters = meters;
        this.alerts = alerts;
    }

    /** Brings the meters in line with the node's alerts and catalogue. */
    public synchronized void sync(@Nullable QueryRegistry registry) {
        syncAlerts(alerts.get().orElse(null));
        syncCatalog(registry != null && registry.policy() instanceof CatalogPolicy catalog ? catalog : null);
    }

    // ------------------------------------------------------------------ alerts

    private void syncAlerts(@Nullable AlertService service) {
        if (service != publishedAlerts) {
            removeAll(alertNode);
            removeAll(perChannel);
            perAlert.values().forEach(this::removeAll);
            perAlert.clear();
            channelsPublished = Set.of();
            publishedAlerts = service;
            if (service != null) {
                publishAlertNode(service);
            }
        }
        if (service == null) {
            return;
        }
        Set<String> channels = service.channels().keySet();
        if (!channels.equals(channelsPublished)) {
            removeAll(perChannel);
            channels.forEach(channel -> publishChannel(service.statistics(), channel));
            channelsPublished = Set.copyOf(channels);
        }
        Map<String, Integer> firing = service.firingByAlert();
        for (String alert : firing.keySet()) {
            perAlert.computeIfAbsent(alert, a -> publishAlert(service, a));
        }
        for (String gone :
                perAlert.keySet().stream().filter(a -> !firing.containsKey(a)).toList()) {
            removeAll(java.util.Objects.requireNonNull(perAlert.remove(gone), "a key of the map"));
        }
    }

    private void publishAlertNode(AlertService service) {
        AlertStatistics statistics = service.statistics();
        alertNode.add(Gauge.builder("pravaha.alert.notifications.owed", service, AlertService::owed)
                .description("Notifications decided and not yet accepted by a channel, across every alert")
                .register(meters)
                .getId());
        alertNode.add(FunctionCounter.builder(
                        "pravaha.alert.journal.write.failures", statistics, AlertStatistics::journalFailures)
                .description("Alert journal writes that failed; the decisions they carried were not made")
                .register(meters)
                .getId());
    }

    private List<Meter.Id> publishAlert(AlertService service, String alert) {
        AlertStatistics statistics = service.statistics();
        List<Meter.Id> ids = new ArrayList<>();
        Tags tags = Tags.of("alert", alert);
        ids.add(Gauge.builder(
                        "pravaha.alert.keys.firing",
                        service,
                        s -> s.firingByAlert().getOrDefault(alert, 0))
                .description("Keys of the alert firing now")
                .tags(tags)
                .register(meters)
                .getId());
        for (String kind : List.of(AlertStatistics.FIRED, AlertStatistics.CLEARED)) {
            ids.add(FunctionCounter.builder("pravaha.alert.transitions", statistics, s -> s.transitions(alert, kind))
                    .description("Keys that fired or cleared since the node started")
                    .tags(tags.and("kind", kind))
                    .register(meters)
                    .getId());
        }
        return ids;
    }

    private void publishChannel(AlertStatistics statistics, String channel) {
        AlertStatistics.Channel counted = statistics.channel(channel);
        Tags tags = Tags.of("channel", channel);
        perChannel.add(
                FunctionCounter.builder("pravaha.alert.notifications", counted, AlertStatistics.Channel::delivered)
                        .description("Notification attempts per channel, by outcome")
                        .tags(tags.and("outcome", "delivered"))
                        .register(meters)
                        .getId());
        perChannel.add(FunctionCounter.builder("pravaha.alert.notifications", counted, AlertStatistics.Channel::failed)
                .description("Notification attempts per channel, by outcome")
                .tags(tags.and("outcome", "failed"))
                .register(meters)
                .getId());
        perChannel.add(
                FunctionCounter.builder("pravaha.alert.notification.retries", counted, AlertStatistics.Channel::retries)
                        .description("Attempts that retried a notification the channel had refused before")
                        .tags(tags)
                        .register(meters)
                        .getId());
        perChannel.add(FunctionTimer.builder(
                        "pravaha.alert.delivery",
                        counted,
                        AlertStatistics.Channel::sends,
                        c -> c.totalNanos(),
                        TimeUnit.NANOSECONDS)
                .description("How long a channel took to accept or refuse a notification")
                .tags(tags)
                .register(meters)
                .getId());
    }

    // ------------------------------------------------------------------ the catalogue

    private void syncCatalog(@Nullable CatalogPolicy catalog) {
        if (catalog == publishedCatalog) {
            return;
        }
        removeAll(catalogMeters);
        publishedCatalog = catalog;
        if (catalog == null) {
            return;
        }
        CatalogStatistics statistics = catalog.service().access().statistics();
        for (Privilege privilege : Privilege.values()) {
            for (boolean allow : new boolean[] {true, false}) {
                catalogMeters.add(FunctionCounter.builder(
                                "pravaha.catalog.access.decisions", statistics, s -> s.decisions(privilege, allow))
                        .description("Access decisions the catalogue made at an enforcement point")
                        .tags("privilege", privilege.name(), "outcome", allow ? "allow" : "deny")
                        .register(meters)
                        .getId());
            }
        }
        catalogMeters.add(FunctionCounter.builder(
                        "pravaha.catalog.decision.cache.lookups", statistics, CatalogStatistics::cacheHits)
                .description("Access decisions looked up in the catalogue's cache, by result")
                .tags("result", "hit")
                .register(meters)
                .getId());
        catalogMeters.add(FunctionCounter.builder(
                        "pravaha.catalog.decision.cache.lookups", statistics, CatalogStatistics::cacheMisses)
                .description("Access decisions looked up in the catalogue's cache, by result")
                .tags("result", "miss")
                .register(meters)
                .getId());
        var journal = catalog.service().catalog();
        for (String kind : CatalogStatistics.CHANGE_KINDS) {
            catalogMeters.add(FunctionCounter.builder("pravaha.catalog.changes", journal, c -> c.changes(kind))
                    .description("Changes made to the catalogue on this node since it started, by kind")
                    .tags("kind", kind)
                    .register(meters)
                    .getId());
        }
    }

    private void removeAll(List<Meter.Id> ids) {
        if (ids != null) {
            ids.forEach(meters::remove);
            ids.clear();
        }
    }
}
