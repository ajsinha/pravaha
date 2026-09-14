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
package com.ash.messaging.pravaha.cli.qa.errc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.Result;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.flight.PravahaFlightServer;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ERRC-089 .. ERRC-093 -- PRV-6xxx, the Flight gateway, which {@code ErrorCode.Category} calls
 * {@code CLUSTER} (fact 5's own claim) -- reconfirmed stale below: since commit 36a984f the category
 * is named {@code FLIGHT}, not {@code CLUSTER} (see {@code docs/qa/logs/ERRC.md}).
 *
 * <p>Surface: a real, in-process {@link PravahaFlightServer}, driven mostly by a raw {@link
 * FlightClient}/{@link FlightSqlClient} rather than the CLI, because several of these cases (a
 * malformed {@link Action}, an oversized parameter payload, an unimplemented Flight SQL metadata
 * call) have no CLI verb at all -- they are protocol-level probes the CLI was never asked to make.
 */
@Timeout(120)
class ErrcFlightTest extends ErrcServerSupport {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("id", Types.int64())
            .field("usr", Types.string())
            .field("amount", Types.int64())
            .field("event_time", Types.timestamp())
            .build();

    private ViewCatalog views;
    private QueryRegistry registry;
    private PravahaFlightServer server;
    private RowArena arena;
    private String url;

    @BeforeEach
    void start() {
        views = new ViewCatalog();
        registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN);
        server = new PravahaFlightServer(views).hosting(registry).start("localhost", 0);
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        url = "grpc://localhost:" + server.port();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        registry.close();
        arena.close();
    }

    // ------------------------------------------------------------ ERRC-089 -- PRV-6100

    @Test
    void aDecimalColumnIsRefusedWithItsOwnCodeAndTheColumnThatCarriesIt() {
        // DECIMAL is one of the six types Y-1 (FINDINGS.md) already found cannot be *declared* from
        // any configured surface; it can still be built programmatically (StreamSchema.builder +
        // Types.decimal), which is the only way to reach ArrowSchemas.arrowTypeOf's default arm.
        //
        // This case found the defect and pinned it; the assertion below is what it looks like fixed.
        //
        // getFlightInfoStatement was `ArrowSchemas.toArrow(plan(sql, context))`. `plan(...)` has its
        // own try/catch and turns a PravahaException into a FlightRuntimeException carrying the
        // right code; the conversion one line outside it did not. PRV-6100 only happens once
        // planning has already *succeeded* -- the SQL is fine and only the wire mapping is not -- so
        // it escaped the gRPC service method uncaught and the client got Arrow's own generic
        // internal-error text. No code, no column, nothing to act on, for a refusal the engine had
        // stated precisely.
        //
        // Both halves are fixed: the conversion is wrapped the way planning was, and the refusal
        // names the column, which it did not before. A client told that some type cannot be sent
        // still has to work out which column carried it, on a schema it may not have written.
        StreamSchema withDecimal = StreamSchema.builder("priced")
                .field("id", Types.int64())
                .field("price", Types.decimal(10, 2))
                .build();
        ViewCatalog v = new ViewCatalog();
        try (QueryRegistry reg = new QueryRegistry(v, SecurityPolicy.PERMISSIVE, AuditSink.NONE, withDecimal);
                PravahaFlightServer srv =
                        new PravahaFlightServer(v).hosting(reg).start("localhost", 0)) {
            reg.register("priced_v", "SELECT id, price FROM priced", List.of(0), Principal.ANONYMOUS);
            String u = "grpc://localhost:" + srv.port();
            ErrcServerSupport.CliResult r = cli("query", "--url", u, "--sql", "SELECT id, price FROM priced_v");
            assertThat(r.code()).isEqualTo(1);
            assertThat(r.err())
                    .as("the engine's own refusal reaches the client, naming the code and the column")
                    .contains("PRV-6100")
                    .contains("price")
                    .doesNotContain("There was an error servicing your request");
        }
    }

    // ------------------------------------------------------------ ERRC-090 -- PRV-6101

    @Test
    void unimplementedFlightSqlMetadataCallsReturnArrowsOwnUnimplementedStatusNotAPravahaCode() throws Exception {
        // Correction to the case: PravahaFlightSqlProducer's only two PRV-6101 throw sites are an
        // unrecognised *custom* Pravaha Action (not a Flight SQL metadata call at all) and a
        // registry action against a server with none hosted -- neither is
        // getSqlInfo/getCrossReference/getPrimaryKeys/beginTransaction, which the class does not
        // override at all. Confirmed empirically: they fall through to Arrow's own
        // FlightSqlProducer base class, which answers UNIMPLEMENTED -- a gRPC/Flight status, not a
        // PRV- code. The case's own falsifier is arguably realised here in a different way: not
        // "returns an empty result" (its named risk) but "returns a framework status with no PRV
        // code and no pointer to what this server does support."
        try (RootAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                FlightClient transport = FlightClient.builder(
                                allocator, Location.forGrpcInsecure("localhost", server.port()))
                        .build()) {
            FlightSqlClient sql = new FlightSqlClient(transport);
            // getPrimaryKeys itself only issues getFlightInfo, which for most Flight SQL metadata
            // calls just returns an empty-result descriptor lazily -- the actual RPC, and any
            // failure, only happens once the returned ticket is fetched via getStream, exactly like
            // PravahaFlightClient.query's own two-step protocol elsewhere in this file.
            var info = sql.getPrimaryKeys(org.apache.arrow.flight.sql.util.TableRef.of(null, null, "txn"));
            assertThatThrownBy(() -> {
                        try (var stream =
                                sql.getStream(info.getEndpoints().get(0).getTicket())) {
                            while (stream.next()) {
                                // drain
                            }
                        }
                    })
                    .isInstanceOfSatisfying(FlightRuntimeException.class, e -> {
                        System.out.println(
                                "ERRC-090 getPrimaryKeys: status=" + e.status().code() + " description="
                                        + e.status().description());
                        assertThat(
                                        e.status().description() == null
                                                ? ""
                                                : e.status().description())
                                .as("no PRV- code in an unimplemented Flight SQL metadata call's status")
                                .doesNotContain("PRV-");
                    });
        }
    }

    @Test
    void anUnrecognisedActionAndARegistrylessServerAreTheRealPrv6101Sites() throws Exception {
        try (RootAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                FlightClient transport = FlightClient.builder(
                                allocator, Location.forGrpcInsecure("localhost", server.port()))
                        .build()) {
            // A validly-framed ControlWire payload (so it passes the magic-number/version checks
            // that would otherwise give PRV-6102, as the next test shows) naming an action string
            // the dispatch switch does not recognise.
            byte[] validFrameUnknownAction = com.ash.messaging.pravaha.api.wire.ControlWire.encode(List.of("nosense"));
            var results = transport.doAction(new Action("pravaha.nosuchaction", validFrameUnknownAction));
            assertThatThrownBy(() -> {
                        while (results.hasNext()) {
                            results.next();
                        }
                    })
                    .isInstanceOfSatisfying(FlightRuntimeException.class, e -> {
                        assertThat(e.status().description())
                                .contains("PRV-6101")
                                .contains("nosuchaction");
                    });
        }

        ViewCatalog v = new ViewCatalog();
        try (PravahaFlightServer registryless = new PravahaFlightServer(v).start("localhost", 0);
                RootAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                FlightClient transport = FlightClient.builder(
                                allocator, Location.forGrpcInsecure("localhost", registryless.port()))
                        .build()) {
            byte[] validFrame = com.ash.messaging.pravaha.api.wire.ControlWire.encode(List.of());
            var results = transport.doAction(new Action("pravaha.list", validFrame));
            assertThatThrownBy(() -> {
                        while (results.hasNext()) {
                            results.next();
                        }
                    })
                    .isInstanceOfSatisfying(FlightRuntimeException.class, e -> {
                        assertThat(e.status().description())
                                .contains("PRV-6101")
                                .contains("registry");
                    });
        }
    }

    // ------------------------------------------------------------ ERRC-091 -- PRV-6102

    @Test
    void aHandBuiltActionThatIsNotAPravahaRequestIsRefusedAsAFlightBadHandle() throws Exception {
        try (RootAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                FlightClient transport = FlightClient.builder(
                                allocator, Location.forGrpcInsecure("localhost", server.port()))
                        .build()) {
            // A payload that is not ControlWire-framed at all (no magic number).
            var results = transport.doAction(new Action("pravaha.register", "not a control wire payload".getBytes()));
            List<Result> collected = new java.util.ArrayList<>();
            assertThatThrownBy(() -> {
                        while (results.hasNext()) {
                            collected.add(results.next());
                        }
                    })
                    .isInstanceOfSatisfying(FlightRuntimeException.class, e -> {
                        assertThat(e.status().description()).contains("PRV-6102");
                        // E3 finding: ControlWire's own message is generic ("this Pravaha request is
                        // malformed" / "this is not a Pravaha request") and names nothing specific --
                        // confirmed here, matching the case's own E3(a) concern.
                        assertThat(e.status().description())
                                .as("E3(a) fails: the message names nothing specific")
                                .contains("this is not a Pravaha request");
                    });
        }
        // E2, checked directly rather than by scraping the wire text: PravahaException's rendered
        // form is "code() + '  ' + message", never the ErrorCode's *name* -- so E2 for a Flight-wire
        // code can only be confirmed against the object itself, on the server side. The case's own
        // assertion is that the alias resolves to the documented name.
        assertThat(com.ash.messaging.pravaha.flight.FlightErrors.BAD_HANDLE.name())
                .isEqualTo("FLIGHT_BAD_HANDLE");
        assertThat(com.ash.messaging.pravaha.flight.FlightErrors.BAD_HANDLE)
                .isSameAs(com.ash.messaging.pravaha.api.wire.ControlWire.BAD_REQUEST);
    }

    // ------------------------------------------------------------ ERRC-092 -- PRV-6103

    @Test
    void parametersAtExactlyOneMegabyteSucceedAndOneByteOverIsRefused() {
        // The registration itself carries no placeholder -- QueryRegistry.register builds the plan
        // immediately and an unbound "?" in a continuous-query registration is refused (PRV-2060) at
        // that point. The "?" this case is about lives in the Flight *read* (a bounded, ad-hoc SELECT
        // against the registered view), which is a separate statement bound per call.
        registry.register("v1", "SELECT usr, amount FROM txn", List.of(0), Principal.ANONYMOUS);
        // MAX_PARAMETER_BYTES = 1 << 20 = 1048576, and the boundary is the whole case: a build with
        // no limit passes both.
        try (PravahaFlightClient client = PravahaFlightClient.connect(url)) {
            // The check is on the bound values' Arrow IPC-encoded bytes (StatementHandle.boundTo),
            // not the raw string length -- IPC framing (schema message, buffer padding) adds a
            // measured ~328 bytes of overhead for one STRING parameter this size, so the string
            // itself must stay further under the ceiling than the ceiling alone would suggest.
            // Chosen with headroom and confirmed empirically below, rather than asserted from theory.
            String underCeiling = "x".repeat(1_048_576 - 2_000);
            String overCeiling = "x".repeat(1_048_576 + 1);
            RuntimeException small =
                    org.assertj.core.api.Assertions.catchThrowableOfType(RuntimeException.class, () -> {
                        client.query("SELECT usr FROM v1 WHERE usr = ?", underCeiling)
                                .close();
                    });
            RuntimeException big = org.assertj.core.api.Assertions.catchThrowableOfType(
                    RuntimeException.class, () -> client.query("SELECT usr FROM v1 WHERE usr = ?", overCeiling));
            System.out.println("ERRC-092 small=" + (small == null ? "ok" : small.getMessage()));
            System.out.println("ERRC-092 big=" + (big == null ? "ok" : big.getMessage()));
            assertThat(small)
                    .as("comfortably under the ceiling must succeed -- the vacuity control")
                    .isNull();
            assertThat(big).as("over the ceiling must be refused").isNotNull();
            assertThat(big.getMessage()).contains("PRV-6103").contains("1048576");
        }
    }

    // ------------------------------------------------------------ ERRC-093 -- PRV-6104

    @Test
    void anUnreadableTlsCertificateOrKeyRefusesAtConfigurationTimeNamingTheAbsolutePath(@TempDir Path dir)
            throws Exception {
        ViewCatalog v = new ViewCatalog();
        Path absentCert = dir.resolve("absent-cert.pem");
        Path absentKey = dir.resolve("absent-key.pem");
        Path realKey = dir.resolve("real-key.pem");
        Files.writeString(realKey, "not really a key but a readable file");

        // `encryptedWith` itself is the throw site (PravahaFlightServer.java), reached before start()
        // ever binds a port -- confirmed here at configuration time, matching the case's "at startup"
        // requirement (no port is bound for either of these).
        assertThatThrownBy(() -> new PravahaFlightServer(v).encryptedWith(absentCert.toFile(), realKey.toFile()))
                .hasMessageContaining("PRV-6104")
                .hasMessageContaining(absentCert.toAbsolutePath().toString());

        Files.writeString(absentCert.getParent().resolve("real-cert.pem"), "not really a cert but a readable file");
        Path realCert = absentCert.getParent().resolve("real-cert.pem");
        assertThatThrownBy(() -> new PravahaFlightServer(v).encryptedWith(realCert.toFile(), absentKey.toFile()))
                .hasMessageContaining("PRV-6104")
                .hasMessageContaining(absentKey.toAbsolutePath().toString());
    }

    private void feed(long id, String usr, long amount, long weight) {
        var query = registry.require("v1");
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setLong(0, id);
        writer.setString(1, usr);
        writer.setLong(2, amount);
        writer.setLong(3, id);
        writer.weight(weight).eventTimestampNanos(id).sequence(id).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
        query.commit();
    }
}
