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
package com.ash.messaging.pravaha.identity;

import com.ash.messaging.pravaha.api.ErrorCode;

/** The identity module's codes, PRV-7010 to PRV-7021 (ADR-052). */
public final class IdentityErrors {

    /** One answer for a wrong user and a wrong password, so the answer says nothing about which. */
    public static final ErrorCode CREDENTIALS_REFUSED = new ErrorCode(7010, "IDENTITY_CREDENTIALS_REFUSED");

    /** Too many failures: the account is locked until a stated time. */
    public static final ErrorCode LOCKED = new ErrorCode(7011, "IDENTITY_LOCKED");

    /** A new password the policy refuses; the message names the rule. */
    public static final ErrorCode PASSWORD_POLICY = new ErrorCode(7012, "IDENTITY_PASSWORD_POLICY");

    /** A key that has expired or been revoked. */
    public static final ErrorCode KEY_NOT_VALID = new ErrorCode(7013, "IDENTITY_KEY_NOT_VALID");

    /** A key minted for another deployment: a QA key never reaches production. */
    public static final ErrorCode KEY_WRONG_ENVIRONMENT = new ErrorCode(7014, "IDENTITY_KEY_WRONG_ENVIRONMENT");

    /** A key or a role grant that would give more than its holder or grantor has. */
    public static final ErrorCode WOULD_WIDEN = new ErrorCode(7015, "IDENTITY_WOULD_WIDEN");

    /** A session past its idle or absolute limit, or ended. */
    public static final ErrorCode SESSION_EXPIRED = new ErrorCode(7016, "IDENTITY_SESSION_EXPIRED");

    /** A reset token that is unknown, expired or already used. */
    public static final ErrorCode RESET_TOKEN_INVALID = new ErrorCode(7017, "IDENTITY_RESET_TOKEN_INVALID");

    /** Forced change is configured and this session must change its password first. */
    public static final ErrorCode MUST_CHANGE_PASSWORD = new ErrorCode(7018, "IDENTITY_MUST_CHANGE_PASSWORD");

    /** The bootstrap admin's default password, outside the dev profile. */
    public static final ErrorCode DEFAULT_ADMIN_PASSWORD = new ErrorCode(7019, "IDENTITY_DEFAULT_ADMIN_PASSWORD");

    /** A request the identity service cannot act on as written: a bad user name, a duplicate, a key's life. */
    public static final ErrorCode INVALID_REQUEST = new ErrorCode(7020, "IDENTITY_INVALID_REQUEST");

    /** No user, or no session, by that name. */
    public static final ErrorCode NOT_FOUND = new ErrorCode(7021, "IDENTITY_NOT_FOUND");

    private IdentityErrors() {}
}
