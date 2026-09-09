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
package com.ash.messaging.pravaha.api.plugin;

import java.util.Map;
import java.util.Optional;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.ErrorCode;

/**
 * What a plugin is given at {@link PravahaPlugin#configure}.
 *
 * <p>Carries only that plugin's own configuration, already scoped and with the prefix stripped. A
 * connector cannot read -- or log -- another plugin's credentials, because it is never handed them.
 *
 * <p>Deliberately a plain map rather than the engine's {@code Configuration} type: this interface
 * lives in {@code pravaha-api}, which has zero dependencies, and that constraint is what lets a
 * plugin be compiled against the API alone.
 */
public interface PluginContext {

    /** This plugin's configuration, prefix stripped. */
    Map<String, String> config();

    /** The name this instance was registered under, which may differ from {@link PravahaPlugin#name()}. */
    String instanceName();

    default Optional<String> get(String key) {
        return Optional.ofNullable(config().get(key));
    }

    default String get(String key, String fallback) {
        return config().getOrDefault(key, fallback);
    }

    /**
     * A required setting.
     *
     * @throws ConfigurationException naming the key and this plugin instance
     */
    default String require(String key) {
        String value = config().get(key);
        if (value == null || value.isBlank()) {
            throw new ConfigurationException(
                    MISSING_SETTING,
                    "plugin '" + instanceName() + "' requires '" + key + "', which is not set. Available: "
                            + config().keySet());
        }
        return value;
    }

    default int requireInt(String key) {
        String raw = require(key);
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new ConfigurationException(
                    MISSING_SETTING, "plugin '" + instanceName() + "' setting '" + key + "' is not a number");
        }
    }

    ErrorCode MISSING_SETTING = new ErrorCode(5001, "PLUGIN_MISSING_SETTING");
}
