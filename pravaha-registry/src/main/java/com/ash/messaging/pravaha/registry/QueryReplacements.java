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
package com.ash.messaging.pravaha.registry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.backfill.BackfillErrors;
import com.ash.messaging.pravaha.backfill.BackfillJob;
import com.ash.messaging.pravaha.backfill.BackfillPlan;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;

/**
 * Blue/green replacement of a registered query, from the start of the backfill to the rollback
 * window closing (ADR-046, design section 16.3).
 *
 * <h2>What a cutover has to be exactly right about</h2>
 *
 * <p><strong>The seam is a position, not a moment.</strong> Two queries running at slightly
 * different speeds cannot be swapped at an instant without either dropping the records in between
 * or emitting them from both. So a cutover happens only when the two versions have consumed
 * <em>exactly</em> the same input: both feeds are paused, each is allowed to settle -- its sources
 * frozen between rows, its lanes drained, its view committed -- and their positions are compared
 * token for token, per partition, for every stream they both read. Unequal is not "close enough":
 * the feeds are resumed and it is tried again, and a cutover that cannot find such a point inside
 * {@link QueryReplacement#ALIGNMENT_BUDGET} is refused with {@code PRV-4014} rather than taken.
 *
 * <p>From that, what readers and subscribers see follows:
 *
 * <ul>
 *   <li>A <strong>reader</strong> of the name reads one view, resolved through the catalogue at the
 *       moment of the read. Before the swap that is the old version's, after it the new version's,
 *       and there is no read that mixes them. Neither is behind the other, because both have
 *       consumed the same input -- so a reader sees the answer to the old question up to the seam
 *       and the answer to the new question after it, with no gap and nothing counted twice.
 *   <li>A <strong>subscriber</strong>, plain or from a snapshot, is delivered every commit the old
 *       version ever made -- the last of them is the commit taken while it was paused at the seam --
 *       and is then ended with {@code PRV-4019}. It is told, rather than quietly handed the new
 *       version's changes: a subscriber applying changes by weight would otherwise hold a copy that
 *       is half one query's answer and half another's, with nothing in the stream to say so. A
 *       snapshot subscription that reconnects gets a fresh snapshot of the new version, which is
 *       exactly what it needs and cannot be expressed as a diff.
 *   <li>A <strong>sink</strong> moves with the name, at a checkpoint boundary. See {@link
 *       #handSinkOver}.
 * </ul>
 *
 * <h2>The rollback</h2>
 *
 * <p>The replaced version keeps running, fed and committing, for {@code rollback.retention}. That
 * is what makes a rollback the same operation as the cutover with the versions the other way round,
 * rather than a second backfill: it is at the same position as the version that replaced it, so the
 * same alignment finds the same kind of seam. Once the window closes -- or once somebody confirms
 * the cutover -- it is released, and from then on the answer to "roll back" is that there is
 * nothing to roll back to.
 */
