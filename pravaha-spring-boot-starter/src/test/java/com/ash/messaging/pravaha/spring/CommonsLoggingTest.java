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

import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SBCOMMONSLOG-1: the starter brings no commons-logging jar -- Calcite's aggdesigner did, and Spring
 * warned at every start -- so the commons-logging API is the one spring-jcl provides.
 */
class CommonsLoggingTest {

    @Test
    void theCommonsLoggingApiIsSpringJcls() {
        assertThat(LogFactory.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toString())
                .contains("spring-jcl");
    }
}
