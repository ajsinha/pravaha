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
package com.ash.messaging.pravaha.registry.alert;

import java.util.Locale;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.plugin.Notification;
import com.ash.messaging.pravaha.api.plugin.NotifierPlugin;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * {@code log}: each notification as one line in the node's log, under the logger {@code
 * pravaha.alerts}, with its JSON. For development, for a deployment whose log shipping is its alerting,
 * and as a second channel beside a webhook so the log records what was sent. Always delivered.
 *
 * <p>Options: {@code level} -- {@code info} (the default) or {@code warn}.
 */
public final class LogNotifier implements NotifierPlugin {

    private static final System.Logger LOG = System.getLogger("pravaha.alerts");

    private System.Logger.Level level = System.Logger.Level.INFO;

    @Override
    public String name() {
        return "log";
    }

    @Override
    public Version version() {
        return Version.apiVersion();
    }

    @Override
    public void configure(PluginContext context) throws ConfigurationException {
        String written = context.config().getOrDefault("level", "info").strip().toLowerCase(Locale.ROOT);
        level = switch (written) {
            case "info" -> System.Logger.Level.INFO;
            case "warn", "warning" -> System.Logger.Level.WARNING;
            default ->
                throw new ConfigurationException(
                        AlertErrors.NOTIFIER_MISCONFIGURED,
                        "the log channel '" + context.instanceName() + "' takes level info or warn, not '" + written
                                + "'");
        };
    }

    @Override
    public void open() {}

    @Override
    public Delivery send(Notification notification) {
        LOG.log(level, "alert " + notification.summary() + " " + notification.toJson());
        return Delivery.delivered(1, "logged");
    }

    @Override
    public void close() {}
}
