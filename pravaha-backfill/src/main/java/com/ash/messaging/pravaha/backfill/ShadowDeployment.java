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
package com.ash.messaging.pravaha.backfill;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Changing a running query's SQL without stopping it (design section 16.3).
 *
 * <p>The incumbents' answer is a stop-the-world restart: drain, redeploy, backfill, hope. Pravaha's
 * is a shadow deployment, which is only possible because backfill is a routine checkpointed
 * capability rather than a special mode. The new version runs alongside the old, catches up, and
 * takes over at a point both of them agree on.
 *
 * <p><strong>The seam is a frontier, not a moment.</strong> Wall-clock cutover is what makes this
 * hard for everybody else: two queries running at slightly different speeds cannot be swapped at an
 * instant without either dropping the records in between or emitting them from both. A frontier --
 * how far through the input a version has got -- is a point both versions can be compared at
 * exactly. Every input record belongs to exactly one version's output, chosen by which side of the
 * seam its frontier falls on, and that is what makes the changeover lossless and duplicate-free
 * whatever the two versions' relative speeds.
 *
 * <p>Rollback is the same mechanism with the versions the other way round, which is the point of
 * expressing cutover as a list of segments rather than as a flag. A flag has to be un-set and
 * anything already emitted under it reasoned about; a segment list simply gains another entry, and
 * the record of what was served by what stays intact and auditable.
 *
 * <p>What this class does not do is run the queries or hold their state -- it decides which
 * version's output is the truth for a given input position. That is deliberately the whole of it:
 * the decision is the part that has to be exactly right, and it is testable without starting
 * anything.
 */
public final class ShadowDeployment {

    /** Where a deployment is in its life. */
    public enum State {
        /** The candidate is loading history and is behind the active version. */
        BACKFILLING,

        /** The candidate has reached the active version's frontier and may take over. */
        CAUGHT_UP,

        /** The candidate is serving; the previous version is retained for rollback. */
        CUT_OVER,

        /** A cutover was undone. The previous version is serving again. */
        ROLLED_BACK
    }

    /**
     * From this input position onward, this version's output is the one that counts.
     *
     * @param fromFrontier the input position the version took over at, or {@link Long#MIN_VALUE}
     *     for the first version, which has owned the output from the beginning
     * @param version the version's identifier (a fingerprint, where a registry supplies one)
     */
    public record Segment(long fromFrontier, String version) {

        /** Whether this is the first version, which took over at no position at all. */
        public boolean fromTheBeginning() {
            return fromFrontier == Long.MIN_VALUE;
        }

        /** The segment as one sentence: {@code "from 4471: <version>"}. */
        public String sentence() {
            return (fromTheBeginning() ? "from the beginning" : "from " + fromFrontier) + ": " + version;
        }
    }

    private final String initialVersion;
    private final List<Segment> segments = new ArrayList<>();

    private @Nullable String candidate;
    private long activeFrontier;
    private long candidateFrontier;
    private State state = State.BACKFILLING;
    private long cutovers;
    private long rollbacks;

    public ShadowDeployment(String initialVersion) {
        if (initialVersion == null || initialVersion.isBlank()) {
            throw new IllegalArgumentException("a deployment needs a version identifier");
        }
        this.initialVersion = initialVersion;
        // From the beginning of everything, the first version owns the output.
        this.segments.add(new Segment(Long.MIN_VALUE, initialVersion));
    }

    /** Registers the version being prepared. Only one at a time: two shadows is a decision tree. */
    public void startShadow(String version) {
        if (version.equals(currentVersion())) {
            throw new IllegalArgumentException("'" + version
                    + "' is already serving; a shadow deployment of the running version would " + "cut over to itself");
        }
        this.candidate = version;
        this.candidateFrontier = Long.MIN_VALUE;
        this.state = State.BACKFILLING;
    }

    /**
     * Reports how far each version has processed.
     *
     * <p>The candidate is caught up when it reaches the active version's frontier -- not when it has
     * read some number of rows, and not after some duration. Both of those are proxies that are
     * wrong exactly when the input rate changes, which is when somebody is most likely to be
     * deploying.
     */
    public State observeFrontiers(long active, long shadow) {
        this.activeFrontier = active;
        this.candidateFrontier = shadow;
        if (candidate != null && state == State.BACKFILLING && shadow >= active) {
            state = State.CAUGHT_UP;
        }
        return state;
    }

