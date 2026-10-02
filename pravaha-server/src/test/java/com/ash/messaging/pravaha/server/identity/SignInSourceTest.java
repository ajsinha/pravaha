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

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** LOCKENUM-1: which address a sign-in's failures are counted against. */
class SignInSourceTest {

    @Test
    void withNoTrustedProxyTheHeaderIsIgnored() {
        SignInSource none = new SignInSource(List.of());
        assertThat(none.of("203.0.113.5", "198.51.100.1")).isEqualTo("203.0.113.5");
    }

    @Test
    void aTrustedProxyIsBelievedAndAnyoneElseIsNot() {
        SignInSource console = new SignInSource(List.of("172.16.0.0/12", "::1"));
        assertThat(console.of("172.18.0.4", "198.51.100.1")).isEqualTo("198.51.100.1");
        assertThat(console.of("::1", "198.51.100.1")).isEqualTo("198.51.100.1");
        assertThat(console.of("203.0.113.5", "198.51.100.1"))
                .as("a caller who could choose its own source could spread guesses over invented ones")
                .isEqualTo("203.0.113.5");
        assertThat(console.of("172.18.0.4", null)).isEqualTo("172.18.0.4");
    }

    @Test
    void theRightMostUntrustedHopIsTheSourceNotWhatTheClientWroteFirst() {
        SignInSource proxies = new SignInSource(List.of("10.0.0.0/8"));
        assertThat(proxies.of("10.0.0.2", "1.2.3.4, 198.51.100.1, 10.0.0.3")).isEqualTo("198.51.100.1");
        assertThat(proxies.of("10.0.0.2", "10.0.0.7")).isEqualTo("10.0.0.2");
    }

    @Test
    void aMalformedEntryIsRefusedAtConfiguration() {
        assertThatThrownBy(() -> new SignInSource(List.of("console.local")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7004")
                .hasMessageContaining("trusted-proxies");
        assertThatThrownBy(() -> new SignInSource(List.of("10.0.0.0/33")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7004");
    }
}
