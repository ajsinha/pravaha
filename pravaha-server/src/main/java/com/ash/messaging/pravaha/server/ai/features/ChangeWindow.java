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
package com.ash.messaging.pravaha.server.ai.features;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.serving.AnswerListener;
import com.ash.messaging.pravaha.serving.ServedView;

/**
 * A view's most recent committed changes, kept so "why did this view change?" has something to be grounded
 * in. Nothing is kept until someone asks about a view: the first question starts following its answer (as an
 * alert does, {@link AnswerListener}) and says so; later questions are answered from the commits since. At
 * most {@code views} views are followed and {@code commits} commits kept per view, the view asked about
 * longest ago let go first, so memory is bounded whatever is asked. The rows are the engine's; each reader's
 * row filter and masks are applied when they ask ({@link AiExplains}), never here.
 */
final class ChangeWindow implements AutoCloseable {

    /**
     * One commit's change to a view's answer.
     *
     * @param at when it was seen
     * @param left how many rows left the answer
     * @param entered how many rows entered it
     * @param leaving the first {@link #ROWS} of the rows that left
     * @param entering the first {@link #ROWS} of the rows that entered
     */
    record Commit(Instant at, int left, int entered, List<Object[]> leaving, List<Object[]> entering) {}

    /** The most rows of each side of one commit kept; the counts are always whole. */
    static final int ROWS = 200;

    /**
     * What is kept of one view.
     *
     * @param since when following started
     * @param commits the commits since, oldest first
     */
    record Kept(Instant since, List<Commit> commits) {}

    private final int views;
    private final int commits;
    private final Clock clock;
    private final LinkedHashMap<String, Recorder> recorders = new LinkedHashMap<>(16, 0.75f, true);

    ChangeWindow(int views, int commits, Clock clock) {
        this.views = Math.max(1, views);
        this.commits = Math.max(1, commits);
        this.clock = clock;
    }

    /**
     * What is kept of {@code engineName}, following it from now if nothing is: then the answer has no commits
     * and says when it started.
     */
    synchronized Kept kept(String engineName, ServedView view) {
        Recorder r = recorders.get(engineName);
        if (r == null || r.view != view) {
            if (r != null) {
                r.view.unfollowAnswer(r); // the name now serves another view: a replacement
            }
            r = new Recorder(view, clock.instant(), commits, clock);
            recorders.put(engineName, r);
            view.followAnswer(r);
            trim();
        }
        return r.kept();
    }

    private void trim() {
        Iterator<Map.Entry<String, Recorder>> it = recorders.entrySet().iterator();
        while (recorders.size() > views && it.hasNext()) {
            Recorder oldest = it.next().getValue();
            oldest.view.unfollowAnswer(oldest);
            it.remove();
        }
    }

    @Override
    public synchronized void close() {
        recorders.values().forEach(r -> r.view.unfollowAnswer(r));
        recorders.clear();
    }

    /** Follows one view's answer, keeping its last commits. */
    private static final class Recorder implements AnswerListener {

        final ServedView view;
        private final Instant since;
        private final int max;
        private final Clock clock;
        private final Deque<Commit> kept = new ArrayDeque<>();

        Recorder(ServedView view, Instant since, int max, Clock clock) {
            this.view = view;
            this.since = since;
            this.max = max;
            this.clock = clock;
        }

        @Override
        public void onSnapshot(List<Object[]> rows, long frontier) {
            // Where the answer stood when following began, or after a restore: not a change.
        }

        @Override
        public synchronized void onAnswer(List<Object[]> leaving, List<Object[]> entering, long frontier) {
            if (leaving.isEmpty() && entering.isEmpty()) {
                return;
            }
            kept.addLast(new Commit(clock.instant(), leaving.size(), entering.size(), copy(leaving), copy(entering)));
            while (kept.size() > max) {
                kept.removeFirst();
            }
        }

        synchronized Kept kept() {
            return new Kept(since, List.copyOf(kept));
        }

        private static List<Object[]> copy(List<Object[]> rows) {
            List<Object[]> out = new ArrayList<>(Math.min(rows.size(), ROWS));
            for (int i = 0; i < rows.size() && i < ROWS; i++) {
                out.add(rows.get(i).clone());
            }
            return out;
        }
    }
}
