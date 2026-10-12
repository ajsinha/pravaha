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
package com.ash.messaging.pravaha.ai;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The hand-written SigV4 against AWS's own published test vector ({@code get-vanilla} of the signature test suite). */
class SigV4Test {

    @Test
    void getVanillaSignsAsAwsDocumentsIt() {
        Map<String, String> h = SigV4.sign(
                "GET",
                URI.create("https://example.amazonaws.com/"),
                new byte[0],
                "",
                "AKIDEXAMPLE",
                "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                "",
                "us-east-1",
                "service",
                Instant.parse("2015-08-30T12:36:00Z"));
        assertThat(h.get("x-amz-date")).isEqualTo("20150830T123600Z");
        assertThat(h.get("Authorization"))
                .isEqualTo(
                        "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, "
                                + "SignedHeaders=host;x-amz-date, Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31");
    }

    @Test
    void theCanonicalPathEncodesEachSegmentAgainAndASessionTokenIsSigned() {
        assertThat(SigV4.canonicalUri(URI.create("https://h/model/a%3A0/invoke")))
                .isEqualTo("/model/a%253A0/invoke");
        assertThat(SigV4.canonicalQuery(URI.create("https://h/x?b=2&a=1"))).isEqualTo("a=1&b=2");
        assertThat(SigV4.hostHeader(URI.create("http://127.0.0.1:8123/x"))).isEqualTo("127.0.0.1:8123");
        Map<String, String> h = SigV4.sign(
                "POST",
                URI.create("https://h/x"),
                new byte[] {1},
                "application/json",
                "AK",
                "SK",
                "tok",
                "eu-west-1",
                "bedrock",
                Instant.parse("2026-10-12T00:00:00Z"));
        assertThat(h.get("x-amz-security-token")).isEqualTo("tok");
        assertThat(h.get("Authorization")).contains("SignedHeaders=content-type;host;x-amz-date;x-amz-security-token");
    }
}
