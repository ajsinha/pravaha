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
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.FileAuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        return PravahaNode.builder()
                .withCatalog(new StreamCatalog())
                .withSecurity(security)
                .withWatermark(Duration.ofSeconds(30), Duration.ofSeconds(1))
                .withFlight(false, "127.0.0.1", 0)
                .withPersistence(persistence())
                .withNodeId("audit-file-node")
                .build();
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

        AuditSink recorder = node.auditSink();
        recorder.record(AuditEvent.of(
                Principal.of("carol"), "query", "payroll", AccessDecision.deny("denied by policy"), "SELECT *"));
        // The node records through a readable trail whose durable half is the file.
        FileAuditSink sink = (FileAuditSink) ((com.ash.messaging.pravaha.security.AuditTrail) recorder).delegate();
        sink.flush(Duration.ofSeconds(5));

        assertThat(Files.readString(trail))
                .as("the question CFG-23 says an operator cannot answer from a running node")
                .contains("\"principal\":\"carol\"")
                .contains("\"target\":\"payroll\"")
                .contains("\"result\":\"DENY\"");
        sink.close();
    }

    @Test
    void theHttpSurfaceWritesIntoTheSameFileSink(@TempDir Path directory) {
        Path trail = directory.resolve("audit.jsonl");
        PravahaNode node = node(fileAudit(trail));

        AuditSink http = new PravahaServerApplication().pravahaAuditSink(node);

        // CFG-5's rule, which applies to every sink and not only to memory: resolving the key a
        // second time would give the HTTP surface its own file handle on the same path, two
        // appenders interleaving into one file with no coordination.
        assertThat(http).isSameAs(node.auditSink()).isInstanceOf(com.ash.messaging.pravaha.security.AuditTrail.class);
        AuditSink durable = ((com.ash.messaging.pravaha.security.AuditTrail) http).delegate();
        assertThat(durable).isInstanceOf(FileAuditSink.class);
        ((FileAuditSink) durable).close();
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

    /**
     * AUDITROTATE-1: a trail that cannot be written turns the node's health DEGRADED and is counted,
     * where it used to stop recording with the node UP and no metric moving.
     */
    @Test
    void anAuditTrailThatCannotBeWrittenDegradesHealthAndIsCounted(@TempDir Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("trail"));
        Path trail = directory.resolve("audit.jsonl");
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class));
        SecurityProperties security = fileAudit(trail);
        security.setAuditRotateBytes(4096);
        security.setAllowAnonymous(true);
        PravahaNode node = PravahaNode.builder()
                .withCatalog(new StreamCatalog())
                .withSecurity(security)
                .withFlight(true, "127.0.0.1", 0)
                .withPersistence(persistence())
                .withNodeId("audit-health-node")
                .build();
        node.start();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PravahaMetrics metrics = new PravahaMetrics(meters, node);
        AuditSink recorder = node.auditSink();
        FileAuditSink sink = (FileAuditSink) ((com.ash.messaging.pravaha.security.AuditTrail) recorder).delegate();
        try {
            assertThat(new EngineHealthIndicator(node).health().getStatus()).isEqualTo(Status.UP);
            while (Files.size(trail) < 3900) {
                recorder.record(AuditEvent.of(
                        Principal.of("carol"), "query", "payroll", AccessDecision.allow(), "SELECT * FROM payroll"));
                sink.flush(Duration.ofSeconds(5));
            }
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
            Files.setPosixFilePermissions(trail, PosixFilePermissions.fromString("r--------"));
            try {
                assumeTrue(!Files.isWritable(directory), "not root");
                for (int i = 0; i < 40; i++) {
                    recorder.record(AuditEvent.of(
                            Principal.of("carol"), "query", "payroll", AccessDecision.allow(), "SELECT 1"));
                }
                sink.flush(Duration.ofSeconds(5));

                Health health = new EngineHealthIndicator(node).health();
                assertThat(health.getStatus()).isEqualTo(EngineHealthIndicator.DEGRADED);
                assertThat(String.valueOf(health.getDetails().get("audit"))).contains("cannot write the audit trail");
                assertThat(meters.get("pravaha.audit.failing").gauge().value()).isEqualTo(1.0);
                assertThat(meters.get("pravaha.audit.unrecorded")
                                .functionCounter()
                                .count())
                        .isGreaterThan(0.0);
            } finally {
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
                Files.setPosixFilePermissions(trail, PosixFilePermissions.fromString("rw-------"));
            }

            recorder.record(
                    AuditEvent.of(Principal.of("carol"), "query", "payroll", AccessDecision.allow(), "writable again"));
            sink.flush(Duration.ofSeconds(5));
            assertThat(new EngineHealthIndicator(node).health().getStatus()).isEqualTo(Status.UP);
            assertThat(meters.get("pravaha.audit.failing").gauge().value()).isZero();
        } finally {
            metrics.close();
            meters.close();
            node.stop();
            sink.close();
        }
    }
}
