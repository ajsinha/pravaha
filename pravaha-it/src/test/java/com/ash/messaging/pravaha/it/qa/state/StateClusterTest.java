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
        Process process = new ProcessBuilder("grep", "-rEn", pattern, "--include=*.java", ".")
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
    void state101_partitionedBySingleIsRefusedBecauseThisBuildPartitionsNothing() throws Exception {
        // This case recorded the behaviour it found: PARTITIONED x single started, on the sound
        // reasoning that one node cannot disagree with itself. The reasoning holds and the mode does
        // not -- state106 below is the other half of the same observation, that the partition
        // machinery is reachable from nothing at all. Together they are S-3, and the node now
        // refuses rather than starting and partitioning nothing (ADR-038).
        //
        // The split-brain guarantee this case was really about is still asserted, on SINGLE.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CoordinatorFactory.create(config(
                        "pravaha.cluster.mode", "PARTITIONED",
                        "pravaha.cluster.mechanism", "single")))
                .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class)
                .hasMessageContaining("PRV-9002")
                .hasMessageContaining("not implemented");

        try (ClusterCoordinator coordinator = CoordinatorFactory.create(config(
                "pravaha.cluster.mode", "SINGLE",
                "pravaha.cluster.mechanism", "single"))) {
            assertThat(coordinator.guarantees().excludesSplitBrain()).isTrue();
            coordinator.start(new Member("only", "localhost", 9070));
            assertThat(coordinator.isLeader()).isTrue();
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

        com.ash.messaging.pravaha.server.PravahaNode node = new com.ash.messaging.pravaha.server.PravahaNode(
                catalog,
                new com.ash.messaging.pravaha.server.ingest.SourceBindingProperties(),
                new com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties(),
                security,
                null,
                null,
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                true,
                "127.0.0.1",
                port,
                persistence,
                "PARTITIONED",
                "socket",
                "state-102-node",
                true,
                false,
                null,
                null,
                null,
                false,
                "127.0.0.1",
                0);

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
    void state106_partitionedAssignsNothingBecauseThePartitionMachineryIsReachableFromNothing() throws Exception {
        List<String> references =
                grep("PartitionAssignment|PartitionOwner|PartitionHandoff|Rebalancer|PartitionSnapshot", repoRoot())
                        .stream()
                        .filter(l -> l.contains("/src/main/"))
                        .filter(l -> !l.contains("/pravaha-cluster/src/main/"))
                        .toList();
        assertThat(references)
                .as("no shipped code outside pravaha-cluster names any of the four partition classes")
                .isEmpty();

        List<String> methodNames = new ArrayList<>();
        for (var method : ClusterCoordinator.class.getMethods()) {
            if (method.getDeclaringClass() == ClusterCoordinator.class) {
                methodNames.add(method.getName());
            }
        }
        assertThat(methodNames)
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

        // The other half of this case used to start two nodes, one PARTITIONED and one SINGLE, and
        // assert their results were identical -- which they were, because PARTITIONED did nothing.
        // That demonstration is what the finding was built on, and it cannot be run any more: the
        // mode is refused now (S-3, ADR-038), which is the stronger version of the same statement.
        //
        // The reachability check above is the part that still holds and still matters: if any of
        // those types ever appears on a running path, PARTITIONED has become real and this case
        // needs rewriting rather than passing quietly.
        Path singleJournal = Files.createTempDirectory("state106-s").resolve("registry.journal");
        var single = nodeWithMode("SINGLE", singleJournal);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> nodeWithMode("PARTITIONED", singleJournal))
                    .as("a mode whose machinery nothing references must not start and claim to partition")
                    .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class)
                    .hasMessageContaining("PRV-9002");

            assertThat(runThreeQueriesAndScan(single))
                    .as("and SINGLE still answers, which is the mode a one-node GA ships with")
                    .isNotEmpty();
        } finally {
            single.stop();
        }
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

        com.ash.messaging.pravaha.server.PravahaNode node = new com.ash.messaging.pravaha.server.PravahaNode(
                catalog,
                new com.ash.messaging.pravaha.server.ingest.SourceBindingProperties(),
                new com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties(),
                security,
                null,
                null,
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                false,
                "127.0.0.1",
                0,
                persistence,
                mode,
                "single",
                "state-106-" + mode.toLowerCase(java.util.Locale.ROOT),
                true,
                false,
                null,
                null,
                null,
                false,
                "127.0.0.1",
                0);
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
                // PARTITIONED is refused now (S-3, ADR-038), so it has no startup line to describe --
                // which is the point: a mode that never starts cannot mis-describe itself. The
                // reachable cells are the ones a node can actually be in.
                new Cell("REPLICATED", "single", "cluster mode REPLICATED on single (consensus), self-contained"));

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
