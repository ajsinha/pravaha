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
package com.ash.messaging.pravaha.flight;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.wire.ControlWire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WIRE-1: {@code pravaha.list}'s fields are named once, in {@link ControlWire#LIST_FIELDS}, and the
 * Python SDK's copy of the names is held to it here -- a field added on one side and not the other
 * fails this rather than shifting every later field in the other client.
 */
class ControlWireListFieldsTest {

    @Test
    void thePythonSdkNamesTheListingsFieldsInTheServersOrder() throws Exception {
        // The tuple lives in records.py since client.py was split (CONSOLESIZE-1); client.py re-exports it.
        Path client = Path.of("..", "sdk", "python", "pravaha", "records.py");
        String source = Files.readString(client);
        Matcher tuple = Pattern.compile("(?s)\\nLIST_FIELDS = \\((.*?)\\n\\)").matcher(source);
        assertThat(tuple.find()).as("%s declares LIST_FIELDS", client).isTrue();
        List<String> python = new ArrayList<>();
        Matcher name = Pattern.compile("\"([a-z_]+)\"").matcher(tuple.group(1));
        while (name.find()) {
            python.add(name.group(1));
        }
        assertThat(python).isEqualTo(ControlWire.LIST_FIELDS);
    }

    @Test
    void aFieldTheListingDoesNotHaveIsRefusedByName() {
        assertThat(ControlWire.listField("name")).isZero();
        assertThat(ControlWire.listField("sink_message")).isEqualTo(ControlWire.LIST_FIELDS.size() - 1);
        assertThatThrownBy(() -> ControlWire.listField("owner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'owner'");
    }
}
