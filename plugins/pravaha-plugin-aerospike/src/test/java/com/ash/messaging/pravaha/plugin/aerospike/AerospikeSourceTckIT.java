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
package com.ash.messaging.pravaha.plugin.aerospike;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.policy.WritePolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.testkit.tck.SourcePluginTck;

import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The Aerospike source, run against the conformance suite every source plugin is supposed to pass --
 * closing the gap that this connector, the one {@code docs/guides/CONNECTORS.md} holds up as the model, had
 * never actually been run against {@link SourcePluginTck}.
 *
 * <p><strong>It does not pass. {@code replayableOffsetsActuallyReplay} fails, confirmed against a
 * real container, not predicted:</strong>
 *
 * <pre>
 * [resuming from a recorded offset must yield exactly the unread remainder]
 * Expected size: 3 but was: 0 in: []
 * </pre>
 *
 * <p>This is a real defect in {@code LutScanReader}, not a test artefact, and it is deliberately left
 * failing here rather than weakened, disabled, or special-cased -- a red TCK result is the finding.
 * <strong>Root cause:</strong> {@code LutScanReader.scan()} advances {@code watermarkNanos} to the
 * scan's own <em>start time</em> as soon as the scan call returns -- {@code buffered.addAll(found)}
 * happens, then immediately {@code watermarkNanos = startedNanos} -- rather than advancing it once
 * per record as {@link com.ash.messaging.pravaha.api.plugin.PartitionReader#poll} actually delivers
 * them from that buffer. So {@code position()} returns the same token whether it is read after 0
 * records have been drained or after all of them have: "everything up to when this scan started."
 * With a 5-record fixture, polling 2 and then calling {@code position()} returns that same
 * whole-scan watermark; a reader resumed from it filters {@code lastUpdate >= watermarkNanos}, and
 * since every fixture record was written <em>before</em> the scan ran, that filter matches nothing --
 * the 3 unread records are not late, not duplicated, they are <strong>silently lost</strong>. The TCK
 * asserts the general {@code PartitionReader} contract ("must yield exactly the unread remainder"),
 * and this plugin's per-scan (rather than per-record) watermark cannot satisfy it whenever
 * {@code position()} is read mid-drain.
 *
 * <p>{@code AerospikePluginIT.resumingReadsOnlyWhatChangedSince} never exercises this path: it takes
 * {@code position()} only after fully draining a scan, so it tests "resume to see what changed since"
 * and never "resume mid-drain to get the rest of this scan" -- which is exactly what the TCK's
 * {@code replayableOffsetsActuallyReplay} tests, and exactly the case nothing had run before this.
 *
 * <p>Per instruction, {@code LutScanReader} is not touched here. Nothing else was weakened to make
 * this pass: {@code capabilitiesAreInternallyConsistent}, {@code readsEveryRecordExactlyOnce},
 * {@code pollIsNonBlockingAndReturnsZeroWhenExhausted}, {@code pauseStopsProductionAndResumeRestartsIt},
 * {@code discoversAtLeastOneSchema}, {@code reportsAtLeastOnePartition}, {@code declaresANameAndAVersion},
 * {@code closingIsIdempotent} and {@code healthIsReported} all pass because
 * {@code AerospikeSourcePlugin} genuinely does what each of them checks.
 *
 * <p>Uses the same real Community Edition container and host-networking workaround as
 * {@code AerospikePluginIT}; see that class's javadoc for why both are necessary.
 */
@Timeout(300)
class AerospikeSourceTckIT extends SourcePluginTck {

    private static final int RECORDS = 5;
    private static final AtomicInteger SET_SEQUENCE = new AtomicInteger();

    private static GenericContainer<?> aerospike;
    private static String hosts;
    private static IAerospikeClient admin;

    private final String set = "tck" + SET_SEQUENCE.incrementAndGet();

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    @BeforeAll
    static void startServer() {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker is not available; the Aerospike TCK run needs a real server")
                .isTrue();
        aerospike = AerospikeContainer.create();
        aerospike.start();
        hosts = "127.0.0.1:" + AerospikeContainer.port();
        com.aerospike.client.policy.ClientPolicy policy = new com.aerospike.client.policy.ClientPolicy();
        policy.failIfNotConnected = true;
        admin = connectWithRetries(policy);
    }

    /** Even after the migration log line, a client can catch the node mid-initialisation. See AerospikePluginIT. */
    private static IAerospikeClient connectWithRetries(com.aerospike.client.policy.ClientPolicy policy) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return new AerospikeClient(
                        policy, new com.aerospike.client.Host("127.0.0.1", AerospikeContainer.port()));
            } catch (RuntimeException e) {
                last = e;
                sleep(1000);
            }
        }
        throw last;
    }

    @AfterAll
    static void stopServer() {
        if (admin != null) {
            admin.close();
        }
        if (aerospike != null) {
            aerospike.stop();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String createFixture() {
        for (long id = 1; id <= RECORDS; id++) {
            admin.put(
                    new WritePolicy(),
                    new Key(AerospikeContainer.NAMESPACE, set, id),
                    new Bin("order_id", id),
                    new Bin("name", "row-" + id));
        }
        return set;
    }

    @Override
    protected StreamSourcePlugin createPlugin() {
        String setName = createFixture();
        AerospikeSourcePlugin plugin = new AerospikeSourcePlugin();
        plugin.configure(new Ctx(
                "tck",
                Map.of(
                        "hosts",
                        hosts,
                        "namespace",
                        AerospikeContainer.NAMESPACE,
                        "set",
                        setName,
                        "schema",
                        "order_id:INT64,name:STRING",
                        "stream",
                        "tck")));
        plugin.open();
        return plugin;
    }

    @Override
    protected String streamName() {
        return "tck";
    }

    @Override
    protected int expectedRecordCount() {
        return RECORDS;
    }

    @Override
    protected RowCollector newCollector(StreamSourcePlugin plugin) {
        return new AerospikeCollector(plugin.discoverSchemas().get(0));
    }
}
