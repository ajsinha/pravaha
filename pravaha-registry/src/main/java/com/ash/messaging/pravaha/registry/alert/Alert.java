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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.plugin.Notification;
import com.ash.messaging.pravaha.registry.ViewRowValues;
import com.ash.messaging.pravaha.security.ViewNames;
import com.ash.messaging.pravaha.serving.AnswerListener;
import com.ash.messaging.pravaha.serving.ServedView;

/**
 * One alert, running: a follower of its view's answer (ADR-056's {@link AnswerListener}), the state of
 * every key it has seen enter its condition, and the decisions about each (ADR-057).
 *
 * <p><strong>Entering and leaving.</strong> The view hands this the rows that left its answer and the
 * rows that entered it, in commit order. A key whose row enters the condition -- inserted, or updated
 * across the threshold -- is <em>in</em>; one whose row leaves it -- deleted, or updated back -- is
 * <em>out</em>. Retractions are what make clearing honest: without the row that left, an alert can only
 * say that something happened, never that it stopped.
 *
 * <p><strong>Two states per key, kept apart.</strong> What is true -- firing or not, after {@code
 * fire_after} and {@code clear_after} -- and what the channels were last told. Pausing, snoozing and
 * {@code dedupe} hold the second back; they never change the first. Whenever they allow, the channels
 * are told the difference, once: a key that fired and cleared while an alert was paused is never
 * announced, a key that cleared while it was snoozed is announced as cleared when the snooze ends, and
 * a flap inside a {@code dedupe} window is folded into the state at its end.
 *
 * <p><strong>Durability.</strong> Every decision -- fired, cleared, told, acknowledged -- is journalled
 * and forced before anything is sent; notifications are derived from the state, so one that was owed
 * when the process stopped is owed again when it starts, under the same idempotency key.
 *
 * <p>The listener methods run under the view's monitor and only queue; everything else runs on the
 * alert service's thread or a delivery thread, under this object's monitor.
 */
final class Alert implements AnswerListener {

    /** How many changed rows may wait before the queue is dropped and the answer asked for again. */
    static final int QUEUE_LIMIT = 65_536;

    /** How many cleared keys are remembered, for the page; the oldest is forgotten first. */
    static final int CLEARED_KEPT = 1_000;

    /** How many notifications are remembered for the page. */
    static final int HISTORY_KEPT = 100;

    private final AlertService service;
    private volatile AlertDefinition definition;

    // ------------------------------------------------------------ the view's side (queue only)
    private final Object queueLock = new Object();
    private final ArrayDeque<Object> queue = new ArrayDeque<>();
    private int queuedRows;
    private boolean resync;

    /** The answer as it stood, and when the view handed it over. */
    private record Snapshot(List<Object[]> rows, Instant at) {}

    /** One commit's change, and when it was committed: the moment a key entered or left, for the timers. */
    private record Change(List<Object[]> leaving, List<Object[]> entering, Instant at) {}

    /** A notification sent or tried, kept with its raw key so it is shown by column name once followed. */
    @SuppressWarnings("ArrayRecordComponent") // carries the array; nothing compares or hashes one
    private record Told(
            Instant at,
            Object[] key,
            String kind,
            long episode,
            String idempotencyKey,
            List<String> channels,
            String outcome,
            String detail) {}

    // ------------------------------------------------------------ evaluation (under this)
    private @Nullable ServedView view;
    private @Nullable StreamSchema schema;
    private int @Nullable [] keyOrdinals;
    private AlertCondition condition = AlertCondition.always();
    private com.ash.messaging.pravaha.serving.RowNarrowing narrowing =
            com.ash.messaging.pravaha.serving.RowNarrowing.NONE;
    private String narrowedBy = "";
    private String following = "WAITING";
    private @Nullable String problem;
    private final Map<RowKey, Object[]> matching = new java.util.HashMap<>();
    private final Map<RowKey, KeyState> keys = new LinkedHashMap<>();
    private final ArrayDeque<Told> history = new ArrayDeque<>();
    private boolean recovered;
    private @Nullable Instant lastNotificationAt;
    private @Nullable String deliveryError;

    /** A notification decided under this monitor and sent outside it. */
    record Dispatch(
            Alert alert,
            KeyState state,
            Notification notification,
            List<String> channels,
            int reminder,
            boolean retry) {}

