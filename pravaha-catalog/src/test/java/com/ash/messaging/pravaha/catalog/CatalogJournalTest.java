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
package com.ash.messaging.pravaha.catalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;

import static com.ash.messaging.pravaha.catalog.CatalogAccessTest.ANA;
import static com.ash.messaging.pravaha.catalog.CatalogAccessTest.CLOCK;
import static com.ash.messaging.pravaha.catalog.CatalogAccessTest.OPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Everything the catalogue holds survives a restart, a torn tail and a compaction (ADR-059). */
class CatalogJournalTest {

    @TempDir
    Path directory;

    @Test
    void aReplayRestoresObjectsMetadataGrantsMovesRevocationsAndTheImport() {
        Path file = directory.resolve("catalog.journal");
        Catalog first = Catalog.open(file, CLOCK);
        first.createNamespace("acme.sales", Grantee.user("ops"), "Order-to-cash", false, "ops");
        first.registerView("revenue", OPS);
        first.move("acme.default.revenue", "acme.sales", "ops");
        first.comment("acme.sales.revenue", "Revenue per region", "ops");
        first.setTags("acme.sales.revenue", Map.of("domain", "finance", "certified", ""), "ops");
        first.unsetTags("acme.sales.revenue", List.of("certified"), "ops");
        first.grant("acme.sales", Privilege.USE, Grantee.role("analyst"), "ops");
        first.grant("acme.sales.revenue", Privilege.SELECT, Grantee.role("analyst"), "ops");
        first.grant("acme.sales.revenue", Privilege.SUBSCRIBE, Grantee.role("analyst"), "ops");
        first.revoke("acme.sales.revenue", Privilege.SUBSCRIBE, Grantee.role("analyst"));
        first.setOwner("acme.sales.revenue", Grantee.role("finance_data"), "ops");
        first.registerView("scratch", ANA);
        first.dropView("scratch");
        first.importPolicy("authenticated", CatalogPolicy.importedGrants("authenticated", "import", CLOCK.instant()));

        Catalog second = Catalog.open(file, CLOCK);
        CatalogObject revenue = second.object("acme.sales.revenue").orElseThrow();
        assertThat(revenue.description()).isEqualTo("Revenue per region");
        assertThat(revenue.tags()).isEqualTo(Map.of("domain", "finance"));
        assertThat(revenue.owner()).isEqualTo(Grantee.role("finance_data"));
        assertThat(revenue.version())
                .isEqualTo(first.object("acme.sales.revenue").orElseThrow().version());
        assertThat(second.byEngineName(ObjectKind.VIEW, "revenue")).contains(revenue);
        assertThat(second.byEngineName(ObjectKind.VIEW, "scratch")).isEmpty();
        assertThat(second.grantsOn("acme.sales.revenue"))
                .extracting(Grant::privilege)
                .containsExactly(Privilege.SELECT);
        assertThat(second.object("acme.sales").orElseThrow().description()).isEqualTo("Order-to-cash");
        assertThat(second.importedPolicy()).contains("authenticated");
        assertThat(second.grants()).isEqualTo(first.grants());
        assertThat(second.objects()).isEqualTo(first.objects());
    }

    @Test
    void aTornFinalRecordIsDroppedAndEverythingBeforeItKept() throws Exception {
        Path file = directory.resolve("catalog.journal");
        Catalog first = Catalog.open(file, CLOCK);
        first.grant(CatalogNames.ROOT, Privilege.SELECT, Grantee.role("analyst"), "ops");
        // A crash mid-append: a length that promises more than was written.
        Files.write(file, new byte[] {0, 0, 1, 0, 'g'}, StandardOpenOption.APPEND);
        Catalog second = Catalog.open(file, CLOCK);
        assertThat(second.grants()).hasSize(1);
    }

    @Test
    void compactionKeepsOnlyWhatIsLiveAndMeansTheSame() throws Exception {
        Path file = directory.resolve("catalog.journal");
        Catalog first = Catalog.open(file, CLOCK);
        for (int i = 0; i < 50; i++) {
            first.grant(CatalogNames.ROOT, Privilege.SELECT, Grantee.user("u" + i), "ops");
            first.revoke(CatalogNames.ROOT, Privilege.SELECT, Grantee.user("u" + i));
        }
        first.grant(CatalogNames.ROOT, Privilege.SELECT, Grantee.role("analyst"), "ops");
        long before = Files.size(file);
        first.compact();
        assertThat(Files.size(file)).isLessThan(before);
        assertThat(Files.exists(directory.resolve("catalog.journal.compacting")))
                .isFalse();

        Catalog second = Catalog.open(file, CLOCK);
        assertThat(second.grants()).isEqualTo(first.grants());
        assertThat(second.objects()).isEqualTo(first.objects());
    }

    @Test
    void aJournalGrownStaleIsCompactedAtOpen() throws Exception {
        Path file = directory.resolve("catalog.journal");
        Catalog first = Catalog.open(file, CLOCK);
        for (int i = 0; i < 400; i++) {
            first.grant(CatalogNames.ROOT, Privilege.SELECT, Grantee.user("u"), "ops");
            first.revoke(CatalogNames.ROOT, Privilege.SELECT, Grantee.user("u"));
        }
        long before = Files.size(file);
        Catalog.open(file, CLOCK);
        assertThat(Files.size(file)).isLessThan(before / 10);
    }

    @Test
    void aRecordFromANewerVersionIsRefusedByName() throws Exception {
        Path file = directory.resolve("catalog.journal");
        byte[] payload = com.ash.messaging.pravaha.api.wire.ControlWire.encode(List.of("zz", "x"));
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(4 + payload.length)
                .putInt(payload.length)
                .put(payload);
        Files.write(file, buffer.array());
        assertThatThrownBy(() -> Catalog.open(file, CLOCK))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7035")
                .hasMessageContaining("newer version");
    }

    @Test
    void theJournalIsOwnerOnly() throws Exception {
        Path file = directory.resolve("catalog.journal");
        Catalog.open(file, CLOCK).grant(CatalogNames.ROOT, Privilege.USE, Grantee.role("a"), "ops");
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(file))
                    .isEqualTo(Set.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        }
    }
}
