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

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.catalog.Catalog;
import com.ash.messaging.pravaha.catalog.CatalogPolicy;
import com.ash.messaging.pravaha.catalog.CatalogService;
import com.ash.messaging.pravaha.catalog.ObjectKind;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An alert runs as its owner (ADR-059 §4): it sees the view it follows through the owner's row filters
 * and masks, its notifications carry the masked values, and a changed policy makes it follow again.
 */
@Timeout(60)
class AlertNarrowingTest {

    private static final Principal OPS = new Principal("ops", "acme", Set.of("admin"), Map.of());
    private static final Principal ANA = new Principal("ana", "acme", Set.of("analyst"), Map.of("region", "LDN"));

    private AlertFixture fixture;

    @BeforeEach
    void setUp() {
        Catalog catalog = Catalog.inMemory(Clock.systemUTC());
        catalog.infrastructure(() -> Map.<ObjectKind, Collection<String>>of(
                ObjectKind.STREAM, List.of("stock"), ObjectKind.NOTIFIER, List.of("buyers")));
        CatalogService service = new CatalogService(catalog, AuditSink.NONE);
        service.resolvingUsersWith(id -> id.equals("ana") ? Optional.of(ANA) : Optional.empty());
        fixture = new AlertFixture(new CatalogPolicy(service, List.of()), OPS);
        fixture.open(null);
        fixture.sql(OPS, "GRANT CREATE ON NAMESPACE default TO ROLE analyst");
        fixture.sql(OPS, "GRANT SELECT ON VIEW low_stock TO ROLE analyst");
        fixture.sql(OPS, "GRANT WRITE ON NOTIFIER buyers TO ROLE analyst");
        fixture.sql(OPS, "CREATE ROW FILTER mine AS warehouse = session_attribute('region') EXCEPT ROLE admin");
        fixture.sql(OPS, "CREATE MASK counted ON COLUMN on_hand AS 0 * on_hand EXCEPT ROLE admin");
        fixture.sql(OPS, "ALTER VIEW low_stock SET POLICY mine");
        fixture.sql(OPS, "ALTER VIEW low_stock SET POLICY counted");
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void theOwnersFilterDecidesWhatFiresAndTheirMaskWhatIsSent() {
        fixture.sql(ANA, "CREATE ALERT low ON low_stock NOTIFY buyers");
        fixture.stock("sku-1", "MAN", 1, 5, 1);
        fixture.stock("sku-2", "LDN", 4, 5, 1);
        fixture.tick();
        assertThat(fixture.sent()).containsExactly("FIRED sku-2/LDN");
        assertThat(fixture.channel.accepted.get(0).row()).containsEntry("on_hand", 0L);

        // The policy changes: the alert follows again under the new one, and MAN is now its owner's.
        fixture.sql(OPS, "ALTER VIEW low_stock UNSET POLICY mine");
        fixture.tick();
        fixture.tick();
        assertThat(fixture.sent()).contains("FIRED sku-1/MAN");
    }

    @Test
    void aConditionOnAMaskedColumnBreaksTheAlertByName() {
        fixture.sql(ANA, "CREATE ALERT tiny ON low_stock WHERE on_hand < 2 NOTIFY buyers");
        fixture.tick();
        assertThat(fixture.service.detail(ANA, "tiny").alert().problem()).contains("PRV-7006");
    }
}
