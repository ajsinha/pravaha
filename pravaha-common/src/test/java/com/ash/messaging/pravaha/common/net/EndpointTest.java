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
package com.ash.messaging.pravaha.common.net;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How an address is written down, and the two host spellings a configuration file gets wrong.
 *
 * <p>CFG-2. Both defects here were found by starting real nodes: one advertised an address no
 * client library can parse, and one bound a different address from the one in the file and said
 * nothing about having reinterpreted it.
 */
class EndpointTest {

    @Test
    void anIpv6LiteralIsBracketedSoTheAddressCanBeParsedBack_CFG2() {
        // `pravaha.flight.host: ::1` logged `Flight SQL listening on ::1:19800`. Where does the
        // host end? Every colon looks the same, so the answer needs the reader to already know.
        assertThat(Endpoint.address("::1", 19090)).isEqualTo("[::1]:19090");
        assertThat(Endpoint.address("fe80::1%eth0", 19090)).isEqualTo("[fe80::1%eth0]:19090");
        assertThat(Endpoint.address("::", 19090)).isEqualTo("[::]:19090");
    }

    @Test
    void anAddressThatIsAlreadyWritableIsLeftExactlyAsItIs_CFG2() {
        assertThat(Endpoint.address("127.0.0.1", 19090)).isEqualTo("127.0.0.1:19090");
        assertThat(Endpoint.address("0.0.0.0", 19090)).isEqualTo("0.0.0.0:19090");
        assertThat(Endpoint.address("db-1.internal", 5432)).isEqualTo("db-1.internal:5432");
        // Applying it twice must not double-bracket, because a caller cannot always know whether
        // the host it was handed came from a file or from another formatter.
        assertThat(Endpoint.address("[::1]", 19090)).isEqualTo("[::1]:19090");
    }

    @Test
    void anAbbreviatedIpv4AddressIsRecognisedAndExpanded_CFG2() {
        // `host: 127` is accepted by InetAddress as 0.0.0.127 and then fails to bind with "Cannot
        // assign requested address", which says nothing about the reinterpretation -- and on a
        // machine that did hold 0.0.0.127 it would have bound it silently.
        assertThat(Endpoint.isAbbreviatedIpv4("127")).isTrue();
        assertThat(Endpoint.expandedIpv4("127")).isEqualTo("0.0.0.127");
        assertThat(Endpoint.isAbbreviatedIpv4("10.1")).isTrue();
        assertThat(Endpoint.expandedIpv4("10.1")).isEqualTo("10.0.0.1");
        assertThat(Endpoint.isAbbreviatedIpv4("1.2.3")).isTrue();
        assertThat(Endpoint.expandedIpv4("1.2.3")).isEqualTo("1.2.0.3");
    }

    @Test
    @SuppressWarnings("NullAway") // null on purpose: pins the tolerance
    void aHostThatMeansWhatItSaysIsNotCalledAbbreviated_CFG2() {
        // The refusal must not catch anything an operator legitimately writes, or it becomes the
        // defect instead of the fix.
        assertThat(Endpoint.isAbbreviatedIpv4("127.0.0.1")).isFalse();
        assertThat(Endpoint.isAbbreviatedIpv4("0.0.0.0")).isFalse();
        assertThat(Endpoint.isAbbreviatedIpv4("localhost")).isFalse();
        assertThat(Endpoint.isAbbreviatedIpv4("db-1.internal")).isFalse();
        assertThat(Endpoint.isAbbreviatedIpv4("::1")).isFalse();
        assertThat(Endpoint.isAbbreviatedIpv4("")).isFalse();
        assertThat(Endpoint.isAbbreviatedIpv4(null)).isFalse();
        assertThat(Endpoint.isAbbreviatedIpv4("10.")).isFalse();
    }
}
