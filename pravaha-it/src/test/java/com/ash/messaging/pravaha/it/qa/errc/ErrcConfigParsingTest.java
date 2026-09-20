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
package com.ash.messaging.pravaha.it.qa.errc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.common.config.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ERRC-001 .. ERRC-011 -- the {@code ConfigErrors} family (PRV-1001 .. PRV-1026), reached through the
 * embedded engine's own {@code ConfigurationBuilder}/{@code Configuration}, which is the real class
 * every product entry point (the server, the CLI, embedded mode) builds its configuration through.
 * Not a call to the throwing method: {@code addFile}, {@code addOptionalFile} and the typed {@code
 * get*} accessors are the actual public surface a caller uses.
 */
class ErrcConfigParsingTest extends ErrcTestSupport {

    // ------------------------------------------------------------ ERRC-001 -- PRV-1001

    @Test
    void aConfigurationFileThatDoesNotExistNamesTheAbsolutePath() {
        Path absent = qa.resolve("conf/absent.properties");

        ConfigurationException e =
                catchConfig(() -> Configuration.builder().addFile(absent).build());

        assertThat(e.errorCode().code()).isEqualTo("PRV-1001");
        assertThat(e.errorCode().name()).isEqualTo("CONFIG_FILE_UNREADABLE");
        // E3: the absolute path, not a relative fragment -- the whole point of this code over a bare
        // IOException is that the operator can open the exact file named.
        assertThat(e.getMessage()).contains(absent.toAbsolutePath().toString());
    }

    @Test
    void aConfigurationFileWithNoReadPermissionAlsoNamesItButWithADifferentMessage() throws IOException {
        Path unreadable = qa.resolve("conf/mode000.properties");
        Files.createDirectories(unreadable.getParent());
        Files.writeString(unreadable, "a=1\n");
        Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"));

        try {
            ConfigurationException e = catchConfig(
                    () -> Configuration.builder().addFile(unreadable).build());

            assertThat(e.errorCode().code()).isEqualTo("PRV-1001");
            assertThat(e.errorCode().name()).isEqualTo("CONFIG_FILE_UNREADABLE");
            // Two throw sites, two different messages, for the same code (the case file's own point):
            // ConfigurationBuilder says "does not exist", PropertiesFormat says "cannot read". Both
            // must still carry the (already-absolute, since qa is a @TempDir) path.
            assertThat(e.getMessage())
                    .contains("cannot read configuration file")
                    .contains(unreadable.toString());
        } finally {
            // Otherwise @TempDir cleanup cannot remove the directory afterwards.
            Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rw-------"));
        }
    }

    // ------------------------------------------------------------ ERRC-002 -- PRV-1002

    @Test
    void threePropertiesFormatSitesGivePrv1002WithTheLineNumber() {
        record Case(String name, String content, int line) {}
        // The three PropertiesFormat.java throw sites the case names: no separator (:92), an empty
        // key (:97), an unterminated line-continuation (:82).
        var cases = java.util.List.of(
                new Case("noSeparator.properties", "a=1\nnotkeyvalue\n", 2),
                new Case("emptyKey.properties", "a=1\n=novalue\n", 2),
                new Case("continuation.properties", "a=1\\\n", 1));

        for (Case c : cases) {
            Path f = file("conf/" + c.name(), c.content);
            ConfigurationException e =
                    catchConfig(() -> Configuration.builder().addFile(f).build());
            assertThat(e.errorCode().code()).as(c.name()).isEqualTo("PRV-1002");
            assertThat(e.errorCode().name()).isEqualTo("CONFIG_FILE_MALFORMED");
            // E3: the message must name the line -- "f:line" is the rendering at all three
            // PropertiesFormat sites.
            assertThat(e.getMessage()).as(c.name()).contains(f + ":" + c.line);
        }
    }

