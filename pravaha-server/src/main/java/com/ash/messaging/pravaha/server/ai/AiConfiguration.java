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

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.ash.messaging.pravaha.ai.DefaultLlmPolicy;
import com.ash.messaging.pravaha.ai.LlmGateway;
import com.ash.messaging.pravaha.ai.LlmPolicy;
import com.ash.messaging.pravaha.ai.LlmRegistry;
import com.ash.messaging.pravaha.ai.PolicySettings;
import com.ash.messaging.pravaha.ai.PromptMasker;
import com.ash.messaging.pravaha.ai.ProviderConfigLoader;
import com.ash.messaging.pravaha.ai.ProviderFactories;
import com.ash.messaging.pravaha.ai.llm.LLMFactory;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;

/**
 * Wires the AI layer (ADR-063), the one place outside {@code pravaha-ai} that names it: the registry (the
 * provider files in {@code pravaha.ai.dir}, read only while the layer is on), the policy (masking from
 * {@code pravaha.ai.policy.*}, one audit event per call into the node's own audit trail), the gateway every
 * caller goes through (behind the master switch), the canonical {@link LLMFactory}, and the watcher that
 * reloads the folder. Everything is off by default.
 */
@Configuration
public class AiConfiguration {

    /** The registry; empty while the layer is off, so no file is read. Keys resolve from the environment and the configuration. */
    @Bean(destroyMethod = "close")
    LlmRegistry llmRegistry(AiProperties ai, Environment env) {
        LlmRegistry registry = new LlmRegistry(
                ProviderFactories.discover(List.of()),
                () -> ai.isEnabled()
                        ? ProviderConfigLoader.load(Path.of(ai.getDir()), env::getProperty)
                        : new ProviderConfigLoader.Loaded(List.of(), Map.of()),
                ai.getDefaultModel());
        registry.reload();
        return registry;
    }

    /** The policy every call goes through; its audit rows go to the node's audit trail, never with the prompt or the answer. */
    @Bean
    LlmPolicy llmPolicy(AiProperties ai, AuditSink audit) {
        AiProperties.Policy p = ai.getPolicy();
        Map<String, Set<String>> roles = new HashMap<>();
        Map<String, Integer> perMin = new HashMap<>();
        p.getFeatures().forEach((f, v) -> {
            roles.put(f, new HashSet<>(v.getRoles()));
            Integer limit = v.getPerUserPerMinute();
            if (limit != null) {
                perMin.put(f, limit);
            }
        });
        PolicySettings ps = new PolicySettings(
                p.isMaskRedacted(),
                p.getMaskPatterns(),
                p.isAudit(),
                p.getPerUserPerMinute(),
                p.getPerUserPerDay(),
                p.getCostPerUserPerDay(),
                roles,
                perMin);
        return new DefaultLlmPolicy(
                ps,
                new PromptMasker(p.getRedact(), p.getMaskPatterns()),
                a -> audit.record(event(a)),
                Clock.systemUTC());
    }

    /** One audit event of a call: who, which feature, which model, sizes, tokens, cost, outcome. */
    static AuditEvent event(DefaultLlmPolicy.AuditRecord a) {
        boolean refused = "policy".equals(a.outcome()) || "rate_limited".equals(a.outcome());
        String detail = "feature=" + a.feature() + " promptChars=" + a.promptChars() + " answerChars=" + a.answerChars()
                + " promptTokens=" + a.promptTokens() + " completionTokens=" + a.completionTokens() + " cost="
                + String.format(Locale.ROOT, "%.6f", a.cost()) + " ms=" + a.millis();
        return new AuditEvent(
                Instant.now(),
                Principal.of(a.user()),
                "ai.call",
                a.provider() + "/" + a.model(),
                !refused,
                a.outcome(),
                Optional.of(detail));
    }

    @Bean
    LlmGateway llmGateway(LlmRegistry registry, LlmPolicy policy, AiProperties ai) {
        return new LlmGateway(registry, policy, ai::isEnabled);
    }

    /** The canonical door: {@code LLMModel}s with the policy applied, over the same gateway. */
    @Bean
    LLMFactory llmFactory(LlmGateway gateway) {
        return new LLMFactory(gateway, null);
    }

    /** Looks at the folder every {@code pravaha.ai.reload-interval} while the layer is on; stopped with the application. */
    @Bean(destroyMethod = "close")
    AiFolderWatch aiFolderWatch(AiProperties ai, LlmRegistry registry) {
        AiFolderWatch watch = new AiFolderWatch(Path.of(ai.getDir()), registry);
        if (ai.isEnabled()) {
            watch.start(ai.getReloadInterval());
        }
        return watch;
    }
}
