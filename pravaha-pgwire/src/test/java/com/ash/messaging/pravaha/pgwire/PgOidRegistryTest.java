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
package com.ash.messaging.pravaha.pgwire;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An oid is derived from the view's engine name, so the number one tenant is handed says nothing about
 * how many relations any other tenant described before it (ADR-060).
 */
class PgOidRegistryTest {

    @Test
    void anOidDependsOnTheNameAloneAndNotOnWhatWasDescribedBefore() {
        PgOidRegistry quiet = new PgOidRegistry();
        PgOidRegistry busy = new PgOidRegistry();
        for (int i = 0; i < 500; i++) {
            busy.oidOf("globex.default.view_" + i);
        }

        int alone = quiet.oidOf("acme.default.orders");
        assertThat(busy.oidOf("acme.default.orders"))
                .as("five hundred other tenant's views described first change nothing")
                .isEqualTo(alone)
                .isEqualTo(PgOidRegistry.hashed("acme.default.orders"));
        assertThat(alone).isGreaterThanOrEqualTo(16_384);
        assertThat(quiet.oidOf("acme.default.orders"))
                .as("stable for the life of the server")
                .isEqualTo(alone);
        assertThat(quiet.nameOf(alone)).contains("acme.default.orders");
    }

    @Test
    @SuppressWarnings("NullAway") // nulls on purpose: what a caller outside NullAway may pass
    void twoNamesThatHashAlikeAreGivenTwoOids() {
        Map<Integer, String> seen = new HashMap<>();
        String first = null;
        String second = null;
        for (int i = 0; first == null; i++) {
            String name = "v" + i;
            String earlier = seen.putIfAbsent(PgOidRegistry.hashed(name), name);
            if (earlier != null) {
                first = earlier;
                second = name;
            }
        }
        PgOidRegistry oids = new PgOidRegistry();
        int a = oids.oidOf(first);
        int b = oids.oidOf(second);

        assertThat(a).isNotEqualTo(b);
        assertThat(oids.nameOf(a)).contains(first);
        assertThat(oids.nameOf(b)).contains(second);
        assertThat(oids.oidOf(second)).isEqualTo(b);
    }
}
