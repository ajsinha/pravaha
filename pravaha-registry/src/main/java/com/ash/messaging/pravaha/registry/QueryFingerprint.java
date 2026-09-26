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
package com.ash.messaging.pravaha.registry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

/**
 * What makes two registrations the same computation (ADR-025).
 *
 * <p>The fingerprint is the <em>normalised plan</em>, not the SQL text. Two people asking the same
 * question will not type it the same way -- different aliases, different whitespace, the operands of
 * an AND in a different order -- and hashing the text would give them separate computations with
 * separate state, which is the read amplification the whole architecture exists to avoid. Hashing
 * the plan means the engine notices they asked the same thing.
 *
 * <p>It includes the <strong>security predicates</strong> applied to the plan, and that inclusion is
 * what makes sharing safe rather than merely efficient. Two principals with different entitlements
 * produce different plans and therefore different fingerprints, so a shared computation can never
 * serve one principal rows that were filtered for another. Nobody has to remember the rule; it falls
 * out of what is hashed.
 */
public record QueryFingerprint(String value) {

    public QueryFingerprint {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a fingerprint needs a value");
        }
    }

    /**
     * The fingerprint of a planned query.
     *
     * @param plan the physical plan, after any security predicate has been injected
     * @param rowFilters the security predicates applied, in the order applied. Included separately
     *     as well as being in the plan, so that a future change to plan rendering cannot silently
     *     stop distinguishing two principals
     */
    public static QueryFingerprint of(PhysicalOperator plan, List<String> rowFilters) {
        return of(plan, rowFilters, List.of(), null);
    }

    /**
     * The identity of a computation: its plan, the row filters applied to it, its key columns and
     * its retention.
     *
     * <p>I-3. The last two were missing, and a fingerprint that omits them says two registrations
     * are the same question when they are not. {@code --keys 1} and {@code --keys 0,1} over
     * identical SQL produced one fingerprint, so the second registrant was handed the first one's
     * view -- <strong>keyed the way the first asked, with no error</strong>. A view keyed on a
     * different column conflates different rows together, so the second caller's answers were wrong
     * rather than merely surprising. The same path discarded the second registrant's retention and
     * skipped the key-ordinal bounds check, because both live in {@code start(...)} and sharing
     * returns before it.
     *
     * <p>Key columns are <em>not</em> sorted. {@code --keys 0,1} and {@code --keys 1,0} are
     * different views: the ordinals are a tuple in the order given, and a subscriber conflates on
     * that order.
     *
     * <p>A null retention is the caller saying it has none to declare, which is what the
     * two-argument form means; it is distinct from an explicit {@link Retention#forever()}.
     */
    public static QueryFingerprint of(
            PhysicalOperator plan, List<String> rowFilters, List<Integer> keyColumns, Retention retention) {
        return of(plan, rowFilters, keyColumns, retention, null);
    }

    /**
     * The identity of a computation within one tenant (ADR-050).
     *
     * <p>The tenant is in the fingerprint for the reason the row filters are: a computation shared
     * across tenants could not be charged to either, and a pause, a replacement or a failure of one
     * tenant's name would reach the other's. Identical SQL still shares one computation among the
     * principals of one tenant. A null tenant is the caller declaring none, as the four-argument
     * form does, and is distinct from every named tenant.
     */
    public static QueryFingerprint of(
            PhysicalOperator plan,
            List<String> rowFilters,
            List<Integer> keyColumns,
            Retention retention,
            String tenant) {
        StringBuilder canonical = new StringBuilder(PhysicalPlanBuilder.explain(plan));
        if (tenant != null) {
            // Length-prefixed, because a tenant comes from an identity provider and could carry a
            // newline: unprefixed, tenant "t\nsecurity:p" would hash as tenant "t" with row filter p.
            canonical.append("\ntenant:").append(tenant.length()).append(':').append(tenant);
        }
        for (String filter : rowFilters) {
            canonical.append("\nsecurity:").append(filter);
        }
        canonical.append("\nkeys:").append(keyColumns);
        canonical.append("\nretention:").append(retention == null ? "unspecified" : retention.maxAge());
        return new QueryFingerprint(digest(canonical.toString()));
    }

    public static QueryFingerprint of(PhysicalOperator plan) {
        return of(plan, List.of());
    }

    /** A short form for logs and names. Long enough that a collision is not the explanation. */
    public String shortForm() {
        return value.substring(0, 12);
    }

    private static String digest(String canonical) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    /** Whether this is the same computation as another. */
    public boolean matches(Optional<QueryFingerprint> other) {
        return other.isPresent() && other.get().value().equals(value);
    }

    @Override
    public String toString() {
        return shortForm();
    }
}
