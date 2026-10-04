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

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.Notification;
import com.ash.messaging.pravaha.api.plugin.NotifierPlugin;
import com.ash.messaging.pravaha.catalog.CatalogNames;
import com.ash.messaging.pravaha.catalog.CatalogObject;
import com.ash.messaging.pravaha.catalog.CatalogPolicy;
import com.ash.messaging.pravaha.catalog.ObjectKind;
import com.ash.messaging.pravaha.catalog.Privilege;
import com.ash.messaging.pravaha.common.observe.EngineSpans;
import com.ash.messaging.pravaha.registry.Alerting;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.ViewNames;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.AlertStatement;

/**
 * The node's alerts (ADR-057): every alert, its journal, the thread that evaluates them and the threads
 * that deliver what they owe. Every rule is here, once, for SQL ({@code CREATE ALERT} ...), REST ({@code
 * /api/v1/alerts}), the CLI and the console.
 *
 * <p><strong>Guarantees, plainly.</strong> The state is exactly once: every decision about a key is
 * journalled and forced before anything is sent, and a restart replays the journal, so a key firing
 * before it is firing after -- never re-fired -- and a clear that was decided and not yet delivered is
 * delivered. Delivery is at least once: a channel that does not accept a notification is sent it again
 * (by the plugin with backoff, then by this service every {@code redeliver-after}) until it does, and a
 * restart between a send and its record sends it again. Every attempt carries the same idempotency key.
 *
 * <p><strong>Locks.</strong> This service's lock guards the set of alerts; each alert's monitor its
 * state; a third lock the journal. They are taken in that order and never the other way, which is why
 * the alerts call back only into the unsynchronised parts of this class.
 */
public final class AlertService implements Alerting, AutoCloseable {

    /**
     * Where this class used to synchronize on itself. A ReentrantLock rather than a monitor,
     * because it calls into the registry under it, and a registration holds the registry's lock
     * across network I/O, and on JDK 21 a virtual thread blocked inside a monitor pins its carrier
     * (ADR-062).
     */
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    private static final System.Logger LOG = System.getLogger(AlertService.class.getName());

    /** The principal the audit trail names for what an alert decides on its own. */
    static final Principal ENGINE = new Principal("pravaha-alerts", "*", Set.of(), Map.of());

    /**
     * How the service runs.
     *
     * @param tick how often the alerts are evaluated
     * @param recoveryGrace after a restart, how long a firing key missing from the restored answer waits
     *     before it may clear -- the time for a view rebuilt by replay to catch up
     * @param redeliverAfter how long a notification a channel did not accept waits before it is sent again
     * @param deliveryThreads how many notifications are sent at once; 0 sends on the evaluating thread
     * @param background whether the service evaluates on its own thread; false for a test that calls
     *     {@link #tick()} itself
     */
    public record Settings(
            Duration tick, Duration recoveryGrace, Duration redeliverAfter, int deliveryThreads, boolean background) {

        public static Settings defaults() {
            return new Settings(Duration.ofMillis(250), Duration.ofSeconds(30), Duration.ofSeconds(60), 2, true);
        }

        /** Nothing in the background, sent on the caller's thread, no grace: a test drives it. */
        public static Settings manual() {
            return new Settings(Duration.ofMillis(250), Duration.ZERO, Duration.ofSeconds(60), 0, false);
        }

        public Settings withRecoveryGrace(Duration grace) {
            return new Settings(tick, grace, redeliverAfter, deliveryThreads, background);
        }

        public Settings withRedeliverAfter(Duration after) {
            return new Settings(tick, recoveryGrace, after, deliveryThreads, background);
        }
    }

    private final QueryRegistry registry;
    private final Notifiers notifiers;
    private final AlertAccess access;
    private final AuditSink audit;
    private final Clock clock;
    private final Settings settings;
    private final AlertJournal journal;
    private final Object journalLock = new Object();

    /** What the alerts have done, for the node's meters. */
    private final AlertStatistics statistics = new AlertStatistics();

    /** By name; read without the lock by the evaluating and delivering threads. */
    private final Map<String, Alert> alerts = new ConcurrentHashMap<>();