    /**
     * Hands the output over at {@code frontier}.
     *
     * <p>Refused unless the candidate has caught up. Cutting over early is not a smaller version of
     * the same operation -- it is a gap: input between the candidate's frontier and the seam is
     * reflected in neither version's output, permanently, and nothing downstream can tell.
     */
    public void cutOver(long frontier) {
        if (candidate == null) {
            throw new IllegalStateException("no shadow deployment is running");
        }
        if (state != State.CAUGHT_UP) {
            throw new PravahaException(
                    BackfillErrors.NOT_CAUGHT_UP,
                    "'" + candidate + "' is at frontier " + candidateFrontier + " and '" + currentVersion()
                            + "' is at " + activeFrontier + ". Cutting over now would leave the input between "
                            + "them in neither version's output, permanently and invisibly.");
        }
        requireForward(frontier);
        segments.add(new Segment(frontier, candidate));
        state = State.CUT_OVER;
        cutovers++;
        candidate = null;
    }

    /**
     * Puts the previous version back, from {@code frontier} onward.
     *
     * <p>The same operation as cutover with the versions reversed, which is why it takes the same
     * few milliseconds and needs no separate machinery. The previous version has to still be running
     * -- that is what the retention window is for -- and this class does not check that, because it
     * does not own the processes; a caller that has already released the old version is rolling back
     * to nothing and will find out immediately rather than subtly.
     */
    public void rollBack(long frontier) {
        if (segments.size() < 2) {
            throw new IllegalStateException("nothing to roll back to: only one version has ever served");
        }
        requireForward(frontier);
        String previous = segments.get(segments.size() - 2).version();
        segments.add(new Segment(frontier, previous));
        state = State.ROLLED_BACK;
        rollbacks++;
    }

    private void requireForward(long frontier) {
        long last = segments.get(segments.size() - 1).fromFrontier();
        if (last != Long.MIN_VALUE && frontier <= last) {
            throw new PravahaException(
                    BackfillErrors.SEAM_WENT_BACKWARDS,
                    "a seam at " + frontier + " is at or before the previous one at " + last
                            + ". Seams only move forward: an overlapping one would make two versions both "
                            + "responsible for the same input, and both would emit it.");
        }
    }

    /**
     * Whether a version's output for an input at {@code frontier} should be published.
     *
     * <p>The whole contract in one method. Exactly one version owns any given frontier, so a record
     * is published once however many versions are running, and a version that is behind or ahead of
     * its segment is simply ignored rather than coordinated with.
     */
    public boolean isAuthoritative(String version, long frontier) {
        return ownerAt(frontier).equals(version);
    }

    /** Which version's output counts for an input at this position. */
    public String ownerAt(long frontier) {
        String owner = initialVersion;
        for (Segment segment : segments) {
            if (frontier >= segment.fromFrontier()) {
                owner = segment.version();
            } else {
                break;
            }
        }
        return owner;
    }

    /** The version serving now. */
    public String currentVersion() {
        return segments.get(segments.size() - 1).version();
    }

    /** The version being prepared, or null. */
    public @Nullable String shadowVersion() {
        return candidate;
    }

    public State state() {
        return state;
    }

    /** How far the candidate still has to go. Zero or less means it is ready. */
    public long backfillRemaining() {
        return candidate == null ? 0 : Math.max(0, activeFrontier - candidateFrontier);
    }

    public long cutovers() {
        return cutovers;
    }

    public long rollbacks() {
        return rollbacks;
    }

    /** Who served what, oldest first. The audit trail a cutover has to leave behind. */
    public List<Segment> segments() {
        return List.copyOf(segments);
    }

    /** {@link #segments()}, each as its {@link Segment#sentence() sentence}. */
    public List<String> history() {
        return segments.stream().map(Segment::sentence).toList();
    }

    @Override
    public String toString() {
        return "ShadowDeployment[serving=" + currentVersion() + ", shadow=" + candidate + ", " + state + "]";
    }
}
