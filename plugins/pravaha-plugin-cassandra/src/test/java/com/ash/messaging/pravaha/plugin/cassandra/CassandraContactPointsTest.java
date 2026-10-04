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
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CassandraContactPointsTest {

    @Test
    void parsesOneOrMoreHostPortPairs() {
        List<InetSocketAddress> single = CassandraContactPoints.parse("127.0.0.1:9042");
        assertThat(single).hasSize(1);
        assertThat(single.get(0).getPort()).isEqualTo(9042);

        List<InetSocketAddress> several = CassandraContactPoints.parse("cass-1:9042, cass-2:9042");
        assertThat(several).hasSize(2);
        assertThat(several.get(1).getHostString()).isEqualTo("cass-2");
    }

    @Test
    void rejectsAnEntryWithNoPort() {
        assertThatThrownBy(() -> CassandraContactPoints.parse("127.0.0.1"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("host:port");
    }

    @Test
    void rejectsANonNumericPort() {
        assertThatThrownBy(() -> CassandraContactPoints.parse("127.0.0.1:abc"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("not a number");
    }

    @Test
    void aTrailingSeparatorIsRefusedLikeAnyOtherMalformedEntry() {
        // SPLITTRAIL-1: String.split dropped trailing empty strings, so "cass-1:9042:" read as
        // cass-1:9042 and a trailing comma vanished, while the same slip mid-list was refused.
        assertThatThrownBy(() -> CassandraContactPoints.parse("cass-1:9042,"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("host:port");
        assertThatThrownBy(() -> CassandraContactPoints.parse("cass-1:9042:"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("host:port");
    }
}
