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

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.StaticTokenVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityServiceTest {

    static final String GOOD = "Correct-horse-9";

    /** A clock the test moves. */
    static final class Ticking extends Clock {
        Instant now = Instant.parse("2026-09-27T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    final Ticking clock = new Ticking();
    final List<AuditEvent> audit = new ArrayList<>();

    IdentityService service(IdentitySettings settings) {
        return IdentityService.inMemory(settings, audit::add, clock);
    }

    IdentityService service() {
        return service(IdentitySettings.defaults("qa"));
    }

    static Principal admin() {
        return new Principal("admin", "public", Set.of("admin", "operator"), Map.of());
    }

    static String code(Throwable t) {
        return ((PravahaException) t).errorCode().code();
    }

    @Test
    void theKdfIsSelfDescribingAndVerifiesBothForms() {
        String argon = Kdf.hash("s3cret");
        assertThat(argon).startsWith("argon2id$m=65536,t=3,p=4$");
        assertThat(Kdf.verify("s3cret", argon)).isTrue();
        assertThat(Kdf.verify("s3creT", argon)).isFalse();
        String pbkdf2 = Kdf.hashPbkdf2("s3cret");
        assertThat(Kdf.verify("s3cret", pbkdf2)).isTrue();
        assertThat(Kdf.needsRehash(pbkdf2)).isTrue();
        assertThat(Kdf.needsRehash(argon)).isFalse();
        assertThat(Kdf.hash("s3cret")).isNotEqualTo(argon);
    }

    @Test
    void thePolicyIsTwelveCharactersFromThreeClassesAndNoReuse() {
        PasswordPolicy policy = new PasswordPolicy(IdentitySettings.defaults("qa"));
        assertThat(policy.refusal("u", "Short-1", List.of())).isPresent();
        assertThat(policy.refusal("u", "alllowercaseletters", List.of())).isPresent();
        assertThat(policy.refusal("u", GOOD, List.of())).isEmpty();
        assertThat(policy.refusal("u", GOOD, List.of(Kdf.hash(GOOD)))).isPresent();
    }

    @Test
    void anEmptyStoreGetsTheBootstrapAdminAndOutsideDevItRefusesToStart() {
        IdentityService identity = service();
        assertThat(identity.defaultAdminPasswordInUse()).isTrue();
        assertThatThrownBy(identity::requireStartable)
                .satisfies(t -> assertThat(code(t)).isEqualTo("PRV-7019"));
        service(IdentitySettings.defaults("qa").withDev(true)).requireStartable();

        IdentityService.Login login = identity.login("admin", IdentityService.DEFAULT_ADMIN_PASSWORD, null);
        assertThat(login.mustChangePassword()).isFalse();
        Principal me = identity.principalFor(login.token()).orElseThrow();
        identity.changePassword(me, IdentityService.DEFAULT_ADMIN_PASSWORD, GOOD);
        identity.requireStartable();
    }

    @Test
    void aDeploymentSuppliedInitialPasswordReplacesThePublishedOne() {
        IdentityService identity = new IdentityService(
                IdentityStore.inMemory(), IdentitySettings.defaults("qa"), audit::add, clock, () -> GOOD);
        assertThat(identity.defaultAdminPasswordInUse()).isFalse();
        identity.requireStartable();
        identity.login("admin", GOOD, null);
        assertThat(code(catchIt(() -> identity.login("admin", IdentityService.DEFAULT_ADMIN_PASSWORD, null))))
                .isEqualTo("PRV-7010");

        assertThat(code(catchIt(() -> new IdentityService(
                        IdentityStore.inMemory(), IdentitySettings.defaults("qa"), audit::add, clock, () -> "short"))))
                .as("the initial password meets the same policy as every other")
                .isEqualTo("PRV-7012");
    }

    @Test
    void forcedChangeHappensOnlyWhenConfigured() {
        IdentityService plain = service();
        plain.createUser(admin(), "ana", null, null, null, Set.of("analyst"), GOOD, false);
        assertThat(plain.login("ana", GOOD, null).mustChangePassword()).isFalse();

        IdentityService forced = service(IdentitySettings.defaults("qa").withForceChange(true));
        forced.createUser(admin(), "ana", null, null, null, Set.of("analyst"), GOOD, false);
        IdentityService.Login login = forced.login("ana", GOOD, null);
        assertThat(login.mustChangePassword()).isTrue();
        assertThat(forced.principalFor(login.token()).orElseThrow().claims())
                .containsEntry("mustChangePassword", "true");
        assertThat(forced.login("admin", IdentityService.DEFAULT_ADMIN_PASSWORD, null)
                        .mustChangePassword())
                .isTrue();
    }

    @Test
    void fiveFailuresLockTheAccountAndOneMessageServesUnknownAndWrong() {
        IdentityService identity = service();
        identity.createUser(admin(), "ana", null, null, null, Set.of("analyst"), GOOD, false);
        Throwable unknown = catchIt(() -> identity.login("nobody", GOOD, null));
        Throwable wrong = catchIt(() -> identity.login("ana", "Wrong-password-1", null));
        assertThat(unknown.getMessage()).isEqualTo(wrong.getMessage());
        for (int i = 0; i < 4; i++) {
            catchIt(() -> identity.login("ana", "Wrong-password-1", null));
        }
        assertThat(code(catchIt(() -> identity.login("ana", GOOD, null)))).isEqualTo("PRV-7011");
        clock.advance(Duration.ofMinutes(31));
        identity.login("ana", GOOD, null);
        assertThat(audit).extracting(AuditEvent::action).contains("auth.lockout", "auth.login");
    }

    @Test
    void sessionsExpireWhenIdleAreCappedAtThreeAndEndOnPasswordChange() {
        IdentityService identity = service();
        identity.createUser(admin(), "ana", null, null, null, Set.of("analyst"), GOOD, false);
        String first = identity.login("ana", GOOD, null).token();
        String second = identity.login("ana", GOOD, null).token();
        identity.login("ana", GOOD, null);
        identity.login("ana", GOOD, null);
        assertThatThrownBy(() -> identity.principalFor(first)).isInstanceOf(PravahaException.class);
        Principal ana = identity.principalFor(second).orElseThrow();
        assertThat(ana.roles()).containsExactly("analyst");

        clock.advance(Duration.ofMinutes(31));
        assertThat(code(catchIt(() -> identity.principalFor(second)))).isEqualTo("PRV-7016");

        String a = identity.login("ana", GOOD, null).token();
        String b = identity.login("ana", GOOD, null).token();
        identity.changePassword(identity.principalFor(a).orElseThrow(), GOOD, "Another-horse-10");
        assertThat(identity.principalFor(a)).isPresent();
        assertThatThrownBy(() -> identity.principalFor(b)).isInstanceOf(PravahaException.class);
    }

    @Test
    void aKeyIsShownOnceNarrowsItsHolderAndCanBeRotatedAndRevoked() {
        IdentityService identity = service();
        identity.createUser(admin(), "ana", null, null, null, Set.of("analyst", "operator"), GOOD, false);
        Principal ana =
                identity.principalFor(identity.login("ana", GOOD, null).token()).orElseThrow();

        assertThat(code(catchIt(() -> identity.createKey(ana, "k", Set.of("admin"), null, null))))
                .isEqualTo("PRV-7015");
        assertThat(code(catchIt(() -> identity.createKey(ana, "k", null, 400, null))))
                .isEqualTo("PRV-7020");

        IdentityService.IssuedKey key = identity.createKey(ana, "reports", Set.of("analyst"), null, null);
        assertThat(key.key()).matches("prv_qa_[0-9a-f]{12}_[A-Za-z0-9-]{32}");
        assertThat(key.expiresAt()).isEqualTo(clock.now.plus(Duration.ofDays(90)));
        Principal viaKey = identity.principalFor(key.key()).orElseThrow();
        assertThat(viaKey.id()).isEqualTo("ana");
        assertThat(viaKey.roles()).containsExactly("analyst");
        assertThat(identity.keys(ana, false))
                .singleElement()
                .satisfies(k -> assertThat(k.lastUsedAt()).isNotNull());

        String wrongEnv = key.key().replace("prv_qa_", "prv_prod_");
        assertThat(code(catchIt(() -> identity.principalFor(wrongEnv)))).isEqualTo("PRV-7014");
        String wrongSecret = key.key().substring(0, key.key().length() - 3) + "AAA";
        assertThat(code(catchIt(() -> identity.principalFor(wrongSecret)))).isEqualTo("PRV-7013");

        IdentityService.Rotated rotated = identity.rotateKey(ana, key.keyId());
        assertThat(rotated.oldExpiresAt()).isEqualTo(clock.now.plus(Duration.ofDays(7)));
        assertThat(identity.principalFor(key.key())).isPresent();
        clock.advance(Duration.ofDays(8));
        assertThat(code(catchIt(() -> identity.principalFor(key.key())))).isEqualTo("PRV-7013");
        assertThat(identity.principalFor(rotated.successor().key())).isPresent();

        identity.revokeKey(ana, rotated.successor().keyId());
        assertThat(code(catchIt(() -> identity.principalFor(rotated.successor().key()))))
                .isEqualTo("PRV-7013");
    }

    @Test
    void aResetTokenIsSingleUseAndEndsEverySession() {
        IdentityService identity = service();
        identity.createUser(admin(), "ana", null, null, null, Set.of("analyst"), GOOD, false);
        String session = identity.login("ana", GOOD, null).token();
        IdentityService.Reset reset = identity.issueReset(admin(), "ana");
        identity.redeemReset(reset.token(), "Brand-new-pass-7");
        assertThatThrownBy(() -> identity.principalFor(session)).isInstanceOf(PravahaException.class);
        assertThat(code(catchIt(() -> identity.redeemReset(reset.token(), "Brand-new-pass-8"))))
                .isEqualTo("PRV-7017");
        identity.login("ana", "Brand-new-pass-7", null);
    }

    @Test
    void onlyAnAdministratorAdministers() {
        IdentityService identity = service();
        identity.createUser(admin(), "ana", null, null, null, Set.of("analyst"), GOOD, false);
        Principal ana =
                identity.principalFor(identity.login("ana", GOOD, null).token()).orElseThrow();
        assertThat(code(catchIt(() -> identity.users(ana)))).isEqualTo("PRV-7002");
        assertThat(code(catchIt(() -> identity.issueReset(ana, "admin")))).isEqualTo("PRV-7002");
        identity.updateUser(admin(), "ana", null, null, null, "disabled");
        assertThat(code(catchIt(() -> identity.login("ana", GOOD, null)))).isEqualTo("PRV-7010");
    }

    @Test
    void theJournalSurvivesARestart(@TempDir Path dir) {
        Path file = dir.resolve("identity/identity.journal");
        IdentitySettings settings = IdentitySettings.defaults("qa");
        IdentityService first = IdentityService.open(file, settings, null);
        first.createUser(admin(), "ana", "Ana", "ana@example.com", null, Set.of("analyst"), GOOD, false);
        String session = first.login("ana", GOOD, null).token();
        IdentityService.IssuedKey key = first.createKey(admin(), "ops", Set.of("admin"), 30, null);

        IdentityService second = IdentityService.open(file, settings, null);
        assertThat(second.users(admin()))
                .extracting(IdentityService.UserView::username)
                .contains("admin", "ana");
        assertThat(second.principalFor(session).orElseThrow().id()).isEqualTo("ana");
        assertThat(second.principalFor(key.key()).orElseThrow().roles()).containsExactly("admin");
        second.login("ana", GOOD, null);
    }

    @Test
    void theVerifierAnswersEveryRefusalAlikeAndFallsBackToStaticTokens() {
        IdentityService identity = service();
        IdentityTokenVerifier verifier = new IdentityTokenVerifier(
                identity,
                StaticTokenVerifier.of("legacy-token", new Principal("svc", "public", Set.of("operator"), Map.of())));
        assertThat(verifier.verify("legacy-token").id()).isEqualTo("svc");
        Throwable expired = catchIt(() -> verifier.verify("prv_s_nothing"));
        Throwable badKey = catchIt(() -> verifier.verify("prv_qa_000000000000_nothing"));
        assertThat(code(expired)).isEqualTo("PRV-7001");
        assertThat(expired.getMessage()).isEqualTo(badKey.getMessage());
        String token = identity.login("admin", IdentityService.DEFAULT_ADMIN_PASSWORD, null)
                .token();
        assertThat(verifier.verify(token).roles()).contains("admin");
    }

    static Throwable catchIt(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            return t;
        }
        throw new AssertionError("expected a refusal");
    }
}
