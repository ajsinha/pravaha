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
package com.ash.messaging.pravaha.plugin.mysqlcdc;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A mysql-cdc binding refuses a second reader beside the running one, before connecting (CDCREPL-1).
 *
 * <p>A replacement's backfill and a debug fork each open one; the engine asks this first and
 * refuses the operation with {@code PRV-4018} naming the replica id, which is what the two readers
 * would fight over.
 */
class MySqlCdcSecondReaderTest {

    @Test
    void aSecondReaderIsRefusedNamingTheReplicaIdWithoutConnecting() {
        MySqlCdcSourcePlugin plugin = new MySqlCdcSourcePlugin();
        plugin.configure(new TransactionAssemblerTest.Ctx(
                "customers-cdc",
                Map.of("host", "db.invalid", "user", "cdc", "table", "shop.customers", "server.id", "4242")));

        assertThat(plugin.secondReaderRefusal())
                .hasValueSatisfying(why -> assertThat(why)
                        .contains("server.id 4242")
                        .contains("displace the running version")
                        .contains("not the history"));
    }
}
