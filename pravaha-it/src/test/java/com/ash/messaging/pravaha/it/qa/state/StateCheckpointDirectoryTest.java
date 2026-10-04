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

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.RegistryErrors;
import com.ash.messaging.pravaha.registry.RegistryJournal;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STATE-023..035 -- a directory per query, and names that fight over it.
 *
 * <p>{@code QueryRegistry.checkpointingTo} gives each computation its own store. The name reaches
 * the filesystem, so it is encoded rather than trusted: {@code checkpointDirectoryFor} keeps
 * {@code [A-Za-z0-9-]} and rewrites every other byte as {@code _} plus two lowercase hex digits.
 */
@Timeout(120)
class StateCheckpointDirectoryTest extends StateTestSupport {

    private static Configuration slowCfg() {
        return Configuration.builder()
                .set("pravaha.checkpoint.interval", "1h")
                .set("pravaha.checkpoint.keep", "3")
                .build();
    }

    /**
     * Reflective access to the package-private static encoder, as STATE-027/028/029 require.
     *
     * <p>It moved from {@code QueryRegistry} to {@code QueryCheckpoints} when the checkpoint
     * placement came out of the registry (ADR-046); the encoding it checks is unchanged.
     */
    private static String encode(String name) throws Exception {
        Class<?> checkpoints = Class.forName("com.ash.messaging.pravaha.registry.QueryCheckpoints");
        Method m = checkpoints.getDeclaredMethod("directoryFor", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, name);
    }