    private @Nullable ScheduledExecutorService evaluator;
    private @Nullable ExecutorService delivery;
    private long ticks;
    private volatile boolean closed;

    /** How many records the journal held when it was last compacted or replayed. */
    private volatile int compactedAt;

    private AlertService(
            QueryRegistry registry,
            AlertJournal journal,
            Notifiers notifiers,
            AuditSink audit,
            Clock clock,
            Settings settings) {
        this.registry = registry;
        this.journal = journal;
        this.notifiers = notifiers == null ? Notifiers.none() : notifiers;
        this.audit = audit == null ? AuditSink.NONE : audit;
        this.access = new AlertAccess(registry.policy(), this.audit, registry.owners()::mayAdminister);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.settings = settings == null ? Settings.defaults() : settings;
    }

    /**
     * Opens the alerts journalled at {@code journalFile} (null keeps them only in memory), follows each
     * one's view, attaches to {@code registry} so that a view an alert follows cannot be dropped, and
     * starts evaluating. The registry must have recovered its queries first: an alert whose view did not
     * come back waits for it, and says so.
     */
    public static AlertService open(
            QueryRegistry registry,
            @Nullable Path journalFile,
            Notifiers notifiers,
            AuditSink audit,
            Clock clock,
            Settings settings) {
        AlertJournal journal = journalFile == null ? AlertJournal.inMemory() : AlertJournal.at(journalFile);
        AlertService service = new AlertService(registry, journal, notifiers, audit, clock, settings);
        service.recover();
        registry.alertingWith(service);
        service.start();
        return service;
    }

    // ------------------------------------------------------------------ recovery

    private void recover() {
        Map<String, Alert> byId = new java.util.LinkedHashMap<>();
        journal.replay(record -> {
            switch (record.get(0)) {
                case "A" -> {
                    AlertDefinition definition = AlertDefinition.decode(record);
                    Alert known = byId.get(definition.id());
                    if (known == null) {
                        byId.put(definition.id(), new Alert(this, definition, true));
                    } else {
                        known.define(definition);
                    }
                }
                case "D" -> byId.remove(record.get(1));
                case "F", "C", "N", "K", "S" -> {
                    Alert alert = byId.get(record.get(1));
                    if (alert != null) {
                        alert.replay(record);
                    }
                }
                default ->
                    throw new PravahaException(
                            AlertErrors.JOURNAL_FAILED,
                            "the alert journal at " + journal.file() + " has a record of kind '" + record.get(0)
                                    + "', which this engine does not know; it was written by a newer version");
            }
        });
        // Keyed by engine name (ADR-060): an alert recorded before names were per tenant recorded its
        // name and its tenant, which is all the key needs.
        byId.values().forEach(alert -> alerts.put(alert.definition().engineName(), alert));
        int live = liveCount();
        compactedAt = live;
        if (journal.records() > live + 256) {
            compact();
        }
        if (registry.policy() instanceof CatalogPolicy catalog) {
            for (Alert alert : alerts.values()) {
                AlertDefinition d = alert.definition();
                try {
                    catalog.alertCreated(new Principal(d.owner(), d.tenant(), Set.of(), Map.of()), d.engineName());
                } catch (PravahaException e) {
                    LOG.log(
                            System.Logger.Level.WARNING,
                            "the catalogue could not record the alert '" + d.name() + "': " + e.getMessage());
                }
            }
            catalog.service().catalog().reconcile(ObjectKind.ALERT, alerts.keySet());
        }
        for (Alert alert : alerts.values()) {
            attach(alert);
        }
        if (!alerts.isEmpty()) {
            LOG.log(
                    System.Logger.Level.INFO,
                    "alerts: recovered " + alerts.size() + " from "
                            + (journal.file() == null
                                    ? "memory"
                                    : journal.file().toString()));
        }
    }

