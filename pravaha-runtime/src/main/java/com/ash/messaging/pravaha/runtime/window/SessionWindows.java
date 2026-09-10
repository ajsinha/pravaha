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
package com.ash.messaging.pravaha.runtime.window;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Sessions: windows whose boundaries the data decides.
 *
 * <p>A session is a run of activity for one key with no gap longer than {@code gap}. Nothing about
 * it is known in advance -- not when it starts, not when it ends, not how many there will be -- so
 * none of the slicing arithmetic applies and the state is a set of intervals per key rather than a
 * grid.
 *
 * <p><strong>Merging is the whole problem.</strong> Records arrive out of order, so a record landing
 * between two existing sessions does not join one of them: it <em>joins them to each other</em>. A
 * user idle for 25 minutes either side of a single click, with a 30-minute gap, has one session
 * spanning the lot -- and the implementation only discovers that when the click arrives, possibly
 * long after both neighbours were created. An implementation that assigns a record to the nearest
 * session and moves on produces two sessions where there is one, with no error and no way to notice.
 *
 * <p>Intervals are kept in a {@link TreeMap} per key, ordered by start, so a merge inspects only the
 * neighbours on either side rather than the whole set. A key with a thousand sessions costs a
 * logarithmic lookup and a constant number of comparisons per record.
 *
 * <p>Half-open like every other window here: {@code [start, end)}. Two sessions that merely touch --
 * one ending exactly where the next begins -- are merged, because the gap between them is zero and
 * zero is not more than the gap.
 */
public final class SessionWindows {

    /** One session for one key. */
    public record Session(long key, long startNanos, long endNanos) {
        public long durationNanos() {
            return endNanos - startNanos;
        }
    }

    private final long gapNanos;
    private final Map<Long, NavigableMap<Long, Long>> byKey = new java.util.HashMap<>();
    private long merges;

    public SessionWindows(long gapNanos) {
        if (gapNanos <= 0) {
            throw new IllegalArgumentException("session gap must be positive, got " + gapNanos);
        }
        this.gapNanos = gapNanos;
    }

    /**
     * Records an event, creating, extending or merging sessions as needed.
     *
     * @return the session the record now belongs to, after any merging
     */
    public Session record(long key, long eventTimeNanos) {
        NavigableMap<Long, Long> sessions = byKey.computeIfAbsent(key, k -> new TreeMap<>());

        long start = eventTimeNanos;
        long end = eventTimeNanos + gapNanos;

        // Absorb the session before this one if it reaches the record, then the session after it if
        // the extended interval reaches that. Two merges at most, and the second is what handles a
        // record bridging two sessions into one.
        //
        // At most one on each side, and that is provable rather than hopeful. Existing sessions are
        // maximal -- any two that could merge already have -- so consecutive starts are separated by
        // at least one session's length, which is at least the gap. A new record reaches exactly one
        // gap forward, so at most one existing start can fall inside its reach; and once that one is
        // absorbed, the following session begins after the absorbed session's end, out of range.
        //
        // This was a loop until a seeded "merge only once" bug passed every test, which is what a
        // loop that can only run once does: it looks like generality and hides the fact that nothing
        // exercises the second iteration, because nothing can.
        Map.Entry<Long, Long> before = sessions.floorEntry(start);
        if (before != null && before.getValue() >= start) {
            start = before.getKey();
            end = Math.max(end, before.getValue());
            sessions.remove(before.getKey());
            merges++;
        }
        Map.Entry<Long, Long> after = sessions.ceilingEntry(start);
        if (after != null && after.getKey() <= end) {
            end = Math.max(end, after.getValue());
            sessions.remove(after.getKey());
            merges++;
        }

        sessions.put(start, end);
        return new Session(key, start, end);
    }

    /**
     * Sessions that have closed at this watermark, removing them.
     *
     * <p>A session closes when the watermark passes its end, which is {@code lastEvent + gap} -- so
     * closing is exactly the statement "no further record can extend this one". Removing on fire is
     * what bounds the state: a key's memory is proportional to its open sessions, not to its
     * history.
     */
    public List<Session> closedBy(long watermarkNanos) {
        List<Session> closed = new ArrayList<>();
        for (Map.Entry<Long, NavigableMap<Long, Long>> entry : byKey.entrySet()) {
            NavigableMap<Long, Long> sessions = entry.getValue();
            List<Long> starts = new ArrayList<>();
            for (Map.Entry<Long, Long> session : sessions.entrySet()) {
                if (session.getValue() <= watermarkNanos) {
                    closed.add(new Session(entry.getKey(), session.getKey(), session.getValue()));
                    starts.add(session.getKey());
                } else {
                    // Ordered by start, and a later session cannot end earlier than one it follows
                    // once merging has run -- so the first session that is still open ends the scan
                    // for this key.
                    break;
                }
            }
            starts.forEach(sessions::remove);
        }
        byKey.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        closed.sort((a, b) -> a.endNanos() != b.endNanos()
                ? Long.compare(a.endNanos(), b.endNanos())
                : Long.compare(a.key(), b.key()));
        return closed;
    }

    /** Open sessions for a key, in time order. */
    public List<Session> sessionsOf(long key) {
        NavigableMap<Long, Long> sessions = byKey.get(key);
        if (sessions == null) {
            return List.of();
        }
        List<Session> result = new ArrayList<>();
        sessions.forEach((start, end) -> result.add(new Session(key, start, end)));
        return result;
    }

    /** Total open sessions across every key. What bounded-state enforcement watches. */
    public int openSessions() {
        return byKey.values().stream().mapToInt(Map::size).sum();
    }

    public int keyCount() {
        return byKey.size();
    }

    /** How many times two sessions became one. High against record count means a gap set too wide. */
    public long merges() {
        return merges;
    }

    public long gapNanos() {
        return gapNanos;
    }
}
