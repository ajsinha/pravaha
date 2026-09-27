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
package com.ash.messaging.pravaha.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DOCX-21: the help-page base is configuration, unset means no URL, and a value that is not a URL
 * is refused by name.
 */
class HelpUrlsTest {

    @AfterEach
    void clearTheConfiguredBase() {
        HelpUrls.configure(null);
    }

    @Test
    void unsetMeansNoUrlAnywhere() {
        assertThat(HelpUrls.configured()).isFalse();
        assertThat(HelpUrls.base()).isEmpty();
        assertThat(HelpUrls.forCode("PRV-2002")).isEmpty();
    }

    /**
     * The half of the decision that is easy to lose: a message that loses its link has to say
     * something in its place, and it has to name somewhere that works with no network.
     */
    @Test
    void withNoBaseTheLineSaysWhereToLookTheCodeUpInstead() {
        assertThat(HelpUrls.helpLine("PRV-2002"))
                .isEqualTo("look PRV-2002 up in the console's help under Errors, or in docs/TROUBLESHOOTING.md");
        assertThat(HelpUrls.helpLine("PRV-2002")).doesNotContain("http");
    }

    @Test
    void aConfiguredBaseCarriesTheCode() {
        HelpUrls.configure("http://localhost:17070/help/codes/");
        assertThat(HelpUrls.configured()).isTrue();
        assertThat(HelpUrls.forCode("PRV-2002")).isEqualTo("http://localhost:17070/help/codes/PRV-2002");
        assertThat(HelpUrls.helpLine("PRV-2002")).isEqualTo("http://localhost:17070/help/codes/PRV-2002");
    }

    /** A base written without its trailing slash is the commonest way to get this wrong. */
    @Test
    void aMissingTrailingSlashIsSuppliedRatherThanConcatenatedAway() {
        HelpUrls.configure("https://help.example.test/errors");
        assertThat(HelpUrls.forCode("PRV-2002")).isEqualTo("https://help.example.test/errors/PRV-2002");
    }

    @Test
    void blankClearsTheBase() {
        HelpUrls.configure("https://help.example.test/errors/");
        HelpUrls.configure("   ");
        assertThat(HelpUrls.configured()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not-a-url",
                "/errors/",
                "errors.example.test/errors/",
                "ftp://help.example.test/errors/",
                "file:///usr/share/doc/pravaha/",
                "http://"
            })
    void aBaseThatIsNotAnAbsoluteHttpUrlIsRefusedByName(String written) {
        assertThatThrownBy(() -> HelpUrls.configure(written))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-1029")
                .hasMessageContaining("pravaha.docs.base-url is '" + written + "'")
                .hasMessageContaining("docs/TROUBLESHOOTING.md");
    }

    /** A refused value must not become the base: half-applied configuration is its own defect. */
    @Test
    void aRefusedBaseLeavesThePreviousOneAlone() {
        HelpUrls.configure("https://help.example.test/errors/");
        assertThatThrownBy(() -> HelpUrls.configure("ftp://nope/")).isInstanceOf(PravahaException.class);
        assertThat(HelpUrls.forCode("PRV-2002")).isEqualTo("https://help.example.test/errors/PRV-2002");
    }

    @Test
    void theRefusalCodeIsInTheConfigurationRange() {
        assertThat(HelpUrls.BASE_URL_INVALID.code()).isEqualTo("PRV-1029");
        assertThat(HelpUrls.BASE_URL_INVALID.category()).isEqualTo(ErrorCode.Category.CONFIGURATION);
    }
}
