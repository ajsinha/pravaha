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
package com.ash.messaging.pravaha.identity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Captive users: declared in configuration, ensured at every start, refused changes at runtime. */
class CaptiveUsersTest {

    static final String PASSWORD = "pravaha-dev-admin123";
    static final IdentitySettings DEV = IdentitySettings.defaults("dev").withDev(true);
    static final IdentitySettings PROD = IdentitySettings.defaults("prod");

    @TempDir
    Path dir;

    final List<AuditEvent> audit = new ArrayList<>();

    static CaptiveUsers.Entry literal(String name, String password, String... roles) {
        return new CaptiveUsers.Entry(name, "Dev admin", null, null, List.of(roles), password, null, null);
    }

    static List<CaptiveUsers.Account> resolve(IdentitySettings settings, CaptiveUsers.Entry... entries) {
        return CaptiveUsers.resolve(List.of(entries), settings, false, name -> null);
    }

    /** A start of the node: the store opened, the bootstrap, then the captive users ensured. */
    IdentityService start(List<CaptiveUsers.Account> accounts) {
        IdentityService identity = IdentityService.open(dir.resolve("identity.journal"), DEV, audit::add);
        identity.ensureCaptive(accounts);
        return identity;
    }

    static Principal admin() {
        return new Principal("admin", "public", Set.of("admin"), Map.of());
    }

    static String code(Throwable t) {
        return ((PravahaException) t).errorCode().code();
    }

    List<String> captiveAudit() {
        return audit.stream()
                .filter(e -> e.action().equals("identity.captive_ensured"))
                .map(AuditEvent::reason)
                .toList();
    }

    @Test
    void createdOnAnEmptyStoreBesideTheBootstrapAdmin() {
        IdentityService identity = start(resolve(DEV, literal("pravaha-dev-admin", PASSWORD, "admin")));
        List<String> names = identity.users(admin()).stream()
                .map(IdentityService.UserView::username)
                .toList();
        assertThat(names).containsExactly("admin", "pravaha-dev-admin");
        IdentityService.Login login = identity.login("pravaha-dev-admin", PASSWORD, null);
        assertThat(login.mustChangePassword()).isFalse();
        Principal me = identity.principalFor(login.token()).orElseThrow();
        assertThat(me.roles()).containsExactly("admin");
        assertThat(identity.isCaptive("pravaha-dev-admin")).isTrue();
        assertThat(identity.isCaptive("admin")).isFalse();
        assertThat(captiveAudit()).singleElement().asString().startsWith("created");
        assertThat(audit.toString()).doesNotContain(PASSWORD);
    }

    @Test
    void createdOnAStoreWithOtherUsers() {
        IdentityService first = start(List.of());
        first.createUser(admin(), "bob", null, null, null, Set.of("guest"), "Bob-password-12", false);
        IdentityService identity = start(resolve(DEV, literal("pravaha-dev-admin", PASSWORD, "admin")));
        assertThat(identity.users(admin()))
                .extracting(IdentityService.UserView::username)
                .containsExactly("admin", "bob", "pravaha-dev-admin");
    }

    @Test
    void reconciledAfterAnAdministratorDisabledItChangedItsRolesAndPassword() {
        // The journal as a node without the entry left it: the account exists, and was changed freely.
        IdentityService before = start(List.of());
        before.createUser(admin(), "pravaha-dev-admin", null, null, null, Set.of("guest"), "Other-password-1", false);
        before.updateUser(admin(), "pravaha-dev-admin", null, null, null, "disabled");

        start(resolve(DEV, literal("pravaha-dev-admin", PASSWORD, "admin")));
        assertThat(captiveAudit())
                .singleElement()
                .asString()
                .contains("roles [guest] -> [admin]", "re-enabled", "password reset")
                .doesNotContain(PASSWORD);

        // Replayed from the journal, as the next start sees it.
        IdentityService after = start(List.of());
        IdentityService.UserView view = after.users(admin()).stream()
                .filter(u -> u.username().equals("pravaha-dev-admin"))
                .findFirst()
                .orElseThrow();
        assertThat(view.status()).isEqualTo("active");
        assertThat(view.roles()).containsExactly("admin");
        after.login("pravaha-dev-admin", PASSWORD, null);
    }

    @Test
    void aSecondStartWithNothingChangedWritesNothing() throws Exception {
        List<CaptiveUsers.Account> accounts = resolve(DEV, literal("pravaha-dev-admin", PASSWORD, "admin"));
        start(accounts);
        long size = Files.size(dir.resolve("identity.journal"));
        audit.clear();
        start(accounts);
        assertThat(Files.size(dir.resolve("identity.journal"))).isEqualTo(size);
        assertThat(captiveAudit()).isEmpty();
    }

    @Test
    void aLockoutIsClearedAndTheConfiguredPasswordIsSetEvenWhenInTheHistory() {
        IdentityService before = start(List.of());
        before.createUser(admin(), "ops", null, null, null, Set.of("admin"), PASSWORD, false);
        before.setPassword(admin(), "ops", "Another-password-2"); // the configured one is now in the history
        for (int i = 0; i < 50; i++) {
            try {
                before.login("ops", "wrong", "10.0.0." + i);
            } catch (PravahaException expected) {
                // counted
            }
        }
        start(resolve(DEV, literal("ops", PASSWORD, "admin")));
        assertThat(captiveAudit()).singleElement().asString().contains("lockout cleared", "password reset");
        start(List.of()).login("ops", PASSWORD, null);
    }

