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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.Principal;

import static com.ash.messaging.pravaha.registry.alert.AlertFixture.BUYER;
import static com.ash.messaging.pravaha.registry.alert.AlertFixture.LOW_STOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * ADR-060 with ADR-057: an alert's view is resolved in the alert's tenant, so two tenants' alerts on
 * the same view name each follow their own tenant's view -- and an alert cannot name another
 * tenant's.
 */
class AlertTenantNamesTest {

    private static final Principal OLI = new Principal("oli", "globex", Set.of("buyer"), Map.of());

    @TempDir
    Path dir;

    private AlertFixture fixture;

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void eachTenantsAlertFollowsItsOwnTenantsViewOfTheSameName() {
        fixture = new AlertFixture();
        // globex's own low_stock, beside acme's, over the same stream.
        fixture.registry.register("low_stock", LOW_STOCK, List.of(0, 1), OLI);
        fixture.open(dir.resolve("alerts.journal"));
        fixture.sql(BUYER, "CREATE ALERT acme_low ON low_stock NOTIFY buyers");
        fixture.sql(OLI, "CREATE ALERT globex_low ON low_stock NOTIFY buyers");

        // Only acme's view is fed.
        fixture.stock("sku-100", "LDN", 3, 10, 1);
        fixture.tick();

        assertThat(fixture.channel.accepted)
                .as("acme's row fires acme's alert and not globex's, which follows a view of its own")
                .singleElement()
                .satisfies(sent -> {
                    assertThat(sent.alert()).isEqualTo("acme_low");
                    assertThat(sent.view()).isEqualTo("low_stock");
                    assertThat(sent.tenant()).isEqualTo("acme");
                });
        assertThat(fixture.registry.dependantsOf("acme.default.low_stock")).containsExactly("ALERT acme_low");
        assertThat(fixture.registry.dependantsOf("globex.default.low_stock")).containsExactly("ALERT globex_low");
    }

    @Test
    void anAlertNamingAnotherTenantsViewIsRefusedAsAViewNobodyHolds() {
        fixture = new AlertFixture();
        fixture.open(dir.resolve("alerts.journal"));

        PravahaException held = catchThrowableOfType(
                PravahaException.class, () -> fixture.sql(OLI, "CREATE ALERT a ON low_stock NOTIFY buyers"));
        PravahaException nobody = catchThrowableOfType(
                PravahaException.class, () -> fixture.sql(OLI, "CREATE ALERT b ON nothing NOTIFY buyers"));

        assertThat(held.errorCode()).isEqualTo(nobody.errorCode());
        assertThat(held.getMessage().replace("low_stock", "X"))
                .isEqualTo(nobody.getMessage().replace("nothing", "X"));
    }
}
