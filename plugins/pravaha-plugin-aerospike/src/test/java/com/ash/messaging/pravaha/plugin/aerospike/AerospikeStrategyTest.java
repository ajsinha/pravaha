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

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AerospikeStrategy}'s refusal logic, exercised without a server.
 *
 * <p>Reachable only through {@code AerospikePluginIT} until now, which meant a contributor without
 * Docker never exercised it -- the same gap {@code CassandraStrategyTest} closes for the Cassandra
 * plugin. The specific behaviour worth pinning: an unimplemented strategy must be <strong>refused</strong>
 * rather than silently downgraded to {@code lut-scan}, because the strategies carry different delivery
 * guarantees and a query that thinks it is getting XDR should not quietly get a scan instead.
 */
class AerospikeStrategyTest {

    @Test
    void theOnlyImplementedStrategyParses() {
        assertThat(AerospikeStrategy.parse("lut-scan")).isEqualTo(AerospikeStrategy.LUT_SCAN);
        assertThat(AerospikeStrategy.parse("Lut-Scan"))
                .as("parsing is case-insensitive")
                .isEqualTo(AerospikeStrategy.LUT_SCAN);
        assertThat(AerospikeStrategy.LUT_SCAN.isImplemented()).isTrue();
    }

    @Test
    void xdrKafkaIsRefusedRatherThanSilentlyDowngradedToLutScan() {
        assertThatThrownBy(() -> AerospikeStrategy.parse("xdr-kafka"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("designed but not implemented")
                .hasMessageContaining("Enterprise XDR")
                .hasMessageContaining("lut-scan");
        assertThat(AerospikeStrategy.XDR_KAFKA.isImplemented()).isFalse();
    }

    @Test
    void xdrHttpIsRefusedRatherThanSilentlyDowngradedToLutScan() {
        assertThatThrownBy(() -> AerospikeStrategy.parse("xdr-http"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("designed but not implemented")
                .hasMessageContaining("lut-scan");
        assertThat(AerospikeStrategy.XDR_HTTP.isImplemented()).isFalse();
    }

    @Test
    void writeInterceptIsRefusedRatherThanSilentlyDowngradedToLutScan() {
        assertThatThrownBy(() -> AerospikeStrategy.parse("write-intercept"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("designed but not implemented")
                .hasMessageContaining("lut-scan");
        assertThat(AerospikeStrategy.WRITE_INTERCEPT.isImplemented()).isFalse();
    }

    @Test
    void anUnknownStrategyNamesWhatIsSupported() {
        assertThatThrownBy(() -> AerospikeStrategy.parse("token-range-scan"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("unknown strategy")
                .hasMessageContaining("lut-scan")
                .hasMessageContaining("xdr-kafka")
                .hasMessageContaining("xdr-http")
                .hasMessageContaining("write-intercept");
    }

    @Test
    void onlyOneOfTheFourDeclaredStrategiesIsImplemented() {
        // The enum's whole point: it names four ways to get changes out and implements one. If a
        // second one is ever implemented, this pins that the change was deliberate rather than a
        // silent widening of what parse() accepts.
        long implemented = java.util.Arrays.stream(AerospikeStrategy.values())
                .filter(AerospikeStrategy::isImplemented)
                .count();
        assertThat(implemented).isEqualTo(1);
        assertThat(AerospikeStrategy.values()).hasSize(4);
    }
}
