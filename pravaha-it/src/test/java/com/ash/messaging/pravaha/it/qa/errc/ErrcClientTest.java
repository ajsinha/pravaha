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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.sdk.ClientOptions;
import com.ash.messaging.pravaha.sdk.Endpoint;
import com.ash.messaging.pravaha.sdk.PravahaClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ERRC-012 .. ERRC-017 -- the SDK/CLI client family (PRV-1030 .. PRV-1043), none of them documented
 * in {@code TROUBLESHOOTING.md} (case file fact 8) and ERRC-014 the one the case calls "the
 * highest-severity finding in the missing set" -- the first error a new SDK user meets.
 *
 * <p>{@code pravaha queries --url ...} (via {@code ErrcTestSupport.cli}) is the CLI surface;
 * {@code Endpoint.parse} directly is the SDK surface for the parse-only half of ERRC-012, used where
 * the alternative would be a live network attempt this environment cannot make reliably (no network
 * per the harness notes) -- {@code Endpoint} is public SDK API, not an internal throwing helper, so
 * calling it directly is calling the product, the same way an application embedding the SDK would.
 */
class ErrcClientTest extends ErrcTestSupport {

    // ------------------------------------------------------------ ERRC-012 -- PRV-1030

    @Test
    void twoOfTheCasesFiveMalformedEndpointExamplesActuallyMalform() {
        // "grpc://" (no host) and "grpc://h:99999" (port out of range) are genuinely malformed and
        // refused at parse time, with no network involved -- the CLI surface, end to end.
        ErrcTestSupport.CliResult noHost = cli("queries", "--url", "grpc://");
        assertThat(noHost.exitCode()).isEqualTo(1);
        assertThat(noHost.stderr()).contains("PRV-1030").contains("it names no host");

        ErrcTestSupport.CliResult badPort = cli("queries", "--url", "grpc://h:99999");
        assertThat(badPort.exitCode()).isEqualTo(1);
        assertThat(badPort.stderr())
                .contains("PRV-1030")
                .contains("port 99999 is outside 1-65535")
                // E3(b): the accepted form is stated, not just the failure.
                .contains("grpc://host:port");
    }

    @Test
    void theOtherThreeOfTheCasesFiveExamplesDoNotMalformAtAll() {
        // ERRC-012's own Setup names five candidate inputs as "genuinely malformed"; empirically,
        // three of the five parse successfully because Endpoint.parse accepts more than the case
        // assumes: "http"/"https" are accepted aliases for grpc/grpc+tls (Endpoint.java:93), a bare
        // hostname with no colon at all defaults to port 9090 (parseHostPort, no exception), and a
        // scheme with a host but no port also defaults to 9090. Verified against the SDK's own
        // Endpoint.parse directly (a public, documented entry point -- not the throwing method called
        // out of context) rather than over the network, since a live connection attempt to a
        // nonexistent host in this environment risks a DNS-timeout hang unrelated to what this case
        // is checking. Recorded as a finding: the case's own "one-line reach" example
        // (`--url nonsense`) does not, in fact, produce PRV-1030.
        assertThat(Endpoint.parse("nonsense").nodes()).containsExactly(new Endpoint.HostPort("nonsense", 9090));
        assertThat(Endpoint.parse("nonsense").tls()).isTrue();

        Endpoint http = Endpoint.parse("http://h:9090");
        assertThat(http.tls()).isFalse();
        assertThat(http.nodes()).containsExactly(new Endpoint.HostPort("h", 9090));

        assertThat(Endpoint.parse("grpc+tls://h").nodes()).containsExactly(new Endpoint.HostPort("h", 9090));
    }

    // ------------------------------------------------------------ ERRC-013 -- PRV-1031

    @Test
    void fourClientOptionsValidationSitesAllRefuseWithARetryableFalseException() {
        Endpoint anyEndpoint = Endpoint.parse("grpc://localhost:9090");

        assertRefused(
                () -> ClientOptions.builder(anyEndpoint).subscriberBufferRows(0),
                "subscriberBufferRows must be at least 1, got 0");
        assertRefused(
                () -> ClientOptions.builder(anyEndpoint).subscriberBufferRows(-1),
                "subscriberBufferRows must be at least 1, got -1");
        assertRefused(
                () -> ClientOptions.builder(anyEndpoint).applicationName(""), "applicationName must not be blank");
        assertRefused(
                () -> ClientOptions.builder(anyEndpoint).applicationName(null), "applicationName must not be blank");
        assertRefused(
                () -> ClientOptions.builder(anyEndpoint).connectTimeout(Duration.ZERO),
                "connectTimeout must be positive");
        assertRefused(
                () -> ClientOptions.builder(anyEndpoint).requestTimeout(Duration.ofSeconds(-1)),
                "requestTimeout must be positive");
    }

