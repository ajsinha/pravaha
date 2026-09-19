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
package com.ash.messaging.pravaha.spring;

import java.util.List;
import java.util.Objects;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.ash.messaging.pravaha.embedded.RowChange;

/**
 * What happens when a {@link PravahaListener} method throws.
 *
 * <p>The listener's worker calls this with the failure -- the query, the listener, the change or
 * commit it was handed, and what it threw -- and does what the returned {@link Decision} says:
 * {@link Decision#CONTINUE} goes on to the next change, {@link Decision#STOP} detaches the listener
 * from its query and delivers nothing more to it. A change is never redelivered: the view has
 * already moved on, and handing the same change to a method that just refused it is how one bad row
 * becomes a loop.
 *
 * <p><strong>Never silent.</strong> Whatever a handler decides, the listener's container counts the
 * failure and keeps the last one, and the actuator endpoint reports both. A handler that itself
 * throws, or returns {@code null}, stops the listener and is logged at error: a handler that cannot
 * say what to do is not a reason to go on delivering to a method that is failing.
 *
 * <p>The auto-configured handler logs at error with the query and the change, and continues --
 * {@code pravaha.listener.on-error=stop} makes it stop instead. Declare a bean of this type to
 * replace it for every listener, or name one in {@link PravahaListener#errorHandler()} for one.
 */
@FunctionalInterface
public interface PravahaListenerErrorHandler {

    /** Decides what a listener does after its method has thrown. Called on the listener's thread. */
    Decision handle(Failure failure);

    /** What the listener does next. */
    enum Decision {
        /** Deliver the next change. The one that failed is not delivered again. */
        CONTINUE,
        /** Detach from the query and deliver nothing more. */
        STOP
    }

    /**
     * One failed call.
     *
     * @param listener the bean and method, as {@code bean.method}
     * @param queryName the query the listener follows
     * @param changes what the call was handed: one change, or a whole commit for a {@code
     *     List<RowChange>} listener
     * @param exception what the method threw
     */
    record Failure(String listener, String queryName, List<RowChange> changes, Throwable exception) {

        public Failure {
            Objects.requireNonNull(listener, "listener");
            Objects.requireNonNull(queryName, "queryName");
            changes = List.copyOf(changes);
            Objects.requireNonNull(exception, "exception");
        }

        /** The changes, as a log line can carry them: the first ten, and how many more. */
        public String describeChanges() {
            int shown = Math.min(changes.size(), 10);
            String head = changes.subList(0, shown).toString();
            return changes.size() > shown ? head + " and " + (changes.size() - shown) + " more" : head;
        }
    }

    /** Logs the failure at error, with the query and the change, and goes on to the next change. */
    static PravahaListenerErrorHandler logAndContinue() {
        return failure -> Logging.log(failure, Decision.CONTINUE);
    }

    /** Logs the failure at error, with the query and the change, and stops the listener. */
    static PravahaListenerErrorHandler logAndStop() {
        return failure -> Logging.log(failure, Decision.STOP);
    }

    /** The one place the two logging handlers write from, so both say the same things. */
    final class Logging {

        private static final Log LOG = LogFactory.getLog(PravahaListenerErrorHandler.class);

        private Logging() {}

        static Decision log(Failure failure, Decision decision) {
            LOG.error(
                    "@PravahaListener " + failure.listener() + " on query '" + failure.queryName() + "' threw on "
                            + failure.describeChanges() + "; "
                            + (decision == Decision.CONTINUE
                                    ? "the change is not redelivered and the listener goes on to the next one"
                                    : "the listener is stopped and receives nothing more (pravaha.listener.on-error=stop)"),
                    failure.exception());
            return decision;
        }
    }
}