    Alert(AlertService service, AlertDefinition definition, boolean recovered) {
        this.service = service;
        this.definition = definition;
        this.recovered = recovered;
    }

    AlertDefinition definition() {
        return definition;
    }

    synchronized void define(AlertDefinition next) {
        this.definition = next;
    }

    synchronized boolean following() {
        return view != null;
    }

    // ------------------------------------------------------------------ following the view

    /** Starts following {@code followed}: its snapshot arrives at once, under its monitor. */
    void follow(ServedView followed) {
        follow(followed, com.ash.messaging.pravaha.serving.RowNarrowing.NONE, "");
    }

    /**
     * Starts following {@code followed} as its owner is shown it (ADR-059 §4): every snapshot and change
     * passes {@code shown} before the condition sees it.
     *
     * @param by what {@code shown} enforces, to notice when the policies behind it change
     */
    void follow(ServedView followed, com.ash.messaging.pravaha.serving.RowNarrowing shown, String by) {
        synchronized (this) {
            this.narrowing = shown;
            this.narrowedBy = by;
            this.view = followed;
            this.schema = followed.schema();
            this.keyOrdinals =
                    followed.keyOrdinals().stream().mapToInt(Integer::intValue).toArray();
            this.condition = AlertCondition.compile(definition.where(), schema);
            AlertService.requireColumns(definition.options().include(), schema);
            this.following = "FOLLOWING";
            this.problem = null;
        }
        followed.followAnswer(this);
    }

    synchronized String narrowedBy() {
        return narrowedBy;
    }

    synchronized void broken(@Nullable String why) {
        this.following = "BROKEN";
        this.problem = why;
    }

    synchronized void waiting(String why) {
        this.following = "WAITING";
        this.problem = why;
    }

    void unfollow() {
        ServedView followed;
        synchronized (this) {
            followed = view;
            view = null;
        }
        if (followed != null) {
            followed.unfollowAnswer(this);
        }
    }

    @Override
    public void onSnapshot(List<Object[]> rows, long frontier) {
        synchronized (queueLock) {
            queue.clear();
            queuedRows = 0;
            resync = false;
            queue.add(new Snapshot(rows, service.now()));
        }
    }

    @Override
    public void onAnswer(List<Object[]> leaving, List<Object[]> entering, long frontier) {
        synchronized (queueLock) {
            if (resync) {
                return;
            }
            int rows = leaving.size() + entering.size();
            if (queuedRows + rows > QUEUE_LIMIT) {
                // Conflated, not lost: the snapshot asked for next is compared with what the alert holds.
                queue.clear();
                queuedRows = 0;
                resync = true;
            } else {
                queue.add(new Change(leaving, entering, service.now()));
                queuedRows += rows;
            }
        }
    }

    // ------------------------------------------------------------------ evaluating

    /**
     * Applies what the view has handed over, decides every timer that has run out, journals the
     * decisions, and answers the notifications now owed -- to be sent by the caller, outside this
     * monitor.
     */
    List<Dispatch> tick(Instant now) {
        List<Object> items;
        boolean again;
        synchronized (queueLock) {
            items = new ArrayList<>(queue);
            queue.clear();
            queuedRows = 0;
            again = resync;
        }
        ServedView followed;
        List<Dispatch> owed = new ArrayList<>();
        synchronized (this) {
            followed = view;
            for (Object item : items) {
                if (item instanceof Snapshot snapshot) {
                    applySnapshot(snapshot.rows(), snapshot.at());
                } else if (item instanceof Change change) {
                    applyChange(change.leaving(), change.entering(), change.at());
                }
            }
            List<List<String>> decided = new ArrayList<>();
            AlertOptions options = definition.options();
            for (KeyState state : keys.values()) {
                if (!state.firing
                        && state.in
                        && state.inSince != null
                        && !now.isBefore(state.inSince.plus(options.fireAfter()))) {
                    state.firing = true;
                    state.episode++;
                    state.fired++;
                    state.firingSince = now;
                    state.clearedAt = null;
                    state.outSince = null;
                    state.acknowledgedBy = null;
                    state.acknowledgedAt = null;
                    state.reminders = 0;
                    decided.add(List.of(
                            "F",
                            definition.id(),
                            encodeKey(state.key),
                            Long.toString(state.episode),
                            now.toString(),
                            encodeRow(state.row)));
                    service.audit("alert.fire", definition, describe(state.key));
                    service.transition(definition.engineName(), AlertStatistics.FIRED);
                } else if (state.firing
                        && !state.in
                        && state.outSince != null
                        && !now.isBefore(state.outSince.plus(options.clearAfter()))) {
                    state.firing = false;
                    state.clearedAt = now;
                    state.outSince = null;
                    decided.add(List.of("C", definition.id(), encodeKey(state.key), now.toString()));
                    service.audit("alert.clear", definition, describe(state.key));
                    service.transition(definition.engineName(), AlertStatistics.CLEARED);
                }
            }
            // Journalled and forced before anything is sent: the state is exactly once.
            service.journal(decided);
            if (!definition.paused() && !definition.snoozed(now) && view != null) {
                for (KeyState state : keys.values()) {
                    Dispatch dispatch = owed(state, now);
                    if (dispatch != null) {
                        state.inFlight = true;
                        owed.add(dispatch);
                    }
                }
            }
            forgetOldClears();
        }
        if (again && followed != null) {
            // Outside both monitors: this enters the view's, which hands the snapshot to onSnapshot.
            synchronized (queueLock) {
                resync = false;
            }
            followed.resnapshotAnswer(this);
        }
        return owed;
    }

