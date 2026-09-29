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
package com.ash.messaging.pravaha.registry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

/**
 * Writes the state ADR-060's compatibility test loads, with the code of the commit it is run at.
 *
 * <p>{@code src/test/resources/adr060/develop-1f52ecf2} is what this wrote when run at develop
 * {@code 1f52ecf2}, before names were per tenant -- {@code -Dtest=Adr060DevelopFixture
 * -Dpravaha.fixture.out=<dir>} -- and {@link TenantViewNamesTest} loads a copy of it. Run on a later
 * commit it writes that commit's layout, which is not the fixture: it is kept to say how the fixture
 * was made, and skips without the property.
 */
class Adr060DevelopFixture {

    static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    static final Principal PAT = new Principal("pat", "public", Set.of("analyst"), Map.of());
    static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    @Test
    void write() throws Exception {
        String out = System.getProperty("pravaha.fixture.out");
        Assumptions.assumeTrue(out != null);
        Path root = Path.of(out);
        Files.createDirectories(root);
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN)
                        .journalTo(new RegistryJournal(root.resolve("registry.journal")))
                        .checkpointingTo(
                                root.resolve("checkpoints"),
                                Configuration.builder()
                                        .set("pravaha.checkpoint.interval", "1h")
                                        .set("pravaha.checkpoint.timeout", "10s")
                                        .build())) {
            RegisteredQuery spend =
                    registry.register("spend", "SELECT user_id, amount FROM txn WHERE amount > 0", List.of(0), PAT);
            RegisteredQuery totals = registry.register(
                    "totals", "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn", List.of(0), DANA);
            for (RegisteredQuery query : List.of(spend, totals)) {
                txn(arena, query, "u1", 100);
                txn(arena, query, "u2", 250);
                query.commit();
                checkpointerOf(query).checkpointNow();
            }
        }
    }

    private static PeriodicCheckpointer checkpointerOf(RegisteredQuery query) throws ReflectiveOperationException {
        java.lang.reflect.Field field = RegisteredQuery.class.getDeclaredField("checkpointer");
        field.setAccessible(true);
        return (PeriodicCheckpointer) field.get(query);
    }

    private static void txn(RowArena arena, RegisteredQuery query, String user, long amount) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setLong(1, amount);
        writer.weight(1L).eventTimestampNanos(amount).sequence(amount).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept("txn", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }
}
