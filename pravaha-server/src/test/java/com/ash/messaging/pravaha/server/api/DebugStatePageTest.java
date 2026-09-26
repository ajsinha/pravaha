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
package com.ash.messaging.pravaha.server.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.runtime.exec.OperatorState;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DBG-1: the REST debug read keeps an entry's key apart from its columns, so an operator holding a
 * column named {@code key} cannot overwrite the entry's own.
 */
class DebugStatePageTest {

    @Test
    void aColumnNamedKeyDoesNotOverwriteTheEntrysKey() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("key", "a column that happens to be called key");
        values.put("count", "3");
        OperatorState.Page page = new OperatorState.Page(
                "window#0", "window", null, 0, 50, 1, List.of(new OperatorState.Entry("group-7", values)));

        JsonNode json = new ObjectMapper().valueToTree(DebugController.statePage(page));

        JsonNode entry = json.get("entries").get(0);
        assertThat(entry.get("key").asText()).isEqualTo("group-7");
        assertThat(entry.get("values").get("key").asText()).isEqualTo("a column that happens to be called key");
        assertThat(entry.get("values").get("count").asText()).isEqualTo("3");
    }
}