    private void applySnapshot(List<Object[]> rows, Instant now) {
        Map<RowKey, Object[]> after = new LinkedHashMap<>();
        for (Object[] row : narrowing.apply(rows)) {
            if (condition.holds(row)) {
                after.put(keyOf(row), row);
            }
        }
        // After a restart, a firing key the restored answer does not hold may simply not have been
        // replayed yet: its clear waits out the recovery grace as well as clear_after.
        Instant outFrom = recovered ? now.plus(service.recoveryGrace()) : now;
        recovered = false;
        for (Map.Entry<RowKey, KeyState> entry : new ArrayList<>(keys.entrySet())) {
            if (entry.getValue().in && !after.containsKey(entry.getKey())) {
                left(entry.getKey(), outFrom);
            } else if (entry.getValue().firing
                    && !entry.getValue().in
                    && !after.containsKey(entry.getKey())
                    && entry.getValue().outSince == null) {
                left(entry.getKey(), outFrom);
            }
        }
        matching.clear();
        after.forEach((key, row) -> {
            matching.put(key, row);
            entered(key, row, now);
        });
    }

    private void applyChange(List<Object[]> leaving, List<Object[]> entering, Instant now) {
        Map<RowKey, Object[]> after = new LinkedHashMap<>();
        for (Object[] row : narrowing.apply(leaving)) {
            after.put(keyOf(row), null);
        }
        for (Object[] row : narrowing.apply(entering)) {
            after.put(keyOf(row), row);
        }
        after.forEach((key, row) -> {
            boolean in = row != null && condition.holds(row);
            Object[] before = matching.get(key);
            if (in) {
                matching.put(key, row);
                entered(key, row, now);
            } else {
                matching.remove(key);
                if (before != null) {
                    left(key, now);
                }
            }
        });
    }

    private void entered(RowKey key, Object[] row, Instant now) {
        KeyState state = keys.computeIfAbsent(key, k -> new KeyState(k.values()));
        state.row = row;
        if (!state.in) {
            state.in = true;
            state.outSince = null;
            if (!state.firing) {
                state.inSince = now;
            }
        }
    }

    private void left(RowKey key, Instant outFrom) {
        KeyState state = keys.get(key);
        if (state == null) {
            return;
        }
        state.in = false;
        state.inSince = null;
        if (state.firing) {
            state.outSince = outFrom;
        } else if (state.fired == 0 && !state.inFlight) {
            keys.remove(key);
        }
    }

