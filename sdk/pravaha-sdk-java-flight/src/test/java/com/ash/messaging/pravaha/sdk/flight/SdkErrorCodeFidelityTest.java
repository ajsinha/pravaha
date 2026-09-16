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
package com.ash.messaging.pravaha.sdk.flight;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.flight.PravahaFlightServer;
import com.ash.messaging.pravaha.sdk.PravahaClientException;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S-4 and E-7: what an application can branch on when a call fails.
 *
 * <p>S-4 is that no server error code reached an SDK caller <em>as a code</em> -- every
 * {@link org.apache.arrow.flight.FlightRuntimeException} was re-stamped
 * {@code PRV-1041 CLIENT_QUERY_REFUSED} and the server's real code survived only as the first token
 * of the message, which the console recovered with a regular expression. E-7 is the same catch
 * block seen from the other side: {@code PRV-1040 CLIENT_CONNECT_FAILED} was unreachable, because
 * {@code connect()} builds a lazy gRPC channel that does not fail for an unreachable host, so the
 * scenario every new user hits first -- a server that is not running -- arrived as a non-retryable
 * "the server refused the query".
 *
 * <p>Every assertion here is on {@code errorCode()} and {@code retryable()} rather than on message
 * text, on purpose: message text is what this failure mode was reduced to, and asserting on it
 * would pin the defect rather than the fix.
 */
@Timeout(90)
class SdkErrorCodeFidelityTest {

    /** Closed on purpose, and never bound in this test. */
    private static final int DEAD_PORT = 19_901;

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    private PravahaFlightServer server;
    private PravahaFlightClient client;

    @BeforeEach
    void start() {
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 100);
        view.applyValues(new Object[] {"u1", 300L}, 1, 10);
        view.commit(10);
        server = new PravahaFlightServer(new ViewCatalog().register(view)).start("localhost", 0);
        client = PravahaFlightClient.connect("grpc://localhost:" + server.port());
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    // ------------------------------------------------------------------------------ S-4

    @Test
    void aServersRefusalReachesTheCallerUnderTheServersOwnCode() {
        // PRV-2002 SQL_VALIDATION_FAILED: a name the planner cannot resolve is refused during
        // validation, before the serving layer is ever asked. Before this fix the caller got
        // PRV-1041 and had to search the message for "PRV-" to learn which of the hundred-odd
        // refusals had happened.
        assertThatThrownBy(() -> client.query("SELECT user_id FROM nowhere"))
                .isInstanceOfSatisfying(PravahaClientException.class, e -> {
                    assertThat(e.errorCode().code()).isEqualTo("PRV-2002");
                    assertThat(e.errorCode().name())
                            .as("the code's name travels in a trailer, not in the message")
                            .isEqualTo("SQL_VALIDATION_FAILED");
                    assertThat(e.retryable())
                            .as("a table that does not exist will not exist on a retry")
                            .isFalse();
                    // The diagnosis is unchanged, and rendered once rather than under a second code:
                    // "PRV-1041  PRV-2002  ..." was what a user used to read.
                    assertThat(e.getMessage()).startsWith("PRV-2002  ").contains("nowhere");
                    assertThat(e.getMessage()).doesNotContain("PRV-1041");
                });
    }

    @Test
    void theCodeSurvivesTheControlActionsToo() {
        // The registry verbs go through doAction, not Flight SQL -- a separate catch block, and one
        // that had drifted to the identical catch-all. This server hosts no registry, so the action
        // itself is the refusal: PRV-6101 FLIGHT_UNSUPPORTED_REQUEST. That is the point rather than
        // a compromise -- 6101 is declared in pravaha-flight and 8002 in pravaha-registry, and the
        // client must carry neither to report either.
        assertThatThrownBy(() -> client.drop("never-registered"))
                .isInstanceOfSatisfying(PravahaClientException.class, e -> {
                    assertThat(e.errorCode().code()).isEqualTo("PRV-6101");
                    assertThat(e.errorCode().name()).isEqualTo("FLIGHT_UNSUPPORTED_REQUEST");
                    assertThat(e.retryable()).isFalse();
                });
    }

    @Test
    void anErrorCodeTheClientHasNeverHeardOfStillArrivesAsACode() {
        // The property that matters for a client SDK shipped separately from the engine: it must not
        // need a table of the server's codes, because it will be a version behind. PRV-6100
        // FLIGHT_UNSUPPORTED_TYPE is declared in pravaha-flight, which this SDK does not depend on
        // at compile time at all.
        StreamSchema withDecimal = StreamSchema.builder("ledger")
                .field("entry_id", Types.string())
                .field("amount", Types.decimal(18, 2))
                .build();
        ServedView ledger = new ServedView("ledger", withDecimal, List.of(0), 10);
        try (PravahaFlightServer other =
                        new PravahaFlightServer(new ViewCatalog().register(ledger)).start("localhost", 0);
                PravahaFlightClient reader = PravahaFlightClient.connect("grpc://localhost:" + other.port())) {
            assertThatThrownBy(() -> reader.query("SELECT amount FROM ledger").close())
                    .isInstanceOfSatisfying(PravahaClientException.class, e -> {
                        assertThat(e.errorCode().code()).isEqualTo("PRV-6100");
                        assertThat(e.errorCode().number()).isEqualTo(6100);
                        assertThat(e.errorCode().helpUrl()).isEqualTo("https://docs.pravaha.io/errors/PRV-6100");
                    });
        }
    }

