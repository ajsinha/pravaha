/*
 * Tutorial 13 (docs/tutorials/13-writing-a-connector.md): the source TCK over the acme ticker.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
 */
package com.example.acme;

import java.util.Map;

import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.testkit.tck.SourcePluginTck;

/**
 * The ten conformance tests every shipped source runs, over a fixture of two symbols -- two
 * partitions -- and five ticks each. The replay test resumes every partition from an offset of its
 * own and expects that partition's remainder.
 */
class AcmeSourceTckTest extends SourcePluginTck {

    private record Context(String instanceName, Map<String, String> config) implements PluginContext {}

    @Override
    protected StreamSourcePlugin createPlugin() {
        AcmeSourcePlugin plugin = new AcmeSourcePlugin();
        plugin.configure(new Context("ticks", Map.of("symbols", "ACME,GLOBEX", "ticks", "5")));
        plugin.open();
        return plugin;
    }

    @Override
    protected String streamName() {
        return "ticks";
    }

    @Override
    protected int expectedRecordCount() {
        return 10;
    }
}