    /** The notification {@code state} is owed now, or null. */
    private @Nullable Dispatch owed(KeyState state, Instant now) {
        if (state.inFlight || (state.retryAt != null && now.isBefore(state.retryAt))) {
            return null;
        }
        AlertOptions options = definition.options();
        String kind;
        if (state.firing && !state.notified.equals("FIRED")) {
            kind = "FIRED";
        } else if (!state.firing && state.notified.equals("FIRED")) {
            kind = "CLEARED";
        } else if (state.firing
                && !options.resendEvery().isZero()
                && state.acknowledgedBy == null
                && state.lastSentAt != null
                && !now.isBefore(state.lastSentAt.plus(options.resendEvery()))) {
            kind = "REMINDER";
        } else {
            return null;
        }
        if (!kind.equals("REMINDER")
                && state.lastNotifiedAt != null
                && now.isBefore(state.lastNotifiedAt.plus(options.dedupe()))) {
            return null; // told within the window; what is true when it ends is sent then, if it differs
        }
        int reminder = kind.equals("REMINDER") ? state.reminders + 1 : 0;
        String keyText = encodeKey(state.key);
        Notification notification = new Notification(
                idempotencyKey(definition.id(), keyText, state.episode, kind, reminder),
                definition.name(),
                ViewNames.localName(definition.view()),
                definition.tenant(),
                kind,
                options.severity(),
                state.episode,
                keyMap(state.key),
                kind.equals("CLEARED") && !state.in ? Map.of() : rowMap(state.row),
                state.firingSince,
                kind.equals("CLEARED") ? state.clearedAt : now,
                1);
        return new Dispatch(this, state, notification, definition.channels(), reminder, state.lastError != null);
    }

    /** What the channels answered: recorded, journalled when delivered, and retried later when not. */
    void delivered(Dispatch dispatch, boolean ok, String detail, Instant now) {
        KeyState state = dispatch.state();
        Notification n = dispatch.notification();
        synchronized (this) {
            state.inFlight = false;
            lastNotificationAt = now;
            if (ok) {
                if (!n.kind().equals("REMINDER")) {
                    state.notified = n.kind();
                    state.lastNotifiedAt = now;
                } else {
                    state.reminders = dispatch.reminder();
                }
                if (!n.kind().equals("CLEARED")) {
                    state.lastSentAt = now;
                }
                state.retryAt = null;
                state.lastError = null;
                deliveryError = null;
                service.journal(List.of(List.of(
                        "N",
                        definition.id(),
                        encodeKey(state.key),
                        n.kind(),
                        Long.toString(n.episode()),
                        now.toString(),
                        Integer.toString(state.reminders))));
            } else {
                state.retryAt = now.plus(service.redeliverAfter());
                state.lastError = detail;
                deliveryError = n.kind() + " for " + describe(state.key) + " not delivered: " + detail;
            }
            remember(new Told(
                    now,
                    state.key,
                    n.kind(),
                    n.episode(),
                    n.idempotencyKey(),
                    dispatch.channels(),
                    ok ? "DELIVERED" : "FAILED",
                    detail));
        }
    }

    /** Acknowledges every firing key, or the one named; how many were acknowledged. */
    synchronized int acknowledge(@Nullable String keyText, String by, Instant now) {
        int acknowledged = 0;
        List<List<String>> decided = new ArrayList<>();
        for (KeyState state : keys.values()) {
            if (!state.firing || state.acknowledgedBy != null) {
                continue;
            }
            if (keyText != null && !keyText.equals(describe(state.key)) && !keyText.equals(encodeKey(state.key))) {
                continue;
            }
            state.acknowledgedBy = by;
            state.acknowledgedAt = now;
            decided.add(List.of(
                    "K", definition.id(), encodeKey(state.key), Long.toString(state.episode), by, now.toString()));
            acknowledged++;
        }
        service.journal(decided);
        return acknowledged;
    }

    private void remember(Told sent) {
        history.addFirst(sent);
        while (history.size() > HISTORY_KEPT) {
            history.removeLast();
        }
    }

    private void forgetOldClears() {
        List<Map.Entry<RowKey, KeyState>> cleared = new ArrayList<>();
        for (Map.Entry<RowKey, KeyState> entry : keys.entrySet()) {
            KeyState s = entry.getValue();
            if (!s.firing && !s.in && !s.inFlight && !s.notified.equals("FIRED")) {
                cleared.add(entry);
            }
        }
        if (cleared.size() <= CLEARED_KEPT) {
            return;
        }
        cleared.sort(
                Comparator.comparing(e -> e.getValue().clearedAt == null ? Instant.EPOCH : e.getValue().clearedAt));
        for (int i = 0; i < cleared.size() - CLEARED_KEPT; i++) {
            keys.remove(cleared.get(i).getKey());
        }
    }

