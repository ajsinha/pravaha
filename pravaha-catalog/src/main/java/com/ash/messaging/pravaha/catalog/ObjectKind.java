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
package com.ash.messaging.pravaha.catalog;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * What a governed object is (ADR-059 §1).
 *
 * <p>A registered continuous query and the view it keeps are one object with two facets; it is kept
 * as {@link #VIEW}, and {@code ON QUERY} in a statement names the same object. {@link #ALERT} and
 * {@link #POLICY} are reserved for the phases that build them: nothing creates one yet.
 */
public enum ObjectKind {
    NAMESPACE(EnumSet.allOf(Privilege.class)),
    STREAM(EnumSet.of(Privilege.SELECT, Privilege.BUILD_ON, Privilege.MODIFY, Privilege.MANAGE, Privilege.OWN)),
    SOURCE(EnumSet.of(Privilege.MANAGE, Privilege.OWN)),
    SINK(EnumSet.of(Privilege.WRITE, Privilege.MANAGE, Privilege.OWN)),
    /** A notifier channel an alert may name in {@code NOTIFY} (ADR-057): {@code pravaha.notifiers.*}. */
    NOTIFIER(EnumSet.of(Privilege.WRITE, Privilege.MANAGE, Privilege.OWN)),
    VIEW(EnumSet.of(
            Privilege.SELECT,
            Privilege.SUBSCRIBE,
            Privilege.BUILD_ON,
            Privilege.MODIFY,
            Privilege.MANAGE,
            Privilege.OWN)),
    LOOKUP(EnumSet.of(Privilege.SELECT, Privilege.BUILD_ON, Privilege.MANAGE, Privilege.OWN)),
    ALERT(EnumSet.of(Privilege.SELECT, Privilege.MODIFY, Privilege.MANAGE, Privilege.OWN)),
    POLICY(EnumSet.of(Privilege.MANAGE, Privilege.OWN));

    private final Set<Privilege> applicable;

    ObjectKind(Set<Privilege> applicable) {
        this.applicable = Set.copyOf(applicable);
    }

    /**
     * The privileges that mean something on this kind. Everything applies to a namespace, because a
     * grant there is inherited by every object in it, now and later.
     */
    public Set<Privilege> applicable() {
        return applicable;
    }

    /** The kind a statement's word names: {@code QUERY} is {@link #VIEW}. */
    public static ObjectKind named(String word) {
        String upper = word.toUpperCase(Locale.ROOT);
        if (upper.equals("QUERY")) {
            return VIEW;
        }
        try {
            return ObjectKind.valueOf(upper);
        } catch (IllegalArgumentException e) {
            throw new PravahaException(
                    CatalogErrors.INVALID_REQUEST,
                    "'" + word + "' is not a kind of object; they are NAMESPACE, VIEW (or QUERY), STREAM, SOURCE, "
                            + "SINK, LOOKUP, NOTIFIER and ALERT");
        }
    }
}
