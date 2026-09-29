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

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.Principal;

import static com.ash.messaging.pravaha.identity.IdentityServiceTest.GOOD;
import static com.ash.messaging.pravaha.identity.IdentityServiceTest.admin;
import static com.ash.messaging.pravaha.identity.IdentityServiceTest.catchIt;
import static com.ash.messaging.pravaha.identity.IdentityServiceTest.code;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * STORECLAIMS-1: a user's attributes, recorded in the identity store, are the claims every credential of
 * theirs presents -- so a policy reading {@code session_attribute('region')} can apply to them.
 */
class IdentityAttributesTest {

    private final IdentityServiceTest.Ticking clock = new IdentityServiceTest.Ticking();
    private final List<AuditEvent> audit = new ArrayList<>();

    private IdentityService service() {
        return IdentityService.inMemory(IdentitySettings.defaults("qa"), audit::add, clock);
    }

    @Test
    void aSessionAKeyAndTheUserDirectoryPresentTheAttributesAsClaims() {
        IdentityService identity = service();
        identity.createUser(admin(), "ana", null, null, null, Set.of("analyst"), GOOD, false);
        String session = identity.login("ana", GOOD, null).token();
        Principal ana = identity.principalFor(session).orElseThrow();
        assertThat(ana.claim("region")).isEmpty();
        IdentityService.IssuedKey key = identity.createKey(ana, "reports", null, null, null);

        IdentityService.UserView view = identity.setAttributes(admin(), "ana", Map.of("region", "EU", "desk", "rates"));
        assertThat(view.attributes()).containsExactly(Map.entry("desk", "rates"), Map.entry("region", "EU"));

        // Read from the store at each request: an open session and an existing key see the change at once.
        Principal viaSession = identity.principalFor(session).orElseThrow();
        assertThat(viaSession.claim("region")).contains("EU");
        assertThat(viaSession.claim("via")).contains("session");
        Principal viaKey = identity.principalFor(key.key()).orElseThrow();
        assertThat(viaKey.claim("region")).contains("EU");
        assertThat(viaKey.claim("desk")).contains("rates");
        assertThat(viaKey.claim("key")).contains(key.keyId());
        assertThat(identity.principalOfUser("ana").orElseThrow().claims())
                .containsExactlyInAnyOrderEntriesOf(Map.of("region", "EU", "desk", "rates"));

        // Replaced whole, like roles: what is left out is removed, from the key too.
        identity.setAttributes(admin(), "ana", Map.of("region", "US"));
        assertThat(identity.principalFor(key.key()).orElseThrow().claims())
                .containsEntry("region", "US")
                .doesNotContainKey("desk");

        assertThat(audit)
                .filteredOn(e -> e.action().equals("user.attributes_changed"))
                .extracting(AuditEvent::reason)
                .containsExactly("set [desk, region], removed []", "set [region], removed [desk]");
        assertThat(audit).noneMatch(e -> String.valueOf(e.reason()).contains("rates"));
    }

    @Test
    void onlyAnAdministratorSetsThemAndReservedOrMalformedOnesAreRefused() {
        IdentityService identity = service();
        identity.createUser(admin(), "ana", null, null, null, Set.of("analyst"), GOOD, false);
        Principal ana =
                identity.principalFor(identity.login("ana", GOOD, null).token()).orElseThrow();
        assertThat(code(catchIt(() -> identity.setAttributes(ana, "ana", Map.of("region", "EU")))))
                .isEqualTo("PRV-7002");
        for (Map<String, String> bad : List.of(
                Map.of("via", "sso"),
                Map.of("session", "x"),
                Map.of("key", "x"),
                Map.of("mustChangePassword", "false"),
                Map.of("9lives", "x"),
                Map.of("region", ""),
                Map.of("region", "E\nU"),
                Map.of("region", "x".repeat(257)))) {
            assertThat(code(catchIt(() -> identity.setAttributes(admin(), "ana", bad))))
                    .as(bad.toString())
                    .isEqualTo("PRV-7020");
        }
        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i < 33; i++) {
            many.put("a" + i, "v");
        }
        assertThat(code(catchIt(() -> identity.setAttributes(admin(), "ana", many))))
                .isEqualTo("PRV-7020");
        assertThat(code(catchIt(() -> identity.setAttributes(admin(), "nobody", Map.of("region", "EU")))))
                .isEqualTo("PRV-7021");
    }

    @Test
    void theAttributesAreJournalledAndARecordWrittenBeforeThemStillReplays(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("identity.journal");
        // A user record as the engine wrote it before attributes existed: seventeen fields.
        writeRecord(
                file,
                List.of(
                        "user",
                        "old",
                        "",
                        "",
                        "acme",
                        "analyst",
                        "active",
                        "0",
                        "",
                        "",
                        "0",
                        "",
                        "0",
                        "",
                        "",
                        "",
                        "2026-09-01T00:00:00Z"));
        IdentityService first = IdentityService.open(file, IdentitySettings.defaults("qa"), null);
        assertThat(first.principalOfUser("old").orElseThrow().claims()).isEmpty();
        first.setAttributes(admin(), "old", Map.of("region", "EU", "note", "a, b = c"));

        IdentityService second = IdentityService.open(file, IdentitySettings.defaults("qa"), null);
        assertThat(second.principalOfUser("old").orElseThrow().claims())
                .containsExactlyInAnyOrderEntriesOf(Map.of("region", "EU", "note", "a, b = c"));
        assertThat(second.principalOfUser("old").orElseThrow().roles()).containsExactly("analyst");
    }

    private static void writeRecord(Path file, List<String> fields) throws Exception {
        byte[] payload = ControlWire.encode(fields);
        ByteBuffer buffer =
                ByteBuffer.allocate(4 + payload.length).putInt(payload.length).put(payload);
        Files.write(file, buffer.array());
    }
}