    // ------------------------------------------------------------------ the journal

    /** Applies one replayed record about this alert's keys. */
    synchronized void replay(List<String> f) {
        switch (f.get(0)) {
            case "F" -> {
                KeyState state = restored(f.get(2));
                state.firing = true;
                state.episode = Long.parseLong(f.get(3));
                state.fired++;
                state.firingSince = Instant.parse(f.get(4));
                state.clearedAt = null;
                state.acknowledgedBy = null;
                state.acknowledgedAt = null;
                state.reminders = 0;
                state.row = decodeRow(f.get(5));
            }
            case "C" -> {
                KeyState state = restored(f.get(2));
                state.firing = false;
                state.clearedAt = Instant.parse(f.get(3));
            }
            case "N" -> {
                KeyState state = restored(f.get(2));
                Instant at = Instant.parse(f.get(5));
                if (!f.get(3).equals("REMINDER")) {
                    state.notified = f.get(3);
                    state.lastNotifiedAt = at;
                }
                if (!f.get(3).equals("CLEARED")) {
                    state.lastSentAt = at;
                }
                state.reminders = Integer.parseInt(f.get(6));
                lastNotificationAt = at;
                remember(new Told(
                        at,
                        state.key,
                        f.get(3),
                        Long.parseLong(f.get(4)),
                        idempotencyKey(
                                definition.id(),
                                f.get(2),
                                Long.parseLong(f.get(4)),
                                f.get(3),
                                f.get(3).equals("REMINDER") ? state.reminders : 0),
                        definition.channels(),
                        "DELIVERED",
                        ""));
            }
            case "K" -> {
                KeyState state = restored(f.get(2));
                if (Long.parseLong(f.get(3)) == state.episode) {
                    state.acknowledgedBy = f.get(4);
                    state.acknowledgedAt = Instant.parse(f.get(5));
                }
            }
            case "S" -> {
                KeyState state = restored(f.get(2));
                state.firing = f.get(3).equals("1");
                state.episode = Long.parseLong(f.get(4));
                state.fired = Long.parseLong(f.get(5));
                state.firingSince = instant(f.get(6));
                state.clearedAt = instant(f.get(7));
                state.notified = f.get(8);
                state.lastNotifiedAt = instant(f.get(9));
                state.lastSentAt = instant(f.get(10));
                state.reminders = Integer.parseInt(f.get(11));
                state.acknowledgedBy = f.get(12).isEmpty() ? null : f.get(12);
                state.acknowledgedAt = instant(f.get(13));
                state.row = decodeRow(f.get(14));
            }
            default -> {
                // Unknown to this alert: ignored here, refused by the service.
            }
        }
    }

    /** What this alert holds, as the records a compaction writes: one per remembered key. */
    synchronized List<List<String>> live() {
        List<List<String>> live = new ArrayList<>();
        for (KeyState s : keys.values()) {
            if (s.fired == 0) {
                continue; // pending keys are not durable: their fire_after starts again
            }
            live.add(List.of(
                    "S",
                    definition.id(),
                    encodeKey(s.key),
                    s.firing ? "1" : "0",
                    Long.toString(s.episode),
                    Long.toString(s.fired),
                    text(s.firingSince),
                    text(s.clearedAt),
                    s.notified,
                    text(s.lastNotifiedAt),
                    text(s.lastSentAt),
                    Integer.toString(s.reminders),
                    s.acknowledgedBy == null ? "" : s.acknowledgedBy,
                    text(s.acknowledgedAt),
                    encodeRow(s.row)));
        }
        return live;
    }

    private KeyState restored(String encodedKey) {
        Object[] key = java.util.Objects.requireNonNull(decodeRow(encodedKey), "an encoded key is never empty");
        return keys.computeIfAbsent(new RowKey(key), k -> new KeyState(key));
    }

    // ------------------------------------------------------------------ reading

