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
package com.ash.messaging.pravaha.server.identity;

import java.nio.file.Path;
import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.identity.CaptiveUsers;
import com.ash.messaging.pravaha.identity.IdentityService;
import com.ash.messaging.pravaha.identity.IdentitySettings;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.SecurityErrors;

/**
 * {@code pravaha.identity.*}: users, passwords, API keys and sessions kept by this node (ADR-052).
 *
 * <pre>
 * pravaha:
 *   identity:
 *     enabled: true
 *     store: /opt/pravaha/data/identity/identity.journal
 *     environment: qa
 *     mode: password            # password, the only value: SSO is not built (SSOMODE-1)
 *     password:
 *       force-change: false     # first sign-in and admin resets must change the password
 *     captive-users:            # accounts the node ensures at every start (see CaptiveUsers)
 *       - username: ops-admin
 *         roles: [admin]
 *         password-env: PRAVAHA_OPS_ADMIN_PASSWORD   # or password-file, or (dev only) password
 * </pre>
 *
 * <p>Off by default until the migration stage, so a node configured before ADR-052 starts exactly as
 * it did. An unknown key is refused at startup: a misspelt policy value is a rule the operator believes
 * is in force.
 */
@Component
@ConfigurationProperties(prefix = "pravaha.identity", ignoreUnknownFields = false)
public class IdentityProperties {

    private boolean enabled;
    private String store = "";
    private String environment = "dev";
    private String mode = "password";
    private boolean dev;
    private boolean allowDefaultAdminPassword;
    private final Password password = new Password();
    private final Lockout lockout = new Lockout();
    private final Session session = new Session();
    private final Key key = new Key();
    private Duration resetTokenLife = Duration.ofMinutes(60);
    private String bootstrapPasswordFile = "";
    private java.util.List<CaptiveUser> captiveUsers = new java.util.ArrayList<>();
    private boolean allowLiteralCaptivePasswords;

    private @Nullable IdentityService service;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public boolean isDev() {
        return dev;
    }

    public void setDev(boolean dev) {
        this.dev = dev;
    }

    public boolean isAllowDefaultAdminPassword() {
        return allowDefaultAdminPassword;
    }

    public void setAllowDefaultAdminPassword(boolean allow) {
        this.allowDefaultAdminPassword = allow;
    }

    public Password getPassword() {
        return password;
    }

    public Lockout getLockout() {
        return lockout;
    }

    public Session getSession() {
        return session;
    }

    public Key getKey() {
        return key;
    }

    public String getBootstrapPasswordFile() {
        return bootstrapPasswordFile;
    }

    public void setBootstrapPasswordFile(String bootstrapPasswordFile) {
        this.bootstrapPasswordFile = bootstrapPasswordFile;
    }

    public java.util.List<CaptiveUser> getCaptiveUsers() {
        return captiveUsers;
    }

    public void setCaptiveUsers(java.util.List<CaptiveUser> captiveUsers) {
        this.captiveUsers = captiveUsers == null ? new java.util.ArrayList<>() : captiveUsers;
    }

    public boolean isAllowLiteralCaptivePasswords() {
        return allowLiteralCaptivePasswords;
    }

    public void setAllowLiteralCaptivePasswords(boolean allow) {
        this.allowLiteralCaptivePasswords = allow;
    }

    /**
     * {@code pravaha.identity.captive-users} validated and with their passwords read, or {@code PRV-7022},
     * before the store is opened.
     */
    public java.util.List<CaptiveUsers.Account> captiveAccounts() {
        return CaptiveUsers.resolve(
                captiveUsers.stream().map(CaptiveUser::entry).toList(),
                settings(),
                allowLiteralCaptivePasswords,
                System::getenv);
    }

    public Duration getResetTokenLife() {
        return resetTokenLife;
    }

    public void setResetTokenLife(Duration resetTokenLife) {
        this.resetTokenLife = resetTokenLife;
    }

    /**
     * How people sign in: {@code password}, the only mechanism this node has.
     *
     * <p>{@code sso} and {@code hybrid} were accepted and then ignored (SSOMODE-1): no identity
     * provider can be configured -- ADR-052's stage 5 was dropped by the owner on 2026-09-27 -- so a
     * node set to either signed everyone in with passwords while its configuration said otherwise,
     * and only a startup warning admitted it. A setting that promises a mechanism the node does not
     * have is a rule the operator believes is in force, which is what this class refuses at start for
     * every other key. So both are refused, by name, before the node serves anything.
     *
     * @throws PravahaException {@code PRV-7004} for any value but {@code password}
     */
    public String effectiveMode() {
        String asked = mode == null ? "password" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if ("password".equals(asked)) {
            return "password";
        }
        if ("sso".equals(asked) || "hybrid".equals(asked)) {
            throw new PravahaException(
                    SecurityErrors.MISCONFIGURED,
                    "pravaha.identity.mode is '" + mode + "', and the only accepted value is password: single "
                            + "sign-on is not built, so no identity provider can be configured and this node would "
                            + "sign everyone in with passwords whatever the setting said. Remove the setting or set "
                            + "it to password.");
        }
        throw new PravahaException(
                SecurityErrors.MISCONFIGURED,
                "pravaha.identity.mode is '" + mode + "'; the only accepted value is password");
    }

