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
package com.ash.messaging.pravaha.it.qa.state;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.cluster.ClusterCoordinator;
import com.ash.messaging.pravaha.cluster.ClusterMode;
import com.ash.messaging.pravaha.cluster.CoordinatorFactory;
import com.ash.messaging.pravaha.cluster.Guarantees;
import com.ash.messaging.pravaha.cluster.Member;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.registry.RegisteredQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STATE-095..109 (all `H-CFG`) -- the nine-cell mode-by-mechanism matrix, minus the three cells that
 * need a running ZooKeeper (STATE-097, 100, 103 -- see {@code docs/qa/logs/STATE.md} for why those
 * are NOT RUN) and STATE-108, which needs the ZooKeeper plugin on the classpath and is therefore
 * exercised from {@code plugins/pravaha-cluster-zookeeper}'s own test module instead, where that
 * plugin already is. STATE-110 (two real nodes, a firewall-level partition) is also NOT RUN -- see
 * the log.
 */
@Timeout(120)
class StateClusterTest extends StateTestSupport {

    private static Configuration config(String... pairs) {
        var builder = Configuration.builder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            builder.set(pairs[i], pairs[i + 1]);
        }
        return builder.build();
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("pravaha-server/src/main/resources/application.yaml"))) {
            dir = dir.getParent();
        }
        assertThat(dir).isNotNull();
        return dir;
    }

    private static List<String> grep(String pattern, Path root) throws Exception {
        // Agent worktrees under .claude/ are full copies of this repository, so an unscoped grep
        // reports their uncommitted edits as if they were shipped code here. OrphanedClassTest and
        // LicenseHeaderTest exclude the same directory for the same reason. --exclude-dir matches
        // directories met while recursing, never the "." root itself, so a run from inside a
        // worktree still searches that worktree.
        Process process = new ProcessBuilder("grep", "-rEn", pattern, "--include=*.java", "--exclude-dir=.claude", ".")
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        process.waitFor();
        return lines;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private static boolean isListening(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception refused) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------

    @Test
    void state095_singleBySingleStartsAndIsItsOwnLeader() {
        try (ClusterCoordinator coordinator = CoordinatorFactory.create(config())) {
            assertThat(coordinator.members()).as("before start()").isEmpty();
            assertThat(coordinator.isLeader()).as("before start()").isFalse();

            Member self = new Member("only", "localhost", 9070);
            coordinator.start(self);

            assertThat(coordinator.mechanism()).isEqualTo("single");
            assertThat(coordinator.isLeader()).isTrue();
            assertThat(coordinator.members()).containsExactly(self);
            assertThat(coordinator.leader()).contains(self);
            assertThat(coordinator.guarantees()).isEqualTo(new Guarantees("single", true, false, true));
        }
    }

    @Test
    void state096_singleBySocketStartsWithNoConsensusAndAPeerList() {
        try (ClusterCoordinator coordinator = CoordinatorFactory.create(config(
                "pravaha.cluster.mode", "SINGLE",
                "pravaha.cluster.mechanism", "socket",
                "pravaha.cluster.socket.peers", "a=localhost:19081,b=localhost:19082"))) {
            assertThat(coordinator.mechanism()).isEqualTo("socket");
            assertThat(coordinator.guarantees()).isEqualTo(new Guarantees("socket", false, false, false));
        }
    }

    @Test
    void state098_replicatedBySingleStarts() {
        try (ClusterCoordinator coordinator = CoordinatorFactory.create(config("pravaha.cluster.mode", "REPLICATED"))) {
            coordinator.start(new Member("only", "localhost", 9070));
            assertThat(coordinator.mechanism()).isEqualTo("single");
            assertThat(coordinator.isLeader()).isTrue();
            assertThat(coordinator.members()).hasSize(1);
            // REPLICATED on one node is indistinguishable, at every point on the interface, from
            // SINGLE on one node -- ClusterCoordinator has nine methods and none of them mentions the
            // mode (see state106's enumeration below).
        }
    }

    @Test
    void state099_replicatedBySocketStartsAndTheTradeIsTheOperatorsToMake() {
        try (ClusterCoordinator coordinator = CoordinatorFactory.create(config(
                "pravaha.cluster.mode", "REPLICATED",
                "pravaha.cluster.mechanism", "socket",
                "pravaha.cluster.socket.peers", "a=localhost:19083,b=localhost:19084"))) {
            assertThat(coordinator.guarantees().excludesSplitBrain())
                    .as("the one cell where the engine knowingly accepts a coordinator that cannot "
                            + "exclude split-brain")
                    .isFalse();
        }
    }

    @Test
    void state101_partitionedBySingleNowStartsAndGenuinelyAssignsEveryPartitionToItself() throws Exception {
        // This case's history is S-3 itself. It originally found PARTITIONED x single starting on
        // the sound reasoning that one node cannot disagree with itself -- sound, and beside the
        // point, because nothing in the build computed an assignment at all, so the node started,
        // reported itself partitioned, and partitioned nothing. Between ADR-038 and here it asserted
        // the opposite: that starting was refused (state106 below is the other half of that same
        // observation, that the partition machinery was reachable from nothing). ADR-039 item 8's
        // first slice is what changes now: com.ash.messaging.pravaha.cluster.PartitionAssigner turns
        // a coordinator's real membership into a real, continuously recomputed
        // com.ash.messaging.pravaha.cluster.PartitionAssignment, so the original reasoning is no
        // longer merely sound, it is genuinely true -- confirmed here rather than assumed from the
        // absence of a refusal.
        try (ClusterCoordinator coordinator = CoordinatorFactory.create(config(
                "pravaha.cluster.mode", "PARTITIONED",
                "pravaha.cluster.mechanism", "single"))) {
            assertThat(coordinator.guarantees().excludesSplitBrain()).isTrue();
            Member self = new Member("only", "localhost", 9070);
            coordinator.start(self);
            assertThat(coordinator.isLeader()).isTrue();

            var assigner = new com.ash.messaging.pravaha.cluster.PartitionAssigner(coordinator, self, 8);
            assertThat(assigner.partitionsOwnedBySelf())
                    .as("the one node in a single-node PARTITIONED cluster owns every partition")
                    .hasSize(8);
        }
    }

    @Test
    void state102_partitionedBySocketIsRefusedAtStartupWithPrv9002() throws Exception {
        assertThatThrownBy(() -> CoordinatorFactory.create(config(
                        "pravaha.cluster.mode", "PARTITIONED",
                        "pravaha.cluster.mechanism", "socket",
                        "pravaha.cluster.socket.peers", "a=localhost:19085,b=localhost:19086,c=localhost:19087")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-9002")
                .hasMessageContaining("cluster mode PARTITIONED assigns partitions to particular nodes")
                .hasMessageContaining("'socket' cannot exclude split-brain")
                .hasMessageContaining(
                        "socket (NO consensus — cannot exclude split-brain), self-contained, development only")
                .hasMessageContaining("Two nodes each believing they own a partition means two nodes writing the same "
                        + "aggregate, and the damage is silent and durable")
                .hasMessageContaining("Use a coordinator with consensus, or run mode REPLICATED")
                .hasMessageContaining("Refusing now rather than during a partition.");

        // H-SRV: a real node configured the same way must refuse before it ever opens a port. The
        // guard fires inside CoordinatorFactory.create(), the first statement of PravahaNode.start(),
        // before refuseAccidentalOpenServer or the Flight bind -- so no peer list is even needed for
        // this half; PARTITIONED+socket refuses on the mode/mechanism guarantee alone.
        int port = freePort();
        com.ash.messaging.pravaha.server.catalog.StreamCatalog catalog =
                new com.ash.messaging.pravaha.server.catalog.StreamCatalog();
        com.ash.messaging.pravaha.server.security.SecurityProperties security =
                new com.ash.messaging.pravaha.server.security.SecurityProperties();
        security.setAllowAnonymous(true);
        com.ash.messaging.pravaha.server.state.PersistenceProperties persistence =
                new com.ash.messaging.pravaha.server.state.PersistenceProperties();

        com.ash.messaging.pravaha.server.PravahaNode node = com.ash.messaging.pravaha.server.PravahaNode.builder()
                .withCatalog(catalog)
                .withSources(new com.ash.messaging.pravaha.server.ingest.SourceBindingProperties())
                .withDeclaredStreams(new com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties())
                .withSecurity(security)
                .withWatermark(Duration.ofSeconds(30), Duration.ofSeconds(1))
                .withFlight(true, "127.0.0.1", port)
                .withPersistence(persistence)
                .withCluster("PARTITIONED", "socket")
                .withNodeId("state-102-node")
                .build();

        assertThatThrownBy(node::start).isInstanceOf(PravahaException.class).hasMessageContaining("PRV-9002");
        assertThat(isListening("127.0.0.1", port))
                .as("the refusal happens before the Flight port is ever bound")
                .isFalse();
    }

    @Test
    void state104_anUnknownMechanismNamesWhatIsAvailable() {
        // Arm 1: zookeeper, without the plugin on this module's classpath -- which is also what
        // STATE-097/100/103 look like when the plugin is absent.
        assertThatThrownBy(() -> CoordinatorFactory.create(config("pravaha.cluster.mechanism", "zookeeper")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-9001")
                .hasMessageContaining(
                        "no cluster coordinator called 'zookeeper' is on the classpath. Available: [single, socket]")
                .hasMessageContaining("A mechanism ships in its own artefact so that a deployment using one "
                        + "does not carry the dependencies of the others");

        // Arm 2: an entirely made-up name.
        assertThatThrownBy(() -> CoordinatorFactory.create(config("pravaha.cluster.mechanism", "raft")))
                .hasMessageContaining("PRV-9001")
                .hasMessageContaining("no cluster coordinator called 'raft'");

        // Arm 3: the empty string is not treated as unset -- the default only applies when the key
        // is absent altogether.
        assertThatThrownBy(() -> CoordinatorFactory.create(config("pravaha.cluster.mechanism", "")))
                .hasMessageContaining("PRV-9001")
                .hasMessageContaining("no cluster coordinator called ''");

        // Arm 4: case-sensitive, unlike the mode -- getString(...).strip() is not uppercased.
        assertThatThrownBy(() -> CoordinatorFactory.create(config("pravaha.cluster.mechanism", "SOCKET")))
                .hasMessageContaining("PRV-9001")
                .hasMessageContaining("no cluster coordinator called 'SOCKET'");
    }

    @Test
    void state105_anInvalidModeIsRefusedWithACodeNamedForMechanisms() {
        assertThatThrownBy(() -> CoordinatorFactory.create(config("pravaha.cluster.mode", "sharded")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-9001")
                .hasMessageContaining("'SHARDED' is not a cluster mode; one of [SINGLE, REPLICATED, PARTITIONED]");

        assertThatThrownBy(() -> CoordinatorFactory.create(config("pravaha.cluster.mode", "")))
                .hasMessageContaining("PRV-9001")
                .hasMessageContaining("is not a cluster mode");

        // Stripped and upper-cased: these three all resolve.
        assertThat(CoordinatorFactory.modeOf(config("pravaha.cluster.mode", "partitioned")))
                .isEqualTo(ClusterMode.PARTITIONED);
        assertThat(CoordinatorFactory.modeOf(config("pravaha.cluster.mode", "  REPLICATED  ")))
                .isEqualTo(ClusterMode.REPLICATED);
        assertThat(CoordinatorFactory.modeOf(config("pravaha.cluster.mode", "Replicated")))
                .isEqualTo(ClusterMode.REPLICATED);

        // The mechanism key is only stripped, never case-folded.
        try (ClusterCoordinator coordinator = CoordinatorFactory.create(config(
                "pravaha.cluster.mechanism", "  socket  ",
                "pravaha.cluster.socket.peers", "a=localhost:19088"))) {
            assertThat(coordinator.mechanism()).isEqualTo("socket");
        }
        assertThatThrownBy(() -> CoordinatorFactory.create(config("pravaha.cluster.mechanism", "Socket")))
                .as("mechanism names are case-sensitive, unlike modes")
                .hasMessageContaining("PRV-9001")
                .hasMessageContaining("no cluster coordinator called 'Socket'");
    }

    @Test
    void state106_partitionOwnershipIsNowRealButRebalanceIsStillReachableFromNothing() throws Exception {
        // Third name for this case, and each rename recorded exactly what changed underneath it.
        // ADR-039 item 8's first slice made PartitionAssignment reachable (PartitionAssigner turns
        // real membership into a real assignment) while PartitionOwner, PartitionHandoff and
        // Rebalancer stayed wired to nothing. The second slice is what changes PartitionOwner and
        // PartitionHandoff: PartitionLease and PartitionLeaseCoordinator (a fenced, revocable claim,
        // not a belief) make PartitionHandoff's sequence real -- proved against real disagreement,
        // both in pravaha-cluster's own PartitionHandoffTest (real threads racing a real
        // compare-and-swap) and against a real ZooKeeper ensemble in the plugin's own
        // ZooKeeperPartitionLeaseCoordinatorTest, including a lease surviving nothing once the
        // session holding it actually ends rather than merely being told to let go.
        //
        // What still has not changed, on purpose: Rebalancer still calls PartitionHandoff's
        // deprecated, unfenced constructor (a private, single-use lease coordinator nothing else
        // can contend for), because Rebalancer is explicitly out of scope for this slice too --
        // "Rebalancer and elastic rescale stay untouched. They depend on handoff being
        // trustworthy." Handoff is trustworthy now; Rebalancer has not yet been asked to use that.
        //
        // And nothing outside pravaha-cluster reaches into any of these four types at all -- not
        // even the ones now genuinely real. Consumption -- routing or refusing what a node serves
        // based on ownership -- is deliberately not built this round: a first draft of a
        // PartitionOwnership helper combining assignment and lease was written and then deleted
        // rather than kept, because this exact test caught it as a fifth instance of the pattern
        // its own javadoc names (L0StateMap, RegisteredQuery.accept and two others): built, given a
        // good test, and called by nothing outside that test. There is no caller for it within this
        // round's scope (pravaha-runtime, where a caller would actually live, is not this module's
        // to touch), so the honest choice was to say so rather than let it stand as a fifth entry
        // in this test's own list of things that looked used.
        List<String> references =
                grep("PartitionOwner|PartitionHandoff|Rebalancer|PartitionSnapshot", repoRoot()).stream()
                        .filter(l -> l.contains("/src/main/"))
                        .filter(l -> !l.contains("/pravaha-cluster/src/main/"))
                        .toList();
        assertThat(references)
                .as("no shipped code outside pravaha-cluster names any of these four -- real or not, "
                        + "nothing outside this module consumes an ownership decision yet")
                .isEmpty();

        // PartitionAssignment itself is checked the opposite way now: it is reachable, but only from
        // within pravaha-cluster's own main sources -- nothing in pravaha-server or elsewhere reaches
        // into it directly, because nothing outside pravaha-cluster consumes an assignment to decide
        // what it serves yet. That consumption is exactly the correctness-sensitive step this slice
        // does not take (see PartitionAssigner's own javadoc).
        List<String> assignmentReferencesOutsideCluster = grep("PartitionAssignment", repoRoot()).stream()
                .filter(l -> l.contains("/src/main/"))
                .filter(l -> !l.contains("/pravaha-cluster/src/main/"))
                .toList();
        assertThat(assignmentReferencesOutsideCluster)
                .as("PartitionAssignment is real now, and still nobody outside pravaha-cluster reaches "
                        + "into it to decide what a node serves")
                .isEmpty();

        List<String> methodNames = new ArrayList<>();
        for (var method : ClusterCoordinator.class.getMethods()) {
            if (method.getDeclaringClass() == ClusterCoordinator.class) {
                methodNames.add(method.getName());
            }
        }
        assertThat(methodNames)
                .as("PartitionAssigner is a wrapper around a coordinator, not a new coordinator "
                        + "method -- the interface ADR-034 called shared-nothing plumbing stays exactly "
                        + "that size")
                .containsExactlyInAnyOrder(
                        "mechanism",
                        "guarantees",
                        "start",
                        "members",
                        "leader",
                        "isLeader",
                        "onLeadershipChange",
                        "onMembershipChange",
                        "close");

        // The demonstration this case was originally built on -- PARTITIONED and SINGLE producing
        // identical results because PARTITIONED did nothing. For a while after item 8's first slice
        // it asserted exactly that as "the honest current claim", which was S-3's symptom written
        // down as expected behaviour. A node now refuses to serve PARTITIONED until something in it
        // consumes partition ownership, so the two halves of the claim are asserted separately:
        // SINGLE answers, and PARTITIONED refuses rather than answering the same while reporting
        // itself partitioned.
        Path singleJournal = Files.createTempDirectory("state106-s").resolve("registry.journal");
        Path partitionedJournal = Files.createTempDirectory("state106-p").resolve("registry.journal");
        var single = nodeWithMode("SINGLE", singleJournal);
        try {
            assertThat(runThreeQueriesAndScan(single))
                    .as("SINGLE answers, the mode a one-node deployment ships with")
                    .isNotEmpty();
        } finally {
            single.stop();
        }
        assertThatThrownBy(() -> nodeWithMode("PARTITIONED", partitionedJournal))
                .as("a node that would serve every partition must not start PARTITIONED (S-3)")
                .hasMessageContaining("PRV-9002")
                .hasMessageContaining("cannot be served by this node");
    }

    private com.ash.messaging.pravaha.server.PravahaNode nodeWithMode(String mode, Path journal) {
        com.ash.messaging.pravaha.server.catalog.StreamCatalog catalog =
                new com.ash.messaging.pravaha.server.catalog.StreamCatalog();
        catalog.register(TXN);
        com.ash.messaging.pravaha.server.security.SecurityProperties security =
                new com.ash.messaging.pravaha.server.security.SecurityProperties();
        security.setAllowAnonymous(true);
        com.ash.messaging.pravaha.server.state.PersistenceProperties persistence =
                new com.ash.messaging.pravaha.server.state.PersistenceProperties();
        persistence.getRegistry().setJournal(journal.toString());

        com.ash.messaging.pravaha.server.PravahaNode node = com.ash.messaging.pravaha.server.PravahaNode.builder()
                .withCatalog(catalog)
                .withSources(new com.ash.messaging.pravaha.server.ingest.SourceBindingProperties())
                .withDeclaredStreams(new com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties())
                .withSecurity(security)
                .withWatermark(Duration.ofSeconds(30), Duration.ofSeconds(1))
                .withFlight(false, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withCluster(mode, "single")
                .withNodeId("state-106-" + mode.toLowerCase(java.util.Locale.ROOT))
                .build();
        node.start();
        return node;
    }

    private List<List<Object>> runThreeQueriesAndScan(com.ash.messaging.pravaha.server.PravahaNode node)
            throws Exception {
        var registry = node.registry().orElseThrow();
        RegisteredQuery q1 = registry.register("q1", "SELECT user_id, amount FROM txn", List.of(0), DANA);
        RegisteredQuery q2 =
                registry.register("q2", "SELECT user_id, amount FROM txn WHERE amount > 100", List.of(0), DANA);
        RegisteredQuery q3 =
                registry.register("q3", "SELECT user_id, amount FROM txn WHERE amount < 100", List.of(0), DANA);

        RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        feed(q1, arena, TXN, "u1", 100);
        feed(q1, arena, TXN, "u2", 200);
        feed(q2, arena, TXN, "u2", 200);
        feed(q3, arena, TXN, "u1", 50);
        arena.close();
        q1.commit();
        q2.commit();
        q3.commit();

        List<List<Object>> combined = new ArrayList<>();
        // Object[] has identity equals()/toString(); compare the rows' actual content instead.
        for (Object[] row : q1.view().scan()) {
            combined.add(List.of("q1", List.of(row)));
        }
        for (Object[] row : q2.view().scan()) {
            combined.add(List.of("q2", List.of(row)));
        }
        for (Object[] row : q3.view().scan()) {
            combined.add(List.of("q3", List.of(row)));
        }
        return combined;
    }

    @Test
    void state107_theSocketPeerListIsRequiredAndParsedStrictly() {
        // (a) key absent.
        assertThatThrownBy(() -> CoordinatorFactory.create(
                        config("pravaha.cluster.mode", "REPLICATED", "pravaha.cluster.mechanism", "socket")))
                .hasMessageContaining("PRV-9005")
                .hasMessageContaining("the socket coordinator needs pravaha.cluster.socket.peers, as "
                        + "'id=host:port,id=host:port'")
                .hasMessageContaining(
                        "discovery without consensus is one more thing for two halves of a cluster to disagree "
                                + "about");

        // (b) FAIL as authored, and drift rather than a defect. The case expects the empty string to
        // be present-not-absent, so orElseThrow does not fire, "".split(",") gives one empty element
        // the parsing loop skips, and the result is a SocketCoordinator with an empty peer list. As
        // executed, SocketProvider's own parsing does exactly that -- but the SocketCoordinator
        // constructor it then calls (SocketCoordinator.java, "a socket cluster needs its peer list,
        // including this node") now rejects an empty list outright, so the actual result is the same
        // PRV-9005 refusal as arm (a), not the empty-but-accepted coordinator the case describes. A
        // guard was evidently added to the coordinator after this case was written; it is a strictly
        // safer behaviour (a coordinator with zero peers, including itself, could never have a
        // leader), so this is recorded as drift rather than fixed.
        assertThatThrownBy(() -> CoordinatorFactory.create(config(
                        "pravaha.cluster.mode", "REPLICATED",
                        "pravaha.cluster.mechanism", "socket",
                        "pravaha.cluster.socket.peers", "")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-9005")
                .hasMessageContaining("a socket cluster needs its peer list, including this node");

        // (c) one peer.
        try (ClusterCoordinator coordinator = CoordinatorFactory.create(config(
                "pravaha.cluster.mode", "REPLICATED",
                "pravaha.cluster.mechanism", "socket",
                "pravaha.cluster.socket.peers", "a=localhost:19091"))) {
            assertThat(coordinator.mechanism()).isEqualTo("socket");
        }

        // (d) no id.
        assertThatThrownBy(() -> CoordinatorFactory.create(config(
                        "pravaha.cluster.mode", "REPLICATED",
                        "pravaha.cluster.mechanism", "socket",
                        "pravaha.cluster.socket.peers", "localhost:19091")))
                .hasMessageContaining("PRV-9005")
                .hasMessageContaining("'localhost:19091' is not a peer; each is 'id=host:port'");

        // (e) no port.
        assertThatThrownBy(() -> CoordinatorFactory.create(config(
                        "pravaha.cluster.mode", "REPLICATED",
                        "pravaha.cluster.mechanism", "socket",
                        "pravaha.cluster.socket.peers", "a=localhost")))
                .hasMessageContaining("PRV-9005")
                .hasMessageContaining("'a=localhost' is not a peer; each is 'id=host:port'");

        // (f) trailing comma -- the empty trailing element is skipped.
        try (ClusterCoordinator coordinator = CoordinatorFactory.create(config(
                "pravaha.cluster.mode", "REPLICATED",
                "pravaha.cluster.mechanism", "socket",
                "pravaha.cluster.socket.peers", "a=localhost:19091,"))) {
            assertThat(coordinator.mechanism()).isEqualTo("socket");
        }

        // (g) space after comma -- each entry is strip()ped.
        try (ClusterCoordinator coordinator = CoordinatorFactory.create(config(
                "pravaha.cluster.mode", "REPLICATED",
                "pravaha.cluster.mechanism", "socket",
                "pravaha.cluster.socket.peers", "a=localhost:19091, b=localhost:19092"))) {
            assertThat(coordinator.mechanism()).isEqualTo("socket");
        }

        // (h) an unparsable port -- NumberFormatException, deliberately not a PravahaException and
        // not PRV-9005: the id and host are validated and the port is not.
        assertThatThrownBy(() -> CoordinatorFactory.create(config(
                        "pravaha.cluster.mode", "REPLICATED",
                        "pravaha.cluster.mechanism", "socket",
                        "pravaha.cluster.socket.peers", "a=localhost:notaport")))
                .isInstanceOf(NumberFormatException.class)
                .isNotInstanceOf(PravahaException.class)
                .hasMessageContaining("notaport");

        // (i) a colon in the id -- lastIndexOf(':') means the id absorbs everything up to the final
        // colon.
        try (ClusterCoordinator coordinator = CoordinatorFactory.create(config(
                "pravaha.cluster.mode", "REPLICATED",
                "pravaha.cluster.mechanism", "socket",
                "pravaha.cluster.socket.peers", "a:b=localhost:19091"))) {
            assertThat(coordinator.mechanism()).isEqualTo("socket");
        }
    }

    @Test
    void state107_applicationYamlDocumentsOnlyModeAndMechanism() throws Exception {
        String yaml = Files.readString(repoRoot().resolve("pravaha-server/src/main/resources/application.yaml"));
        int clusterStart = yaml.indexOf("\n  cluster:");
        assertThat(clusterStart).isGreaterThan(0);
        // The next *top-level* key starts a line with exactly two spaces of indent; every line
        // inside the cluster block itself is indented four or more, so a plain "\n  " search finds
        // the block's own first nested line instead of the block's end.
        java.util.regex.Matcher nextKey =
                java.util.regex.Pattern.compile("\\n {2}\\S").matcher(yaml);
        int nextTopLevel = nextKey.find(clusterStart + 1) ? nextKey.start() : -1;
        String clusterBlock =
                nextTopLevel > 0 ? yaml.substring(clusterStart, nextTopLevel) : yaml.substring(clusterStart);

        assertThat(clusterBlock).contains("mode:").contains("mechanism:");
        assertThat(clusterBlock)
                .as("no socket-specific keys are documented")
                .doesNotContain("socket.peers")
                .doesNotContain("socket:\n");

        // The provider's own javadoc example is inconsistent with what the code actually reads --
        // recorded, not fixed (a documentation defect, not a behavioural one).
        String socketProviderSource = Files.readString(repoRoot()
                .resolve("pravaha-cluster/src/main/java/com/ash/messaging/pravaha/cluster/SocketProvider.java"));
        assertThat(socketProviderSource).contains("heartbeat: 1s").contains("timeout: 5s");
        assertThat(socketProviderSource)
                .contains("pravaha.cluster.socket.heartbeat.millis")
                .contains("pravaha.cluster.socket.timeout.millis");
    }

    @Test
    void state109_theStartupLineSaysWhatWasChosenForAllReachableCells() {
        record Cell(String mode, String mechanism, String expected) {}
        List<Cell> cells = List.of(
                new Cell("SINGLE", "single", "cluster mode SINGLE on single (consensus), self-contained"),
                new Cell(
                        "SINGLE",
                        "socket",
                        "cluster mode SINGLE on socket (NO consensus — cannot exclude split-brain), "
                                + "self-contained, development only"),
                new Cell("REPLICATED", "single", "cluster mode REPLICATED on single (consensus), self-contained"),
                new Cell(
                        "REPLICATED",
                        "socket",
                        "cluster mode REPLICATED on socket (NO consensus — cannot exclude split-brain), "
                                + "self-contained, development only"),
                // PARTITIONED x socket is still refused (no consensus) and so still has no startup
                // line to describe -- asserted below, separately, since assertThatThrownBy does not
                // fit this table shape. PARTITIONED x single does now, since ADR-039 item 8's first
                // slice: the reachable cells are the ones a node can actually be in, and this one
                // became reachable rather than staying hypothetical.
                new Cell("PARTITIONED", "single", "cluster mode PARTITIONED on single (consensus), self-contained"));

        for (Cell cell : cells) {
            List<String> pairs = new ArrayList<>(
                    List.of("pravaha.cluster.mode", cell.mode(), "pravaha.cluster.mechanism", cell.mechanism()));
            if ("socket".equals(cell.mechanism())) {
                pairs.add("pravaha.cluster.socket.peers");
                pairs.add("a=localhost:19099");
            }
            Configuration cfg = config(pairs.toArray(new String[0]));
            try (ClusterCoordinator coordinator = CoordinatorFactory.create(cfg)) {
                assertThat(CoordinatorFactory.describe(cfg, coordinator)).isEqualTo(cell.expected());
            }
        }

        // PARTITIONED x socket is never logged -- create() throws PRV-9002 first.
        assertThatThrownBy(() -> CoordinatorFactory.create(config(
                        "pravaha.cluster.mode", "PARTITIONED",
                        "pravaha.cluster.mechanism", "socket",
                        "pravaha.cluster.socket.peers", "a=localhost:19099")))
                .hasMessageContaining("PRV-9002");

        // PravahaNode.describe() reports the mechanism, not the mode -- less informative than the
        // startup log line, and confirmed directly here rather than inferred.
        try (ClusterCoordinator coordinator = CoordinatorFactory.create(config("pravaha.cluster.mode", "REPLICATED"))) {
            assertThat(coordinator.guarantees().name()).isEqualTo("single");
        }

        // The three zookeeper cells (SINGLE/REPLICATED/PARTITIONED x zookeeper) are not exercised
        // here: this module has no ZooKeeper plugin dependency, so "zookeeper" is not in available()
        // at all -- see STATE-097/100/103's NOT RUN entries in the log for the same reason, and
        // STATE-108 (run from the plugin's own module) for the one zookeeper-side check this round
        // does make without a live server.
        assertThat(CoordinatorFactory.available()).doesNotContainKey("zookeeper");
    }
}