    @Test
    void aResolverFailureIsAlsoPrv1002ButWithoutALineNumber() {
        // ERRC-002's own falsifier is "a PRV-1002 without a line number" -- and this is one. The
        // resolver failure site (ConfigResolver.matchingBrace, for an unclosed '${') is a *fourth*
        // throw site for the same code, and unlike the three PropertiesFormat sites it has no access
        // to a line number at all: ConfigResolver works on the fully-merged value, after the file has
        // already been read into a flat key/value map, so all it can name is the key and the file
        // path, not the line within it. Confirmed here rather than assumed: this is a real,
        // evidenced E3 gap for one of PRV-1002's five sites, recorded in the ERRC log and in
        // FINDINGS rather than silently treated as covered by the other four.
        Path f = file("conf/resolverFailure.properties", "a=${b\n");
        ConfigurationException e =
                catchConfig(() -> Configuration.builder().addFile(f).build());
        assertThat(e.errorCode().code()).isEqualTo("PRV-1002");
        assertThat(e.errorCode().name()).isEqualTo("CONFIG_FILE_MALFORMED");
        assertThat(e.getMessage()).contains("unclosed").contains("${").contains(f.toString());
        assertThat(e.getMessage())
                .as("no line number is available at this throw site")
                .doesNotContain(f + ":");
    }

    @Test
    void anExtensionNoFormatHandlesIsAlsoPrv1002ButWithNoLineNumber() {
        // ConfigurationBuilder.readInto's own throw site: not a PropertiesFormat/YAML line failure at
        // all (the fifth of the five sites), so E3 cannot name a line either -- it names the
        // extension and what is registered instead, which is the actionable substitute.
        Path f = file("conf/weird.ini", "a=1\n");
        ConfigurationException e =
                catchConfig(() -> Configuration.builder().addFile(f).build());
        assertThat(e.errorCode().code()).isEqualTo("PRV-1002");
        assertThat(e.getMessage()).contains("no configuration format handles").contains("registered extensions");
    }

    // ------------------------------------------------------------ ERRC-003 -- PRV-1010

    @Test
    void anUnresolvedReferenceNamesBothTheOwningKeyAndTheMissingOne() {
        Path f = file("conf/unresolved.properties", "a=${nosuch.key}\n");
        ConfigurationException e =
                catchConfig(() -> Configuration.builder().addFile(f).build());

        assertThat(e.errorCode().code()).isEqualTo("PRV-1010");
        assertThat(e.errorCode().name()).isEqualTo("CONFIG_UNRESOLVED_REFERENCE");
        // E3(a)+(b): both the referrer ('a') and the missing key ('nosuch.key'), and what to do
        // (set it, or give it a default) -- naming only one half would leave the operator grepping.
        assertThat(e.getMessage()).contains("'a'").contains("${nosuch.key}").contains("nosuch.key:some-default");
    }

    // ------------------------------------------------------------ ERRC-004 -- PRV-1011

    @Test
    void aTwoKeyCircularReferenceIsRefusedAndA100DeepChainIsNot() {
        Path cycle = file("conf/cycle.properties", "a=${b}\nb=${a}\n");
        ConfigurationException e =
                catchConfig(() -> Configuration.builder().addFile(cycle).build());
        assertThat(e.errorCode().code()).isEqualTo("PRV-1011");
        assertThat(e.errorCode().name()).isEqualTo("CONFIG_CIRCULAR_REFERENCE");
        // E3: the cycle's members in order, not just "circular reference".
        assertThat(e.getMessage()).contains("a").contains("b");

        // Vacuity, as the case specifies: a genuinely deep-but-terminating chain must NOT be
        // refused, or a depth limit is being mistaken for a cycle detector.
        //
        // ERRC-004 used to FAIL its own vacuity control. MAX_DEPTH was 32 and shared
        // CONFIG_CIRCULAR_REFERENCE with the real detector (the `visiting` set, exercised by the
        // two-key case above), so the case's own suggested 100-deep chain came back naming a
        // circular reference that does not exist -- the false positive the control was written to
        // catch. Finding E-8: the guard is a backstop against the stack, not a cycle detector, and
        // it is 256 now and refuses under PRV-1012 CONFIG_REFERENCE_TOO_DEEP. Both of these lines
        // are the assertion the case always intended; only the second one has changed.
        assertThat(probeDepth(33)).as("33 resolves, as it always did").startsWith("OK");
        assertThat(probeDepth(100))
                .as("ERRC-004's own vacuity control, which now passes: a terminating 100-deep chain "
                        + "is not a cycle and is not refused as one")
                .startsWith("OK");
        assertThat(probeDepth(400))
                .as("and past the stack backstop it says depth, under a code of its own")
                .startsWith("FAIL PRV-1012");
    }

