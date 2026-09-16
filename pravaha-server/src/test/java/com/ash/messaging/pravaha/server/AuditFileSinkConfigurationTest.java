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
package com.ash.messaging.pravaha.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.FileAuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code pravaha.security.audit: file} — the setting that leaves a record somebody can read.
 *
 * <p>CFG-23. {@code memory} is correct, complete and unreachable: nothing in any {@code src/main}
 * calls {@code events()}, so an operator asked "who read payroll" had nothing to answer from. This
 * asserts the other half of the fix — that the node builds the file sink, that the HTTP surface
 * shares the same object (the CFG-5 rule, which a second sink would quietly break), and that a path
 * that cannot be written stops the node instead of being discovered at the first decision nobody
 * sees.
 */
class AuditFileSinkConfigurationTest {

    private static PersistenceProperties persistence() {
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal("");
        return persistence;
    }

    private static PravahaNode node(SecurityProperties security) {
        return new PravahaNode(
                new StreamCatalog(),
                new SourceBindingProperties(),
                new StreamDeclarationProperties(),
                security,
                null,
                null,
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                false,
                "127.0.0.1",
                0,
                persistence(),
                "SINGLE",
                "single",
                "audit-file-node",
                true,
                false,
                null,
                null,
                false,
                "127.0.0.1",
                0);
    }

    private static SecurityProperties fileAudit(Path trail) {
        SecurityProperties security = new SecurityProperties();
        security.setAudit("file");
        security.setAuditFile(trail.toString());
        return security;
    }

    @Test
    void aDecisionMadeOnThisNodeIsReadableAfterTheProcessWouldHaveEnded(@TempDir Path directory) throws Exception {
        Path trail = directory.resolve("audit.jsonl");
        PravahaNode node = node(fileAudit(trail));

        AuditSink sink = node.auditSink();
        sink.record(AuditEvent.of(
                Principal.of("carol"), "query", "payroll", AccessDecision.deny("denied by policy"), "SELECT *"));
        ((FileAuditSink) sink).flush(Duration.ofSeconds(5));

        assertThat(Files.readString(trail))
                .as("the question CFG-23 says an operator cannot answer from a running node")
                .contains("\"principal\":\"carol\"")
                .contains("\"target\":\"payroll\"")
                .contains("\"result\":\"DENY\"");
        ((FileAuditSink) sink).close();
    }

    @Test
    void theHttpSurfaceWritesIntoTheSameFileSink(@TempDir Path directory) {
        Path trail = directory.resolve("audit.jsonl");
        PravahaNode node = node(fileAudit(trail));

        AuditSink http = new PravahaServerApplication().pravahaAuditSink(node);

        // CFG-5's rule, which applies to every sink and not only to memory: resolving the key a
        // second time would give the HTTP surface its own file handle on the same path, two
        // appenders interleaving into one file with no coordination.
        assertThat(http).isSameAs(node.auditSink()).isInstanceOf(FileAuditSink.class);
        ((FileAuditSink) http).close();
    }

    @Test
    void aPathThatCannotBeWrittenStopsTheNode(@TempDir Path directory) throws Exception {
        Path blocked = directory.resolve("occupied");
        Files.writeString(blocked, "not a directory");

        assertThatThrownBy(() -> node(fileAudit(blocked.resolve("audit.jsonl"))).auditSink())
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7004");
    }

    @Test
    void anUnknownAuditSettingNamesTheThreeThatWork() {
        SecurityProperties security = new SecurityProperties();
        security.setAudit("syslog");

        assertThatThrownBy(() -> node(security).auditSink())
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7004")
                .hasMessageContaining("'none', 'memory' or 'file'");
    }
}
