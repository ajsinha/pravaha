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

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.Principal;

import static com.ash.messaging.pravaha.registry.alert.AlertFixture.BUYER;
import static com.ash.messaging.pravaha.registry.alert.AlertFixture.LOW_STOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * ADR-060 with ADR-057: an alert's view is resolved in the alert's tenant, so two tenants' alerts on
 * the same view name each follow their own tenant's view -- and an alert cannot name another
 * tenant's. An alert's own name is unique within its tenant too: another tenant's is free, and nothing
 * a caller can ask says it exists.
 */
class AlertTenantNamesTest {

    private static final Principal OLI = new Principal("oli", "globex", Set.of("buyer"), Map.of());
    private static final Principal ROOT = new Principal("root", "public", Set.of("admin"), Map.of());

    @TempDir
    Path dir;

    @SuppressWarnings("NullAway.Init") // a test sets it before reading it
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
        assertThat(java.util.Objects.requireNonNull(held.getMessage()).replace("low_stock", "X"))
                .isEqualTo(java.util.Objects.requireNonNull(nobody.getMessage()).replace("nothing", "X"));
    }

    @Test
    void twoTenantsMayEachHaveAnAlertOfTheSameNameAndEachAddressesItsOwn() {
        fixture = new AlertFixture();
        fixture.registry.register("low_stock", LOW_STOCK, List.of(0, 1), OLI);
        fixture.open(dir.resolve("alerts.journal"));
        fixture.sql(BUYER, "CREATE ALERT low ON low_stock NOTIFY buyers");

        // Another tenant's name is free: PRV-8041 is for the caller's own tenant only.
        fixture.sql(OLI, "CREATE ALERT low ON low_stock NOTIFY buyers");
        assertThat(fixture.service.list(BUYER))
                .singleElement()
                .satisfies(s -> assertThat(s.tenant()).isEqualTo("acme"));
        assertThat(fixture.service.list(OLI)).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("low");
            assertThat(s.tenant()).isEqualTo("globex");
        });

        fixture.sql(OLI, "PAUSE ALERT low");
        assertThat(fixture.service.detail(OLI, "low").alert().state()).isEqualTo("PAUSED");
        assertThat(fixture.service.detail(BUYER, "low").alert().state()).isEqualTo("ACTIVE");

        // Within a tenant the name is still unique.
        assertThatThrownBy(() -> fixture.sql(BUYER, "CREATE ALERT low ON low_stock NOTIFY buyers"))
                .hasMessageContaining("PRV-8041");
    }

    @Test
    void anotherTenantsAlertAnswersEveryVerbAsAnAlertNobodyHolds() {
        fixture = new AlertFixture();
        fixture.open(dir.resolve("alerts.journal"));
        fixture.sql(BUYER, "CREATE ALERT secret_low ON low_stock NOTIFY buyers");

        for (String verb : List.of(
                "PAUSE ALERT %s", "RESUME ALERT %s", "DROP ALERT %s", "ACK ALERT %s", "SNOOZE ALERT %s FOR '1h'")) {
            assertThat(java.util.Objects.requireNonNull(refusal(OLI, verb.formatted("secret_low")))
                            .replace("secret_low", "X"))
                    .as(verb)
                    .isEqualTo(java.util.Objects.requireNonNull(refusal(OLI, verb.formatted("nothing")))
                            .replace("nothing", "X"));
        }
        assertThat(java.util.Objects.requireNonNull(refusal(() -> fixture.service.detail(OLI, "secret_low")))
                        .replace("secret_low", "X"))
                .isEqualTo(java.util.Objects.requireNonNull(refusal(() -> fixture.service.detail(OLI, "nothing")))
                        .replace("nothing", "X"));
        assertThat(java.util.Objects.requireNonNull(
                                refusal(() -> fixture.service.detail(OLI, "acme.default.secret_low")))
                        .replace("secret_low", "X"))
                .as("qualified: refused alike, held or not")
                .isEqualTo(java.util.Objects.requireNonNull(
                                refusal(() -> fixture.service.detail(OLI, "acme.default.nothing")))
                        .replace("nothing", "X"));
        assertThat(fixture.service.list(OLI)).isEmpty();
        assertThat(fixture.service.list(BUYER))
                .extracting(AlertStatus.Summary::name)
                .containsExactly("secret_low");
    }

    @Test
    void anAdminReachesAnotherTenantsAlertByItsQualifiedName() {
        fixture = new AlertFixture();
        fixture.open(dir.resolve("alerts.journal"));
        fixture.sql(BUYER, "CREATE ALERT low ON low_stock NOTIFY buyers");

        assertThat(fixture.service.list(ROOT))
                .extracting(AlertStatus.Summary::name)
                .containsExactly("acme.default.low");
        assertThat(fixture.service.pause(ROOT, "acme.default.low").state()).isEqualTo("PAUSED");
        assertThat(fixture.service.detail(BUYER, "low").alert().state()).isEqualTo("PAUSED");
        assertThatThrownBy(() -> fixture.service.detail(ROOT, "low"))
                .as("unqualified, a name is the admin's own tenant's")
                .hasMessageContaining("PRV-8040");
        assertThat(fixture.service.drop(ROOT, "acme.default.low", false)).isTrue();
        assertThat(fixture.service.list(BUYER)).isEmpty();
    }

    @Test
    void bothTenantsAlertsOfOneNameComeBackAfterARestartWithTheirOwnState() {
        fixture = new AlertFixture();
        fixture.registry.register("low_stock", LOW_STOCK, List.of(0, 1), OLI);
        fixture.open(dir.resolve("alerts.journal"));
        fixture.sql(BUYER, "CREATE ALERT low ON low_stock NOTIFY buyers");
        fixture.sql(OLI, "CREATE ALERT low ON low_stock NOTIFY buyers");
        fixture.sql(OLI, "PAUSE ALERT low");
        fixture.stock("sku-100", "LDN", 3, 10, 1);
        fixture.tick();

        // The journal records a definition's name and tenant, as it always has: an alert written before
        // names were per tenant -- in the default tenant or any other -- is read exactly this way.
        fixture.service.close();
        fixture.open(dir.resolve("alerts.journal"));

        assertThat(fixture.service.detail(BUYER, "low").alert()).satisfies(s -> {
            assertThat(s.state()).isEqualTo("ACTIVE");
            assertThat(s.firing()).isEqualTo(1);
        });
        assertThat(fixture.service.detail(OLI, "low").alert()).satisfies(s -> {
            assertThat(s.state()).isEqualTo("PAUSED");
            assertThat(s.firing()).isZero();
        });
        assertThat(fixture.service.firingByAlert()).containsOnlyKeys("acme.default.low", "globex.default.low");
    }

    private @Nullable String refusal(Principal who, String statement) {
        return refusal(() -> fixture.sql(who, statement));
    }

    private static @Nullable String refusal(Runnable call) {
        try {
            call.run();
            return "answered";
        } catch (PravahaException e) {
            return e.getMessage();
        }
    }
}