    @Test
    void state023_eachRegisteredComputationGetsItsOwnDirectoryCreatedAtRegistration(@TempDir Path root)
            throws Exception {
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN_T).checkpointingTo(root, slowCfg())) {
            registry.register("w", WIN_SQL, List.of(0), DANA);
            registry.register(
                    "w2",
                    "SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id, TUMBLE(ts, INTERVAL '20' SECOND)",
                    List.of(0),
                    DANA);

            List<String> names;
            try (var files = Files.list(root)) {
                names = files.map(p -> p.getFileName().toString()).toList();
            }
            assertThat(names).containsExactlyInAnyOrder("w", "w2");
            assertThat(new FileCheckpointStore(root.resolve("w")).availableIds())
                    .isEmpty();
            assertThat(new FileCheckpointStore(root.resolve("w2")).availableIds())
                    .isEmpty();
        }
    }

    @Test
    void state024_theDirectoryNameIsTheEncodingNotTheRawName(@TempDir Path root) throws Exception {
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN_T).checkpointingTo(root, slowCfg())) {
            registry.register("my_view", WIN_SQL, List.of(0), DANA);
            try (var files = Files.list(root)) {
                assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("my_5fview");
            }
        }
        assertThat(encode("my_view")).isEqualTo("my_5fview");
    }

    @Test
    void state025_q1AndQUnderscore1DoNotShareADirectory(@TempDir Path root) throws Exception {
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN_T).checkpointingTo(root, slowCfg())) {
            registry.register("q_1", WIN_SQL, List.of(0), DANA);
            registry.register(
                    "q1",
                    "SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id, TUMBLE(ts, INTERVAL '30' SECOND)",
                    List.of(0),
                    DANA);
            List<String> names;
            try (var files = Files.list(root)) {
                names = files.map(p -> p.getFileName().toString()).toList();
            }
            assertThat(names).containsExactlyInAnyOrder("q_5f1", "q1");
        }
    }

    @Test
    void state026_qAndQUpperAreTwoDirectoriesOnACaseSensitiveFilesystem(@TempDir Path root) throws Exception {
        ViewCatalog views = new ViewCatalog();
        boolean caseSensitive =
                !Files.exists(root.resolve(root.getFileName().toString().toUpperCase(Locale.ROOT)))
                        || probeCaseSensitivity(root);
        try (QueryRegistry registry = new QueryRegistry(views, TXN_T).checkpointingTo(root, slowCfg())) {
            registry.register("Q", WIN_SQL, List.of(0), DANA);
            registry.register(
                    "q",
                    "SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id, TUMBLE(ts, INTERVAL '40' SECOND)",
                    List.of(0),
                    DANA);
            long count;
            try (var files = Files.list(root)) {
                count = files.count();
            }
            if (caseSensitive) {
                assertThat(count)
                        .as("ext4/xfs: case-sensitive, two directories")
                        .isEqualTo(2);
            } else {
                assertThat(count)
                        .as("case-insensitive filesystem: one directory shared")
                        .isEqualTo(1);
            }
        }
    }

    private static boolean probeCaseSensitivity(Path dir) {
        try {
            Path lower = Files.createFile(dir.resolve("case-probe-x"));
            boolean sensitive = !Files.exists(dir.resolve("CASE-PROBE-X"));
            Files.deleteIfExists(lower);
            return sensitive;
        } catch (Exception e) {
            return true; // assume Linux/ext4, this repo's usual environment
        }
    }

    @Test
    void state027_theEncoderIsInjectiveAcrossAHostileCorpus() throws Exception {
        String[] corpus = {
            "",
            ".",
            "..",
            "a",
            "A",
            "a.b",
            "a_b",
            "a b",
            "a-b",
            "ab",
            "a#!b",
            "a\"@b",
            "../../etc/passwd",
            "..\\..\\etc",
            "a/b",
            "a\\b",
            "a%2eb",
            "a_2eb",
            "q",
            "q1",
            "q_1",
            "q-1",
            "é",
            " "
        };
        Set<String> encoded = new LinkedHashSet<>();
        for (String name : corpus) {
            encoded.add(encode(name));
        }
        assertThat(encoded).hasSize(24);

        assertThat(encode("")).isEqualTo("");
        assertThat(encode(".")).isEqualTo("_2e");
        assertThat(encode("..")).isEqualTo("_2e_2e");
        assertThat(encode("a.b")).isEqualTo("a_2eb");
        assertThat(encode("a_b")).isEqualTo("a_5fb");
        assertThat(encode("a b")).isEqualTo("a_20b");
        assertThat(encode("a-b")).isEqualTo("a-b");
        assertThat(encode("a#!b")).isEqualTo("a_23_21b");
        assertThat(encode("a\"@b")).isEqualTo("a_22_40b");
        assertThat(encode("../../etc/passwd")).isEqualTo("_2e_2e_2f_2e_2e_2fetc_2fpasswd");
        assertThat(encode("a%2eb")).isEqualTo("a_252eb");
        assertThat(encode("a_2eb")).isEqualTo("a_5f2eb");
        assertThat(encode("é")).isEqualTo("_c3_a9");
        assertThat(encode(" ")).isEqualTo("_20");
    }

    @Test
    void state028_dotDotAndDotCannotEscapeTheCheckpointRoot() throws Exception {
        Path root = Path.of("/tmp/ckroot/a/b");
        for (String input : new String[] {".", "..", "../..", "/etc", "a/../.."}) {
            String encoded = encode(input);
            Path resolved = root.resolve(encoded).normalize();
            assertThat(resolved.startsWith(root))
                    .as("resolving the encoding of '" + input + "' must stay under the root")
                    .isTrue();
            assertThat(root.relativize(resolved).getNameCount())
                    .as("exactly one path element beyond root for '" + input + "'")
                    .isEqualTo(1);
        }
        assertThat(encode("..")).isEqualTo("_2e_2e");
        assertThat(encode("../..")).isEqualTo("_2e_2e_2f_2e_2e");
        assertThat(encode("/etc")).isEqualTo("_2fetc");
    }

    @Test
    void state029_twoNamesThatSaniseAlikeKeepSeparateDirectoriesAndDoNotPruneEachOther(@TempDir Path tmp)
            throws Exception {
        // Arm A: both stores share one directory -- the control, showing the shared-directory
        // failure is real.
        Path shared = tmp.resolve("shared");
        FileCheckpointStore x = new FileCheckpointStore(shared);
        FileCheckpointStore y = new FileCheckpointStore(shared);
        for (long id = 1; id <= 3; id++) {
            x.store(cp(id));
        }
        for (long id = 4; id <= 6; id++) {
            y.store(cp(id));
        }
        assertThat(x.availableIds()).containsExactlyInAnyOrder(6L, 5L, 4L, 3L, 2L, 1L);
        int removed = x.prune(3);
        assertThat(removed).isEqualTo(3);
        assertThat(x.availableIds()).containsExactlyInAnyOrder(6L, 5L, 4L);

        // Arm B: separate, encoded directories.
        Path root = tmp.resolve("root");
        FileCheckpointStore encX = new FileCheckpointStore(root.resolve(encode("a.b")));
        FileCheckpointStore encY = new FileCheckpointStore(root.resolve(encode("a_b")));
        for (long id = 1; id <= 3; id++) {
            encX.store(cp(id));
        }
        for (long id = 4; id <= 6; id++) {
            encY.store(cp(id));
        }
        assertThat(encX.availableIds()).containsExactly(3L, 2L, 1L);
        int removedB = encX.prune(3);
        assertThat(removedB).isZero();
        assertThat(encX.availableIds()).containsExactly(3L, 2L, 1L);
        assertThat(encY.availableIds()).containsExactly(6L, 5L, 4L);
    }

    @Test
    void state030_aUnicodeViewNameIsRefusedBeforeItReachesTheFilesystem(@TempDir Path root) throws Exception {
        // As executed on current develop: requireSayableName's regex is [\p{L}_][\p{L}\p{N}_]*, and
        // its own comment says the ASCII-only version was deliberately widened ("my first version
        // refused a name like 金额 that the planner resolves perfectly well"). So 'café' is a letter
        // followed by letters -- é is \p{L} -- and passes both the regex and Calcite's identifier
        // parser. STATE.md's authored premise (the regex is ASCII-only) no longer holds: the name
        // registers, and gets a directory, exactly what this case's Falsifier describes.
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).checkpointingTo(root, slowCfg())) {
            RegisteredQuery q = registry.register("café", "SELECT user_id, amount FROM txn", List.of(0), DANA);
            assertThat(q.name()).isEqualTo("café");
            List<String> names;
            try (var files = Files.list(root)) {
                names = files.map(p -> p.getFileName().toString()).toList();
            }
            assertThat(names)
                    .as("a directory *does* appear, contrary to STATE-030 as authored")
                    .containsExactly(encode("café"));
            assertThat(encode("café")).isEqualTo("caf_c3_a9");
        }
    }

    @Test
    void state031_hostileNamesAreRefusedAtThePublicDoor(@TempDir Path root) {
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).checkpointingTo(root, slowCfg())) {
            String sql = "SELECT user_id, amount FROM txn";
            List<String> unusable = List.of("..", ".", "a.b", "a b", "a#!b", "../../etc", "1q", "q-1");
            for (String name : unusable) {
                assertThatThrownBy(() -> registry.register(name, sql, List.of(0), DANA))
                        .as("name=<" + name + ">")
                        .isInstanceOf(PravahaException.class)
                        .hasMessageContaining("cannot be used as a view name");
            }
            // As executed: requireName's blank check (name == null || name.isBlank()) now runs
            // before requireSayableName, so both blank forms throw IllegalArgumentException rather
            // than the PravahaException STATE-031 expects for them.
            for (String blank : List.of("", "   ")) {
                assertThatThrownBy(() -> registry.register(blank, sql, List.of(0), DANA))
                        .as("blank=<" + blank + ">")
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("a registration needs a name");
            }
            for (String reserved : List.of("select", "from")) {
                assertThatThrownBy(() -> registry.register(reserved, sql, List.of(0), DANA))
                        .as("reserved=" + reserved)
                        .isInstanceOf(PravahaException.class)
                        .hasMessageContaining("cannot appear in a FROM clause")
                        .hasMessageContaining("reserved word in SQL");
            }
            // A long name is refused too, and for its own reason. It used to be told it was a
            // reserved word, which is false and sends whoever chose it looking for a list they will
            // not find themselves on: the refusal now quotes what the parser actually said.
            String tooLong = "a".repeat(500);
            assertThatThrownBy(() -> registry.register(tooLong, sql, List.of(0), DANA))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("cannot appear in a FROM clause");
            // As executed on current develop: requireName checks for null before requireSayableName
            // (QueryRegistry.java:843-849, and its own comment records the fix), so null now throws
            // the intended IllegalArgumentException rather than an NPE -- STATE.md's authored
            // expectation of a bare NullPointerException here no longer holds.
            assertThatThrownBy(() -> registry.register(null, sql, List.of(0), DANA))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("a registration needs a name");

            long count;
            try (var files = Files.list(root)) {
                count = files.count();
            } catch (Exception e) {
                count = 0;
            }
            assertThat(count).isZero();
        }
    }

    @Test
    void state032_aHandEditedJournalNamingDotDotEtcIsRefusedAtReplayNotTraversed(@TempDir Path tmp) throws Exception {
        Path journalFile = tmp.resolve("registry.journal");
        new RegistryJournal(journalFile)
                .recordRegistration(
                        "../../etc",
                        "SELECT user_id, amount FROM txn",
                        List.of(0),
                        "dana",
                        Retention.DEFAULT,
                        List.of());

        Path root = tmp.resolve("checkpoints");
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN)
                .journalTo(new RegistryJournal(journalFile))
                .checkpointingTo(root, slowCfg())) {
            QueryRegistry.Recovery r = registry.recover(id -> Optional.of(DANA));
            assertThat(r.recovered()).isEmpty();
            assertThat(r.refused()).hasSize(1);
            assertThat(r.refused().get(0).toString())
                    .startsWith("../../etc: ")
                    .contains("cannot be used as a view name");
            // Not an authorization refusal -- the name itself is unusable, so the refusal keeps
            // register()'s own code rather than being relabelled PRV-8007.
            assertThat(r.refused().get(0).code()).contains(RegistryErrors.NAME_UNUSABLE);
            assertThat(r.complete()).isFalse();

            long count;
            try (var files = Files.list(root)) {
                count = files.count();
            } catch (Exception e) {
                count = 0;
            }
            assertThat(count).isZero();
            assertThat(Files.exists(root.getParent().getParent())).isTrue();
            try (var files = Files.list(root.getParent().getParent())) {
                assertThat(files.map(p -> p.getFileName().toString()).toList())
                        .as("nothing was written above the checkpoint root")
                        .doesNotContain("etc");
            }
        }
    }

    @Test
    void state033_theCheckpointRootIsCreatedIfAbsentAndAFileWhereItShouldBeIsAHardFailure(@TempDir Path tmp)
            throws Exception {
        // Arm A: absent -- registration succeeds and the directory is created.
        Path rootA = tmp.resolve("a").resolve("ck");
        ViewCatalog viewsA = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(viewsA, TXN_T).checkpointingTo(rootA, slowCfg())) {
            registry.register("w", WIN_SQL, List.of(0), DANA);
            assertThat(Files.isDirectory(rootA.resolve("w"))).isTrue();
        }

        // Arm B: the root is a regular file.
        Path rootB = tmp.resolve("b").resolve("ck");
        Files.createDirectories(rootB.getParent());
        Files.createFile(rootB);
        ViewCatalog viewsB = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(viewsB, TXN_T).checkpointingTo(rootB, slowCfg())) {
            assertThatThrownBy(() -> registry.register("w", WIN_SQL, List.of(0), DANA))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-4093")
                    .hasMessageContaining("cannot create the checkpoint directory")
                    .hasMessageContaining(rootB.resolve("w").toString());
            assertThat(registry.names()).doesNotContain("w");
        }

        // Arm C: the root exists but is not writable.
        Path rootC = tmp.resolve("c").resolve("ck");
        Files.createDirectories(rootC);
        if (rootC.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(
                    rootC,
                    Set.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
            try {
                ViewCatalog viewsC = new ViewCatalog();
                try (QueryRegistry registry = new QueryRegistry(viewsC, TXN_T).checkpointingTo(rootC, slowCfg())) {
                    assertThatThrownBy(() -> registry.register("w", WIN_SQL, List.of(0), DANA))
                            .isInstanceOf(PravahaException.class)
                            .hasMessageContaining("cannot create the checkpoint directory");
                }
            } finally {
                Files.setPosixFilePermissions(
                        rootC,
                        Set.of(
                                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
            }
        }
    }

    @Test
    void state034_droppingAQueryDeletesItsCheckpointDirectory(@TempDir Path root) throws Exception {
        // ST-1, fixed: drop used to re-derive the directory from query.name() *after*
        // removeName had emptied the name set, so name() fell through to the fingerprint's 12-hex
        // short form -- a directory that had never existed. Files.list threw NoSuchFileException
        // into a catch that could not tell it from a real one, and every drop leaked its
        // checkpoints. The registry now records the path the checkpointer was given and deletes
        // that.
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN_T).checkpointingTo(root, slowCfg())) {
            RegisteredQuery w = registry.register("w", WIN_SQL, List.of(0), DANA);
            PeriodicCheckpointer checkpointer = checkpointerOf(w);
            for (int i = 0; i < 3; i++) {
                checkpointer.checkpointNow();
            }
            assertThat(new FileCheckpointStore(root.resolve("w")).availableIds())
                    .hasSize(3);

            registry.drop("w");
            List<String> remaining;
            try (var files = Files.list(root)) {
                remaining = files.map(p -> p.getFileName().toString()).toList();
            }
            assertThat(remaining)
                    .as("the checkpoint directory goes with the computation that owned it")
                    .isEmpty();
            assertThat(new FileCheckpointStore(root.resolve("w")).availableIds())
                    .as("and so do the three checkpoint files in it")
                    .isEmpty();
        }
    }

    @Test
    void state035_droppingTheLastNameOfASharedComputationDeletesTheWrongDirectory(@TempDir Path root) throws Exception {
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN_T).checkpointingTo(root, slowCfg())) {
            RegisteredQuery alpha = registry.register("alpha", WIN_SQL, List.of(0), DANA);
            registry.register("beta", WIN_SQL, List.of(0), DANA);
            PeriodicCheckpointer checkpointer = checkpointerOf(alpha);
            for (int i = 0; i < 3; i++) {
                checkpointer.checkpointNow();
            }

            List<String> afterRegister;
            try (var files = Files.list(root)) {
                afterRegister = files.map(p -> p.getFileName().toString()).toList();
            }
            assertThat(afterRegister).containsExactly("alpha");
            assertThat(new FileCheckpointStore(root.resolve("alpha")).availableIds())
                    .hasSize(3);

            registry.drop("alpha");
            List<String> afterDropAlpha;
            try (var files = Files.list(root)) {
                afterDropAlpha = files.map(p -> p.getFileName().toString()).toList();
            }
            assertThat(afterDropAlpha).containsExactly("alpha");
            assertThat(new FileCheckpointStore(root.resolve("alpha")).availableIds())
                    .hasSize(3);

            registry.drop("beta");
            List<String> afterDropBeta;
            try (var files = Files.list(root)) {
                afterDropBeta = files.map(p -> p.getFileName().toString()).toList();
            }
            assertThat(afterDropBeta)
                    .as("the last name goes, so the computation goes, and alpha's directory goes with "
                            + "it -- the directory the checkpointer was actually started with, which is "
                            + "the thing neither the dropped name nor the fingerprint could name")
                    .isEmpty();
            assertThat(new FileCheckpointStore(root.resolve("alpha")).availableIds())
                    .isEmpty();
        }
    }
}
