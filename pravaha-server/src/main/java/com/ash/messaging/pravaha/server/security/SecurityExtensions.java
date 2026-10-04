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
package com.ash.messaging.pravaha.server.security;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.ListableBeanFactory;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.TokenVerifier;

/**
 * A {@link SecurityPolicy}, {@link TokenVerifier} or {@link AuditSink} of the deployment's own, which
 * the node uses in place of the one its settings would build (POLICYPLUG-1).
 *
 * <p>On {@code pravaha-server} each is a Spring bean of that type in the application context -- a
 * {@code @Bean} method or a component in a jar on the classpath that the application scans. The node's
 * own beans of the same types ({@code pravahaSecurityPolicy}, {@code pravahaAuditSink}) are left out of
 * the search, and are {@code @Primary}, so everything that injects a policy or a sink by type still gets
 * the node's one object -- which, with an extension, is the extension (the sink wrapped in the readable
 * trail). Until this existed the node's refusal of an unknown {@code pravaha.security.policy} said "or
 * implement SecurityPolicy" and nothing on a node would ever have used one.
 *
 * <p>Resolved when the node first asks, not when this is made, so a deployment's bean may depend on
 * whatever it likes without a cycle through the node. Two beans of one type are refused by name with
 * {@code PRV-7004}: which one the node should trust is not a guess to make for the operator.
 */
public final class SecurityExtensions {

    /** No extensions: everything from the settings. */
    public static final SecurityExtensions NONE = of(null, null, null);

    /** The node's own beans, which are what an extension replaces and never an extension themselves. */
    public static final Set<String> NODE_BEANS = Set.of("pravahaSecurityPolicy", "pravahaAuditSink");

    private final Supplier<Optional<SecurityPolicy>> policy;
    private final Supplier<Optional<TokenVerifier>> verifier;
    private final Supplier<Optional<AuditSink>> audit;

    private SecurityExtensions(
            Supplier<Optional<SecurityPolicy>> policy,
            Supplier<Optional<TokenVerifier>> verifier,
            Supplier<Optional<AuditSink>> audit) {
        this.policy = policy;
        this.verifier = verifier;
        this.audit = audit;
    }

    /** Extensions given directly: a node built without Spring, and tests. Each may be null. */
    public static SecurityExtensions of(SecurityPolicy policy, TokenVerifier verifier, AuditSink audit) {
        Optional<SecurityPolicy> p = Optional.ofNullable(policy);
        Optional<TokenVerifier> v = Optional.ofNullable(verifier);
        Optional<AuditSink> a = Optional.ofNullable(audit);
        return new SecurityExtensions(() -> p, () -> v, () -> a);
    }

    /** The beans of each type in {@code beans}, apart from the node's own. */
    public static SecurityExtensions from(ListableBeanFactory beans) {
        return new SecurityExtensions(
                memoized(() -> single(beans, SecurityPolicy.class)),
                memoized(() -> single(beans, TokenVerifier.class)),
                memoized(() -> single(beans, AuditSink.class)));
    }

    /** The deployment's policy, which replaces {@code pravaha.security.policy}. */
    public Optional<SecurityPolicy> policy() {
        return policy.get();
    }

    /** The deployment's verifier, which replaces {@code pravaha.security.tokens} under {@code authentication: token}. */
    public Optional<TokenVerifier> verifier() {
        return verifier.get();
    }

    /** The deployment's durable sink, which replaces {@code pravaha.security.audit}. */
    public Optional<AuditSink> audit() {
        return audit.get();
    }

    /** What the startup line says the policy is: the setting's name, or the extension's class. */
    public String describePolicy(String configured) {
        return policy().map(custom -> "custom (" + custom.getClass().getName() + ")")
                .orElse(configured);
    }

    private static <T> Optional<T> single(ListableBeanFactory beans, Class<T> type) {
        List<String> names = Arrays.stream(beans.getBeanNamesForType(type, true, false))
                .filter(name -> !NODE_BEANS.contains(name))
                .toList();
        if (names.size() > 1) {
            throw new PravahaException(
                    SecurityErrors.MISCONFIGURED,
                    "the application context has " + names.size() + " " + type.getSimpleName() + " beans "
                            + names + ", and a node uses one in place of the one its settings build. Keep one, "
                            + "or compose them into one bean of your own.");
        }
        return names.isEmpty() ? Optional.empty() : Optional.of(beans.getBean(names.get(0), type));
    }

    private static <T> Supplier<Optional<T>> memoized(Supplier<Optional<T>> resolve) {
        return new Supplier<>() {
            private Optional<T> resolved;

            @Override
            public synchronized Optional<T> get() {
                if (resolved == null) {
                    resolved = resolve.get();
                }
                return resolved;
            }
        };
    }
}
