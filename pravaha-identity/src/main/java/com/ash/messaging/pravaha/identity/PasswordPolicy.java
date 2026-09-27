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

import java.util.List;
import java.util.Optional;

/**
 * Whether a new password may be used. One place, called by every path that sets a password --
 * creation, change, an administrator's reset, a reset token -- so no surface can skip it.
 */
final class PasswordPolicy {

    private final IdentitySettings settings;

    PasswordPolicy(IdentitySettings settings) {
        this.settings = settings;
    }

    /**
     * Empty when {@code candidate} is acceptable; otherwise the rule it breaks, in a sentence a
     * person can act on. {@code previous} is the user's current hash first, then older ones.
     */
    Optional<String> refusal(String username, String candidate, List<String> previous) {
        if (candidate == null || candidate.length() < settings.minLength()) {
            return Optional.of("a password needs at least " + settings.minLength() + " characters");
        }
        int classes = 0;
        classes += candidate.chars().anyMatch(Character::isLowerCase) ? 1 : 0;
        classes += candidate.chars().anyMatch(Character::isUpperCase) ? 1 : 0;
        classes += candidate.chars().anyMatch(Character::isDigit) ? 1 : 0;
        classes += candidate.chars().anyMatch(c -> !Character.isLetterOrDigit(c)) ? 1 : 0;
        if (classes < settings.requireClasses()) {
            return Optional.of("a password needs at least " + settings.requireClasses()
                    + " of: lower-case letters, upper-case letters, digits, symbols");
        }
        int checked = 0;
        for (String hash : previous) {
            if (checked++ >= settings.history()) {
                break;
            }
            if (Kdf.verify(candidate, hash)) {
                return Optional.of("a password may not be one of the last " + settings.history() + " used");
            }
        }
        return Optional.empty();
    }
}
