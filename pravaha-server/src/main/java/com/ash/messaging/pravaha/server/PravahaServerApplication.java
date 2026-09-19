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
package com.ash.messaging.pravaha.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.embedded.PravahaEngine;

/**
 * The engine node.
 *
 * <p>Spring Boot is a <strong>bootstrap layer</strong> here, not a layer inside the engine
 * (ADR-019). It owns the HTTP surface and the engine's lifecycle; the engine itself carries no
 * Spring, which is what lets the same engine be embedded in a host application on whatever Spring
 * version that application already runs.
 */
@SpringBootApplication
public class PravahaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PravahaServerApplication.class, args);
    }

    /**
     * The engine, as one bean.
     *
     * <p>Configuration is bound from Spring's environment into the engine's own immutable
     * {@code Configuration} type -- one configuration model, two ways of filling it, never two
     * sources of truth (design section 22.5).
     */
    @Bean
    public PravahaEngine pravahaEngine(Environment environment) {
        Configuration configuration = Configuration.builder()
                .set("pravaha.node.id", environment.getProperty("pravaha.node.id", "pravaha-node-01"))
                .build();
        return PravahaEngine.create(configuration);
    }

    /**
     * Authenticates the HTTP surface, when the deployment asked for authentication.
     *
     * <p>Registered only when there is a verifier. A filter that let every request through because
     * authentication was off would still be on the stack, one misread condition away from doing
     * nothing at all -- and "is this server authenticating?" should be answerable by whether the
     * filter is registered rather than by reading its body.
     */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<
                    com.ash.messaging.pravaha.server.security.BearerTokenFilter>
            pravahaAuthentication(com.ash.messaging.pravaha.server.security.SecurityProperties security) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<
                com.ash.messaging.pravaha.server.security.BearerTokenFilter>();
        com.ash.messaging.pravaha.security.TokenVerifier verifier = security.verifier();
        // Disabled rather than absent, and with a filter instance either way: a
        // FilterRegistrationBean holding no filter fails the servlet container at context refresh
        // with "'filter' must not be null", which MockMvc never reaches because it does not start
        // one. That bug shipped as far as the first run of the executable jar.
        registration.setFilter(new com.ash.messaging.pravaha.server.security.BearerTokenFilter(
                verifier == null ? com.ash.messaging.pravaha.security.TokenVerifier.rejectAll() : verifier));
        registration.setEnabled(verifier != null);
        registration.addUrlPatterns("/*");
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * Starts the engine after the web layer can serve health, and stops it before the web layer
     * goes away.
     *
     * <p>Ordered explicitly through {@link SmartLifecycle} rather than left to Spring's bean
     * destruction order: lanes must drain before the HTTP surface stops accepting, and bean order
     * does not express that.
     */
    /**
     * The policy the HTTP surface authorizes against, and the sink that records its decisions.
     *
     * <p>Built from the same configuration key the engine reads, so the two halves of the node
     * cannot disagree about who may see what. Until this existed, the REST controllers authorized
     * against nothing at all.
     */
    @Bean
    public com.ash.messaging.pravaha.security.SecurityPolicy pravahaSecurityPolicy(
            com.ash.messaging.pravaha.server.security.SecurityProperties security) {
        String configured = security.getPolicy() == null
                ? "permissive"
                : security.getPolicy().trim();
        return switch (configured.toLowerCase(java.util.Locale.ROOT)) {
            case "permissive" -> com.ash.messaging.pravaha.security.SecurityPolicy.PERMISSIVE;
            case "authenticated", "authenticated-only" ->
                new com.ash.messaging.pravaha.server.security.AuthenticatedOnlyPolicy(security.getAuditReaders());
            default ->
                throw new com.ash.messaging.pravaha.api.PravahaException(
                        com.ash.messaging.pravaha.security.SecurityErrors.MISCONFIGURED,
                        "pravaha.security.policy is '" + configured + "', which is not a policy this node "
                                + "knows. Use 'permissive' or 'authenticated', or implement SecurityPolicy "
                                + "for rules of your own.");
        };
    }

    /**
     * The sink the HTTP surface records into -- the same object the engine and Flight use.
     *
     * <p>CFG-5. This returned {@code AuditSink.NONE} unconditionally, so {@code HttpAuthorizer} --
     * the only thing enforcing authorization on {@code /api/v1/**} -- discarded every decision it
     * made, whatever {@code pravaha.security.audit} said, while the Flight half of the same node
     * recorded correctly. Nothing at startup mentioned the difference and {@code docs/SECURITY.md}
     * did not distinguish the two transports.
     *
     * <p>Taken from the node rather than resolved again from the same key, and that distinction is
     * the whole fix: {@code memory} builds an {@code InMemory} sink, so resolving twice would give
     * the HTTP surface a second one that nothing reads. The events would still be invisible and the
     * configuration would now look correct, which is worse than the bug it replaced.
     */
    @Bean
    public com.ash.messaging.pravaha.security.AuditSink pravahaAuditSink(PravahaNode node) {
        return node.auditSink();
    }

    @Bean
    public SmartLifecycle pravahaLifecycle(PravahaEngine engine) {
        return new SmartLifecycle() {
            @Override
            public int getPhase() {
                return Integer.MAX_VALUE - 1000;
            }

            @Override
            public void start() {
                engine.start();
            }

            @Override
            public void stop() {
                engine.stop();
            }

            @Override
            public boolean isRunning() {
                return engine.state() == com.ash.messaging.pravaha.api.EngineState.RUNNING;
            }
        };
    }
}
