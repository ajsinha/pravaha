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
package com.ash.messaging.pravaha.catalog;

import com.ash.messaging.pravaha.api.ErrorCode;

/**
 * The catalogue's codes, PRV-7030 to PRV-7040 (ADR-059).
 *
 * <p>In the security range because every one of them is about who may do what to which object. A
 * read, a subscription or a registration the catalogue refuses is still {@code PRV-7002
 * SECURITY_FORBIDDEN} -- the code every enforcement point already throws and every client already
 * handles -- and these are the refusals only the catalogue gives: of a statement that changes it, of
 * a name it does not hold, of a node configured with two authorities.
 */
public final class CatalogErrors {

    /** A catalogue statement or endpoint on a node whose catalogue is off ({@code pravaha.catalog.enabled}). */
    public static final ErrorCode DISABLED = new ErrorCode(7030, "CATALOG_DISABLED");

    /**
     * No object by that name that the caller may know of.
     *
     * <p>The same answer for a name that does not exist and a name the caller may not see, so the
     * refusal is not an oracle for what exists.
     */
    public static final ErrorCode NO_SUCH_OBJECT = new ErrorCode(7031, "CATALOG_NO_SUCH_OBJECT");

    /** A privilege that means nothing on that kind of object: {@code WRITE} on a view, {@code CREATE} on a sink. */
    public static final ErrorCode PRIVILEGE_NOT_APPLICABLE = new ErrorCode(7032, "CATALOG_PRIVILEGE_NOT_APPLICABLE");

    /** Changing an object's grants, owner, description or tags needs {@code MANAGE} or {@code OWN} on it. */
    public static final ErrorCode MANAGE_REQUIRED = new ErrorCode(7033, "CATALOG_MANAGE_REQUIRED");

    /**
     * A node configured with two authorities for who may do what, refused at startup.
     *
     * <p>{@code pravaha.security.policy} was imported into the catalogue once and has since been set to
     * something else. Which one governs is the deployment's decision to write down, not the engine's to
     * guess.
     */
    public static final ErrorCode TWO_AUTHORITIES = new ErrorCode(7034, "CATALOG_TWO_AUTHORITIES");

    /** The catalogue journal cannot be read or appended to; a change is refused rather than acknowledged and lost. */
    public static final ErrorCode JOURNAL_FAILED = new ErrorCode(7035, "CATALOG_JOURNAL_FAILED");

    /** {@code CREATE NAMESPACE} of a namespace that exists. */
    public static final ErrorCode OBJECT_EXISTS = new ErrorCode(7036, "CATALOG_OBJECT_EXISTS");

    /** A request the catalogue cannot carry out as written: a bad name, tag or grantee, or a grant of {@code OWN}. */
    public static final ErrorCode INVALID_REQUEST = new ErrorCode(7037, "CATALOG_INVALID_REQUEST");

    /**
     * A row filter or mask the catalogue will not hold or bind (ADR-059 §4): a subquery, a
     * non-deterministic function, a function not on the policy list, a mask naming a column other than
     * its own or producing another type, a filter naming a column the object does not carry, or one
     * that is true for every row.
     */
    public static final ErrorCode POLICY_INVALID = new ErrorCode(7038, "CATALOG_POLICY_INVALID");

    /**
     * A policy that applies to the caller reads a claim with {@code session_attribute} that their
     * credential does not carry. Refused rather than guessed: an absent region is not "every region"
     * and not "none".
     */
    public static final ErrorCode POLICY_CLAIM_MISSING = new ErrorCode(7039, "CATALOG_POLICY_CLAIM_MISSING");

    /**
     * Two policies that cannot both hold: two masks on one column for the same reader, a policy dropped
     * while it is still bound, a binding that already exists, or a filter bound where a mask was meant.
     */
    public static final ErrorCode POLICY_CONFLICT = new ErrorCode(7040, "CATALOG_POLICY_CONFLICT");

    private CatalogErrors() {}
}
