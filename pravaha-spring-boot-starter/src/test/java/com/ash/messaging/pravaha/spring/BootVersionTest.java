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
package com.ash.messaging.pravaha.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootVersion;
import org.springframework.core.SpringVersion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Boot matrix's witness: the Boot on the test classpath is the one the build says it is testing.
 *
 * <p>A matrix leg that silently resolved the default Boot would report two green runs of the same
 * thing. The starter's pom passes {@code spring.boot.version} -- whatever profile set it -- to the
 * tests, and this compares it with the {@code spring-boot} jar actually loaded.
 */
class BootVersionTest {

    @Test
    void theBootOnTheClasspathIsTheOneThisLegIsFor() {
        String expected = System.getProperty("pravaha.expected.spring.boot.version");
        assumeTrue(expected != null, "run outside Maven: no expected version to compare with");
        System.out.println("starter tests running on Spring Boot " + SpringBootVersion.getVersion()
                + ", Spring Framework " + SpringVersion.getVersion());
        assertThat(SpringBootVersion.getVersion()).isEqualTo(expected);
    }
}