    @Test
    void aFifthSiteTheCaseDoesNotEnumerateAlsoRefusesWithPrv1031() {
        // ClientOptions.Builder.build() itself refuses a token over a plaintext endpoint unless
        // allowInsecureToken(true) -- a fifth PRV-1031 throw site (build(), not one of the four
        // fluent setters the case names by line number). Recorded as a minor completeness note on
        // the case rather than a product defect: the code is correct, the case's own site count is
        // short by one.
        Endpoint plaintext = Endpoint.parse("grpc://localhost:9090");
        assertThatThrownBy(
                        () -> ClientOptions.builder(plaintext).token("secret").build())
                .isInstanceOfSatisfying(PravahaClientException.class, e -> {
                    assertThat(e.errorCode().code()).isEqualTo("PRV-1031");
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage())
                            .contains("refusing to send a token over a plaintext connection")
                            .contains("allowInsecureToken(true)");
                });
    }

    private static void assertRefused(Runnable action, String expectedMessageFragment) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(PravahaClientException.class, e -> {
            assertThat(e.errorCode().code()).isEqualTo("PRV-1031");
            assertThat(e.errorCode().name()).isEqualTo("CLIENT_INVALID_OPTIONS");
            assertThat(e.retryable())
                    .as("retryability must be false: this is a caller mistake, not a transient failure")
                    .isFalse();
            assertThat(e.getMessage()).contains(expectedMessageFragment);
        });
    }

    // ------------------------------------------------------------ ERRC-014 -- PRV-1040

    @Test
    void connectingToADeadLoopbackPortProducesClientConnectFailed() throws Exception {
        // INVERTED for E-7. This test was named ...DoesNotProduceClientConnectFailed and asserted
        // `doesNotContain("PRV-1040")` and `isEqualToIgnoringNewLines("PRV-1041  io exception")` --
        // it recorded the defect as the expected output, which pinned it open: fixing it would have
        // turned this red, and the register's own text said so ("the operator now searches PRV-1041
        // and reads about parameter binding and duplicate names, not about a dead server").
        //
        // What changed: connect() still returns -- gRPC/Arrow-Flight builds the channel lazily and
        // nothing about an unreachable host is knowable synchronously, so the finding's diagnosis of
        // *why* PRV-1040 was unreachable stands and is pinned by the test below. What is fixed is
        // where it is reported. The first RPC now asks whether the failure carries a Pravaha code at
        // all; UNAVAILABLE with none means no server answered, which is PRV-1040 CLIENT_CONNECT_FAILED,
        // retryable, naming the endpoint -- rather than PRV-1041, non-retryable, "io exception".
        //
        // The case's own Setup: "node down; pravaha queries --url grpc://127.0.0.1:19900". Port
        // 19900 chosen closed deliberately (nothing bound there in this test).
        //
        // Run as a real subprocess of the CLI's own shaded jar (ErrcTestSupport.cliSubprocess), not
        // the in-process cli() helper: pravaha-it's own test classpath mixes netty 4.1.135 (pulled in
        // transitively via pravaha-server, test scope) with the 4.2.9 line Arrow Flight needs, and the
        // in-process call throws a bare AbstractMethodError the moment a FlightClient is actually
        // constructed -- a classpath defect in this test module, not in the product (confirmed
        // separately: pravaha-cli's own dependency tree is netty 4.2.9 throughout). Recorded as a
        // BLOCKED-then-worked-around note, not a silent switch: every other case in this package that
        // touches Flight uses the same subprocess route for the same reason.
        ErrcTestSupport.CliResult result =
                cliSubprocess(Duration.ofSeconds(30), "queries", "--url", "grpc://127.0.0.1:19900");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).as("verbatim CLI stderr for a down node").isNotBlank();
        assertThat(result.stderr())
                .as("ERRC-014: connecting to a down node is a connection failure, and says so")
                .contains("PRV-1040")
                .doesNotContain("PRV-1041");
        // E3(a) and E3(b), which the old "PRV-1041  io exception" failed both of: name the specific
        // thing that went wrong, and say what to do about it.
        assertThat(result.stderr()).contains("127.0.0.1:19900").contains("running");
    }

    @Test
    void connectBuilderItselfNeverThrowsSynchronouslyForAnUnreachableHost() throws Exception {
        // The narrower claim, isolated from the CLI's own RPC retry/timeout behaviour: connect()
        // alone, against a definitely-closed loopback port, returns a client rather than throwing.
        // Exercised through a tiny inline Java program run as a subprocess against the CLI's own
        // shaded jar's classpath, for the same netty-conflict reason as the test above.
        ErrcTestSupport.CliResult probe = connectProbeSubprocess("127.0.0.1", 19900);
        assertThat(probe.exitCode())
                .as("connect() must return, not throw: " + probe.combined())
                .isZero();
        assertThat(probe.stdout()).contains("connected-ok");
    }

    // ------------------------------------------------------------ ERRC-017 -- PRV-1043 (unreachable)

    @Test
    void usingAClosedClientProducesPrv1043() throws Exception {
        // INVERTED alongside E-7. This was noCandidateForClientClosedProducesPrv1043 and asserted
        // `doesNotContain("PRV-1043")` -- ClientErrors.CLOSED was declared and thrown nowhere, and
        // the test recorded that rather than closing it.
        //
        // It had to change with E-7 rather than separately. gRPC answers a call on a shut-down
        // channel with UNAVAILABLE and the text "Channel shutdown invoked", which is
        // indistinguishable from an unreachable server -- so once "UNAVAILABLE with no Pravaha code"
        // began meaning PRV-1040 "retry, the server may come back", a closed client would have been
        // told to retry something that can never work again. The guard in PravahaFlightClient keeps
        // PRV-1040 to one meaning and gives PRV-1043 the throw site it was declared for.
        //
        // Still not run here: the case's other two candidates (iterate a QueryResult after closing
        // its client; use a subscription after close()) need a QueryResult or Subscription that only
        // a live server can produce. Recorded as NOT RUN, not silently skipped.
        ErrcTestSupport.CliResult probe = closedClientProbeSubprocess();
        assertThat(probe.exitCode()).as(probe.combined()).isZero();
        // Closing twice stays a no-op: close() is idempotent by design and turning a second one into
        // a refusal would break try-with-resources around an explicit close.
        assertThat(probe.stdout()).contains("double-close: ok, no exception");
        assertThat(probe.stdout()).contains("query-after-close:").contains("PRV-1043");
    }

    private static ErrcTestSupport.CliResult closedClientProbeSubprocess() throws Exception {
        Path dir = Files.createTempDirectory("errc-closed-client-probe");
        String src = """
                import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
                public class ClosedClientProbe {
                    public static void main(String[] a) throws Exception {
                        PravahaFlightClient c = PravahaFlightClient.connect("grpc://127.0.0.1:19900");
                        c.close();
                        try {
                            c.close();
                            System.out.println("double-close: ok, no exception");
                        } catch (Exception e) {
                            System.out.println("double-close: " + e.getClass().getName() + ": " + e.getMessage());
                        }
                        try {
                            c.query("SELECT 1").close();
                            System.out.println("query-after-close: no exception");
                        } catch (Exception e) {
                            System.out.println("query-after-close: " + e.getClass().getName() + ": " + e.getMessage());
                        }
                    }
                }
                """;
        Path srcFile = dir.resolve("ClosedClientProbe.java");
        Files.writeString(srcFile, src);

        Path cliJarPath = repoRootJar();
        String javacBin = System.getProperty("java.home") + "/bin/javac";
        Process compile = new ProcessBuilder(
                        javacBin, "-cp", cliJarPath.toString(), "-d", dir.toString(), srcFile.toString())
                .redirectErrorStream(true)
                .start();
        String compileOut = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!compile.waitFor(60, TimeUnit.SECONDS) || compile.exitValue() != 0) {
            throw new IllegalStateException("could not compile the closed-client probe: " + compileOut);
        }

        String javaBin = System.getProperty("java.home") + "/bin/java";
        Process run =
                new ProcessBuilder(javaBin, "-cp", dir + File.pathSeparator + cliJarPath, "ClosedClientProbe").start();
        String out = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(run.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!run.waitFor(30, TimeUnit.SECONDS)) {
            run.destroyForcibly();
            throw new IllegalStateException("closed-client probe did not exit");
        }
        return new ErrcTestSupport.CliResult(run.exitValue(), out, err);
    }

    /**
     * A one-off subprocess that does exactly {@code PravahaFlightClient.connect(url)} and reports
     * whether it threw, run on the CLI jar's own (conflict-free) classpath -- there is no CLI verb for
     * "connect and immediately close", so this is the smallest real use of the public SDK entry point
     * available without adding one.
     */
    private static ErrcTestSupport.CliResult connectProbeSubprocess(String host, int port) throws Exception {
        Path dir = Files.createTempDirectory("errc-connect-probe");
        String src = """
                import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
                public class ConnectProbe {
                    public static void main(String[] a) throws Exception {
                        try (PravahaFlightClient c = PravahaFlightClient.connect("grpc://%s:%d")) {
                            System.out.println("connected-ok");
                        }
                    }
                }
                """.formatted(host, port);
        Path srcFile = dir.resolve("ConnectProbe.java");
        Files.writeString(srcFile, src);

        Path cliJarPath = repoRootJar();
        String javacBin = System.getProperty("java.home") + "/bin/javac";
        Process compile = new ProcessBuilder(
                        javacBin, "-cp", cliJarPath.toString(), "-d", dir.toString(), srcFile.toString())
                .redirectErrorStream(true)
                .start();
        String compileOut = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!compile.waitFor(60, TimeUnit.SECONDS) || compile.exitValue() != 0) {
            throw new IllegalStateException("could not compile the connect probe: " + compileOut);
        }

        String javaBin = System.getProperty("java.home") + "/bin/java";
        Process run = new ProcessBuilder(javaBin, "-cp", dir + File.pathSeparator + cliJarPath, "ConnectProbe").start();
        String out = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(run.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!run.waitFor(30, TimeUnit.SECONDS)) {
            run.destroyForcibly();
            throw new IllegalStateException("connect probe did not exit");
        }
        return new ErrcTestSupport.CliResult(run.exitValue(), out, err);
    }

    private static Path repoRootJar() throws IOException {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("docs/adr"))) {
            path = path.getParent();
        }
        try (var files = Files.list(path.resolve("pravaha-cli/target"))) {
            return files.filter(p -> p.getFileName().toString().endsWith("-cli.jar"))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
