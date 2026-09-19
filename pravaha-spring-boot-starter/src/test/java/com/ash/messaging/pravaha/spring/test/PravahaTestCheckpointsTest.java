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
package com.ash.messaging.pravaha.spring.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.spring.PravahaAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @PravahaTest(checkpoints = true)}: a temporary directory the engine checkpoints into, gone
 * when the context closes; and the properties the slice sets on the test's behalf.
 */
class PravahaTestCheckpointsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(PravahaAutoConfiguration.class, PravahaTestAutoConfiguration.class))
            .withPropertyValues("pravaha.streams.txn.schema=user_id:STRING,amount:INT64");

    @Test
    void askedForCheckpointsTheEngineGetsATemporaryDirectoryThatIsDeletedOnClose() {
        AtomicReference<Path> used = new AtomicReference<>();
        runner.withPropertyValues(PravahaTestContextBootstrapper.CHECKPOINTS + "=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PravahaTester.class);
                    String directory = context.getBean(PravahaEngine.class)
                            .configuration()
                            .getString("pravaha.checkpoint.directory")
                            .orElseThrow();
                    Path path = Path.of(directory);
                    used.set(path);
                    assertThat(path.getFileName().toString()).startsWith("pravaha-test-checkpoints-");
                    assertThat(path).isDirectory();
                    Files.writeString(path.resolve("left-behind"), "x");
                });
        assertThat(used.get()).as("deleted with the context").doesNotExist();
    }

    @Test
    void notAskedTheEngineKeepsNoCheckpointsAndAGivenDirectoryIsLeftAlone(@TempDir Path given) {
        runner.run(context -> assertThat(
                        context.getBean(PravahaEngine.class).configuration().getString("pravaha.checkpoint.directory"))
                .isEmpty());
        runner.withPropertyValues(
                        PravahaTestContextBootstrapper.CHECKPOINTS + "=true", "pravaha.checkpoint.directory=" + given)
                .run(context -> assertThat(context.getBean(PravahaEngine.class)
                                .configuration()
                                .getString("pravaha.checkpoint.directory"))
                        .contains(given.toString()));
        assertThat(given).as("the test's own directory is not deleted").isDirectory();
    }

    @Test
    void theSliceClearsOnlyThePathsATestHasNotSet() {
        String[] declared = {"pravaha.checkpoint.directory=/tmp/x", "pravaha.dlq.directory: /tmp/y"};
        assertThat(PravahaTestContextBootstrapper.sets(declared, "pravaha.checkpoint.directory"))
                .isTrue();
        assertThat(PravahaTestContextBootstrapper.sets(declared, "pravaha.dlq.directory"))
                .isTrue();
        assertThat(PravahaTestContextBootstrapper.sets(declared, "pravaha.registry.journal"))
                .isFalse();
        assertThat(PravahaTestContextBootstrapper.sets(
                        new String[] {"pravaha.checkpoint.directory-other=1"}, "pravaha.checkpoint.directory"))
                .isFalse();

        String[] properties = new PravahaTestContextBootstrapper().getProperties(Declared.class);
        assertThat(properties)
                .contains(
                        "pravaha.checkpoint.directory=/tmp/x",
                        "pravaha.registry.journal=",
                        "pravaha.dlq.directory=",
                        PravahaTestContextBootstrapper.CHECKPOINTS + "=true")
                .doesNotContain("pravaha.checkpoint.directory=");
    }

    @PravahaTest(checkpoints = true, properties = "pravaha.checkpoint.directory=/tmp/x")
    static class Declared {}

    /** The annotation end to end: a real {@code @PravahaTest} context, checkpointing into a temporary directory. */
    @Nested
    @PravahaTest(
            checkpoints = true,
            properties = {
                "pravaha.streams.txn.schema=user_id:STRING,amount:INT64",
                "pravaha.queries.all_txn.sql=SELECT user_id, amount FROM txn",
                "pravaha.queries.all_txn.keys=user_id"
            })
    class WithCheckpoints {

        @Configuration(proxyBeanMethods = false)
        static class NoApplication {}

        @Autowired
        PravahaTester pravaha;

        @Test
        void theEngineCheckpointsIntoATemporaryDirectory() {
            Path directory = Path.of(pravaha.engine()
                    .configuration()
                    .getString("pravaha.checkpoint.directory")
                    .orElseThrow());
            assertThat(directory.getFileName().toString()).startsWith("pravaha-test-checkpoints-");
            pravaha.push("txn", new Object[] {"u1", 5L});
            assertThat(pravaha.awaitView("all_txn", rows -> rows.size() == 1)).hasSize(1);
        }
    }
}
