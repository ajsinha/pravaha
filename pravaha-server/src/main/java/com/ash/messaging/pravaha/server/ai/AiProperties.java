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
package com.ash.messaging.pravaha.server.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code pravaha.ai.*}: the AI layer (ADR-063, docs/design/architecture/ai.md). <b>Off by default</b>:
 * while {@code enabled} is false (or {@code PRAVAHA_AI_ENABLED} is not {@code true}) no file in
 * {@code dir} is read, no model is called, and every AI call is refused with {@code PRV-1080}.
 */
@Component
@ConfigurationProperties(prefix = "pravaha.ai")
public class AiProperties {

    private boolean enabled = false;
    private String dir = "config/ai";
    private String defaultModel = "";
    private Duration reloadInterval = Duration.ofSeconds(5);
    private int testPerMinute = 6;
    private Policy policy = new Policy();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** The folder of provider files, one {@code <name>.yaml} per provider. */
    public String getDir() {
        return dir;
    }

    public void setDir(@Nullable String dir) {
        this.dir = dir == null || dir.isBlank() ? "config/ai" : dir.strip();
    }

    /** The logical model a call that names none gets ({@code provider/model} or a unique model name). */
    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(@Nullable String defaultModel) {
        this.defaultModel = defaultModel == null ? "" : defaultModel.strip();
    }

    /** How often the folder is looked at for added, changed and removed files, and failed providers retried. */
    public Duration getReloadInterval() {
        return reloadInterval;
    }

    public void setReloadInterval(@Nullable Duration reloadInterval) {
        this.reloadInterval = reloadInterval == null || reloadInterval.isNegative() || reloadInterval.isZero()
                ? Duration.ofSeconds(5)
                : reloadInterval;
    }

    /** How many test connections one administrator may make per minute ({@code POST /api/v1/admin/ai/test}). */
    public int getTestPerMinute() {
        return testPerMinute;
    }

    public void setTestPerMinute(int testPerMinute) {
        this.testPerMinute = Math.max(1, testPerMinute);
    }

    public Policy getPolicy() {
        return policy;
    }

    public void setPolicy(@Nullable Policy policy) {
        this.policy = policy == null ? new Policy() : policy;
    }

    /** {@code pravaha.ai.policy.*}: the hooks around every call. A limit of 0 means none. */
    public static class Policy {
        private boolean maskRedacted = true;
        private List<String> redact = new ArrayList<>();
        private List<String> maskPatterns = new ArrayList<>();
        private boolean audit = true;
        private int perUserPerMinute;
        private int perUserPerDay;
        private double costPerUserPerDay;
        private Map<String, Feature> features = new LinkedHashMap<>();

        public boolean isMaskRedacted() {
            return maskRedacted;
        }

        public void setMaskRedacted(boolean maskRedacted) {
            this.maskRedacted = maskRedacted;
        }

        /** Field names whose values are masked in every prompt ({@code name: value}, {@code name=value}, {@code "name":"value"}). */
        public List<String> getRedact() {
            return redact;
        }

        public void setRedact(@Nullable List<String> redact) {
            this.redact = redact == null ? new ArrayList<>() : new ArrayList<>(redact);
        }

        /** Regular expressions whose matches are masked in every prompt. */
        public List<String> getMaskPatterns() {
            return maskPatterns;
        }

        public void setMaskPatterns(@Nullable List<String> maskPatterns) {
            this.maskPatterns = maskPatterns == null ? new ArrayList<>() : new ArrayList<>(maskPatterns);
        }

        public boolean isAudit() {
            return audit;
        }

        public void setAudit(boolean audit) {
            this.audit = audit;
        }

        public int getPerUserPerMinute() {
            return perUserPerMinute;
        }

        public void setPerUserPerMinute(int perUserPerMinute) {
            this.perUserPerMinute = Math.max(0, perUserPerMinute);
        }

        public int getPerUserPerDay() {
            return perUserPerDay;
        }

        public void setPerUserPerDay(int perUserPerDay) {
            this.perUserPerDay = Math.max(0, perUserPerDay);
        }

        public double getCostPerUserPerDay() {
            return costPerUserPerDay;
        }

        public void setCostPerUserPerDay(double costPerUserPerDay) {
            this.costPerUserPerDay = Math.max(0, costPerUserPerDay);
        }

        /** Per-feature rules, by feature ({@code api:<name>} for the REST endpoint's callers). */
        public Map<String, Feature> getFeatures() {
            return features;
        }

        public void setFeatures(@Nullable Map<String, Feature> features) {
            this.features = features == null ? new LinkedHashMap<>() : new LinkedHashMap<>(features);
        }
    }

    /** One feature's rules: the roles that may use it (empty: everyone signed in) and its own per-minute limit. */
    public static class Feature {
        private List<String> roles = new ArrayList<>();
        private @Nullable Integer perUserPerMinute;

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(@Nullable List<String> roles) {
            this.roles = roles == null ? new ArrayList<>() : new ArrayList<>(roles);
        }

        public @Nullable Integer getPerUserPerMinute() {
            return perUserPerMinute;
        }

        public void setPerUserPerMinute(@Nullable Integer perUserPerMinute) {
            this.perUserPerMinute = perUserPerMinute;
        }
    }
}
