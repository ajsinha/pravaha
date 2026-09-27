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

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;

/**
 * Users, passwords, API keys and sessions, the way ADR-052 (and MAYA) has them. One method per
 * concern, so no surface -- REST, CLI, console -- reaches a rule another surface skips.
 *
 * <p>State changes are serialised on this object. The slow hash never runs under that lock: a login
 * reads what it needs, verifies outside, and comes back to record the outcome, so one person signing
 * in does not stall every request the node is answering.
 */
public final class IdentityService {

    /** The bootstrap admin's name and its well-known password, refused outside dev (PRV-7019). */
    public static final String ADMIN = "admin";

    public static final String DEFAULT_ADMIN_PASSWORD = "pravaha-dev-admin";

    /** The role that administers identity. */
    public static final String ADMIN_ROLE = "admin";

    static final String SESSION_PREFIX = "prv_s_";
    static final String KEY_PREFIX = "prv_";
    private static final Duration KEY_CHECK_CACHE = Duration.ofSeconds(5);
    private static final Duration LAST_USED_EVERY = Duration.ofMinutes(1);

    private final IdentityStore store;
    private final IdentitySettings settings;
    private final PasswordPolicy policy;
    private final AuditSink audit;
    private final Clock clock;
    private final Map<String, String> sessionByTokenHash = new HashMap<>();
    private final Map<String, Instant> lastSeen = new HashMap<>();
    private final Map<String, Instant> verifiedKeys = new ConcurrentHashMap<>();
    private final java.util.function.Supplier<String> initialAdminPassword;

    IdentityService(IdentityStore store, IdentitySettings settings, AuditSink audit, Clock clock) {
        this(store, settings, audit, clock, null);
    }

    /**
     * @param initialAdminPassword what {@link #ADMIN} is created with when the store is empty, asked for
     *     only then; null, or a supplier answering null, means the published default
     */
    IdentityService(
            IdentityStore store,
            IdentitySettings settings,
            AuditSink audit,
            Clock clock,
            java.util.function.Supplier<String> initialAdminPassword) {
        this.initialAdminPassword = initialAdminPassword;
        this.store = store;
        this.settings = settings;
        this.policy = new PasswordPolicy(settings);
        this.audit = audit == null ? AuditSink.NONE : audit;
        this.clock = clock;
        Instant now = clock.instant();
        // After a restart a session's idle clock starts again: the store records when it began, not every
        // request, and ending every session at every restart would be worse than a fresh idle window.
        store.sessions.values().forEach(s -> {
            sessionByTokenHash.put(s.tokenHash(), s.id());
            lastSeen.put(s.id(), now);
        });
        bootstrap();
    }

    /** A service over the journal at {@code file}, created on first use. */
    public static IdentityService open(Path file, IdentitySettings settings, AuditSink audit) {
        return new IdentityService(IdentityStore.open(file), settings, audit, Clock.systemUTC());
    }

    /**
     * A service over the journal at {@code file}, whose bootstrap admin -- made only when the store is
     * empty -- gets the password {@code initialAdminPassword} supplies rather than the published one. An
     * installer generates it, so no deployment starts on a password printed in the documentation.
     */
    public static IdentityService open(
            Path file,
            IdentitySettings settings,
            AuditSink audit,
            java.util.function.Supplier<String> initialAdminPassword) {
        return new IdentityService(IdentityStore.open(file), settings, audit, Clock.systemUTC(), initialAdminPassword);
    }

    /** A service that keeps nothing on disk: for an embedded engine and tests. */
    public static IdentityService inMemory(IdentitySettings settings, AuditSink audit, Clock clock) {
        return new IdentityService(IdentityStore.inMemory(), settings, audit, clock);
    }

    public IdentitySettings settings() {
        return settings;
    }

    // ------------------------------------------------------------------ bootstrap

