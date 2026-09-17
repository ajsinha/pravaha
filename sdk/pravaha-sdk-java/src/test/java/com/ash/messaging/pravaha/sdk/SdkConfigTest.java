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
package com.ash.messaging.pravaha.sdk;

import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SdkConfigTest {

    @AfterEach
    void clearTestProperty() {
        System.clearProperty("pravaha.tls.enabled");
    }

    @Test
    void layeredKeepsABaseKeyThatNoSystemPropertyOverrides() {
        Map<String, String> merged = SdkConfig.layered(Map.of("hosts", "a:1"), "pravaha.");
        assertThat(merged).containsEntry("hosts", "a:1");
    }

    @Test
    void layeredLetsASystemPropertyOverrideTheBaseMap() {
        System.setProperty("pravaha.tls.enabled", "false");
        Map<String, String> merged = SdkConfig.layered(Map.of("tls.enabled", "true"), "pravaha.");
        // The prefix is stripped back off so the merged map stays in the same unprefixed
        // convention TlsOptions.applyConfig and Endpoint.fromConfig read: "tls.enabled", the base
        // map's own key, now carrying the system property's value.
        assertThat(merged.get("tls.enabled")).isEqualTo("false");
    }

    @Test
    void fromPropertiesTurnsAPropertiesObjectIntoAPlainMap() {
        Properties props = new Properties();
        props.setProperty("hosts", "a:1");
        props.setProperty("tls.enabled", "true");
        Map<String, String> map = SdkConfig.fromProperties(props);
        assertThat(map).containsEntry("hosts", "a:1").containsEntry("tls.enabled", "true");
    }

    @Test
    void truthyAndFalsyRecogniseTheCommonSpellings() {
        assertThat(SdkConfig.truthy("true")).isTrue();
        assertThat(SdkConfig.truthy("TRUE")).isTrue();
        assertThat(SdkConfig.truthy("yes")).isTrue();
        assertThat(SdkConfig.truthy("1")).isTrue();
        assertThat(SdkConfig.falsy("false")).isTrue();
        assertThat(SdkConfig.falsy("no")).isTrue();
        assertThat(SdkConfig.falsy("0")).isTrue();
    }
}