    /**
     * Whose address a sign-in is counted against (LOCKENUM-1), from {@code
     * pravaha.identity.lockout.trusted-proxies}; a malformed entry is {@code PRV-7004}.
     */
    public SignInSource signInSource() {
        return new SignInSource(lockout.trustedProxies);
    }

    /** The engine's form of these settings, refused with {@code PRV-7004} for an impossible value. */
    public IdentitySettings settings() {
        signInSource(); // refused at start, not at the first sign-in through a proxy
        try {
            return new IdentitySettings(
                    environment,
                    password.minLength,
                    password.requireClasses,
                    password.history,
                    password.maxAge,
                    password.forceChange,
                    lockout.failures,
                    lockout.window,
                    lockout.duration,
                    session.idle,
                    session.absolute,
                    session.perUser,
                    key.defaultDays,
                    key.maxDays,
                    key.rotationOverlap,
                    resetTokenLife,
                    allowDefaultAdminPassword,
                    dev);
        } catch (IllegalArgumentException wrong) {
            throw new PravahaException(
                    SecurityErrors.MISCONFIGURED,
                    java.util.Objects.requireNonNullElse(wrong.getMessage(), wrong.toString()));
        }
    }

    /**
     * The node's one identity service, opened on first use; empty when identity is off.
     *
     * <p>Checks {@code pravaha.identity.mode} first, whether identity is on or not: the node asks for
     * this at start either way, and a mode it cannot honour is refused there (SSOMODE-1) rather than
     * left in the file to be believed later, when somebody turns identity on.
     */
    public synchronized java.util.Optional<IdentityService> service(AuditSink audit) {
        effectiveMode();
        if (!enabled) {
            return java.util.Optional.empty();
        }
        if (service == null) {
            if (store == null || store.isBlank()) {
                throw new PravahaException(
                        SecurityErrors.MISCONFIGURED,
                        "pravaha.identity.enabled is true and "
                                + "pravaha.identity.store is not set; it is the file every user and key is kept in, on "
                                + "the data volume that is backed up");
            }
            java.util.List<CaptiveUsers.Account> captive = captiveAccounts();
            IdentityService opened = IdentityService.open(Path.of(store), settings(), audit, this::bootstrapPassword);
            opened.ensureCaptive(captive);
            service = opened;
        }
        return java.util.Optional.of(service);
    }

    /**
     * Refuses a node still on the bootstrap admin's published password outside dev (PRV-7019), then says
     * once at startup how this node signs people in.
     */
    public void announce(IdentityService users, org.slf4j.Logger log) {
        users.requireStartable();
        log.info(
                "identity: users, API keys and sessions kept in {} (environment {}, sign-in by {}, forced "
                        + "password change {})",
                store,
                environment,
                effectiveMode(),
                password.isForceChange() ? "on" : "off");
        if (!captiveUsers.isEmpty()) {
            log.info(
                    "identity: captive users {} ensured from pravaha.identity.captive-users; they cannot be disabled, "
                            + "re-roled or re-passworded at runtime",
                    captiveUsers.stream().map(CaptiveUser::getUsername).toList());
        }
        if (users.defaultAdminPasswordInUse()) {
            log.warn("identity: the user 'admin' still has the default password; change it before anyone else can "
                    + "reach this node");
        }
    }

    /**
     * The initial password for {@code admin}, read from {@code bootstrap-password-file} when there is one
     * -- asked for only when the store is empty, so the file can be deleted once the node has started.
     * Null means the published default, which a node outside dev refuses to keep (PRV-7019).
     */
    private @Nullable String bootstrapPassword() {
        if (bootstrapPasswordFile == null || bootstrapPasswordFile.isBlank()) {
            return null;
        }
        Path file = Path.of(bootstrapPasswordFile);
        try {
            String password = java.nio.file.Files.readString(file).strip();
            if (password.isEmpty()) {
                throw new PravahaException(
                        SecurityErrors.MISCONFIGURED,
                        "pravaha.identity.bootstrap-password-file " + file
                                + " is empty; it holds the password the first administrator is created with");
            }
            return password;
        } catch (java.io.IOException e) {
            throw new PravahaException(
                    SecurityErrors.MISCONFIGURED,
                    "pravaha.identity.bootstrap-password-file names "
                            + file + ", which cannot be read (" + e.getMessage()
                            + "); the identity store is empty, so this "
                            + "is the password the first administrator would be created with",
                    e);
        }
    }

    /**
     * One {@code pravaha.identity.captive-users} entry: {@code username}, {@code roles}, optional {@code
     * display-name}, {@code tenant} and {@code email}, and one password source -- {@code password} (dev
     * only, or with {@code allow-literal-captive-passwords}), {@code password-env} or {@code password-file}.
     */
    public static class CaptiveUser {
        private @Nullable String username;
        private @Nullable String displayName;
        private @Nullable String email;
        private @Nullable String tenant;
        private java.util.List<String> roles = new java.util.ArrayList<>();
        private @Nullable String password;
        private @Nullable String passwordEnv;
        private @Nullable String passwordFile;

