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

import com.ash.messaging.pravaha.api.ErrorCode;

/**
 * Registry error codes, PRV-8nnn: the lifecycle of a registered query.
 *
 * <p>8000 and not 5000, which is where these started. 5xxx belongs to plugins, and
 * {@code REGISTRY_NAME_IN_USE} collided with {@code PLUGIN_MISSING_SETTING} -- two different
 * failures answering to one code, which makes a support conversation start with "which 5001?".
 * Renumbered while nothing had shipped against them; a released code is never renumbered.
 */
public final class RegistryErrors {

    /** A name is already taken by a different computation. */
    public static final ErrorCode NAME_IN_USE = new ErrorCode(8001, "REGISTRY_NAME_IN_USE");

    /** No registered query answers to that name. */
    public static final ErrorCode NO_SUCH_QUERY = new ErrorCode(8002, "REGISTRY_NO_SUCH_QUERY");

    /** The operation is not legal from the state the query is in. */
    public static final ErrorCode ILLEGAL_TRANSITION = new ErrorCode(8003, "REGISTRY_ILLEGAL_TRANSITION");

    /** The query failed while running; its recorded cause says how. */
    public static final ErrorCode QUERY_FAILED = new ErrorCode(8004, "REGISTRY_QUERY_FAILED");

    /**
     * A record in the registry journal cannot be read.
     *
     * <p>Refused rather than skipped. A skipped registration is a view a client expects to find and
     * will not, and it would fail at subscribe time with "no such view" -- a long way from the
     * unreadable byte that caused it.
     */
    public static final ErrorCode JOURNAL_UNREADABLE = new ErrorCode(8005, "REGISTRY_JOURNAL_UNREADABLE");

    /**
     * The registry journal cannot be written.
     *
     * <p>The registration is refused. Acknowledging one that will not survive a restart tells the
     * client something that is not true, and nothing will correct it later.
     */
    public static final ErrorCode JOURNAL_UNWRITABLE = new ErrorCode(8006, "REGISTRY_JOURNAL_UNWRITABLE");

    /**
     * A journalled registration was replayed for a principal who may no longer have it.
     *
     * <p>A registration is not a standing permission. Replaying blindly would be a way to keep an
     * entitlement after it was revoked, by having registered before it was.
     */
    public static final ErrorCode REPLAY_UNAUTHORIZED = new ErrorCode(8007, "REGISTRY_REPLAY_UNAUTHORIZED");

    private RegistryErrors() {}
}