    private synchronized void bootstrap() {
        if (!store.users.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        String chosen = initialAdminPassword == null ? null : initialAdminPassword.get();
        if (chosen != null) {
            Optional<String> refusal = policy.refusal(ADMIN, chosen, List.of());
            if (refusal.isPresent()) {
                throw new PravahaException(
                        IdentityErrors.PASSWORD_POLICY,
                        "the initial password for '" + ADMIN + "' is refused: " + refusal.get());
            }
        }
        store.putUser(new Identities.User(
                ADMIN,
                "Administrator",
                null,
                "public",
                Set.of(ADMIN_ROLE, "operator"),
                "active",
                false,
                Kdf.hash(chosen == null ? DEFAULT_ADMIN_PASSWORD : chosen),
                List.of(),
                settings.forceChange(),
                now,
                0,
                null,
                null,
                null,
                now));
        record(
                system(),
                "bootstrap.admin_created",
                ADMIN,
                true,
                chosen == null
                        ? "no users existed; the published default password"
                        : "no users existed; the initial password the deployment supplied",
                null);
    }

    /** True while the bootstrap admin still has its well-known password. */
    public synchronized boolean defaultAdminPasswordInUse() {
        Identities.User admin = store.users.get(ADMIN);
        return admin != null && admin.active() && Kdf.verify(DEFAULT_ADMIN_PASSWORD, admin.passwordHash());
    }

    /** Refuses to start a node outside dev whose admin still has the default password (PRV-7019). */
    public void requireStartable() {
        if (!settings.dev() && !settings.allowDefaultAdminPassword() && defaultAdminPasswordInUse()) {
            throw new PravahaException(
                    IdentityErrors.DEFAULT_ADMIN_PASSWORD,
                    "the user '" + ADMIN + "' still has the default password '" + DEFAULT_ADMIN_PASSWORD
                            + "', which is published; this node is not the dev profile, so it refuses to start. "
                            + "Change it (pravaha user password " + ADMIN + ", or sign in and change it), or set "
                            + "pravaha.identity.allow-default-admin-password to run like this on purpose");
        }
    }

    // ------------------------------------------------------------------ login and sessions

    /** What a successful login hands back. The token is shown once and stored only as its hash. */
    public record Login(String token, String sessionId, Instant expiresAt, boolean mustChangePassword) {}

    public Login login(String username, String password, String from) {
        Identities.User user;
        synchronized (this) {
            user = username == null ? null : store.users.get(username);
            Instant now = clock.instant();
            if (user != null && user.lockedUntil() != null && now.isBefore(user.lockedUntil())) {
                record(named(username), "auth.login_locked", username, false, "locked", from);
                throw new PravahaException(
                        IdentityErrors.LOCKED,
                        "this account is locked until "
                                + user.lockedUntil() + " after too many failed sign-ins; try again then, or ask an "
                                + "administrator");
            }
        }
        // The slow check outside the lock. An unknown user still pays for one, so the time a refusal
        // takes does not say whether the name exists.
        boolean ok = user != null && user.active() && user.passwordHash() != null
                ? Kdf.verify(password == null ? "" : password, user.passwordHash())
                : Kdf.verify(password == null ? "" : password, TimingEqualiser.HASH);
        synchronized (this) {
            Instant now = clock.instant();
            Identities.User current = user == null ? null : store.users.get(username);
            if (!ok || current == null || !current.active()) {
                if (current != null) {
                    recordFailure(current, now, from);
                } else {
                    record(named(username), "auth.login_failed", String.valueOf(username), false, "unknown user", from);
                }
                throw refused();
            }
            boolean expired = !settings.maxAge().isZero()
                    && current.passwordChangedAt() != null
                    && current.passwordChangedAt().plus(settings.maxAge()).isBefore(now);
            String hash = Kdf.needsRehash(current.passwordHash()) ? Kdf.hash(password) : current.passwordHash();
            Identities.User signedIn = current.withLogin(now, hash, current.mustChangePassword() || expired);
            store.putUser(signedIn);
            Login login = openSession(signedIn, now);
            record(named(username), "auth.login", username, true, expired ? "password expired" : "ok", from);
            return login;
        }
    }

    /** A hash to verify against when there is no user, made once, on the first such login. */
    private static final class TimingEqualiser {
        static final String HASH = Kdf.hash("pravaha-timing-equaliser");
    }

    private void recordFailure(Identities.User user, Instant now, String from) {
        boolean freshWindow = user.firstFailedAt() == null
                || user.firstFailedAt().plus(settings.lockoutWindow()).isBefore(now);
        int failed = freshWindow ? 1 : user.failedAttempts() + 1;
        Instant first = freshWindow ? now : user.firstFailedAt();
        Instant locked = failed >= settings.lockoutFailures() ? now.plus(settings.lockoutFor()) : null;
        // Recorded before the refusal is answered: a failure that a later error could roll back is a
        // lockout an attacker could side-step.
        store.putUser(user.withFailures(locked == null ? failed : 0, locked == null ? first : null, locked));
        record(named(user.username()), "auth.login_failed", user.username(), false, "wrong password", from);
        if (locked != null) {
            record(
                    named(user.username()),
                    "auth.lockout",
                    user.username(),
                    false,
                    failed + " failures within " + settings.lockoutWindow(),
                    from);
        }
    }

    private Login openSession(Identities.User user, Instant now) {
        if (!user.service()) {
            List<Identities.Session> own = new ArrayList<>(store.sessions.values().stream()
                    .filter(s -> s.username().equals(user.username()))
                    .sorted(Comparator.comparing(Identities.Session::createdAt))
                    .toList());
            while (own.size() >= settings.sessionsPerUser()) {
                Identities.Session oldest = own.remove(0);
                end(oldest.id());
                record(
                        named(user.username()),
                        "auth.session_evicted",
                        oldest.id(),
                        true,
                        "more than " + settings.sessionsPerUser() + " sessions",
                        null);
            }
        }
        String token = SESSION_PREFIX + Kdf.randomToken(32);
        String id = Kdf.randomToken(9);
        Identities.Session session = new Identities.Session(
                id, Kdf.sha256Hex(token), user.username(), now, now.plus(settings.sessionAbsolute()));
        store.putSession(session);
        sessionByTokenHash.put(session.tokenHash(), id);
        lastSeen.put(id, now);
        return new Login(token, id, session.absoluteExpiry(), user.mustChangePassword());
    }

    public synchronized void logout(String token) {
        String id = sessionByTokenHash.get(Kdf.sha256Hex(token));
        if (id != null) {
            Identities.Session s = store.sessions.get(id);
            end(id);
            record(named(s == null ? "?" : s.username()), "auth.logout", id, true, "ok", null);
        }
    }

    private void end(String id) {
        Identities.Session s = store.sessions.get(id);
        if (s != null) {
            sessionByTokenHash.remove(s.tokenHash());
            store.endSession(id);
        }
        lastSeen.remove(id);
    }

    private void endAllSessionsOf(String username) {
        for (Identities.Session s : List.copyOf(store.sessions.values())) {
            if (s.username().equals(username)) {
                end(s.id());
            }
        }
    }

    // ------------------------------------------------------------------ resolving a credential

    /**
     * The principal a bearer token stands for, or empty when it is not one of this service's (a static
     * token, for the legacy verifier to try). Throws when it is one of ours and is refused.
     */
    public Optional<Principal> principalFor(String token) {
        if (token == null) {
            return Optional.empty();
        }
        if (token.startsWith(SESSION_PREFIX)) {
            return Optional.of(sessionPrincipal(token));
        }
        if (token.startsWith(KEY_PREFIX)) {
            return Optional.of(keyPrincipal(token));
        }
        return Optional.empty();
    }

    private synchronized Principal sessionPrincipal(String token) {
        Instant now = clock.instant();
        String id = sessionByTokenHash.get(Kdf.sha256Hex(token));
        Identities.Session s = id == null ? null : store.sessions.get(id);
        if (s == null) {
            throw new PravahaException(IdentityErrors.SESSION_EXPIRED, "this session has ended; sign in again");
        }
        Instant seen = lastSeen.getOrDefault(id, s.createdAt());
        if (now.isAfter(s.absoluteExpiry()) || seen.plus(settings.sessionIdle()).isBefore(now)) {
            end(id);
            throw new PravahaException(IdentityErrors.SESSION_EXPIRED, "this session has expired; sign in again");
        }
        Identities.User user = store.users.get(s.username());
        if (user == null || !user.active()) {
            end(id);
            throw new PravahaException(IdentityErrors.SESSION_EXPIRED, "this account is no longer active");
        }
        lastSeen.put(id, now);
        Map<String, String> claims = new HashMap<>();
        claims.put("via", "session");
        claims.put("session", id);
        if (user.mustChangePassword()) {
            claims.put("mustChangePassword", "true");
        }
        return new Principal(user.username(), tenantOf(user), user.roles(), claims);
    }

    private Principal keyPrincipal(String presented) {
        String[] parts = presented.split("_", 4);
        if (parts.length != 4 || !"prv".equals(parts[0])) {
            throw new PravahaException(SecurityErrors.UNAUTHENTICATED, "this is not a Pravaha API key");
        }
        if (!settings.environment().equals(parts[1])) {
            throw new PravahaException(
                    IdentityErrors.KEY_WRONG_ENVIRONMENT,
                    "this key was issued for the '" + parts[1] + "' deployment, and this is '" + settings.environment()
                            + "'");
        }
        String keyId = parts[2];
        String secret = parts[3];
        Identities.ApiKey key;
        Identities.User holder;
        synchronized (this) {
            key = store.keys.get(keyId);
            holder = key == null ? null : store.users.get(key.holder());
        }
        Instant now = clock.instant();
        if (key == null || !key.usableAt(now)) {
            throw new PravahaException(
                    IdentityErrors.KEY_NOT_VALID, "key " + keyId + " is unknown, expired or " + "revoked");
        }
        String cacheKey = keyId + ":" + Kdf.sha256Hex(secret);
        Instant cached = verifiedKeys.get(cacheKey);
        if (cached == null || cached.plus(KEY_CHECK_CACHE).isBefore(now)) {
            if (!Kdf.verify(secret, key.secretHash())) {
                synchronized (this) {
                    record(named(key.holder()), "auth.key_failed", keyId, false, "wrong secret", null);
                }
                throw new PravahaException(IdentityErrors.KEY_NOT_VALID, "key " + keyId + " is not valid");
            }
            verifiedKeys.put(cacheKey, now);
        }
        if (holder == null || !holder.active()) {
            throw new PravahaException(IdentityErrors.KEY_NOT_VALID, "key " + keyId + "'s holder is not active");
        }
        synchronized (this) {
            Identities.ApiKey latest = store.keys.get(keyId);
            if (latest != null
                    && (latest.lastUsedAt() == null
                            || latest.lastUsedAt().plus(LAST_USED_EVERY).isBefore(now))) {
                store.putKey(latest.used(now));
            }
        }
        // A key narrows its holder and never widens: if the holder has since lost a role, so has the key.
        Set<String> roles = new LinkedHashSet<>(key.roles());
        roles.retainAll(holder.roles());
        return new Principal(holder.username(), tenantOf(holder), roles, Map.of("via", "key", "key", keyId));
    }

    private static String tenantOf(Identities.User user) {
        return user.tenant() == null ? "public" : user.tenant();
    }

    // ------------------------------------------------------------------ passwords

    /** A signed-in person changing their own password. Ends every other session of theirs. */
    public synchronized void changePassword(Principal who, String current, String replacement) {
        Identities.User user = requireUser(who.id());
        if (!Kdf.verify(current == null ? "" : current, user.passwordHash())) {
            recordFailure(user, clock.instant(), null);
            throw refused();
        }
        if (current.equals(replacement)) {
            throw new PravahaException(IdentityErrors.PASSWORD_POLICY, "the new password must differ from the old one");
        }
        accept(store.users.get(who.id()), replacement, false);
        String keep = who.claims().get("session");
        for (Identities.Session s : List.copyOf(store.sessions.values())) {
            if (s.username().equals(who.id()) && !s.id().equals(keep)) {
                end(s.id());
            }
        }
        record(who, "auth.password_changed", who.id(), true, "ok", null);
    }

    /** The one place a new password is accepted: the policy, then the history, then the hash. */
    private void accept(Identities.User user, String password, boolean mustChange) {
        List<String> previous = new ArrayList<>();
        if (user.passwordHash() != null) {
            previous.add(user.passwordHash());
        }
        previous.addAll(user.previousHashes());
        Optional<String> refusal = policy.refusal(user.username(), password, previous);
        if (refusal.isPresent()) {
            throw new PravahaException(IdentityErrors.PASSWORD_POLICY, refusal.get());
        }
        List<String> kept = previous.subList(0, Math.min(previous.size(), Math.max(0, settings.history() - 1)));
        store.putUser(user.withPassword(Kdf.hash(password), kept, mustChange, clock.instant()));
    }

    /** An administrator issues a single-use reset token, shown once. */
    public record Reset(String token, Instant expiresAt) {}

    public synchronized Reset issueReset(Principal admin, String username) {
        requireAdmin(admin);
        requireUser(username);
        String token = "prv_r_" + Kdf.randomToken(32);
        Instant expires = clock.instant().plus(settings.resetTokenLife());
        store.putReset(new Identities.ResetToken(Kdf.sha256Hex(token), username, expires, admin.id()));
        record(admin, "auth.password_reset_issued", username, true, "expires " + expires, null);
        return new Reset(token, expires);
    }

    /** Redeems a reset token: sets the password, clears any lockout, ends every session. */
    public synchronized void redeemReset(String token, String password) {
        Identities.ResetToken reset = token == null ? null : store.resets.get(Kdf.sha256Hex(token));
        if (reset == null || clock.instant().isAfter(reset.expiresAt())) {
            record(system(), "auth.password_reset_refused", "?", false, "unknown, used or expired", null);
            throw new PravahaException(
                    IdentityErrors.RESET_TOKEN_INVALID,
                    "this reset token is unknown, already used or expired; ask an administrator for another");
        }
        Identities.User user = requireUser(reset.username());
        accept(user, password, false);
        store.useReset(reset.tokenHash());
        endAllSessionsOf(user.username());
        record(named(user.username()), "auth.password_reset_completed", user.username(), true, "ok", null);
    }

    // ------------------------------------------------------------------ users

    /** What an administrator sees of a user: never a hash. */
    public record UserView(
            String username,
            String displayName,
            String email,
            String tenant,
            Set<String> roles,
            String status,
            boolean service,
            boolean mustChangePassword,
            Instant passwordChangedAt,
            Instant lockedUntil,
            Instant lastLoginAt,
            Instant createdAt) {}

    private static UserView view(Identities.User u) {
        return new UserView(
                u.username(),
                u.displayName(),
                u.email(),
                u.tenant(),
                u.roles(),
                u.status(),
                u.service(),
                u.mustChangePassword(),
                u.passwordChangedAt(),
                u.lockedUntil(),
                u.lastLoginAt(),
                u.createdAt());
    }

    public synchronized List<UserView> users(Principal admin) {
        requireAdmin(admin);
        return store.users.values().stream().map(IdentityService::view).toList();
    }

    public synchronized UserView me(Principal who) {
        return view(requireUser(who.id()));
    }

    public synchronized UserView createUser(
            Principal admin,
            String username,
            String displayName,
            String email,
            String tenant,
            Set<String> roles,
            String password,
            boolean service) {
        requireAdmin(admin);
        if (username == null || !username.matches("[a-z][a-z0-9._-]{1,63}")) {
            throw new PravahaException(
                    IdentityErrors.INVALID_REQUEST,
                    "a user name is 2 to 64 of lower-case letters, "
                            + "digits, '.', '_' and '-', starting with a letter; '" + username + "' is not");
        }
        if (store.users.containsKey(username)) {
            throw new PravahaException(IdentityErrors.INVALID_REQUEST, "a user named '" + username + "' exists");
        }
        Instant now = clock.instant();
        Identities.User user = new Identities.User(
                username,
                displayName,
                email,
                tenant == null ? "public" : tenant,
                roles,
                "active",
                service,
                null,
                List.of(),
                false,
                null,
                0,
                null,
                null,
                null,
                now);
        if (password != null) {
            Optional<String> refusal = policy.refusal(username, password, List.of());
            if (refusal.isPresent()) {
                throw new PravahaException(IdentityErrors.PASSWORD_POLICY, refusal.get());
            }
            user = user.withPassword(Kdf.hash(password), List.of(), settings.forceChange() && !service, now);
        }
        store.putUser(user);
        record(admin, "user.created", username, true, "roles " + roles, null);
        return view(user);
    }

    public synchronized UserView updateUser(
            Principal admin, String username, String displayName, String email, String tenant, String status) {
        requireAdmin(admin);
        Identities.User user = requireUser(username);
        if (status != null && !status.equals("active") && !status.equals("disabled")) {
            throw new PravahaException(IdentityErrors.INVALID_REQUEST, "a user's status is 'active' or 'disabled'");
        }
        Identities.User changed = user.withProfile(
                displayName == null ? user.displayName() : displayName,
                email == null ? user.email() : email,
                tenant == null ? user.tenant() : tenant,
                status == null ? user.status() : status);
        store.putUser(changed);
        if (!changed.active()) {
            endAllSessionsOf(username);
        }
        record(admin, "user.updated", username, true, "status " + changed.status(), null);
        return view(changed);
    }

    public synchronized UserView setRoles(Principal admin, String username, Set<String> roles) {
        requireAdmin(admin);
        Identities.User user = requireUser(username);
        if (username.equals(admin.id()) && !roles.contains(ADMIN_ROLE)) {
            throw new PravahaException(
                    IdentityErrors.WOULD_WIDEN,
                    "an administrator cannot remove their own admin " + "role; another administrator can");
        }
        store.putUser(user.withRoles(roles));
        record(admin, "user.roles_changed", username, true, user.roles() + " -> " + roles, null);
        return view(store.users.get(username));
    }

    /** An administrator sets a password directly; with forced change configured, it must be changed. */
    public synchronized void setPassword(Principal admin, String username, String password) {
        requireAdmin(admin);
        Identities.User user = requireUser(username);
        accept(user, password, settings.forceChange() && !username.equals(admin.id()));
        endAllSessionsOf(username);
        record(admin, "user.password_reset", username, true, "set by an administrator", null);
    }

    // ------------------------------------------------------------------ API keys

    /** A key as issued: {@code key} is shown this once and never again. */
    public record IssuedKey(String key, String keyId, Instant expiresAt) {}

    /** What anyone sees of a key: never its secret or its hash. */
    public record KeyView(
            String keyId,
            String name,
            String holder,
            Set<String> roles,
            Instant createdAt,
            String createdBy,
            Instant expiresAt,
            Instant revokedAt,
            String rotatedTo,
            Instant lastUsedAt) {}

    private static KeyView view(Identities.ApiKey k) {
        return new KeyView(
                k.keyId(),
                k.name(),
                k.holder(),
                k.roles(),
                k.createdAt(),
                k.createdBy(),
                k.expiresAt(),
                k.revokedAt(),
                k.rotatedTo(),
                k.lastUsedAt());
    }

    public synchronized IssuedKey createKey(
            Principal who, String name, Set<String> roles, Integer days, String forUser) {
        String holderName = forUser == null || forUser.isBlank() ? who.id() : forUser;
        Identities.User holder = requireUser(holderName);
        if (!holderName.equals(who.id())) {
            requireAdmin(who);
            if (!holder.service()) {
                throw new PravahaException(
                        SecurityErrors.FORBIDDEN,
                        "an administrator issues keys for service " + "accounts; '" + holderName
                                + "' is a person and issues their own");
            }
        }
        Set<String> scope = roles == null || roles.isEmpty() ? holder.roles() : roles;
        if (!holder.roles().containsAll(scope)) {
            throw new PravahaException(
                    IdentityErrors.WOULD_WIDEN,
                    "a key can hold only roles its holder has; " + holderName + " has " + holder.roles() + ", and "
                            + scope + " was asked for");
        }
        int life = days == null ? settings.keyDefaultDays() : days;
        if (life < 1 || life > settings.keyMaxDays()) {
            throw new PravahaException(
                    IdentityErrors.INVALID_REQUEST, "a key lasts 1 to " + settings.keyMaxDays() + " days, not " + life);
        }
        return issue(who, name, holderName, scope, Duration.ofDays(life));
    }

    private IssuedKey issue(Principal who, String name, String holder, Set<String> scope, Duration life) {
        Instant now = clock.instant();
        String keyId = Kdf.randomHex(6);
        String secret = Kdf.randomToken(24).replace('_', '-');
        store.putKey(new Identities.ApiKey(
                keyId, name, holder, scope, Kdf.hash(secret), now, who.id(), now.plus(life), null, null, null));
        record(who, "auth.key_created", keyId, true, "for " + holder + " with " + scope, null);
        return new IssuedKey(KEY_PREFIX + settings.environment() + "_" + keyId + "_" + secret, keyId, now.plus(life));
    }

    public synchronized List<KeyView> keys(Principal who, boolean all) {
        if (all) {
            requireAdmin(who);
        }
        return store.keys.values().stream()
                .filter(k -> all || k.holder().equals(who.id()))
                .map(IdentityService::view)
                .toList();
    }

    public synchronized void revokeKey(Principal who, String keyId) {
        Identities.ApiKey key = requireKey(who, keyId);
        store.putKey(key.revoked(clock.instant()));
        verifiedKeys.keySet().removeIf(k -> k.startsWith(keyId + ":"));
        record(who, "auth.key_revoked", keyId, true, "ok", null);
    }

    /** A successor with the same scope; the old key keeps working until the end of the overlap. */
    public record Rotated(IssuedKey successor, Instant oldExpiresAt) {}

    public synchronized Rotated rotateKey(Principal who, String keyId) {
        Identities.ApiKey key = requireKey(who, keyId);
        Instant now = clock.instant();
        if (!key.usableAt(now) || key.rotatedTo() != null) {
            throw new PravahaException(
                    IdentityErrors.KEY_NOT_VALID, "key " + keyId + " is expired, revoked or " + "already rotated");
        }
        IssuedKey successor =
                issue(who, key.name(), key.holder(), key.roles(), Duration.between(key.createdAt(), key.expiresAt()));
        Instant oldExpiry = key.expiresAt().isBefore(now.plus(settings.rotationOverlap()))
                ? key.expiresAt()
                : now.plus(settings.rotationOverlap());
        store.putKey(key.rotated(successor.keyId(), oldExpiry));
        record(who, "auth.key_rotated", keyId, true, "successor " + successor.keyId(), null);
        return new Rotated(successor, oldExpiry);
    }

    /** Keys worth an administrator's attention: never used, expiring within 14 days, or superseded. */
    public record KeyReport(List<KeyView> unused, List<KeyView> expiring, List<KeyView> superseded) {}

    public synchronized KeyReport keyReport(Principal admin) {
        requireAdmin(admin);
        Instant now = clock.instant();
        List<Identities.ApiKey> live =
                store.keys.values().stream().filter(k -> k.usableAt(now)).toList();
        return new KeyReport(
                live.stream()
                        .filter(k -> k.lastUsedAt() == null)
                        .map(IdentityService::view)
                        .toList(),
                live.stream()
                        .filter(k -> k.expiresAt().isBefore(now.plus(Duration.ofDays(14))))
                        .map(IdentityService::view)
                        .toList(),
                live.stream()
                        .filter(k -> k.rotatedTo() != null)
                        .map(IdentityService::view)
                        .toList());
    }

    private Identities.ApiKey requireKey(Principal who, String keyId) {
        Identities.ApiKey key = store.keys.get(keyId);
        if (key == null || (!key.holder().equals(who.id()) && !who.roles().contains(ADMIN_ROLE))) {
            throw new PravahaException(IdentityErrors.KEY_NOT_VALID, "no key " + keyId + " that you may manage");
        }
        return key;
    }

    // ------------------------------------------------------------------ sessions, as data

    public record SessionView(String id, String username, Instant createdAt, Instant expiresAt, Instant lastSeen) {}

    public synchronized List<SessionView> sessions(Principal who, boolean all) {
        if (all) {
            requireAdmin(who);
        }
        return store.sessions.values().stream()
                .filter(s -> all || s.username().equals(who.id()))
                .map(s ->
                        new SessionView(s.id(), s.username(), s.createdAt(), s.absoluteExpiry(), lastSeen.get(s.id())))
                .toList();
    }

    public synchronized void endSession(Principal who, String id) {
        Identities.Session s = store.sessions.get(id);
        if (s == null || (!s.username().equals(who.id()) && !who.roles().contains(ADMIN_ROLE))) {
            throw new PravahaException(IdentityErrors.NOT_FOUND, "no session " + id + " that you may end");
        }
        end(id);
        record(who, "auth.session_terminated", id, true, "of " + s.username(), null);
    }

    // ------------------------------------------------------------------ helpers

    private Identities.User requireUser(String username) {
        Identities.User user = username == null ? null : store.users.get(username);
        if (user == null) {
            throw new PravahaException(IdentityErrors.NOT_FOUND, "no user named '" + username + "'");
        }
        return user;
    }

    private static void requireAdmin(Principal who) {
        if (who == null || !who.roles().contains(ADMIN_ROLE)) {
            throw new PravahaException(
                    SecurityErrors.FORBIDDEN, "administering users and keys needs the '" + ADMIN_ROLE + "' role");
        }
    }

    private static PravahaException refused() {
        return new PravahaException(IdentityErrors.CREDENTIALS_REFUSED, "that user name and password do not match");
    }

    private static Principal named(String username) {
        return new Principal(username == null ? "?" : username, "public", Set.of(), Map.of());
    }

    private static Principal system() {
        return new Principal("system", "public", Set.of(), Map.of());
    }

    private void record(Principal who, String action, String target, boolean allowed, String reason, String from) {
        audit.record(new AuditEvent(clock.instant(), who, action, target, allowed, reason, Optional.ofNullable(from)));
    }
}