        CaptiveUsers.Entry entry() {
            return new CaptiveUsers.Entry(
                    username, displayName, email, tenant, roles, password, passwordEnv, passwordFile);
        }

        public @Nullable String getUsername() {
            return username;
        }

        public void setUsername(@Nullable String username) {
            this.username = username;
        }

        public @Nullable String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(@Nullable String displayName) {
            this.displayName = displayName;
        }

        public @Nullable String getEmail() {
            return email;
        }

        public void setEmail(@Nullable String email) {
            this.email = email;
        }

        public @Nullable String getTenant() {
            return tenant;
        }

        public void setTenant(@Nullable String tenant) {
            this.tenant = tenant;
        }

        public java.util.List<String> getRoles() {
            return roles;
        }

        public void setRoles(java.util.List<String> roles) {
            this.roles = roles == null ? new java.util.ArrayList<>() : roles;
        }

        public @Nullable String getPassword() {
            return password;
        }

        public void setPassword(@Nullable String password) {
            this.password = password;
        }

        public @Nullable String getPasswordEnv() {
            return passwordEnv;
        }

        public void setPasswordEnv(@Nullable String passwordEnv) {
            this.passwordEnv = passwordEnv;
        }

        public @Nullable String getPasswordFile() {
            return passwordFile;
        }

        public void setPasswordFile(@Nullable String passwordFile) {
            this.passwordFile = passwordFile;
        }

        @Override
        public String toString() {
            return "CaptiveUser[" + username + "]"; // never the password
        }
    }

    /** {@code pravaha.identity.password.*}. */
    public static class Password {
        private int minLength = 12;
        private int requireClasses = 3;
        private int history = 5;
        private Duration maxAge = Duration.ofDays(90);
        private boolean forceChange;

        public int getMinLength() {
            return minLength;
        }

        public void setMinLength(int minLength) {
            this.minLength = minLength;
        }

        public int getRequireClasses() {
            return requireClasses;
        }

        public void setRequireClasses(int requireClasses) {
            this.requireClasses = requireClasses;
        }

        public int getHistory() {
            return history;
        }

        public void setHistory(int history) {
            this.history = history;
        }

        public Duration getMaxAge() {
            return maxAge;
        }

        public void setMaxAge(Duration maxAge) {
            this.maxAge = maxAge;
        }

        public boolean isForceChange() {
            return forceChange;
        }

        public void setForceChange(boolean forceChange) {
            this.forceChange = forceChange;
        }
    }

    /** {@code pravaha.identity.lockout.*}. */
    public static class Lockout {
        private int failures = 5;
        private Duration window = Duration.ofMinutes(15);
        private Duration duration = Duration.ofMinutes(30);
        private java.util.List<String> trustedProxies = new java.util.ArrayList<>();

        /**
         * Proxies -- the console, a load balancer -- whose {@code X-Forwarded-For} names the address a
         * sign-in is counted against; empty (the default) believes nobody's.
         */
        public java.util.List<String> getTrustedProxies() {
            return trustedProxies;
        }

        public void setTrustedProxies(java.util.List<String> trustedProxies) {
            this.trustedProxies = trustedProxies == null ? new java.util.ArrayList<>() : trustedProxies;
        }

        public int getFailures() {
            return failures;
        }

        public void setFailures(int failures) {
            this.failures = failures;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public Duration getDuration() {
            return duration;
        }

        public void setDuration(Duration duration) {
            this.duration = duration;
        }
    }

    /** {@code pravaha.identity.session.*}. */
    public static class Session {
        private Duration idle = Duration.ofMinutes(30);
        private Duration absolute = Duration.ofHours(12);
        private int perUser = 3;

        public Duration getIdle() {
            return idle;
        }

        public void setIdle(Duration idle) {
            this.idle = idle;
        }

        public Duration getAbsolute() {
            return absolute;
        }

        public void setAbsolute(Duration absolute) {
            this.absolute = absolute;
        }

        public int getPerUser() {
            return perUser;
        }

        public void setPerUser(int perUser) {
            this.perUser = perUser;
        }
    }

    /** {@code pravaha.identity.key.*}. */
    public static class Key {
        private int defaultDays = 90;
        private int maxDays = 365;
        private Duration rotationOverlap = Duration.ofDays(7);

        public int getDefaultDays() {
            return defaultDays;
        }

        public void setDefaultDays(int defaultDays) {
            this.defaultDays = defaultDays;
        }

        public int getMaxDays() {
            return maxDays;
        }

        public void setMaxDays(int maxDays) {
            this.maxDays = maxDays;
        }

        public Duration getRotationOverlap() {
            return rotationOverlap;
        }

        public void setRotationOverlap(Duration rotationOverlap) {
            this.rotationOverlap = rotationOverlap;
        }
    }
}
