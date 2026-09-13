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
package com.ash.messaging.pravaha.it.qa.lifecycle;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** LIFE-029..035 -- key columns: what a registration is allowed to key its view by. */
@Tag("qa")
class LifeKeysTest extends LifecycleTestSupport {

    @Test
    void life029_noKeyColumnsIsRefused() {
        assertThatThrownBy(() -> registry.register("v", S1, List.of(), Principal.ANONYMOUS))
                .as("a view with no key is a log, and a point read against it has nothing to look up")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one key column");

        // Control: the same SQL with a real key registers in the same run.
        assertThat(registry.register("v_ok", S1, List.of(0), Principal.ANONYMOUS))
                .isNotNull();
    }

    @Test
    void life030_aKeyColumnPastTheEndOfTheOutputIsRefusedNamingTheWidth() {
        // S1's output is (usr, amount): two columns, ordinals 0 and 1.
        assertThatThrownBy(() -> registry.register("v1", S1, List.of(5), Principal.ANONYMOUS))
                .isInstanceOf(IllegalArgumentException.class)
                .as("the message must name the actual width, 2, not just reject the ordinal")
                .hasMessageContaining("2 columns");
        assertThatThrownBy(() -> registry.register("v2", S1, List.of(2), Principal.ANONYMOUS))
                .as("one past the end of a two-column output is still out of range")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register("v3", S1, List.of(-1), Principal.ANONYMOUS))
                .isInstanceOf(IllegalArgumentException.class);

        // Control: ordinal 1, the last valid one, registers -- the boundary is at 2, not at 1.
        assertThat(registry.register("v_ok", S1, List.of(1), Principal.ANONYMOUS))
                .isNotNull();
    }

    @Test
    void life032_duplicateOrdinalsInKeysAreSilentlyAccepted() {
        registry.register("v_00", S1 + " WHERE id > 0", List.of(0, 0), Principal.ANONYMOUS);
        registry.register("v_0", S1 + " WHERE id > -1", List.of(0), Principal.ANONYMOUS);

        for (String name : List.of("v_00", "v_0")) {
            push(name, 1, "u1", 10, 1);
            push(name, 2, "u1", 20, 1);
            push(name, 3, "u2", 30, 1);
        }

        // [usr, usr] partitions exactly as [usr] does: the redundant ordinal is accepted, not
        // refused, and changes nothing observable about which rows collapse together.
        assertThat(rows("SELECT usr, amount FROM v_00"))
                .as("a key of [usr, usr] is silently accepted and behaves like [usr]")
                .hasSameSizeAs(rows("SELECT usr, amount FROM v_0"))
                .hasSize(2);
    }

    @Test
    void life033_everyOutputColumnAsAKey() {
        registry.register("v", S1, List.of(0, 1), Principal.ANONYMOUS);
        // Five rows, four distinct (usr, amount) pairs -- (u1,10) pushed twice.
        push("v", 1, "u1", 10, 1);
        push("v", 2, "u1", 10, 1);
        push("v", 3, "u1", 20, 1);
        push("v", 4, "u2", 10, 1);
        push("v", 5, "u3", 30, 1);

        assertThat(rows("SELECT usr, amount FROM v"))
                .as("the widest legal key: one row per distinct (usr, amount) pair, of which there are 4")
                .hasSize(4);
    }

    @Test
    void life034_keyOrdinalsOutOfOrderPartitionIdentically() {
        registry.register("v_01", S1 + " WHERE id > 0", List.of(0, 1), Principal.ANONYMOUS);
        registry.register("v_10", S1 + " WHERE id > -1", List.of(1, 0), Principal.ANONYMOUS);

        assertThat(registry.require("v_01").fingerprint())
                .as("V-distinct: different SQL text keeps these from sharing")
                .isNotEqualTo(registry.require("v_10").fingerprint());

        for (String name : List.of("v_01", "v_10")) {
            push(name, 1, "u1", 10, 1);
            push(name, 2, "u1", 20, 1);
            push(name, 3, "u2", 10, 1);
        }

        assertThat(rows("SELECT usr, amount FROM v_01").size())
                .as("[usr,amount] and [amount,usr] partition the same rows into the same groups")
                .isEqualTo(rows("SELECT usr, amount FROM v_10").size())
                .isEqualTo(3);
    }

    @Test
    void life035_theKeyColumnsDoNotReachTheFingerprint() {
        registry.register("ka", S1, List.of(0), Principal.ANONYMOUS);
        registry.register("kb", S1, List.of(1), Principal.ANONYMOUS);

        assertThat(registry.require("ka").fingerprint())
                .as("identical SQL, different --keys: one computation by the fingerprint's own rule")
                .isEqualTo(registry.require("kb").fingerprint());
        assertThat(registry.names()).as("but two names in the listing").containsExactly("ka", "kb");
        assertThat(registry.size())
                .as("QueryRegistry.size() counts computations, not names")
                .isEqualTo(1);

        registry.register("kc", S1 + " WHERE id > 0", List.of(0), Principal.ANONYMOUS);
        assertThat(registry.require("kc").fingerprint())
                .as("a genuinely different plan still gets a different fingerprint")
                .isNotEqualTo(registry.require("ka").fingerprint());
    }
}
