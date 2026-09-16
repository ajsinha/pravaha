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
package com.ash.messaging.pravaha.plugin.cassandra;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.ConfigurationException;

/** Parses {@code host:port,host:port}. Small, and the first thing a misconfiguration hits. */
final class CassandraContactPoints {

    private CassandraContactPoints() {}

    static List<InetSocketAddress> parse(String spec) {
        List<InetSocketAddress> points = new ArrayList<>();
        for (String entry : spec.split(",")) {
            String[] parts = entry.strip().split(":");
            if (parts.length != 2) {
                throw new ConfigurationException(
                        CassandraErrors.BAD_CONFIGURATION,
                        "contact point '" + entry.strip() + "' is not 'host:port'. Example: 127.0.0.1:9042");
            }
            try {
                points.add(InetSocketAddress.createUnresolved(parts[0].strip(), Integer.parseInt(parts[1].strip())));
            } catch (NumberFormatException e) {
                throw new ConfigurationException(
                        CassandraErrors.BAD_CONFIGURATION, "port '" + parts[1].strip() + "' is not a number");
            }
        }
        return points;
    }
}
