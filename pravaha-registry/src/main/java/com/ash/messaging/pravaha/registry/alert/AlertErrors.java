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

import com.ash.messaging.pravaha.api.ErrorCode;

/**
 * Alerts' codes (ADR-057), in the registry's range: an alert is a registration that follows a view.
 * Refusals of a caller who may not do something are {@code PRV-7002}; dropping a view an alert follows
 * is {@code PRV-8024}, as for a query that reads it.
 */
public final class AlertErrors {

    /** No alert by that name that the caller may see; the same answer for both, so it confirms nothing. */
    public static final ErrorCode NO_SUCH_ALERT = new ErrorCode(8040, "ALERT_NO_SUCH_ALERT");

    /** {@code CREATE ALERT} of a name an alert, a query or a catalogue object already has. */
    public static final ErrorCode ALERT_EXISTS = new ErrorCode(8041, "ALERT_EXISTS");

    /**
     * A definition that cannot be kept: an unknown column or option, a literal of the wrong type, a bad
     * duration or severity, a view that does not exist or cannot be followed.
     */
    public static final ErrorCode DEFINITION_INVALID = new ErrorCode(8042, "ALERT_DEFINITION_INVALID");

    /** {@code NOTIFY} names a channel no {@code pravaha.notifiers.<name>} binding defines. */
    public static final ErrorCode NO_SUCH_CHANNEL = new ErrorCode(8043, "ALERT_NO_SUCH_CHANNEL");

    /** The alert journal cannot be read or written; the change is refused rather than lost. */
    public static final ErrorCode JOURNAL_FAILED = new ErrorCode(8044, "ALERT_JOURNAL_FAILED");

    /**
     * A notification a channel did not accept after its retries. Recorded on the alert, never thrown to
     * a caller: the engine keeps delivering it, at a slower pace, until it is accepted.
     */
    public static final ErrorCode DELIVERY_FAILED = new ErrorCode(8045, "ALERT_DELIVERY_FAILED");

    /**
     * A notifier binding the node cannot start with: no plugin of that name, a missing or unreadable
     * secret, a secret written into the configuration, a bad URL or setting.
     */
    public static final ErrorCode NOTIFIER_MISCONFIGURED = new ErrorCode(8046, "ALERT_NOTIFIER_MISCONFIGURED");

    /** An alert statement where no alert service runs: an embedded engine, or a surface without one. */
    public static final ErrorCode NOT_SERVED = new ErrorCode(8047, "ALERT_NOT_SERVED");

    private AlertErrors() {}
}
