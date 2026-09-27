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

import java.time.Duration;

/**
 * Every limit ADR-052 names, with its default, and nothing else. Built by the server from
 * {@code pravaha.identity.*}; a test builds it directly.
 *
 * @param environment the deployment's name, the {@code <env>} of every key it issues: {@code qa}, {@code prod}
 * @param minLength a new password's minimum length (12)
 * @param requireClasses how many of lower, upper, digit, symbol it must use (3)
 * @param history how many previous passwords it may not repeat, the current one included (5)
 * @param maxAge how long a password lasts before login sets must-change (90 days); zero is for ever
 * @param forceChange whether a new account, the bootstrap admin and an admin reset must change their
 *     password at first login -- off unless configured, as MAYA's {@code force_change}
 * @param lockoutFailures failures that lock an account (5)
 * @param lockoutWindow within this long (15 minutes)
 * @param lockoutFor for this long (30 minutes)
 * @param sessionIdle a session ends after this long unused (30 minutes)
 * @param sessionAbsolute and after this long whatever (12 hours)
 * @param sessionsPerUser at most this many at once; the oldest is ended (3)
 * @param keyDefaultDays a key's life when none is asked for (90)
 * @param keyMaxDays the most it may be given (365)
 * @param rotationOverlap how long a rotated key keeps working beside its successor (7 days)
 * @param resetTokenLife how long an admin-issued reset token lasts (60 minutes)
 * @param allowDefaultAdminPassword run outside dev with the bootstrap admin's default password
 * @param dev whether the node runs the dev profile, where the default admin password is allowed
 */
public record IdentitySettings(
        String environment,
        int minLength,
        int requireClasses,
        int history,
        Duration maxAge,
        boolean forceChange,
        int lockoutFailures,
        Duration lockoutWindow,
        Duration lockoutFor,
        Duration sessionIdle,
        Duration sessionAbsolute,
        int sessionsPerUser,
        int keyDefaultDays,
        int keyMaxDays,
        Duration rotationOverlap,
        Duration resetTokenLife,
        boolean allowDefaultAdminPassword,
        boolean dev) {

    public IdentitySettings {
        if (environment == null || !environment.matches("[a-z0-9]{1,16}")) {
            throw new IllegalArgumentException("pravaha.identity.environment must be 1 to 16 lower-case letters or "
                    + "digits -- it is part of every key -- not '" + environment + "'");
        }
        if (minLength < 8) {
            throw new IllegalArgumentException("pravaha.identity.password.min-length is " + minLength
                    + "; below 8 a password falls to guessing whatever the hash");
        }
        if (requireClasses < 1 || requireClasses > 4) {
            throw new IllegalArgumentException("pravaha.identity.password.require-classes must be 1 to 4");
        }
        if (keyMaxDays < 1 || keyDefaultDays < 1 || keyDefaultDays > keyMaxDays) {
            throw new IllegalArgumentException("pravaha.identity.keys: default-days must be between 1 and max-days");
        }
    }

    /** ADR-052's values, for {@code environment}. */
    public static IdentitySettings defaults(String environment) {
        return new IdentitySettings(
                environment,
                12,
                3,
                5,
                Duration.ofDays(90),
                false,
                5,
                Duration.ofMinutes(15),
                Duration.ofMinutes(30),
                Duration.ofMinutes(30),
                Duration.ofHours(12),
                3,
                90,
                365,
                Duration.ofDays(7),
                Duration.ofMinutes(60),
                false,
                false);
    }

    public IdentitySettings withForceChange(boolean force) {
        return new IdentitySettings(
                environment,
                minLength,
                requireClasses,
                history,
                maxAge,
                force,
                lockoutFailures,
                lockoutWindow,
                lockoutFor,
                sessionIdle,
                sessionAbsolute,
                sessionsPerUser,
                keyDefaultDays,
                keyMaxDays,
                rotationOverlap,
                resetTokenLife,
                allowDefaultAdminPassword,
                dev);
    }

    public IdentitySettings withDev(boolean isDev) {
        return new IdentitySettings(
                environment,
                minLength,
                requireClasses,
                history,
                maxAge,
                forceChange,
                lockoutFailures,
                lockoutWindow,
                lockoutFor,
                sessionIdle,
                sessionAbsolute,
                sessionsPerUser,
                keyDefaultDays,
                keyMaxDays,
                rotationOverlap,
                resetTokenLife,
                allowDefaultAdminPassword,
                isDev);
    }
}