public final class QueryReplacements implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(QueryReplacements.class.getName());

    /** How often the backfills are looked at: progress, catch-up, auto-cutover, retention. */
    private static final Duration WATCH_EVERY = Duration.ofMillis(200);

    /** How long a source may be inside a poll when its position is read. A checkpoint's own budget. */
    private static final Duration FREEZE_TIMEOUT = Duration.ofSeconds(10);

    private final QueryRegistry registry;
    private final SecurityPolicy policy;
    private final AuditSink audit;

    /** Concurrent so that a drop can ask "is this name being replaced?" without taking a lock. */
    private final Map<String, QueryReplacement> byName = new ConcurrentHashMap<>();

    private ScheduledExecutorService watcher;
    private volatile boolean closed;

    /** Seams only move forward, and two cutovers in the same millisecond must not share one. */
    private long lastSeam;

    QueryReplacements(QueryRegistry registry, SecurityPolicy policy, AuditSink audit) {
        this.registry = registry;
        this.policy = policy == null ? SecurityPolicy.PERMISSIVE : policy;
        this.audit = audit == null ? AuditSink.NONE : audit;
    }

    // ------------------------------------------------------------------ starting

    /**
     * Registers the new version beside the running one and starts its backfill.
     *
     * <p>Nothing about the name changes here. The candidate is a shadow: it runs, it reads, it
     * keeps its own state and its own checkpoints, and no reader can reach it. What it is for is to
     * be <em>comparable</em> with the version that is serving, so that the cutover is a decision
     * about two known positions rather than a hope.
     */
    public synchronized QueryReplacement.Status replace(
            String name, String sql, List<Integer> keyColumns, Principal principal, ReplacementOptions options) {
        return replace(name, sql, keyColumns, principal, options, null);
    }

    /**
     * @param resuming the shadow's checkpoint directory when a restart is starting this replacement
     *     again, so its backfill picks up where it left off rather than beginning again beside the
     *     checkpoints of the one that was interrupted; null for a new replacement
     */
    private synchronized QueryReplacement.Status replace(
            String name,
            String sql,
            List<Integer> keyColumns,
            Principal principal,
            ReplacementOptions options,
            String resuming) {
        ContinuousQueryStatements.requireAdministrable(policy, audit, principal, name, "replace");
        RegisteredQuery serving = registry.require(name);
        QueryReplacement existing = byName.get(name);
        if (existing != null && existing.active()) {
            throw new PravahaException(
                    BackfillErrors.REPLACEMENT_IN_PROGRESS,
                    "'" + name + "' is already being replaced (" + existing.state() + "). One shadow at a time: "
                            + "a second candidate would have to be compared against a version that may never "
                            + "serve. Cut over, roll back or abandon the first.");
        }
        if (serving.names().size() > 1) {
            throw new PravahaException(
                    RegistryErrors.ILLEGAL_TRANSITION,
                    "'" + name + "' shares its computation with " + serving.names()
                            + ", and a replacement moves one name: the subscribers of this computation cannot be "
                            + "told which name they arrived through, so some of them would be following the "
                            + "old version under a name that now answers the new one. Registrations that ask "
                            + "the same question share a computation, so change the others too or drop them "
                            + "first.");
        }
        if (serving.state() != QueryState.RUNNING) {
            throw new PravahaException(
                    RegistryErrors.ILLEGAL_TRANSITION,
                    "'" + name + "' is " + serving.state() + ", and a replacement has to meet the running version "
                            + "at a position it is still reaching. Resume it first.");
        }
        for (String stream : registry.sourceStreamsOf(sql)) {
            registry.feeds().backfillRefusal(stream).ifPresent(why -> {
                throw new PravahaException(
                        BackfillErrors.SOURCE_UNSUPPORTED,
                        "'" + name + "' cannot be replaced with a version reading '" + stream + "': " + why
                                + ". The new version would start from empty state and report itself "
                                + "caught up, which is the failure this whole operation exists to "
                                + "avoid.");
            });
        }

        String sink = registry.sinkOf(name).orElse(null);
        Retention retention = serving.view().retention();
        Instant startedAt = Instant.now();
        String directory =
                resuming != null ? resuming : QueryCheckpoints.shadowDirectoryFor(name, startedAt.toEpochMilli());
        BackfillJob job = new BackfillJob("replace:" + name, options.rateLimit());
        BackfillPlan plan = new BackfillPlan(
                job, splicePositions(serving), options.backfill() == ReplacementOptions.Backfill.HISTORY);

        RegisteredQuery candidate =
                registry.startShadow(name, sql, keyColumns, principal, retention, sink, directory, plan);
        QueryReplacement replacement;
        try {
            RegistryJournal.Pending pending = new RegistryJournal.Pending(
                    name, sql, keyColumns, principal.id(), retention, sink, options.toString(), directory);
            RegistryJournal journal = registry.journal();
            if (journal != null && resuming == null) {
                // A replacement being started again after a restart is already in the journal, and
                // recording it twice would leave a second pending entry for one candidate.
                journal.recordReplacementStarted(pending);
            }
            replacement = new QueryReplacement(
                    name,
                    sql,
                    keyColumns,
                    sink,
                    options,
                    principal.id(),
                    startedAt,
                    directory,
                    job,
                    serving,
                    candidate,
                    registry.journalledEntry(name).orElse(null));
        } catch (RuntimeException e) {
            registry.releaseShadow(candidate);
            throw e;
        }
        byName.put(name, replacement);
        watch();
        LOG.log(
                System.Logger.Level.INFO,
                "replacing '" + name + "': " + replacement.status().candidate() + " backfilling beside "
                        + replacement.status().replacing() + " (" + options + ")");
        return replacement.status();
    }

    /** Where the running version is, per stream, so the candidate knows what to splice onto. */
    private Map<String, List<SourceOffset>> splicePositions(RegisteredQuery serving) {
        Map<String, List<SourceOffset>> positions = new LinkedHashMap<>();
        serving.sourcePositions(FREEZE_TIMEOUT).forEach((stream, tokens) -> {
            List<SourceOffset> offsets = new ArrayList<>(tokens.size());
            tokens.forEach(token -> offsets.add(new SourceOffset(token)));
            positions.put(stream, offsets);
        });
        return positions;
    }

    // ------------------------------------------------------------------ reporting

    public Optional<QueryReplacement.Status> of(String name) {
        QueryReplacement replacement = byName.get(name);
        if (replacement == null) {
            return Optional.empty();
        }
        replacement.observe();
        return Optional.of(replacement.status());
    }

    public List<QueryReplacement.Status> all() {
        List<QueryReplacement.Status> all = new ArrayList<>();
        for (QueryReplacement replacement : byName.values()) {
            replacement.observe();
            all.add(replacement.status());
        }
        return List.copyOf(all);
    }

    public boolean isReplacing(String name) {
        QueryReplacement replacement = byName.get(name);
        return replacement != null && replacement.active();
    }

    // ------------------------------------------------------------------ the controls

    public synchronized QueryReplacement.Status throttle(String name, long rowsPerSecond, Principal principal) {
        QueryReplacement replacement = administrable(name, principal, "throttle-backfill");
        try {
            replacement.job().throttleTo(rowsPerSecond);
        } catch (IllegalArgumentException e) {
            throw new PravahaException(
                    BackfillErrors.SOURCE_UNSUPPORTED,
                    "the backfill of '" + name + "' cannot be run at " + rowsPerSecond + " records a second: "
                            + e.getMessage() + ". The limit this replacement was started with is a ceiling, "
                            + "not a suggestion; start another replacement to raise it.");
        }
        return replacement.status();
    }

    public synchronized QueryReplacement.Status pause(String name, Principal principal) {
        QueryReplacement replacement = administrable(name, principal, "pause-backfill");
        replacement.job().pause();
        return replacement.status();
    }

    public synchronized QueryReplacement.Status resume(String name, Principal principal) {
        QueryReplacement replacement = administrable(name, principal, "resume-backfill");
        replacement.job().resume();
        return replacement.status();
    }

    /** Ends a replacement that has not cut over, releasing the candidate and everything it holds. */
    public synchronized QueryReplacement.Status abandon(String name, Principal principal) {
        QueryReplacement replacement = administrable(name, principal, "abandon-replacement");
        if (replacement.state() == QueryReplacement.State.CUT_OVER) {
            throw new PravahaException(
                    RegistryErrors.ILLEGAL_TRANSITION,
                    "'" + name + "' has already cut over. Roll it back to undo it, or finish it to release the "
                            + "version it replaced.");
        }
        registry.releaseShadow(replacement.candidate());
        endedInTheJournal(name);
        replacement.abandoned();
        return replacement.status();
    }

    /**
     * Confirms a cutover: the replaced version is released and there is no rollback from here.
     *
     * <p>Also what the retention window does on its own. An operator who is watching the new
     * version and is satisfied should not have to wait an hour for the old one's memory back.
     */
    public synchronized QueryReplacement.Status finish(String name, Principal principal) {
        QueryReplacement replacement = administrable(name, principal, "finish-replacement");
        if (replacement.state() != QueryReplacement.State.CUT_OVER) {
            throw new PravahaException(
                    RegistryErrors.ILLEGAL_TRANSITION,
                    "'" + name + "' is " + replacement.state() + " and there is nothing to confirm: only a "
                            + "cutover leaves a version retained.");
        }
        release(replacement);
        replacement.finished();
        return replacement.status();
    }

    private void release(QueryReplacement replacement) {
        replacement.retained().ifPresent(registry::releaseShadow);
    }

    // ------------------------------------------------------------------ the cutover

    public synchronized QueryReplacement.Status cutOver(String name, Principal principal) {
        return cutOver(administrable(name, principal, "cutover"), false);
    }

    /**
     * Moves the name from the version serving it to the version that has caught up.
     *
     * <p>The order below is the whole of the correctness argument, and every step is where it is
     * because of what a crash between it and the next one would leave behind.
     */
    private QueryReplacement.Status cutOver(QueryReplacement replacement, boolean automatic) {
        replacement.observe();
        String name = replacement.name();
        RegisteredQuery from = replacement.serving();
        RegisteredQuery to = replacement.candidate();
        long seam = nextSeam();
        if (replacement.state() != QueryReplacement.State.CAUGHT_UP) {
            // ShadowDeployment's refusal, which is the one that says why: the input between the
            // candidate's position and the seam would be in neither version's output, permanently
            // and invisibly.
            replacement.deployment().cutOver(seam);
        }

        // 1. Both versions are brought to the same position in their input, and held there.
        Map<String, List<String>> at = align(from, to);
        try {
            // 2. The replaced version's last checkpoint is taken here, with its feed stopped: what
            //    its sink has been written is now committed, exactly up to this position, and its
            //    open transaction is empty.
            long label = from.checkpointNow().map(Checkpoint::id).orElse(0L);

            // 3. The sink moves, before anything says the name has.
            SinkDelivery moved = handSinkOver(name, from, to, label);

            // 4. Written down. From here a restart brings the name back as the new version -- and
            //    the checkpoint step 3 took is what tells that version's sink what it already
            //    holds. Before this record, a restart brings back the old version, whose own
            //    checkpoint is intact and whose sink is committed exactly to it.
            RegistryJournal journal = registry.journal();
            if (journal != null) {
                journal.recordCutover(new RegistryJournal.Entry(
                        name,
                        replacement.sql(),
                        replacement.keyColumns(),
                        replacement.owner(),
                        to.view().retention(),
                        List.of(),
                        replacement.sink(),
                        replacement.shadowDirectory()));
            }

            // 5. Every subscriber of the old version has had every commit it ever made, including
            //    the one taken at the seam. Now they are told it has been replaced.
            from.endSubscriptions(new PravahaException(
                    BackfillErrors.VIEW_REPLACED,
                    "'" + name + "' was replaced at a cutover and now answers a different query. Every change "
                            + "the version you were following made has been delivered; subscribe again to "
                            + "follow the new one from a fresh snapshot."));

            // 6. The swap itself: one name, moved between two computations, while neither is
            //    reading. A reader resolves the name to one view or the other and never to both.
            registry.moveName(name, from, to);
            from.replacedBy(to.fingerprint().shortForm());
            if (moved != null) {
                moved.attachTo(to, true);
                registry.putDelivery(name, moved);
            }
            replacement.cutOver(Instant.now());
            replacement.deployment().cutOver(seam);
        } finally {
            // The new version first: it is the one answering the name now. The old one keeps
            // running, and that is what makes the rollback instant rather than another backfill.
            to.feed().resume();
            from.feed().resume();
        }
        audit.record(AuditEvent.of(
                Principal.ANONYMOUS,
                automatic ? "cutover:auto" : "cutover",
                name,
                com.ash.messaging.pravaha.security.AccessDecision.allow(),
                "at " + at));
        LOG.log(
                System.Logger.Level.INFO,
                "'" + name + "' cut over to " + to.fingerprint().shortForm() + " at " + at + "; "
                        + from.fingerprint().shortForm() + " is retained for "
                        + replacement.options().rollbackRetention());
        return replacement.status();
    }

    /**
     * Puts the replaced version back, from the same kind of seam and by the same mechanism.
     *
     * <p>The version being rolled back to has been running all along, so this is not a second
     * backfill: it is one alignment and one swap, and it takes the few milliseconds the cutover
     * took.
     */
    public synchronized QueryReplacement.Status rollBack(String name, Principal principal) {
        QueryReplacement replacement = administrable(name, principal, "rollback");
        if (replacement.state() != QueryReplacement.State.CUT_OVER) {
            throw new PravahaException(
                    RegistryErrors.ILLEGAL_TRANSITION,
                    "'" + name + "' is " + replacement.state()
                            + " and there is nothing to roll back: the version it replaced has "
                            + (replacement.state() == QueryReplacement.State.FINISHED
                                    ? "been released, which is what finishing a replacement means"
                                    : "not been replaced"));
        }
        RegisteredQuery from = replacement.serving();
        RegisteredQuery to = replacement.retained().orElseThrow();
        long seam = nextSeam();
        Map<String, List<String>> at = align(from, to);
        try {
            long label = from.checkpointNow().map(Checkpoint::id).orElse(0L);
            SinkDelivery moved = handSinkOver(name, from, to, label);
            RegistryJournal journal = registry.journal();
            RegistryJournal.Entry previous = replacement.previous();
            if (journal != null) {
                journal.recordCutover(
                        previous != null
                                ? previous
                                : new RegistryJournal.Entry(
                                        name,
                                        to.sql(),
                                        to.view().keyOrdinals(),
                                        replacement.owner(),
                                        to.view().retention(),
                                        List.of(),
                                        replacement.sink(),
                                        QueryCheckpoints.directoryFor(name)));
            }
            from.endSubscriptions(new PravahaException(
                    BackfillErrors.VIEW_REPLACED,
                    "the replacement of '" + name + "' was rolled back, so the name answers the previous query "
                            + "again. Subscribe again to follow it."));
            registry.moveName(name, from, to);
            to.replacedBy(null);
            from.replacedBy(to.fingerprint().shortForm());
            if (moved != null) {
                moved.attachTo(to, true);
                registry.putDelivery(name, moved);
            }
            replacement.deployment().rollBack(seam);
            replacement.rolledBack();
        } finally {
            to.feed().resume();
            from.feed().resume();
        }
        // The version that was rolled back is released now rather than retained: nothing reads it,
        // and a rollback is a judgement that it should not have been serving.
        registry.releaseShadow(replacement.candidate());
        endedInTheJournal(name);
        audit.record(AuditEvent.of(
                principal, "rollback", name, com.ash.messaging.pravaha.security.AccessDecision.allow(), "at " + at));
        return replacement.status();
    }

    /**
     * Moves the name's sink from one version to the other, at a checkpoint boundary.
     *
     * <p><strong>Why this is exactly-once and not nearly.</strong> The sink follows the name, not
     * the computation: after the cutover the table it writes is the new version's answer. The
     * handover happens in four ordered steps, with both feeds stopped at the seam:
     *
     * <ol>
     *   <li>The replaced version takes a checkpoint (the caller's step 2). At its cut every row it
     *       has committed is written and prepared, and when the checkpoint is durable that
     *       transaction is committed. The sink now holds exactly the replaced version's view at the
     *       seam, and its open transaction is empty.
     *   <li>The new version is told to number its checkpoints, and so its sink's transactions,
     *       above that checkpoint's id. Transaction labels only increase -- across restarts and
     *       across a change of computation -- or a sink's own store would see a label it has
     *       already committed.
     *   <li>What the sink holds -- the replaced version's view, as a snapshot -- is recorded on the
     *       new version as a carried section, exactly as a restored checkpoint records it, and a
     *       checkpoint of the new version is taken so that a restart finds it. The delivery that
     *       then attaches is owed the <em>difference</em> between that view and the new version's,
     *       and writes it as one batch: the rows the old answer had and the new one does not are
     *       retracted, the rows that changed are rewritten. Nothing is written twice and nothing is
     *       left behind.
     *   <li>Only then does the journal say the name is the new version's (the caller's step 4). A
     *       crash before it leaves the old version serving, its checkpoint intact, and its sink
     *       committed to exactly that checkpoint -- the restore commits the handles it recorded and
     *       abandons everything after, which is the diff transaction. A crash after it leaves the
     *       new version serving with the carried section in its checkpoint, so its sink is again
     *       owed exactly the difference.
     * </ol>
     *
     * @return the delivery to attach to {@code to} after the swap, or null when the name has no sink
     */
    private SinkDelivery handSinkOver(String name, RegisteredQuery from, RegisteredQuery to, long label) {
        SinkDelivery delivery = registry.takeDelivery(name);
        if (delivery == null) {
            return null;
        }
        long continueAfter = Math.max(label, delivery.label());
        byte[] held = from.view().snapshot();
        to.continueLabelsAfter(continueAfter);
        to.carrySinkOver(name, SinkDelivery.Restored.carrying(continueAfter, held));
        to.checkpointNow();
        // Closed, not committed: everything it was written is committed by the checkpoint above,
        // and its empty open transaction is abandoned by the new delivery's recovery.
        delivery.close();
        return registry.newDelivery(name, delivery.sinkName(), to.outputSchema());
    }

    // ------------------------------------------------------------------ alignment

    /**
     * Stops both versions at the same position in their input, and leaves them stopped.
     *
     * <p>Equal positions, token for token, for every stream both read. Not "close enough" and not
     * "within a second": a token is a record, and two versions at different tokens have consumed
     * different input, which is precisely the gap or the overlap a cutover must not create.
     *
     * @return the positions they met at, for the audit record
     * @throws PravahaException {@code PRV-4014} when they cannot be brought together inside the
     *     budget, with both positions in the message
     */
    private Map<String, List<String>> align(RegisteredQuery a, RegisteredQuery b) {
        long deadline = System.nanoTime() + QueryReplacement.ALIGNMENT_BUDGET.toNanos();
        a.feed().pause();
        b.feed().pause();
        Map<String, List<String>> first = Map.of();
        Map<String, List<String>> second = Map.of();
        while (true) {
            first = settle(a);
            second = settle(b);
            if (aligned(first, second)) {
                return first;
            }
            if (System.nanoTime() > deadline) {
                a.feed().resume();
                b.feed().resume();
                throw new PravahaException(
                        BackfillErrors.NOT_CAUGHT_UP,
                        "the two versions of '" + a.name() + "' could not be brought to the same position in "
                                + QueryReplacement.ALIGNMENT_BUDGET + ": one is at " + first + " and the other "
                                + "at " + second + ". A cutover at different positions would leave the input "
                                + "between them in neither version's output, or in both. Nothing has changed; "
                                + "try again, or pause the source.");
            }
            // Let them both run on: a source that delivered a record to one of them between the two
            // pauses will deliver it to the other, and the next attempt finds them level.
            a.feed().resume();
            b.feed().resume();
            sleep(Duration.ofMillis(25));
            a.feed().pause();
            b.feed().pause();
        }
    }

    /**
     * Waits for a paused version to stop moving, then drains and commits it.
     *
     * <p>A pause takes effect between polls, so the poll already running when it was set still
     * delivers -- and reading a position once would read it before that poll finished. Two equal
     * readings in a row mean the feed has stopped; the lanes are then drained and the view
     * committed, so what this version has consumed is also what it has published.
     */
    private Map<String, List<String>> settle(RegisteredQuery query) {
        Map<String, List<String>> positions = query.sourcePositions(FREEZE_TIMEOUT);
        for (int attempt = 0; attempt < 20; attempt++) {
            sleep(Duration.ofMillis(2));
            Map<String, List<String>> again = query.sourcePositions(FREEZE_TIMEOUT);
            if (again.equals(positions)) {
                query.awaitApplied(Duration.ofSeconds(10));
                query.commit();
                return again;
            }
            positions = again;
        }
        return positions;
    }

    /**
     * True when every stream both versions read is at the same position in both.
     *
     * <p>Streams only one of them reads are not compared, because there is nothing to compare them
     * with: what makes the candidate ready on those is that its own history is behind it, which is
     * checked before a cutover is attempted at all.
     */
    private static boolean aligned(Map<String, List<String>> left, Map<String, List<String>> right) {
        for (Map.Entry<String, List<String>> stream : left.entrySet()) {
            List<String> other = right.get(stream.getKey());
            if (other != null && !other.equals(stream.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while cutting over", e);
        }
    }

    private synchronized long nextSeam() {
        lastSeam = Math.max(lastSeam + 1, System.currentTimeMillis());
        return lastSeam;
    }

    // ------------------------------------------------------------------ the watcher

    /**
     * Looks at every replacement a few times a second: progress, catch-up, an automatic cutover, a
     * rollback window that has closed, and a candidate that has failed.
     *
     * <p>One thread for the node, started when the first replacement does and never while none is
     * running -- a registry that never replaces anything starts no thread at all.
     */
    private void watch() {
        if (watcher == null) {
            watcher = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "pravaha-replacements");
                thread.setDaemon(true);
                return thread;
            });
            watcher.scheduleWithFixedDelay(
                    this::tick, WATCH_EVERY.toMillis(), WATCH_EVERY.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void tick() {
        if (closed) {
            return;
        }
        for (QueryReplacement replacement : byName.values()) {
            try {
                replacement.observe();
                if (replacement.state() == QueryReplacement.State.CAUGHT_UP
                        && replacement.options().cutover() == ReplacementOptions.Cutover.AUTO) {
                    synchronized (this) {
                        if (replacement.state() == QueryReplacement.State.CAUGHT_UP) {
                            cutOver(replacement, true);
                        }
                    }
                }
                if (replacement.rollbackWindowClosed(Instant.now())) {
                    synchronized (this) {
                        if (replacement.rollbackWindowClosed(Instant.now())) {
                            LOG.log(
                                    System.Logger.Level.INFO,
                                    "the rollback window for '" + replacement.name() + "' has closed; releasing "
                                            + "the version it replaced");
                            release(replacement);
                            replacement.finished();
                        }
                    }
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "replacement of '" + replacement.name() + "': " + e, e);
            }
        }
    }

    // ------------------------------------------------------------------ recovery and shutdown

    /**
     * Starts again every replacement the journal says was in flight (ADR-046).
     *
     * <p>A backfill can run for hours and a node can restart during one, so a replacement survives
     * a restart rather than being lost: the name comes back as the version that was serving it --
     * the journal never said otherwise -- and the candidate is started again, resuming its backfill
     * from its own checkpoint where there is one and from the beginning where there is not. Both
     * are correct, because the seam it is heading for is written into the positions it checkpoints.
     *
     * <p>Authorization is checked again, as it is for a registration: a replacement is not a
     * standing permission.
     */
    List<QueryRegistry.Recovery.Refusal> recover(
            List<RegistryJournal.Pending> pending,
            java.util.function.Function<String, Optional<Principal>> principals) {
        List<QueryRegistry.Recovery.Refusal> refused = new ArrayList<>();
        for (RegistryJournal.Pending each : pending) {
            Optional<Principal> owner = principals.apply(each.owner());
            if (owner.isEmpty()) {
                refused.add(new QueryRegistry.Recovery.Refusal(
                        each.name() + " (replacement)",
                        Optional.of(RegistryErrors.REPLAY_UNAUTHORIZED),
                        "its owner '" + each.owner() + "' is not a principal this deployment knows, so there is "
                                + "nobody to authorize the replacement as"));
                endedInTheJournal(each.name());
                continue;
            }
            try {
                replace(
                        each.name(),
                        each.sql(),
                        each.keyColumns(),
                        owner.get(),
                        ReplacementOptions.parse(each.options()),
                        each.checkpointDirectory());
            } catch (RuntimeException failure) {
                refused.add(new QueryRegistry.Recovery.Refusal(
                        each.name() + " (replacement)",
                        failure instanceof PravahaException coded ? Optional.of(coded.errorCode()) : Optional.empty(),
                        failure.getMessage()));
                endedInTheJournal(each.name());
            }
        }
        return refused;
    }

    private void endedInTheJournal(String name) {
        RegistryJournal journal = registry.journal();
        if (journal != null) {
            journal.recordReplacementEnded(name);
        }
    }

    private QueryReplacement administrable(String name, Principal principal, String verb) {
        ContinuousQueryStatements.requireAdministrable(policy, audit, principal, name, verb);
        QueryReplacement replacement = byName.get(name);
        if (replacement == null) {
            throw new PravahaException(
                    BackfillErrors.NO_REPLACEMENT,
                    "'" + name + "' is not being replaced. Start one with CREATE OR REPLACE CONTINUOUS QUERY, "
                            + "the replace action, or `pravaha replace`.");
        }
        return replacement;
    }

    /** Everything this holds that a closing registry would otherwise leave running. */
    @Override
    public void close() {
        closed = true;
        ScheduledExecutorService running = watcher;
        watcher = null;
        if (running != null) {
            running.shutdownNow();
        }
        for (QueryReplacement replacement : byName.values()) {
            if (replacement.state() == QueryReplacement.State.BACKFILLING
                    || replacement.state() == QueryReplacement.State.CAUGHT_UP) {
                registry.releaseShadow(replacement.candidate());
            }
            replacement.retained().ifPresent(registry::releaseShadow);
        }
        byName.clear();
    }
}
