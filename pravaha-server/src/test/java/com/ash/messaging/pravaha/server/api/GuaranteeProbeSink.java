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

import java.util.EnumSet;
import java.util.List;

import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * A sink that declares what its options say -- {@code transactional} and {@code idempotent} -- and
 * writes nowhere, so the sink listing can be asked about each kind without a database (HLP-4).
 */
public final class GuaranteeProbeSink implements StreamSinkPlugin {

    private boolean transactional;
    private boolean idempotent;

    @Override
    public String name() {
        return "guarantee-probe";
    }

    @Override
    public Version version() {
        return new Version(1, 0, 0);
    }

    @Override
    public void configure(PluginContext context) {
        transactional = Boolean.parseBoolean(context.get("transactional", "false"));
        idempotent = Boolean.parseBoolean(context.get("idempotent", "false"));
    }

    @Override
    public void open() {}

    @Override
    public SinkCapabilities capabilities() {
        return new SinkCapabilities(EnumSet.of(EmitMode.UPSERT, EmitMode.RETRACT), transactional, idempotent, 0);
    }

    @Override
    public int write(List<RowView> batch) {
        return batch.size();
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
}