    @Test
    void atRuntimeACaptiveUserCannotBeDisabledReRoledOrRePassworded() {
        IdentityService identity = start(resolve(DEV, literal("pravaha-dev-admin", PASSWORD, "admin")));
        String user = "pravaha-dev-admin";
        assertThatThrownBy(() -> identity.updateUser(admin(), user, null, null, null, "disabled"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("PRV-7023"))
                .hasMessageContaining("managed by configuration: pravaha.identity.captive-users");
        assertThatThrownBy(() -> identity.setRoles(admin(), user, Set.of("guest")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("PRV-7023"));
        assertThatThrownBy(() -> identity.setPassword(admin(), user, "Another-password-2"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("PRV-7023"));
        assertThatThrownBy(() -> identity.issueReset(admin(), user))
                .satisfies(t -> assertThat(code(t)).isEqualTo("PRV-7023"));
        Principal me = identity.principalFor(
                        identity.login(user, PASSWORD, null).token())
                .orElseThrow();
        assertThatThrownBy(() -> identity.changePassword(me, PASSWORD, "Another-password-2"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("PRV-7023"));
        // Still a user like any other: a profile change and an API key of its own.
        identity.updateUser(admin(), user, "Renamed", null, null, null);
        assertThat(identity.createKey(me, "laptop", null, null, null).key()).startsWith("prv_dev_");
    }

    @Test
    void everyMisconfigurationIsRefusedWithPrv7022() {
        assertRefused(DEV, "declared twice", literal("ops", PASSWORD, "admin"), literal("ops", PASSWORD, "admin"));
        assertRefused(
                DEV,
                "no password source",
                new CaptiveUsers.Entry("ops", null, null, null, List.of(), null, null, null));
        assertRefused(
                DEV,
                "2 password sources",
                new CaptiveUsers.Entry("ops", null, null, null, List.of(), PASSWORD, "OPS_PASSWORD", null));
        assertRefused(DEV, "policy refuses", literal("ops", "short", "admin"));
        assertRefused(DEV, "user name", literal("Ops!", PASSWORD, "admin"));
        assertRefused(DEV, "bootstrap administrator", literal("admin", PASSWORD, "operator"));
        assertRefused(PROD, "literal password", literal("ops", PASSWORD, "admin"));
        assertRefused(
                DEV, "not set", new CaptiveUsers.Entry("ops", null, null, null, List.of(), null, "OPS_PASSWORD", null));
        assertRefused(
                DEV,
                "cannot be read",
                new CaptiveUsers.Entry(
                        "ops",
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        dir.resolve("missing").toString()));
        // The bootstrap admin's name is fine when it keeps its role.
        assertThat(resolve(DEV, literal("admin", PASSWORD, "admin"))).hasSize(1);
    }

    @Test
    void aLiteralPasswordOutsideDevNeedsTheExplicitAllowance() {
        List<CaptiveUsers.Entry> entries = List.of(literal("ops", PASSWORD, "admin"));
        assertThat(CaptiveUsers.resolve(entries, PROD, true, name -> null)).hasSize(1);
        assertThatThrownBy(() -> CaptiveUsers.resolve(entries, PROD, false, name -> null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("PRV-7022"))
                .hasMessageContaining("allow-literal-captive-passwords");
    }

    @Test
    void aPasswordFromAnEnvironmentVariableOrAFile() throws Exception {
        Path file = dir.resolve("ops.password");
        Files.writeString(file, "From-a-file-123\n");
        List<CaptiveUsers.Account> accounts = CaptiveUsers.resolve(
                List.of(
                        new CaptiveUsers.Entry("ops", null, null, "acme", List.of("admin"), null, "OPS_PASSWORD", null),
                        new CaptiveUsers.Entry(
                                "svc", null, null, null, List.of("reader"), null, null, file.toString())),
                PROD,
                false,
                name -> name.equals("OPS_PASSWORD") ? "From-the-env-123" : null);
        assertThat(accounts.get(0).password()).isEqualTo("From-the-env-123");
        assertThat(accounts.get(0).tenant()).isEqualTo("acme");
        assertThat(accounts.get(1).password()).isEqualTo("From-a-file-123");
        assertThat(accounts.get(1).tenant()).isEqualTo("public");
        assertThat(accounts.toString()).doesNotContain("From-");
        IdentityService identity = start(accounts);
        identity.login("ops", "From-the-env-123", null);
        identity.login("svc", "From-a-file-123", null);
    }

    private static void assertRefused(IdentitySettings settings, String says, CaptiveUsers.Entry... entries) {
        assertThatThrownBy(() -> resolve(settings, entries))
                .satisfies(t -> assertThat(code(t)).isEqualTo("PRV-7022"))
                .hasMessageContaining(says);
    }
}
