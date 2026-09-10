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
package com.ash.messaging.pravaha.plugin.aerospike;

import com.ash.messaging.pravaha.api.ConfigurationException;

/**
 * How this plugin gets changes out of Aerospike (design section 19.1, gap G4).
 *
 * <p>The central constraint, and the reason this is an enum rather than an implementation detail:
 * <strong>Aerospike Community Edition has no change feed at all.</strong> Change propagation is XDR,
 * an Enterprise feature. So the strategy is a configuration choice with different delivery
 * guarantees attached, and the guarantee is declared to the engine rather than assumed -- a query
 * whose source cannot see deletes should be told so at registration, not discover it from a total
 * that never goes down.
 */
public enum AerospikeStrategy {

    /**
     * Partition-parallel scan filtered on {@code record.last_update_time()}, the only strategy that
     * works on Community Edition.
     *
     * <p>Honest about what it costs: at-least-once, no before-image, and <em>deletes are
     * invisible</em> -- a deleted record is simply absent from the next scan, which is
     * indistinguishable from one that never existed. It also misses intra-interval overwrites: two
     * writes between scans are seen as one. Both are properties of scanning rather than of this
     * implementation, and no amount of care removes them.
     */
    LUT_SCAN("lut-scan"),

    /** XDR to a Kafka topic. Enterprise, and not implemented in this build. */
    XDR_KAFKA("xdr-kafka"),

    /** XDR change-notification over HTTP. Enterprise, and not implemented in this build. */
    XDR_HTTP("xdr-http"),

    /** Application writes through a Pravaha wrapper. Intrusive; not implemented in this build. */
    WRITE_INTERCEPT("write-intercept");

    private final String configName;

    AerospikeStrategy(String configName) {
        this.configName = configName;
    }

    public String configName() {
        return configName;
    }

    /** Whether this build can actually run it. */
    public boolean isImplemented() {
        return this == LUT_SCAN;
    }

    static AerospikeStrategy parse(String name) {
        for (AerospikeStrategy strategy : values()) {
            if (strategy.configName.equalsIgnoreCase(name.strip())) {
                if (!strategy.isImplemented()) {
                    throw new ConfigurationException(
                            AerospikeErrors.BAD_CONFIGURATION,
                            "strategy '" + name + "' is designed but not implemented in this build. It needs "
                                    + "Aerospike Enterprise XDR, which cannot be exercised against Community "
                                    + "Edition, and shipping an untested change-feed path would be worse than "
                                    + "not shipping one. Use 'lut-scan', which works on Community Edition and "
                                    + "declares its weaker guarantees.");
                }
                return strategy;
            }
        }
        throw new ConfigurationException(
                AerospikeErrors.BAD_CONFIGURATION,
                "unknown strategy '" + name + "'. Supported: lut-scan (Community Edition). Designed but not "
                        + "implemented: xdr-kafka, xdr-http, write-intercept.");
    }
}
