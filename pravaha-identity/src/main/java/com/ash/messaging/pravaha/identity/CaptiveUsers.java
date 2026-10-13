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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Captive users: accounts declared in {@code pravaha.identity.captive-users}, which the node ensures at
 * every start and which no administrator can disable, re-role or re-password at runtime (PRV-7023). The
 * configuration is the account's authority; the store only holds what it says.
 *
 * <p>{@link #resolve} validates the entries and reads each password from its one source, refusing a
 * misconfiguration with {@code PRV-7022} before the node serves anything. {@link #reconcile} says what one
 * stored user needs to match its entry. A captive user keeps no password history and its password never
 * expires: the configuration sets it again at each start, so a history would only refuse the configured
 * password, and an expiry would ask for a change the next start undoes.
 */
public final class CaptiveUsers {

    /** The configuration key, named in every refusal. */
    public static final String KEY = "pravaha.identity.captive-users";

    private CaptiveUsers() {}

    /**
     * One entry as configured. Exactly one of {@code password}, {@code passwordEnv} (an environment
     * variable's name) and {@code passwordFile} is set.
     */
    public record Entry(
            @Nullable String username,
            @Nullable String displayName,
            @Nullable String email,
            @Nullable String tenant,
            @Nullable List<String> roles,
            @Nullable String password,
            @Nullable String passwordEnv,
            @Nullable String passwordFile) {

        @Override
        public String toString() {
            return "Entry[" + username + "]"; // never the password
        }
    }

    /** An entry validated, with its password read from its source. */
    public record Account(
            String username,
            @Nullable String displayName,
            @Nullable String email,
            String tenant,
            Set<String> roles,
            String password) {

        public Account {
            roles = java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(roles)); // ROLESORDER-1
        }

        @Override
        public String toString() {
            return "Account[" + username + ", roles " + roles + "]"; // never the password
        }
    }

    /**
     * The entries validated and resolved, or {@code PRV-7022} naming the first that is wrong.
     *
     * @param allowLiteral {@code pravaha.identity.allow-literal-captive-passwords}: a literal password
     *     outside the dev profile, on purpose
     * @param env how an environment variable is read; {@code System::getenv} on a node
     */
    public static List<Account> resolve(
            @Nullable List<Entry> entries,
            IdentitySettings settings,
            boolean allowLiteral,
            Function<String, @Nullable String> env) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        PasswordPolicy policy = new PasswordPolicy(settings);
        Set<String> seen = new HashSet<>();
        List<Account> out = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            String where = KEY + "[" + i + "]";
            String name = e.username() == null ? null : e.username().strip();
            if (name == null || !name.matches("[a-z][a-z0-9._-]{1,63}")) {
                throw invalid(where + ".username is '" + name + "'; a user name is 2 to 64 of lower-case letters, "
                        + "digits, '.', '_' and '-', starting with a letter");
            }
            where = KEY + " '" + name + "'";
            if (!seen.add(name)) {
                throw invalid(where + " is declared twice; each captive user is declared once");
            }
            Set<String> roles = new LinkedHashSet<>();
            if (e.roles() != null) {
                e.roles().stream()
                        .filter(r -> r != null && !r.isBlank())
                        .map(String::strip)
                        .forEach(roles::add);
            }
            if (IdentityService.ADMIN.equals(name) && !roles.contains(IdentityService.ADMIN_ROLE)) {
                throw invalid(where + " is the bootstrap administrator's name without the '"
                        + IdentityService.ADMIN_ROLE + "' role, and would take administration away from the "
                        + "account every node starts with; give it the role, or name the captive user otherwise");
            }
            String password = password(e, where, settings, allowLiteral, env);
            Optional<String> refusal = policy.refusal(name, password, List.of());
            if (refusal.isPresent()) {
                throw invalid(where + " has a password the policy refuses: " + refusal.get());
            }
            String tenant = e.tenant() == null || e.tenant().isBlank()
                    ? "public"
                    : e.tenant().strip();
            out.add(new Account(name, blankToNull(e.displayName()), blankToNull(e.email()), tenant, roles, password));
        }
        return List.copyOf(out);
    }

    private static String password(
            Entry e,
            String where,
            IdentitySettings settings,
            boolean allowLiteral,
            Function<String, @Nullable String> env) {
        boolean literal = present(e.password());
        boolean fromEnv = present(e.passwordEnv());
        boolean fromFile = present(e.passwordFile());
        int sources = (literal ? 1 : 0) + (fromEnv ? 1 : 0) + (fromFile ? 1 : 0);
        if (sources != 1) {
            throw invalid(where + " has " + (sources == 0 ? "no password source" : sources + " password sources")
                    + "; set exactly one of password, password-env and password-file");
        }
        if (literal) {
            if (!settings.dev() && !allowLiteral) {
                throw invalid(where + " has a literal password, and this node is not the dev profile: a password "
                        + "written into configuration ships with it. Use password-env or password-file, or set "
                        + "pravaha.identity.allow-literal-captive-passwords to run like this on purpose");
            }
            return java.util.Objects.requireNonNull(e.password());
        }
        if (fromEnv) {
            String variable = java.util.Objects.requireNonNull(e.passwordEnv()).strip();
            String value = env.apply(variable);
            if (value == null || value.isEmpty()) {
                throw invalid(where + " reads its password from the environment variable " + variable
                        + ", which is not set or is empty");
            }
            return value;
        }
        Path file = Path.of(java.util.Objects.requireNonNull(e.passwordFile()).strip());
        try {
            String value = Files.readString(file).strip();
            if (value.isEmpty()) {
                throw invalid(where + " reads its password from " + file + ", which is empty");
            }
            return value;
        } catch (IOException io) {
            throw invalid(
                    where + " reads its password from " + file + ", which cannot be read (" + io.getMessage() + ")");
        }
    }

    /** What changed, in words for the audit trail -- never the password -- and the user as it must be. */
    record Reconciled(Identities.User user, List<String> changes, boolean passwordReset) {}

    /**
     * What {@code current} needs to match {@code account}: its roles exactly, active, not locked, no
     * forced change, and the configured password -- set again, history cleared, only when it no longer
     * verifies. Empty when nothing needs changing, so a second start writes nothing.
     */
    static Optional<Reconciled> reconcile(Identities.User current, Account account, Instant now) {
        List<String> changes = new ArrayList<>();
        Identities.User user = current;
        if (!user.roles().equals(account.roles())) {
            changes.add("roles " + new java.util.TreeSet<>(user.roles()) + " -> "
                    + new java.util.TreeSet<>(account.roles()));
            user = user.withRoles(account.roles());
        }
        if (!user.active()) {
            changes.add("re-enabled (was " + user.status() + ")");
            user = user.withProfile(user.displayName(), user.email(), user.tenant(), "active");
        }
        if (user.lockedUntil() != null) {
            changes.add("lockout cleared");
            user = user.withFailures(0, null, null);
        }
        boolean reset = user.passwordHash() == null || !Kdf.verify(account.password(), user.passwordHash());
        if (reset) {
            changes.add("password reset to the configured one");
            user = user.withPassword(Kdf.hash(account.password()), List.of(), false, now);
        } else if (user.mustChangePassword() || !user.previousHashes().isEmpty()) {
            changes.add("forced change and password history cleared");
            user = user.withPassword(
                    java.util.Objects.requireNonNull(user.passwordHash()),
                    List.of(),
                    false,
                    user.passwordChangedAt() == null ? now : user.passwordChangedAt());
        }
        return changes.isEmpty() ? Optional.empty() : Optional.of(new Reconciled(user, changes, reset));
    }

    /** A new user for {@code account}, as it is created when the store has none of that name. */
    static Identities.User created(Account account, Instant now) {
        return new Identities.User(
                account.username(),
                account.displayName(),
                account.email(),
                account.tenant(),
                account.roles(),
                "active",
                false,
                Kdf.hash(account.password()),
                List.of(),
                false,
                now,
                0,
                null,
                null,
                null,
                now,
                Map.of());
    }

    /** The runtime refusal: the account is the configuration's. */
    static PravahaException managed(String username, String what) {
        return new PravahaException(
                IdentityErrors.CAPTIVE_USER_MANAGED,
                "'" + username + "' is a captive user, managed by configuration: " + KEY + "; " + what
                        + " is refused. Change the configuration and restart the node instead");
    }

    private static PravahaException invalid(String message) {
        return new PravahaException(IdentityErrors.CAPTIVE_USERS_INVALID, message);
    }

    private static boolean present(@Nullable String s) {
        return s != null && !s.isBlank();
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
