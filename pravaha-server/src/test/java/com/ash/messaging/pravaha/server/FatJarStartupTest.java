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
package com.ash.messaging.pravaha.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A node starts with every connector on its classpath and no store beside it.
 *
 * <p>Found by running the packaged jar rather than by any test: once the Cassandra driver was on
 * the server's classpath, Spring Boot's auto-configuration built a {@code CqlSession} to
 * {@code localhost:9042} of its own accord, and a health check against it, and a node with no
 * Cassandra refused to start at all. The same shape waits for any driver Spring Boot has an
 * auto-configuration for, so the assertion is general: the framework holds no client of any
 * connector's store. Connections belong to plugins, opened when something binds them.
 *
 * <p>Checked by class name so the test compiles whether or not a given driver is present.
 */
@SpringBootTest(properties = {"pravaha.security.allow-anonymous=true", "pravaha.flight.port=0"})
class FatJarStartupTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void theNodeStartsAndSpringHoldsNoClientOfAnyConnectorsStore() throws Exception {
        for (String client : new String[] {
            "com.datastax.oss.driver.api.core.CqlSession",
            "org.apache.kafka.clients.producer.Producer",
            "org.apache.kafka.clients.consumer.Consumer",
            "javax.sql.DataSource"
        }) {
            Class<?> type;
            try {
                type = Class.forName(client);
            } catch (ClassNotFoundException absent) {
                continue;
            }
            assertThat(context.getBeanNamesForType(type))
                    .as("Spring built a %s; a connector's connections belong to its plugin", client)
                    .isEmpty();
        }
    }
}