    @Test
    void aFailureWhileReadingAResultIsAPravahaFailureToo() {
        // The half of S-4 that was not a wrong code but no code at all: QueryResult's iterator called
        // FlightStream.next() unguarded, so a stream that died part way through a result -- the lane
        // behind the view failing, the node going away, a credential expiring under a long read --
        // escaped as Arrow's own FlightRuntimeException. A caller with a catch for
        // PravahaClientException around the whole read did not catch it.
        //
        // Provoked by stopping the node part way through a large, multi-batch result, which is the
        // real shape of this: rows are genuinely still arriving. The node is stopped rather than the
        // client closed, because closing a client out from under an open result also strands the
        // Arrow batch it is holding and the allocator reports that as a leak -- a separate, real
        // property of this SDK that this test has no business asserting either way.
        ServedView big = new ServedView("big", SCHEMA, List.of(0), 600_000);
        for (int i = 0; i < 300_000; i++) {
            big.applyValues(new Object[] {"u" + i, (long) i}, 1, 10);
        }
        big.commit(10);

        PravahaFlightServer doomed = new PravahaFlightServer(new ViewCatalog().register(big)).start("localhost", 0);
        try (PravahaFlightClient reader = PravahaFlightClient.connect("grpc://localhost:" + doomed.port());
                QueryResult result = reader.query("SELECT user_id, total FROM big")) {
            java.util.Iterator<Row> rows = result.iterator();
            assertThat(rows.hasNext()).isTrue();
            rows.next();
            doomed.close();

            assertThatThrownBy(() -> {
                        while (rows.hasNext()) {
                            rows.next();
                        }
                    })
                    .as("the node went away mid-result; that is a Pravaha failure with a code, not an Arrow one")
                    .isInstanceOfSatisfying(PravahaClientException.class, e -> {
                        // PRV-1042 CLIENT_READ_FAILED, which is what it says: the result could not be
                        // read. Not PRV-1041 -- nothing refused anything -- and not PRV-1040, because
                        // the connection was made and answered; it was cut afterwards. The three were
                        // one code before this, and READ_FAILED was reachable only by misusing the
                        // API (iterating a result twice), never by a real read failing.
                        assertThat(e.errorCode().code()).isEqualTo("PRV-1042");
                    });
        } finally {
            doomed.close();
        }
    }

    // ------------------------------------------------------------------------------ E-7

    @Test
    void aServerThatIsNotRunningIsAConnectFailureAndSaysWhichNode() {
        // The scenario ERRC-014 calls the first error a new SDK/CLI user meets. connect() itself
        // still returns -- the channel is lazy and nothing about an unreachable host is knowable
        // synchronously -- so the first call is where it has to be reported, and reported as what it
        // is.
        try (PravahaFlightClient dead = PravahaFlightClient.connect("grpc://127.0.0.1:" + DEAD_PORT)) {
            assertThatThrownBy(() -> dead.query("SELECT 1")).isInstanceOfSatisfying(PravahaClientException.class, e -> {
                assertThat(e.errorCode().code()).isEqualTo("PRV-1040");
                assertThat(e.errorCode().name()).isEqualTo("CLIENT_CONNECT_FAILED");
                assertThat(e.retryable())
                        .as("a server that is not up yet may be up shortly")
                        .isTrue();
                assertThat(e.getMessage()).contains("127.0.0.1:" + DEAD_PORT);
            });
        }
    }

    @Test
    void connectItselfStillReturnsForAnUnreachableHostAndTheFirstCallReportsIt() {
        // Pinned rather than assumed: the lazy channel is upstream behaviour this SDK does not
        // control, and if a future Arrow made connect() throw, PRV-1040 would be raised from two
        // places with two different messages. This says which one is expected to speak.
        PravahaFlightClient dead = PravahaFlightClient.connect("grpc://127.0.0.1:" + DEAD_PORT);
        assertThat(dead).isNotNull();
        dead.close();
    }

    @Test
    void aClosedClientSaysSoRatherThanLookingLikeADeadServer() {
        // ERRC-017, and the reason PRV-1040 needs this guard beside it: gRPC answers a call on a
        // shut-down channel with UNAVAILABLE and "Channel shutdown invoked", which is
        // indistinguishable from an unreachable server -- so without this the connect-failure branch
        // would advise retrying a client that can never work again.
        PravahaFlightClient spent = PravahaFlightClient.connect("grpc://localhost:" + server.port());
        spent.close();
        assertThatThrownBy(() -> spent.query("SELECT user_id FROM user_volume"))
                .isInstanceOfSatisfying(PravahaClientException.class, e -> {
                    assertThat(e.errorCode().code()).isEqualTo("PRV-1043");
                    assertThat(e.retryable()).isFalse();
                });
        assertThatThrownBy(spent::queries)
                .isInstanceOfSatisfying(
                        PravahaClientException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("PRV-1043"));
    }
}