    private String probeDepth(int n) {
        StringBuilder sb = new StringBuilder("k0=done\n");
        for (int i = 1; i <= n; i++) {
            sb.append("k").append(i).append("=${k").append(i - 1).append("}\n");
        }
        Path f = file("conf/deepchain" + n + ".properties", sb.toString());
        try {
            Configuration c = Configuration.builder().addFile(f).build();
            return "OK " + c.getString("k" + n);
        } catch (ConfigurationException e) {
            return "FAIL " + e.errorCode().code() + " " + e.getMessage();
        }
    }

    // ------------------------------------------------------------ ERRC-005 -- PRV-1020

    @Test
    void requireStringAndRequireIntOnAnAbsentKeyNameTheKey() {
        Configuration empty = Configuration.empty();

        ConfigurationException e1 = catchConfig(() -> empty.requireString("plugin.path"));
        assertThat(e1.errorCode().code()).isEqualTo("PRV-1020");
        assertThat(e1.errorCode().name()).isEqualTo("CONFIG_MISSING_REQUIRED");
        // E3(a): the key is named -- "required configuration key 'plugin.path' is not set". E3(b) is
        // the borderline the case file asks to be judged explicitly: this message does NOT say what
        // an acceptable value would look like, only that one is required. Recorded as a partial E3 in
        // the log rather than silently marked pass; ERRC-020/ERRC-003 above show what a message that
        // clears both halves looks like (naming the accepted values, not just the missing key).
        assertThat(e1.getMessage()).contains("'plugin.path'").contains("is not set");

        ConfigurationException e2 = catchConfig(() -> empty.requireInt("lanes"));
        assertThat(e2.errorCode().code()).isEqualTo("PRV-1020");
        assertThat(e2.getMessage()).contains("'lanes'");
    }

    // ------------------------------------------------------------ ERRC-006 -- PRV-1021

    @Test
    void aNonNumericValueFailsAndUnderscoresAreStripped() {
        Configuration c = Configuration.builder()
                .set("n", "twelve")
                .set("ok", "1_000_000")
                .build();

        ConfigurationException e = catchConfig(() -> c.getLong("n"));
        assertThat(e.errorCode().code()).isEqualTo("PRV-1021");
        assertThat(e.errorCode().name()).isEqualTo("CONFIG_NOT_A_NUMBER");
        // E3: states the accepted form, including the underscore convention this same key accepts.
        assertThat(e.getMessage()).contains("a whole number, e.g. 16 or 1_000_000");

        assertThat(c.getLong("ok")).contains(1_000_000L);
    }

    // ------------------------------------------------------------ ERRC-007 -- PRV-1022

    @Test
    void anUnrecognisedBooleanFailsAndAllEightAcceptedSpellingsSucceed() {
        Configuration bad = Configuration.builder().set("b", "maybe").build();
        ConfigurationException e = catchConfig(() -> bad.getBoolean("b"));
        assertThat(e.errorCode().code()).isEqualTo("PRV-1022");
        assertThat(e.errorCode().name()).isEqualTo("CONFIG_NOT_A_BOOLEAN");

        var truthy = java.util.List.of("true", "yes", "on", "1");
        var falsy = java.util.List.of("false", "no", "off", "0");
        for (String v : truthy) {
            assertThat(Configuration.builder().set("b", v).build().getBoolean("b"))
                    .as(v)
                    .contains(true);
        }
        for (String v : falsy) {
            assertThat(Configuration.builder().set("b", v).build().getBoolean("b"))
                    .as(v)
                    .contains(false);
        }
    }

    // ------------------------------------------------------------ ERRC-008 -- PRV-1023

    @Test
    void aBareNumberOrUnparseableDurationFailsAndTheAcceptedUnitsAllSucceed() {
        var bad = java.util.List.of("30", "abcs", "30x");
        for (String v : bad) {
            Configuration c = Configuration.builder().set("d", v).build();
            ConfigurationException e = catchConfig(() -> c.getDuration("d"));
            assertThat(e.errorCode().code()).as(v).isEqualTo("PRV-1023");
            assertThat(e.errorCode().name()).isEqualTo("CONFIG_NOT_A_DURATION");
            assertThat(e.getMessage()).as(v).contains("ns, us, ms, s, min, h or d");
        }

        var accepted = java.util.Map.of(
                "200us", 200_000L,
                "30s", 30_000_000_000L,
                "5min", 5L * 60 * 1_000_000_000L,
                "1h", 3_600L * 1_000_000_000L,
                "2d", 2 * 86_400L * 1_000_000_000L,
                "100ms", 100_000_000L,
                "5ns", 5L);
        accepted.forEach((text, nanos) -> {
            Configuration c = Configuration.builder().set("d", text).build();
            assertThat(c.getDuration("d").orElseThrow().toNanos()).as(text).isEqualTo(nanos);
        });
    }

