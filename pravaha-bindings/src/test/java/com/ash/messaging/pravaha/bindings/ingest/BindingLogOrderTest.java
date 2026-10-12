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
package com.ash.messaging.pravaha.bindings.ingest;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.bindings.egress.SinkBinding;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LOGORDER-1: the startup lines "sources bound: ..." and "sinks bound: ..." listed each binding's
 * option names in {@code Map.copyOf}'s order, which changes from one run to the next.
 */
class BindingLogOrderTest {

    private static final Map<String, String> OPTIONS =
            Map.of("schema", "a:INT64", "path", "/data/in.csv", "format", "csv", "delimiter", ",", "header", "true");

    @Test
    void aSourceBindingListsItsOptionNamesSorted() {
        assertThat(new SourceBinding("txn", "filesystem", OPTIONS))
                .hasToString("txn <- filesystem[delimiter, format, header, path, schema]");
    }

    @Test
    void aSinkBindingListsItsOptionNamesSorted() {
        assertThat(new SinkBinding("out", "jdbc", OPTIONS))
                .hasToString("out -> jdbc[delimiter, format, header, path, schema]");
    }
}
