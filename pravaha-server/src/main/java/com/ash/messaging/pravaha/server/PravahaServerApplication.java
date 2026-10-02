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
 *
 * <p><strong>Spring opens no connection to a store.</strong> Every connector ships inside this jar
 * (2026-09-26), and Spring Boot auto-configures a client for any driver it finds on the classpath:
 * with the Cassandra driver present it built its own {@code CqlSession} to {@code localhost:9042}
 * at startup, plus a health check against it, and a node with no Cassandra beside it refused to
 * start. A connector's connections belong to its plugin, opened from {@code pravaha.sources} when
 * something binds it, and never by the framework because a jar happens to be present. Excluded by
 * name, so this class compiles without the auto-configuration classes and the exclusion holds when
 * they are absent. {@code FatJarStartupTest} boots this context with every connector on the
 * classpath and nothing to connect to.
 */
@SpringBootApplication(
        excludeName = {
            "org.springframework.boot.autoconfigure.cassandra.CassandraAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.cassandra.CassandraHealthContributorAutoConfiguration"
        })
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
     *
     * <p>API-F11: the OpenAPI document and the docs UI are open by design, and the filter used to
     * carry its own transcription of where they live. It is handed the configured paths instead, so
     * "which paths are open" and "which paths springdoc serves" are one answer.
     */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<
                    com.ash.messaging.pravaha.server.security.BearerTokenFilter>
            pravahaAuthentication(PravahaNode node, Environment environment) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<
                com.ash.messaging.pravaha.server.security.BearerTokenFilter>();
        com.ash.messaging.pravaha.security.TokenVerifier verifier = node.verifier();
        // Disabled rather than absent, and with a filter instance either way: a
        // FilterRegistrationBean holding no filter fails the servlet container at context refresh
        // with "'filter' must not be null", which MockMvc never reaches because it does not start
        // one. That bug shipped as far as the first run of the executable jar.
        registration.setFilter(new com.ash.messaging.pravaha.server.security.BearerTokenFilter(
                verifier == null ? com.ash.messaging.pravaha.security.TokenVerifier.rejectAll() : verifier,
                documentationPath(
                        environment,
                        "springdoc.api-docs.enabled",
                        "springdoc.api-docs.path",
                        com.ash.messaging.pravaha.server.security.BearerTokenFilter.DEFAULT_API_DOCS_PATH),
                documentationPath(
                        environment,
                        "springdoc.swagger-ui.enabled",
                        "springdoc.swagger-ui.path",
                        com.ash.messaging.pravaha.server.security.BearerTokenFilter.DEFAULT_SWAGGER_UI_PATH)));
        registration.setEnabled(verifier != null);
        registration.addUrlPatterns("/*");
        // Second: RequestLimitFilter runs first, so a body is bounded before anything reads it.
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    /**
     * Bounds every request's body, and the sign-ins running at once, ahead of everything else
     * (HTTPBODY-1). Registered whether or not authentication is on: an open node's bodies are
     * anonymous too.
     */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<
                    com.ash.messaging.pravaha.server.security.RequestLimitFilter>
            pravahaRequestLimits(
                    com.ash.messaging.pravaha.server.security.HttpLimitsProperties limits, Environment environment) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<
                com.ash.messaging.pravaha.server.security.RequestLimitFilter>();
        registration.setFilter(limits.filter(com.ash.messaging.pravaha.server.security.RequestLimitFilter.openPaths(
                documentationPath(
                        environment,
                        "springdoc.api-docs.enabled",
                        "springdoc.api-docs.path",
                        com.ash.messaging.pravaha.server.security.BearerTokenFilter.DEFAULT_API_DOCS_PATH),
                documentationPath(
                        environment,
                        "springdoc.swagger-ui.enabled",
                        "springdoc.swagger-ui.path",
                        com.ash.messaging.pravaha.server.security.BearerTokenFilter.DEFAULT_SWAGGER_UI_PATH))));
        registration.addUrlPatterns("/*");
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * Refuses a request body carrying a UTF-16 surrogate with no partner (API-F10).
     *
     * <p>A {@code Module} bean rather than a customised {@code ObjectMapper}: Spring Boot adds it
     * to the mapper it builds, so the rest of the mapper stays the auto-configured one and this is
     * one behaviour rather than a second configuration of everything.
     *
     * <p>{@link com.ash.messaging.pravaha.server.api.WellFormedTextModule} carries the reasoning,
     * including why refusing at the deserializer is the only place it can be done once.
     */
    @Bean
    public com.fasterxml.jackson.databind.Module pravahaWellFormedText() {
        return new com.ash.messaging.pravaha.server.api.WellFormedTextModule();
    }

    /**
     * Where springdoc serves one of its two documents, or null when the deployment turned it off.
     *
     * <p>Null rather than the default path, so an operator who disabled the UI does not leave an
     * unauthenticated opening onto the address it used to occupy.
     */
    private static String documentationPath(
            Environment environment, String enabledKey, String pathKey, String fallback) {
        if (!environment.getProperty(enabledKey, Boolean.class, Boolean.TRUE)) {
            return null;
        }
        return environment.getProperty(pathKey, fallback);
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
    public com.ash.messaging.pravaha.security.SecurityPolicy pravahaSecurityPolicy(PravahaNode node) {
        // CFG-21. One validator, in SecurityProperties, reached from its own @PostConstruct as well
        // as from here -- so the refusal an operator reads arrives while the properties bean is
        // being built rather than four Caused-by levels under a Tomcat startup failure.
        //
        // ADR-059. Taken from the node rather than built again from the same key: with the catalogue
        // on, the policy holds the grants, and a second instance would be a second catalogue that the
        // HTTP surface consulted and nothing else changed.
        return node.securityPolicy();
    }

    /**
     * Tags every metric this node publishes with the application's name.
     *
     * <p>CFG-19. The seven {@code pravaha_*} series carried a {@code query} label and nothing else,
     * so a fleet scraped into one Prometheus had no label distinguishing Pravaha's own series from
     * any other application's -- and no way to say "this node" either. {@code
     * spring.application.name} was set in the shipped {@code application.yaml} and reached no tag
     * and no endpoint.
     *
     * <p>{@code node} as well as {@code application}, because the question an operator actually
     * asks of a fleet is which node, and {@code pravaha.node.id} is the answer this project has
     * already chosen for it -- it is what names a state claim and what a member advertises (CFG-1).
     */
    @Bean
    public io.micrometer.core.instrument.config.MeterFilter pravahaCommonTags(Environment environment) {
        return io.micrometer.core.instrument.config.MeterFilter.commonTags(java.util.List.of(
                io.micrometer.core.instrument.Tag.of(
                        "application", environment.getProperty("spring.application.name", "pravaha")),
                io.micrometer.core.instrument.Tag.of(
                        "node", environment.getProperty("pravaha.node.id", "pravaha-node-01"))));
    }

    /**
     * The sink the HTTP surface records into -- the same object the engine and Flight use.
     *
     * <p>CFG-5. This returned {@code AuditSink.NONE} unconditionally, so {@code HttpAuthorizer} --
     * the only thing enforcing authorization on {@code /api/v1/**} -- discarded every decision it
     * made, whatever {@code pravaha.security.audit} said, while the Flight half of the same node
     * recorded correctly. Nothing at startup mentioned the difference and {@code docs/operations/SECURITY.md}
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

    /**
     * What {@code GET /actuator/info} says, which was {@code \{\}}.
     *
     * <p>CFG-19. {@code info} was on the shipped exposure list and the endpoint answered an empty
     * object, so the first place an operator looks to identify a node told them nothing -- while
     * {@code /api/v1/status} two paths away knew the version and the id. Contributed in code
     * rather than through {@code management.info.env.*} and a block of {@code info.*} keys,
     * because those are a second copy of facts this process already holds and can go stale against
     * them.
     */
    @Bean
    public org.springframework.boot.actuate.info.InfoContributor pravahaInfo(Environment environment) {
        String version = PravahaServerApplication.class.getPackage().getImplementationVersion();
        return builder -> builder.withDetail(
                "pravaha",
                java.util.Map.of(
                        "name", environment.getProperty("spring.application.name", "pravaha"),
                        "version", version == null ? "unknown" : version,
                        "node", environment.getProperty("pravaha.node.id", "pravaha-node-01")));
    }

    /**
     * Publishes the error shape in the contract, and says which operations answer with it.
     *
     * <p>CFG-20. {@code ApiDtos.ApiError} is the return type of every {@code @ExceptionHandler} and
     * of nothing a controller declares, so springdoc never walked it: the document either omitted
     * {@code components.schemas.ApiError} or carried it with zero properties, and a generated
     * client modelled every error as an empty object. The one schema a client is <em>guaranteed</em>
     * to meet was the one it could not see.
     *
     * <p>Added as a {@code default} response on every operation rather than a per-status list,
     * because the API's own rule is exactly that: any non-2xx, whatever its number, is an
     * {@code ApiError}. Enumerating statuses per endpoint would be a second copy of that rule, kept
     * by hand, wrong the first time an endpoint grows a refusal.
     */
    @Bean
    public org.springdoc.core.customizers.OpenApiCustomizer pravahaErrorShape() {
        return openApi -> {
            io.swagger.v3.oas.models.media.Schema<?> error = io.swagger.v3.core.converter.ModelConverters.getInstance()
                    .readAllAsResolvedSchema(com.ash.messaging.pravaha.server.api.ApiDtos.ApiError.class)
                    .schema;
            if (openApi.getComponents() == null) {
                openApi.setComponents(new io.swagger.v3.oas.models.Components());
            }
            openApi.getComponents().addSchemas("ApiError", error);
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths()
                    .values()
                    .forEach(path -> path.readOperations().forEach(operation -> {
                        if (operation.getResponses() == null
                                || operation.getResponses().getDefault() != null) {
                            return;
                        }
                        operation
                                .getResponses()
                                .setDefault(new io.swagger.v3.oas.models.responses.ApiResponse()
                                        .description("An ApiError. Every non-2xx response on this API is one.")
                                        .content(new io.swagger.v3.oas.models.media.Content()
                                                .addMediaType(
                                                        "application/json",
                                                        new io.swagger.v3.oas.models.media.MediaType()
                                                                .schema(new io.swagger.v3.oas.models.media.Schema<>()
                                                                        .$ref("#/components/schemas/ApiError")))));
                    }));
        };
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
