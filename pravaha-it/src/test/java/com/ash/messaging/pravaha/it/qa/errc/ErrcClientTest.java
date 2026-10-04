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

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.sdk.ClientOptions;
import com.ash.messaging.pravaha.sdk.Endpoint;
import com.ash.messaging.pravaha.sdk.PravahaClientException;
import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ERRC-012 .. ERRC-017 -- the SDK/CLI client family (PRV-1030 .. PRV-1043), none of them documented
 * in {@code TROUBLESHOOTING.md} (case file fact 8) and ERRC-014 the one the case calls "the
 * highest-severity finding in the missing set" -- the first error a new SDK user meets.
 *
 * <p>{@code pravaha queries --url ...}, the Python CLI's now, is exercised through the SDK call it makes;
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
        ErrcTestSupport.CliResult noHost = queries("grpc://");
        assertThat(noHost.exitCode()).isEqualTo(1);
        assertThat(noHost.stderr()).contains("PRV-1030").contains("it names no host");

        ErrcTestSupport.CliResult badPort = queries("grpc://h:99999");
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
        // hostname with no colon at all defaults to port 19090 (parseHostPort, no exception), and a
        // scheme with a host but no port also defaults to 19090. Verified against the SDK's own
        // Endpoint.parse directly (a public, documented entry point -- not the throwing method called
        // out of context) rather than over the network, since a live connection attempt to a
        // nonexistent host in this environment risks a DNS-timeout hang unrelated to what this case
        // is checking. Recorded as a finding: the case's own "one-line reach" example
        // (`--url nonsense`) does not, in fact, produce PRV-1030.
        assertThat(Endpoint.parse("nonsense").nodes()).containsExactly(new Endpoint.HostPort("nonsense", 19090));
        assertThat(Endpoint.parse("nonsense").tls()).isTrue();

        Endpoint http = Endpoint.parse("http://h:19090");
        assertThat(http.tls()).isFalse();
        assertThat(http.nodes()).containsExactly(new Endpoint.HostPort("h", 19090));

        assertThat(Endpoint.parse("grpc+tls://h").nodes()).containsExactly(new Endpoint.HostPort("h", 19090));
    }

    // ------------------------------------------------------------ ERRC-013 -- PRV-1031

    @Test
    // NullAway: nulls passed on purpose
    @SuppressWarnings({"deprecation", "NullAway"
    }) // connectTimeout is deprecated (CONNECTTIMEOUT-1) but still validated
    void fourClientOptionsValidationSitesAllRefuseWithARetryableFalseException() {
        Endpoint anyEndpoint = Endpoint.parse("grpc://localhost:19090");

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
        Endpoint plaintext = Endpoint.parse("grpc://localhost:19090");
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
        // In-process, through the SDK the Java CLI's `queries` used. This once ran as a subprocess
        // of the CLI's shaded jar to dodge E-9 (netty 4.1.135 beside the 4.2.9 line Arrow Flight
        // needs on this module's classpath); that conflict is gone, and the remote commands have
        // left the Java CLI for the Python one.
        ErrcTestSupport.CliResult result = queries("grpc://127.0.0.1:19900");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).as("verbatim refusal for a down node").isNotBlank();
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
        // The narrower claim, isolated from any RPC retry/timeout behaviour: connect()
        // alone, against a definitely-closed loopback port, returns a client rather than throwing.
        // Exercised in-process, now that this module's classpath has one netty line (E-9 is gone).
        ErrcTestSupport.CliResult probe = connectProbe("127.0.0.1", 19900);
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
        ErrcTestSupport.CliResult probe = closedClientProbe();
        assertThat(probe.exitCode()).as(probe.combined()).isZero();
        // Closing twice stays a no-op: close() is idempotent by design and turning a second one into
        // a refusal would break try-with-resources around an explicit close.
        assertThat(probe.stdout()).contains("double-close: ok, no exception");
        assertThat(probe.stdout()).contains("query-after-close:").contains("PRV-1043");
    }

    /**
     * What {@code queries --url <url>} did, through the SDK it used: connect, list, and on a refusal
     * exit 1 with the SDK's own message. The command itself moved to the Python CLI.
     */
    private static ErrcTestSupport.CliResult queries(String url) {
        try (PravahaFlightClient client = PravahaFlightClient.connect(url)) {
            client.queries();
            return new ErrcTestSupport.CliResult(0, "", "");
        } catch (RuntimeException e) {
            return new ErrcTestSupport.CliResult(1, "", String.valueOf(e.getMessage()));
        }
    }

    /** {@code PravahaFlightClient.connect(url)} and nothing else: does it return, or throw? */
    private static ErrcTestSupport.CliResult connectProbe(String host, int port) {
        try (PravahaFlightClient _ = PravahaFlightClient.connect("grpc://" + host + ":" + port)) {
            return new ErrcTestSupport.CliResult(0, "connected-ok\n", "");
        } catch (RuntimeException e) {
            return new ErrcTestSupport.CliResult(1, "", e.getClass().getName() + ": " + e.getMessage());
        }
    }

    /** A client closed twice, then asked a question: what each step says. */
    private static ErrcTestSupport.CliResult closedClientProbe() {
        StringBuilder out = new StringBuilder();
        PravahaFlightClient client = PravahaFlightClient.connect("grpc://127.0.0.1:19900");
        client.close();
        try {
            client.close();
            out.append("double-close: ok, no exception\n");
        } catch (RuntimeException e) {
            out.append("double-close: ")
                    .append(e.getClass().getName())
                    .append(": ")
                    .append(e.getMessage())
                    .append('\n');
        }
        try {
            client.query("SELECT 1").close();
            out.append("query-after-close: no exception\n");
        } catch (RuntimeException e) {
            out.append("query-after-close: ")
                    .append(e.getClass().getName())
                    .append(": ")
                    .append(e.getMessage())
                    .append('\n');
        }
        return new ErrcTestSupport.CliResult(0, out.toString(), "");
    }
}