    synchronized AlertStatus.Summary summary(Instant now) {
        int firing = 0;
        int pending = 0;
        for (KeyState s : keys.values()) {
            if (s.firing) {
                firing++;
            } else if (s.in) {
                pending++;
            }
        }
        AlertDefinition d = definition;
        Map<String, String> options = new LinkedHashMap<>();
        options.put("severity", d.options().severity());
        options.putAll(d.options().written());
        options.remove("severity");
        return new AlertStatus.Summary(
                d.name(),
                ViewNames.localName(d.view()),
                d.tenant(),
                d.owner(),
                d.state(now),
                following,
                d.condition(),
                d.channels(),
                d.options().severity(),
                options,
                d.snoozed(now) ? d.snoozedUntil() : null,
                firing,
                pending,
                keys.size(),
                lastNotificationAt,
                deliveryError,
                problem,
                d.createdAt(),
                d.updatedAt(),
                d.updatedBy());
    }

    synchronized AlertStatus.Detail detail(Instant now) {
        List<AlertStatus.KeyStatus> states = new ArrayList<>();
        for (KeyState s : keys.values()) {
            String state = s.firing ? (s.in ? "FIRING" : "CLEARING") : (s.in ? "PENDING" : "CLEARED");
            String owed = s.firing && !s.notified.equals("FIRED")
                    ? "FIRED"
                    : !s.firing && s.notified.equals("FIRED") ? "CLEARED" : null;
            states.add(new AlertStatus.KeyStatus(
                    keyMapOrText(s.key),
                    state,
                    s.episode,
                    s.fired,
                    s.in ? (s.firing ? s.firingSince : s.inSince) : s.clearedAt,
                    s.firingSince,
                    s.clearedAt,
                    s.notified,
                    s.lastNotifiedAt,
                    owed,
                    s.reminders,
                    s.acknowledgedBy,
                    s.acknowledgedAt,
                    s.row == null ? Map.of() : rowMap(s.row)));
        }
        states.sort(Comparator.comparingInt((AlertStatus.KeyStatus k) -> order(k.state())));
        List<AlertStatus.Sent> told = new ArrayList<>();
        for (Told t : history) {
            told.add(new AlertStatus.Sent(
                    t.at(),
                    keyMapOrText(t.key()),
                    t.kind(),
                    t.episode(),
                    t.idempotencyKey(),
                    t.channels(),
                    t.outcome(),
                    t.detail()));
        }
        return new AlertStatus.Detail(summary(now), states, told);
    }

    private static int order(String state) {
        return switch (state) {
            case "FIRING" -> 0;
            case "CLEARING" -> 1;
            case "PENDING" -> 2;
            default -> 3;
        };
    }

    /** How many keys are firing now, for the {@code pravaha_alert_keys_firing} gauge. */
    synchronized int firingCount() {
        int firing = 0;
        for (KeyState s : keys.values()) {
            if (s.firing) {
                firing++;
            }
        }
        return firing;
    }

    /** How many keys are owed a notification the channels have not yet accepted. */
    synchronized int owedCount() {
        int owed = 0;
        for (KeyState s : keys.values()) {
            if ((s.firing && !s.notified.equals("FIRED")) || (!s.firing && s.notified.equals("FIRED"))) {
                owed++;
            }
        }
        return owed;
    }

    /** One {@code SHOW ALERTS}-style line per key firing now, for an operator's glance. */
    synchronized List<String> firingKeys() {
        List<String> firing = new ArrayList<>();
        for (KeyState s : keys.values()) {
            if (s.firing) {
                firing.add(describe(s.key));
            }
        }
        return firing;
    }

    // ------------------------------------------------------------------ values

    private RowKey keyOf(Object[] row) {
        int[] ordinals = java.util.Objects.requireNonNull(keyOrdinals, "bound to its view");
        Object[] key = new Object[ordinals.length];
        for (int i = 0; i < key.length; i++) {
            key[i] = row[ordinals[i]];
        }
        return new RowKey(key);
    }

    private Map<String, Object> keyMapOrText(Object[] key) {
        if (schema == null || keyOrdinals == null || keyOrdinals.length != key.length) {
            Map<String, Object> positional = new LinkedHashMap<>();
            for (int i = 0; i < key.length; i++) {
                positional.put("key" + i, key[i]);
            }
            return positional;
        }
        return keyMap(key);
    }

