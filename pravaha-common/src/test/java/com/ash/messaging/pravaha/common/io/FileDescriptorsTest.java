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
package com.ash.messaging.pravaha.common.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ceiling nobody set and nothing checked.
 *
 * <p>A node holding many sources reaches the descriptor limit before it reaches anything else, and
 * the failure blames the network: under a low {@code ulimit -n} the Aerospike client reports that the
 * cluster may be down and the port unreachable, when both are fine. It discards the underlying
 * {@code SocketException}, so the only way to tell the two apart is to have counted first (SRC-4).
 */
final class FileDescriptorsTest {

    @Test
    @EnabledOnOs(OS.LINUX)
    void theProcessCanSayHowManyDescriptorsItHoldsAndMayHold() {
        FileDescriptors.Usage usage =
                FileDescriptors.usage().orElseThrow(() -> new AssertionError("no /proc reading on Linux"));

        assertThat(usage.open())
                .as("this JVM holds at least stdin, stdout and stderr")
                .isGreaterThan(2);
        assertThat(usage.limit()).as("a limit is set, even if generously").isGreaterThan(usage.open());
        assertThat(usage.remaining()).isEqualTo(usage.limit() - usage.open());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void openingFilesIsVisibleInTheCount() {
        // The property the whole thing rests on: if this did not move with real descriptors, the
        // hint would fire on the wrong failures and stay silent on the right ones.
        long before = FileDescriptors.usage().orElseThrow().open();
        List<java.io.InputStream> held = new ArrayList<>();
        try {
            for (int i = 0; i < 50; i++) {
                held.add(Files.newInputStream(Path.of("/proc/self/limits")));
            }
            assertThat(FileDescriptors.usage().orElseThrow().open())
                    .as("fifty open files are fifty more descriptors")
                    .isGreaterThanOrEqualTo(before + 50);
        } catch (IOException e) {
            throw new AssertionError(e);
        } finally {
            held.forEach(stream -> {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // Closing a test fixture.
                }
            });
        }
    }

    @Test
    void theHintIsSilentWhenThereIsHeadroomAndSpecificWhenThereIsNot() {
        // Silent on a healthy process, because a hint that always fires sends a reader to the wrong
        // place on every unrelated failure -- which is the defect being fixed, pointed the other way.
        assertThat(new FileDescriptors.Usage(10, 1024).isNearLimit()).isFalse();

        // And fires close to the ceiling, where it is the likelier explanation than anything the
        // plugin's own message offers.
        assertThat(new FileDescriptors.Usage(1000, 1024).isNearLimit()).isTrue();
        assertThat(new FileDescriptors.Usage(240, 300).isNearLimit())
                .as("the measured Aerospike failure was the 224th source under ulimit -n 300")
                .isTrue();

        assertThat(new FileDescriptors.Usage(1000, 1024))
                .asString()
                .as("an operator should be able to act on this without reading the code")
                .contains("1000 of 1024")
                .contains("24 remaining");
    }
}