    // ------------------------------------------------------------ ERRC-009 -- PRV-1024

    @Test
    void dataSizeParsesBinaryUnitsAndABareNumberIsAcceptedAsBytes() {
        Configuration mb = Configuration.builder().set("s", "4MB").build();
        assertThat(mb.getDataSize("s")).contains(4L * 1024 * 1024);

        Configuration bare = Configuration.builder().set("s", "4").build();
        assertThat(bare.getDataSize("s")).contains(4L);

        // Two different throw sites, two different messages, for the same code -- the same shape
        // ERRC-001 found for PRV-1001. "4XB" has a number and an unrecognised unit, so it reaches the
        // site that lists the accepted units. "MB" has no leading digit at all, so it fails earlier,
        // at the generic "a number with an optional unit" site, which does not enumerate the units.
        Configuration badUnit = Configuration.builder().set("s", "4XB").build();
        ConfigurationException eUnit = catchConfig(() -> badUnit.getDataSize("s"));
        assertThat(eUnit.errorCode().code()).isEqualTo("PRV-1024");
        assertThat(eUnit.errorCode().name()).isEqualTo("CONFIG_NOT_A_DATA_SIZE");
        assertThat(eUnit.getMessage()).contains("B, KB, MB, GB");

        Configuration noNumber = Configuration.builder().set("s", "MB").build();
        ConfigurationException eNoNum = catchConfig(() -> noNumber.getDataSize("s"));
        assertThat(eNoNum.errorCode().code()).isEqualTo("PRV-1024");
        assertThat(eNoNum.getMessage()).contains("a number with an optional unit, e.g. 4MB");
        // Note (case ERRC-009): no shipped configuration key actually reads a data size -- confirmed
        // separately by grepping main sources for getDataSize call sites outside this parser and its
        // own test; recorded in the ERRC log rather than re-derived here.
    }

    // ------------------------------------------------------------ ERRC-010 -- PRV-1025

    private enum WaitKind {
        FAST,
        SLOW
    }

    @Test
    void anUnknownEnumNameListsTheAllowedValues() {
        Configuration c = Configuration.builder().set("k", "MEDIUM").build();
        ConfigurationException e = catchConfig(() -> c.getEnum("k", WaitKind.class));
        assertThat(e.errorCode().code()).isEqualTo("PRV-1025");
        assertThat(e.errorCode().name()).isEqualTo("CONFIG_NOT_AN_ENUM");
        assertThat(e.getMessage()).contains("FAST").contains("SLOW");

        Configuration ok = Configuration.builder().set("k", "fast").build();
        assertThat(ok.getEnum("k", WaitKind.class)).contains(WaitKind.FAST);
    }

    // ------------------------------------------------------------ ERRC-011 -- PRV-1026

    @Test
    void getIntOutOfThirtyTwoBitRangeFailsAtTheBoundaryExactly() {
        Configuration tooBig = Configuration.builder().set("n", "2147483648").build();
        ConfigurationException e = catchConfig(() -> tooBig.getInt("n"));
        assertThat(e.errorCode().code()).isEqualTo("PRV-1026");
        assertThat(e.errorCode().name()).isEqualTo("CONFIG_OUT_OF_RANGE");
        assertThat(e.getMessage()).contains("2147483648").contains("does not fit in a 32-bit int");

        Configuration atMax = Configuration.builder().set("n", "2147483647").build();
        assertThat(atMax.getInt("n")).contains(Integer.MAX_VALUE);
    }

    private static ConfigurationException catchConfig(Runnable r) {
        return (ConfigurationException) catchRuntime(r, ConfigurationException.class);
    }

    private static RuntimeException catchRuntime(Runnable r, Class<? extends RuntimeException> type) {
        RuntimeException caught = org.assertj.core.api.Assertions.catchThrowableOfType(r::run, type);
        assertThat(caught)
                .as("expected a " + type.getSimpleName() + " to be thrown")
                .isNotNull();
        return caught;
    }
}
