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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.sql.ContinuousStatements;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RECOVERYHEALTH-1: a registration refused at recovery stays visible -- listed {@code FAILED} with its
 * code -- until it is dropped, which removes it from the journal, or registered again.
 */
class RecoveryRefusalsTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    private static QueryRegistry registry(Path journal) {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)
                .journalTo(new RegistryJournal(journal));
        registry.recover(id -> id.equals("dana") ? Optional.of(DANA) : Optional.empty());
        return registry;
    }

    /** A journal with one recoverable registration and one over a stream this node no longer has. */
    private static Path journal(Path directory) {
        Path journal = directory.resolve("registry.journal");
        try (QueryRegistry first = registry(journal)) {
            first.register("good", "SELECT user_id, amount FROM txn", List.of(0), DANA);
        }
        new RegistryJournal(journal)
                .recordRegistration(
                        "gone", "SELECT a FROM removed_stream", List.of(0), "dana", Retention.DEFAULT, List.of());
        return journal;
    }

    @Test
    void aRefusedRegistrationIsListedFailedWithItsCodeUntilDropped(@TempDir Path directory) {
        Path journal = journal(directory);
        try (QueryRegistry second = registry(journal)) {
            assertThat(second.refusedAtRecovery().all()).singleElement().satisfies(refused -> {
                assertThat(refused.name()).isEqualTo("gone");
                assertThat(refused.code()).startsWith("PRV-");
                assertThat(refused.sql()).contains("removed_stream");
            });

            QueryListing listing = new QueryListing(second, SecurityPolicy.PERMISSIVE, AuditSink.NONE);
            List<QueryListing.RefusedEntry> refused = listing.refused(DANA, "list");
            assertThat(refused).singleElement().satisfies(entry -> {
                String[] row = entry.listRow();
                assertThat(row).hasSize(ControlWire.LIST_FIELDS.size());
                assertThat(row[ControlWire.listField("name")]).isEqualTo("gone");
                assertThat(row[ControlWire.listField("state")]).isEqualTo("FAILED");
                assertThat(row[ControlWire.listField("feed_state")]).isEqualTo("STOPPED");
                assertThat(row[ControlWire.listField("feed_code")]).startsWith("PRV-");
                assertThat(row[ControlWire.listField("feed_where")]).isEqualTo("recovery");
            });
            // SHOW CONTINUOUS QUERIES lists it beside the running one.
            var shown = new ContinuousQueryStatements(second, SecurityPolicy.PERMISSIVE, AuditSink.NONE)
                    .execute(
                            ContinuousStatements.recognize("SHOW CONTINUOUS QUERIES")
                                    .orElseThrow(),
                            DANA);
            assertThat(shown.rows()).anySatisfy(row -> {
                assertThat(row[0]).isEqualTo("gone");
                assertThat(row[1]).isEqualTo("FAILED");
            });

            // Dropped: gone from the listing and, through the journal, from the next start.
            second.drop("gone");
            assertThat(second.refusedAtRecovery().all()).isEmpty();
            assertThat(listing.refused(DANA, "list")).isEmpty();
        }
        try (QueryRegistry third = registry(journal)) {
            assertThat(third.refusedAtRecovery().all()).isEmpty();
            assertThat(third.find("good")).isPresent();
        }
    }

    @Test
    void aRefusedNameRegisteredAgainIsARunningQueryAndNotARefusedOne(@TempDir Path directory) {
        Path journal = journal(directory);
        try (QueryRegistry second = registry(journal)) {
            assertThat(second.refusedAtRecovery().find("gone")).isPresent();
            second.register("gone", "SELECT user_id FROM txn", List.of(0), DANA);
            assertThat(second.refusedAtRecovery().find("gone")).isEmpty();
            assertThat(second.find("gone")).isPresent();
        }
        try (QueryRegistry third = registry(journal)) {
            assertThat(third.refusedAtRecovery().all()).isEmpty();
            assertThat(third.find("gone")).isPresent();
        }
    }
}