    @SuppressWarnings(
            "FutureReturnValueIgnored") // the task reports its own outcome (a callback, or a catch-all in the task)
    private void start() {
        if (settings.deliveryThreads() > 0) {
            delivery = Executors.newFixedThreadPool(settings.deliveryThreads(), runnable -> {
                Thread thread = new Thread(runnable, "pravaha-alert-delivery");
                thread.setDaemon(true);
                return thread;
            });
        }
        if (settings.background()) {
            evaluator = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "pravaha-alerts");
                thread.setDaemon(true);
                return thread;
            });
            long every = Math.max(10, settings.tick().toMillis());
            evaluator.scheduleWithFixedDelay(this::tickQuietly, every, every, TimeUnit.MILLISECONDS);
        }
    }

    /** Follows the alert's view if it is registered, or leaves the alert waiting for it. */
    private void attach(Alert alert) {
        AlertDefinition d = alert.definition();
        Optional<RegisteredQuery> query = registry.find(d.view());
        if (query.isEmpty()) {
            alert.waiting("the view '" + d.view() + "' is not registered; the alert follows it when it is");
            return;
        }
        try {
            alert.follow(query.get().view(), access.narrowingFor(d, query.get().view()), access.narrowingOf(d));
        } catch (PravahaException e) {
            alert.broken(e.getMessage());
        }
    }

    /**
     * Follows again when the policies narrowing the owner's view have changed (ADR-059 §8): the rows it
     * holds were filtered and masked by the old ones, and a fresh snapshot under the new ones replaces
     * them rather than mixing two meanings.
     */
    private void refollowIfNarrowingChanged(Alert alert) {
        String now;
        try {
            now = access.narrowingOf(alert.definition());
        } catch (PravahaException e) {
            alert.unfollow();
            alert.broken(e.getMessage());
            return;
        }
        if (!now.equals(alert.narrowedBy())) {
            alert.unfollow();
            attach(alert);
        }
    }

    // ------------------------------------------------------------------ evaluating

    /** Evaluates every alert once and sends what is owed. What the background thread does, for a test. */
    public void tick() {
        Instant now = clock.instant();
        boolean retryWaiting = !settings.background() || (++ticks % 20 == 0);
        for (Alert alert : alerts.values()) {
            if (retryWaiting && !alert.following()) {
                attach(alert);
            } else if (alert.following()) {
                refollowIfNarrowingChanged(alert);
            }
            for (Alert.Dispatch dispatch : alert.tick(now)) {
                deliver(dispatch);
            }
        }
        if (journal.records() > 2 * compactedAt + 10_000) {
            compact();
        }
    }

    private void tickQuietly() {
        if (closed) {
            return;
        }
        try {
            tick();
        } catch (RuntimeException e) {
            // The next tick tries again; a journal that cannot be written is logged every time it fails.
            LOG.log(System.Logger.Level.ERROR, "alerts: evaluation failed: " + e.getMessage(), e);
        }
    }

    private void deliver(Alert.Dispatch dispatch) {
        Runnable send = () -> {
            boolean ok = true;
            List<String> details = new ArrayList<>();
            for (String channel : dispatch.channels()) {
                NotifierPlugin.Delivery result = sendTraced(channel, dispatch);
                ok &= result.delivered();
                details.add(channel + ": " + notifiers.redact(result.detail()));
            }
            dispatch.alert().delivered(dispatch, ok, String.join("; ", details), clock.instant());
        };
        if (delivery == null) {
            send.run();
        } else {
            delivery.execute(send);
        }
    }

    /**
     * One channel's send, timed and counted, inside a span when the node traces. The span names the
     * alert, the channel and the kind; never the key or the row, which are the view's data.
     */
    private NotifierPlugin.Delivery sendTraced(String channel, Alert.Dispatch dispatch) {
        Notification n = dispatch.notification();
        try (EngineSpans.Span span = EngineSpans.start(
                "pravaha.alert.notify",
                "pravaha.alert",
                n.alert(),
                "pravaha.alert.channel",
                channel,
                "pravaha.alert.kind",
                n.kind())) {
            long started = System.nanoTime();
            NotifierPlugin.Delivery result;
            try {
                result = notifiers.send(channel, n);
            } catch (RuntimeException e) {
                statistics.sent(channel, false, dispatch.retry(), System.nanoTime() - started);
                span.failed(e);
                throw e;
            }
            statistics.sent(channel, result.delivered(), dispatch.retry(), System.nanoTime() - started);
            span.attribute("pravaha.alert.delivered", Boolean.toString(result.delivered()));
            return result;
        }
    }

    // ------------------------------------------------------------------ what alerts call back

    void transition(String alert, String kind) {
        statistics.transition(alert, kind);
    }

    /** What the alerts have done since this node started: transitions, deliveries, journal failures. */
    public AlertStatistics statistics() {
        return statistics;
    }

    /** Keys firing now, per alert, by engine name: what an operator's meters label an alert with. */
    public Map<String, Integer> firingByAlert() {
        Map<String, Integer> firing = new java.util.TreeMap<>();
        for (Alert alert : alerts.values()) {
            firing.put(alert.definition().engineName(), alert.firingCount());
        }
        return firing;
    }

    /** Notifications owed now -- decided and not yet accepted by a channel -- across every alert. */
    public int owed() {
        int owed = 0;
        for (Alert alert : alerts.values()) {
            owed += alert.owedCount();
        }
        return owed;
    }

    Instant now() {
        return clock.instant();
    }

    Duration recoveryGrace() {
        return settings.recoveryGrace();
    }

    Duration redeliverAfter() {
        return settings.redeliverAfter();
    }

    void journal(List<List<String>> records) {
        if (records.isEmpty()) {
            return;
        }
        synchronized (journalLock) {
            try {
                journal.append(records);
            } catch (RuntimeException e) {
                statistics.journalFailed();
                throw e;
            }
        }
    }

    void audit(String action, AlertDefinition alert, String detail) {
        audit.record(AuditEvent.of(ENGINE, action, alert.name(), AccessDecision.allow(), detail));
    }

    private int liveCount() {
        int live = 0;
        for (Alert alert : alerts.values()) {
            live += 1 + alert.live().size();
        }
        return live;
    }

    /**
     * Rewrites the journal with only what is live -- the checkpoint of every alert's state. Holds this
     * service's lock (so no alert is created or dropped meanwhile) and every alert's (so none decides
     * anything), then the journal's: the one order every other path takes them in.
     */
    private void compact() {
        lock.lock();
        try {
            List<Alert> all = new ArrayList<>(alerts.values());
            holding(all, 0, () -> {
                List<List<String>> live = new ArrayList<>();
                for (Alert alert : all) {
                    live.add(alert.definition().encode());
                    live.addAll(alert.live());
                }
                synchronized (journalLock) {
                    journal.rewrite(live);
                }
                compactedAt = live.size();
            });
        } finally {
            lock.unlock();
        }
    }

    private static void holding(List<Alert> all, int from, Runnable body) {
        if (from == all.size()) {
            body.run();
            return;
        }
        synchronized (all.get(from)) {
            holding(all, from + 1, body);
        }
    }

    // ------------------------------------------------------------------ Alerting

    @Override
    public List<String> followersOf(String view) {
        return followersOf(view, null);
    }

    /** {@link #followersOf(String)}, those {@code principal} may see; every one when it is null. */
    @Override
    public List<String> followersOf(String view, @Nullable Principal principal) {
        List<String> followers = new ArrayList<>();
        for (Alert alert : alerts.values()) {
            if (alert.definition().view().equals(view)
                    && (principal == null || access.maySee(principal, alert.definition()))) {
                followers.add("ALERT "
                        + (principal == null
                                ? alert.definition().name()
                                : ViewNames.shown(principal, alert.definition().engineName())));
            }
        }
        java.util.Collections.sort(followers);
        return followers;
    }

    @Override
    public ViewQuery.Result execute(AlertStatement statement, Principal principal) {
        return new AlertStatementRunner(this).execute(statement, principal);
    }

    // ------------------------------------------------------------------ the statements

    /** Names an alert may not take: the alert API's own literal paths (ALERTPATH-1). */
    private static final java.util.Set<String> RESERVED_NAMES = java.util.Set.of("channels");

    /** {@code CREATE ALERT}. */
    public AlertStatus.Summary create(Principal principal, AlertStatement.Create statement) {
        lock.lock();
        try {
            String name = CatalogNames.part(statement.name(), "an alert's name");
            if (RESERVED_NAMES.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                // ALERTPATH-1: GET /api/v1/alerts/channels is the channel list, so an alert of that name
                // could never be reached by its own detail path, nor paused, snoozed or acknowledged there.
                throw AlertOptions.invalid("'" + name + "' cannot name an alert: /api/v1/alerts/" + name + " is the "
                        + "node's list of notifier channels, so the alert could not be reached by its own path. "
                        + "Choose another name");
            }
            // ADR-060: an alert name is unique within its tenant, so only the caller's own tenant can hold it.
            String key = ViewNames.engineName(principal.tenant(), name);
            Alert existing = alerts.get(key);
            if (existing != null) {
                if (statement.ifNotExists() && access.maySee(principal, existing.definition())) {
                    return existing.summary(clock.instant());
                }
                throw new PravahaException(
                        AlertErrors.ALERT_EXISTS,
                        "an alert called '" + name + "' exists already"
                                + (statement.ifNotExists() ? "" : "; add IF NOT EXISTS if that is fine"));
            }
            if (registry.find(key).isPresent()) {
                throw new PravahaException(
                        AlertErrors.ALERT_EXISTS,
                        "'" + name + "' is a continuous query's name, and "
                                + "an alert and a query cannot share one: every surface names both the same way");
            }
            String view = resolveView(principal, statement.view());
            for (String channel : statement.channels()) {
                requireChannel(channel);
            }
            AlertOptions options = AlertOptions.of(AlertOptions.defaults(), statement.options());
            access.requireCreate(principal, name, view, statement.channels());
            StreamSchema schema = registry.find(view).orElseThrow().view().schema();
            AlertCondition.compile(statement.where(), schema);
            requireColumns(options.include(), schema);
            if (registry.policy() instanceof CatalogPolicy catalog) {
                String fullName = CatalogNames.defaultNamespaceOf(principal.tenant()) + "." + name;
                Optional<CatalogObject> taken = catalog.service().catalog().object(fullName);
                if (taken.isPresent()) {
                    throw new PravahaException(
                            AlertErrors.ALERT_EXISTS,
                            "'" + fullName + "' is already the catalogue's " + "name for a "
                                    + taken.get().kind().name().toLowerCase(java.util.Locale.ROOT));
                }
            }
            Instant now = clock.instant();
            AlertDefinition definition = new AlertDefinition(
                    UUID.randomUUID().toString(),
                    name,
                    view,
                    principal.tenant(),
                    principal.id(),
                    now,
                    statement.where(),
                    statement.channels(),
                    options,
                    false,
                    options.snooze().isZero() ? null : now.plus(options.snooze()),
                    now,
                    principal.id());
            // MASKALERT-1: a condition or a key on a column masked for the owner is refused here, to the
            // person creating it (PRV-7006), as SECURITY.md promises -- not accepted ACTIVE and then marked
            // broken when the alert starts following. The same check runs again whenever it follows.
            access.narrowingFor(definition, registry.find(view).orElseThrow().view());
            journal(List.of(definition.encode()));
            if (registry.policy() instanceof CatalogPolicy catalog) {
                try {
                    catalog.alertCreated(principal, key);
                } catch (PravahaException e) {
                    LOG.log(
                            System.Logger.Level.ERROR,
                            "the catalogue could not record the alert '" + name + "': " + e.getMessage()
                                    + "; it is recorded at the next start");
                }
            }
            Alert alert = new Alert(this, definition, false);
            alerts.put(key, alert);
            attach(alert);
            return alert.summary(now);
        } finally {
            lock.unlock();
        }
    }

    /** {@code ALTER ALERT ... NOTIFY} or {@code SET (...)}: {@code MANAGE}. */
    public AlertStatus.Summary alter(Principal principal, AlertStatement.Alter statement) {
        lock.lock();
        try {
            Alert alert = require(principal, statement.name());
            access.require(principal, alert.definition(), Privilege.MANAGE, "alter");
            Instant now = clock.instant();
            AlertDefinition next = alert.definition();
            if (!statement.channels().isEmpty()) {
                for (String channel : statement.channels()) {
                    requireChannel(channel);
                }
                access.requireChannels(principal, next.name(), statement.channels());
                next = next.withChannels(statement.channels(), now, principal.id());
            }
            if (!statement.options().isEmpty()) {
                if (statement.options().containsKey("snooze")) {
                    throw AlertOptions.invalid(
                            "snooze is a CREATE option; an existing alert is snoozed with SNOOZE ALERT " + next.name()
                                    + " FOR <duration>");
                }
                AlertOptions options = AlertOptions.of(next.options(), statement.options());
                registry.find(next.view())
                        .ifPresent(
                                q -> requireColumns(options.include(), q.view().schema()));
                next = next.withOptions(options, now, principal.id());
            }
            return redefine(alert, next, now);
        } finally {
            lock.unlock();
        }
    }

    /** {@code DROP ALERT}: {@code MANAGE}. False when {@code ifExists} and there was none. */
    public boolean drop(Principal principal, String name, boolean ifExists) {
        lock.lock();
        try {
            String key = key(principal, name);
            Alert alert = alerts.get(key);
            if (alert == null || !access.maySee(principal, alert.definition())) {
                if (ifExists) {
                    return false;
                }
                throw noSuch(name);
            }
            access.require(principal, alert.definition(), Privilege.MANAGE, "drop");
            journal(List.of(List.of("D", alert.definition().id())));
            alerts.remove(key);
            statistics.forget(key);
            alert.unfollow();
            if (registry.policy() instanceof CatalogPolicy catalog) {
                catalog.alertDropped(key);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** {@code PAUSE ALERT}: {@code MODIFY}. It keeps following, and says nothing until resumed. */
    public AlertStatus.Summary pause(Principal principal, String name) {
        lock.lock();
        try {
            Alert alert = require(principal, name);
            access.require(principal, alert.definition(), Privilege.MODIFY, "pause");
            Instant now = clock.instant();
            return redefine(alert, alert.definition().withPaused(true, now, principal.id()), now);
        } finally {
            lock.unlock();
        }
    }

    /** {@code RESUME ALERT}: {@code MODIFY}. Ends a pause and a snooze; what changed meanwhile is sent. */
    public AlertStatus.Summary resume(Principal principal, String name) {
        lock.lock();
        try {
            Alert alert = require(principal, name);
            access.require(principal, alert.definition(), Privilege.MODIFY, "resume");
            Instant now = clock.instant();
            return redefine(alert, alert.definition().withPaused(false, now, principal.id()), now);
        } finally {
            lock.unlock();
        }
    }

    /** {@code SNOOZE ALERT ... FOR}: {@code MODIFY}. Quiet until then; what changed meanwhile is sent after. */
    public AlertStatus.Summary snooze(Principal principal, String name, Duration duration) {
        lock.lock();
        try {
            Alert alert = require(principal, name);
            access.require(principal, alert.definition(), Privilege.MODIFY, "snooze");
            if (duration.isZero() || duration.isNegative()) {
                throw AlertOptions.invalid("a snooze is for a positive time; RESUME ALERT " + name + " ends one");
            }
            Instant now = clock.instant();
            return redefine(alert, alert.definition().withSnooze(now.plus(duration), now, principal.id()), now);
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@code ACK ALERT}: {@code MODIFY}. Acknowledges every firing key, or the one {@code key} names
     * ({@code sku=sku-100, warehouse=LDN}); an acknowledged key is not reminded about until it fires again.
     *
     * @return how many keys were acknowledged
     */
    public int acknowledge(Principal principal, String name, @Nullable String key) {
        Alert alert;
        lock.lock();
        try {
            alert = require(principal, name);
            access.require(principal, alert.definition(), Privilege.MODIFY, "ack");
        } finally {
            lock.unlock();
        }
        return alert.acknowledge(key == null || key.isBlank() ? null : key.strip(), principal.id(), clock.instant());
    }

    /**
     * Every alert the caller may see, by name: its own tenant's by name, and -- for an admin -- every
     * other tenant's by {@code tenant.default.name} (ADR-060).
     */
    public List<AlertStatus.Summary> list(Principal principal) {
        Instant now = clock.instant();
        java.util.TreeMap<String, AlertStatus.Summary> listed = new java.util.TreeMap<>();
        for (Alert alert : alerts.values()) {
            AlertDefinition d = alert.definition();
            if (ViewNames.visibleTo(principal, d.engineName()) && access.maySee(principal, d)) {
                String shown = ViewNames.shown(principal, d.engineName());
                listed.put(shown, alert.summary(now).named(shown));
            }
        }
        return List.copyOf(listed.values());
    }

    /** One alert with every key it holds and its recent notifications: {@code SELECT}. */
    public AlertStatus.Detail detail(Principal principal, String name) {
        Alert alert = require(principal, name);
        access.require(principal, alert.definition(), Privilege.SELECT, "show");
        return alert.detail(clock.instant());
    }

    /** The bound notifier channels and their plugins. */
    public Map<String, String> channels() {
        return notifiers.plugins();
    }

    private AlertStatus.Summary redefine(Alert alert, AlertDefinition next, Instant now) {
        journal(List.of(next.encode()));
        alert.define(next);
        return alert.summary(now);
    }

    /**
     * The alert {@code name} means to {@code principal} (ADR-060): a bare name is the caller's tenant's; a
     * {@code tenant.default.name} of another tenant is honoured for an admin and refused to anyone else,
     * in words that do not depend on whether it exists.
     */
    private static String key(Principal principal, String name) {
        return ViewNames.resolve(principal, name);
    }

    private Alert require(Principal principal, String name) {
        Alert alert = alerts.get(key(principal, name));
        if (alert == null) {
            throw noSuch(name);
        }
        return alert;
    }

    private void requireChannel(String channel) {
        if (!notifiers.has(channel)) {
            throw new PravahaException(
                    AlertErrors.NO_SUCH_CHANNEL,
                    "no notifier channel is called '" + channel + "'; bind one under pravaha.notifiers." + channel
                            + (notifiers.channels().isEmpty() ? "" : " (bound: " + notifiers.channels() + ")"));
        }
    }

    /** The engine name of the view {@code written} names for {@code principal}, or {@code PRV-8042}. */
    private String resolveView(Principal principal, String written) {
        // ADR-060: a bare name is a view of the alert's tenant, which is its creator's.
        String view = ViewNames.engineName(principal.tenant(), written);
        if (written.contains(".")) {
            if (!(registry.policy() instanceof CatalogPolicy catalog)) {
                throw AlertOptions.invalid("'" + written + "' names a namespace, and namespaces are the catalogue's "
                        + "(pravaha.catalog.enabled); name the view by its registered name");
            }
            try {
                CatalogObject object = catalog.service().resolveWritten(principal, written);
                if (object.kind() != ObjectKind.VIEW) {
                    throw noView(written);
                }
                view = object.engineName();
            } catch (PravahaException e) {
                throw noView(written);
            }
        }
        if (registry.find(view).isEmpty() || !ViewNames.tenantOf(view).equals(principal.tenant())) {
            throw noView(written);
        }
        return view;
    }

    private static PravahaException noView(String written) {
        return AlertOptions.invalid("there is no registered view '" + written + "' to alert on; an alert watches a "
                + "continuous query's view (SHOW CONTINUOUS QUERIES lists them)");
    }

    static PravahaException noSuch(String name) {
        return new PravahaException(AlertErrors.NO_SUCH_ALERT, "there is no alert '" + name + "' that you may see");
    }

    static void requireColumns(List<String> columns, StreamSchema schema) {
        for (String column : columns) {
            boolean found = schema.fields().stream().anyMatch(f -> f.name().equalsIgnoreCase(column));
            if (!found) {
                throw AlertOptions.invalid("include names '" + column + "', which is not a column of the view; it has "
                        + schema.fields().stream()
                                .map(com.ash.messaging.pravaha.api.data.Field::name)
                                .toList());
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        if (evaluator != null) {
            evaluator.shutdownNow();
        }
        if (delivery != null) {
            delivery.shutdown();
            try {
                delivery.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            delivery.shutdownNow();
        }
        alerts.values().forEach(Alert::unfollow);
        if (registry.alerting() == this) {
            registry.alertingWith(null);
        }
    }
}
