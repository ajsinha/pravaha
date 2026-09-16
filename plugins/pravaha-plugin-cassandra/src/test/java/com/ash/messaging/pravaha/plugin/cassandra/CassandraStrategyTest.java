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

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CassandraStrategy}'s refusal logic, exercised without a server -- unlike its Aerospike
 * counterpart, which is only reached through {@code AerospikePluginIT} and so is untested on a
 * machine without Docker.
 */
class CassandraStrategyTest {

    @Test
    void theOnlyImplementedStrategyParses() {
        assertThat(CassandraStrategy.parse("token-range-scan")).isEqualTo(CassandraStrategy.TOKEN_RANGE_SCAN);
        assertThat(CassandraStrategy.parse("Token-Range-Scan"))
                .as("parsing is case-insensitive, the way the Aerospike plugin's is")
                .isEqualTo(CassandraStrategy.TOKEN_RANGE_SCAN);
        assertThat(CassandraStrategy.TOKEN_RANGE_SCAN.isImplemented()).isTrue();
    }

    @Test
    void writetimeIncrementalIsRefusedRatherThanSilentlyDowngraded() {
        assertThatThrownBy(() -> CassandraStrategy.parse("writetime-incremental"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("ALLOW FILTERING")
                .hasMessageContaining("token-range-scan");
        assertThat(CassandraStrategy.WRITETIME_INCREMENTAL.isImplemented()).isFalse();
    }

    @Test
    void commitlogCdcIsRefused() {
        assertThatThrownBy(() -> CassandraStrategy.parse("commitlog-cdc"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("per-node agent");
        assertThat(CassandraStrategy.COMMITLOG_CDC.isImplemented()).isFalse();
    }

    @Test
    void anUnknownStrategyNamesWhatIsSupported() {
        assertThatThrownBy(() -> CassandraStrategy.parse("xdr-http"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("unknown strategy")
                .hasMessageContaining("token-range-scan");
    }
}