    private Map<String, Object> keyMap(Object[] key) {
        Map<String, Object> named = new LinkedHashMap<>();
        int[] ordinals = java.util.Objects.requireNonNull(keyOrdinals, "checked by keyMapOrText");
        StreamSchema bound = java.util.Objects.requireNonNull(schema, "checked by keyMapOrText");
        for (int i = 0; i < ordinals.length; i++) {
            named.put(bound.field(ordinals[i]).name(), shown(ordinals[i], key[i]));
        }
        return named;
    }

    private Map<String, Object> rowMap(Object @Nullable [] row) {
        if (row == null || schema == null) {
            return Map.of();
        }
        List<String> include = definition.options().include();
        Map<String, Object> named = new LinkedHashMap<>();
        for (int ordinal = 0; ordinal < schema.fieldCount() && ordinal < row.length; ordinal++) {
            String name = schema.field(ordinal).name();
            if (include.isEmpty() || include.stream().anyMatch(name::equalsIgnoreCase)) {
                named.put(name, shown(ordinal, row[ordinal]));
            }
        }
        return named;
    }

    /** A value as a reader expects it: a timestamp as an instant, a date as a date. */
    private @Nullable Object shown(int ordinal, @Nullable Object value) {
        if (value == null || schema == null) {
            return value;
        }
        TypeName type = schema.field(ordinal).type().typeName();
        if (type == TypeName.TIMESTAMP_LTZ && value instanceof Number nanos) {
            return Instant.ofEpochSecond(0, nanos.longValue()).toString();
        }
        if (type == TypeName.DATE && value instanceof Number days) {
            return LocalDate.ofEpochDay(days.longValue()).toString();
        }
        return value;
    }

    /** {@code sku=sku-100, warehouse=LDN}: how a key is written in a message and matched by {@code ack}. */
    String describe(Object[] key) {
        StringBuilder text = new StringBuilder();
        Map<String, Object> named = keyMapOrText(key);
        for (Map.Entry<String, Object> entry : named.entrySet()) {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return text.isEmpty() ? "(the whole answer)" : text.toString();
    }

    static String encodeKey(Object[] key) {
        return encodeRow(key);
    }

    static String encodeRow(Object @Nullable [] row) {
        if (row == null) {
            return "";
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(row.length);
            ViewRowValues.writeRow(out, row);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write an alert's key or row: " + e.getMessage(), e);
        }
        return Base64.getEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }

    static Object @Nullable [] decodeRow(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        byte[] bytes = Base64.getDecoder().decode(text);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return ViewRowValues.readRow(in, in.readInt());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read an alert's key or row: " + e.getMessage(), e);
        }
    }

    /**
     * The same for every attempt to deliver one notification, across retries and restarts: the alert's
     * identity, the key, the episode, the kind, and which reminder.
     */
    static String idempotencyKey(String alertId, String encodedKey, long episode, String kind, int reminder) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String identity = alertId + "|" + encodedKey + "|" + episode + "|" + kind + "|" + reminder;
            return HexFormat.of().formatHex(sha.digest(identity.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String text(@Nullable Instant at) {
        return at == null ? "" : at.toString();
    }

    private static @Nullable Instant instant(String text) {
        return text == null || text.isEmpty() ? null : Instant.parse(text);
    }

    /** One key's state: what is true about it, and what the channels were last told. */
    static final class KeyState {
        final Object[] key;
        Object @Nullable [] row;
        boolean in;

        @Nullable
        Instant inSince;

        @Nullable
        Instant outSince;

        boolean firing;
        long episode;
        long fired;

        @Nullable
        Instant firingSince;

        @Nullable
        Instant clearedAt;

        String notified = "NONE";

        @Nullable
        Instant lastNotifiedAt;

        @Nullable
        Instant lastSentAt;

        int reminders;

        @Nullable
        String acknowledgedBy;

        @Nullable
        Instant acknowledgedAt;

        boolean inFlight;

        @Nullable
        Instant retryAt;

        @Nullable
        String lastError;

        KeyState(Object[] key) {
            this.key = key;
        }
    }

    /** A view key, compared by content -- including a {@code BYTES} column's. */
    @SuppressWarnings("ArrayRecordComponent") // equals and hashCode compare the array's contents
    private record RowKey(Object[] values) {
        @Override
        public boolean equals(Object other) {
            return other instanceof RowKey key && Arrays.deepEquals(values, key.values);
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(values);
        }
    }
}
