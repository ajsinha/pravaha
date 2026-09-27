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
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.api.PravahaException;
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
 *     mode: password            # password | sso | hybrid; sso only when a provider is configured
 *     password:
 *       force-change: false     # first sign-in and admin resets must change the password
 * </pre>
 *
 * <p>Off by default until the migration stage, so a node configured before ADR-052 starts exactly as
 * it did. An unknown key is refused at startup: a misspelt policy value is a rule the operator believes
 * is in force.
 */
@Component
@ConfigurationProperties(prefix = "pravaha.identity", ignoreUnknownFields = false)
public class IdentityProperties {

    private static final Set<String> MODES = Set.of("password", "sso", "hybrid");

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

    private IdentityService service;

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

    public Duration getResetTokenLife() {
        return resetTokenLife;
    }

    public void setResetTokenLife(Duration resetTokenLife) {
        this.resetTokenLife = resetTokenLife;
    }

    /**
     * How people sign in, as it will actually be applied: {@code sso} and {@code hybrid} need an
     * identity provider, and with none configured the node signs people in with passwords.
     */
    public String effectiveMode() {
        String asked = mode == null ? "password" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!MODES.contains(asked)) {
            throw new PravahaException(
                    SecurityErrors.MISCONFIGURED,
                    "pravaha.identity.mode is '" + mode + "'; it is password, sso or hybrid");
        }
        // No provider can be configured until SSO lands (ADR-052 stage 5), so it is always password.
        return "password";
    }

    /** The engine's form of these settings, refused with {@code PRV-7004} for an impossible value. */
    public IdentitySettings settings() {
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
            throw new PravahaException(SecurityErrors.MISCONFIGURED, wrong.getMessage());
        }
    }

    /** The node's one identity service, opened on first use; empty when identity is off. */
    public synchronized java.util.Optional<IdentityService> service(AuditSink audit) {
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
            service = IdentityService.open(Path.of(store), settings(), audit, this::bootstrapPassword);
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
        if (!"password".equals(mode.trim())) {
            log.warn(
                    "pravaha.identity.mode is {} and no identity provider is configured, so people sign in with "
                            + "passwords",
                    mode);
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
    private String bootstrapPassword() {
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
