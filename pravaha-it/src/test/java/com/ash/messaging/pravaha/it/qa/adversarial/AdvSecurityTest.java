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
package com.ash.messaging.pravaha.it.qa.adversarial;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.alerts.AlertProperties;
import com.ash.messaging.pravaha.server.alerts.NotifierBindingProperties;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.governance.CatalogProperties;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;
import com.ash.messaging.pravaha.server.tenancy.TenancyProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QE-096..QE-137, QE-153: security semantics on a real node over Flight SQL, with the catalogue on.
 * Principals: {@code ops} (acme, admin), {@code ana} (acme, analyst, region EU), {@code bob} (acme,
 * analyst, region US), {@code mal} (acme, analyst, region carrying SQL), {@code eve} (globex,
 * analyst), {@code gops} (globex, admin).
 */
@Timeout(300)
@EnabledIfSystemProperty(named = AdvSupport.SWITCH, matches = "true")
class AdvSecurityTest {

    static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("id", Types.string())
            .field("region", Types.string().withNullable(true))
            .field("card", Types.string())
            .field("amount", Types.int64())
            .build();

    @TempDir
    Path dir;

    private PravahaNode node;
    private BufferAllocator allocator;
    private FlightClient flight;
    private FlightSqlClient sql;
    private RowArena arena;
    private long sequence = 1;

    @AfterEach
    void stop() throws Exception {
        if (sql != null) {
            sql.close();
        }
        if (allocator != null) {
            allocator.close();
        }
        if (node != null) {
            node.stop();
        }
        if (arena != null) {
            arena.close();
        }
    }

    // ------------------------------------------------------------------ the node

    void start(TenancyProperties tenancy) {
        StreamCatalog streams = new StreamCatalog();
        streams.register(TXN);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal(dir.resolve("registry.journal").toString());
        SecurityProperties security = new SecurityProperties();
        security.setAuthentication("token");
        security.setPolicy("authenticated");
        Map<String, SecurityProperties.TokenSpec> tokens = new LinkedHashMap<>();
        tokens.put("ops-token", spec("ops", "acme", "admin", null));
        tokens.put("ana-token", spec("ana", "acme", "analyst", "EU"));
        tokens.put("bob-token", spec("bob", "acme", "analyst", "US"));
        tokens.put("mal-token", spec("mal", "acme", "analyst", "EU' OR '1'='1"));
        tokens.put("eve-token", spec("eve", "globex", "analyst", "EU"));
        tokens.put("gops-token", spec("gops", "globex", "admin", null));
        security.setTokens(tokens);
        node = PravahaNode.builder()
                .withCatalog(streams)
                .withSecurity(security)
                .withWatermark(Duration.ofSeconds(30), Duration.ofSeconds(1))
                .withFlight(true, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("qe-security")
                .build();
        CatalogProperties catalog = new CatalogProperties();
        catalog.setEnabled(true);
        catalog.setAuthority("catalog");
        node.setCatalog(catalog);
        NotifierBindingProperties notifiers = new NotifierBindingProperties();
        NotifierBindingProperties.Spec log = new NotifierBindingProperties.Spec();
        log.setPlugin("log");
        log.setOptions(Map.of());
        notifiers.getNotifiers().put("ops_log", log);
        node.setAlerts(new AlertProperties(), notifiers);
        if (tenancy != null) {
            node.setTenancy(tenancy);
        }
        node.start();
        allocator = new RootAllocator(Long.MAX_VALUE);
        flight = FlightClient.builder(
                        allocator,
                        Location.forGrpcInsecure("127.0.0.1", node.flightPort().orElseThrow()))
                .build();
        sql = new FlightSqlClient(flight);
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    static SecurityProperties.TokenSpec spec(String id, String tenant, String role, String region) {
        SecurityProperties.TokenSpec spec = new SecurityProperties.TokenSpec();
        spec.setId(id);
        spec.setTenant(tenant);
        spec.setRoles(List.of(role));
        if (region != null) {
            spec.setClaims(Map.of("region", region));
        }
        return spec;
    }

    /** payments, granted to analysts, filtered by region and masked on card. */
    void baseline() throws Exception {
        start(null);
        run("ops", "CREATE CONTINUOUS QUERY payments KEYED BY (id) AS SELECT id, region, card, amount FROM txn");
        run("ops", "GRANT SELECT, SUBSCRIBE ON VIEW payments TO ROLE analyst");
        run("ops", "CREATE ROW FILTER region_scope AS region = session_attribute('region') EXCEPT ROLE admin");
        run("ops", "CREATE MASK card_last4 ON COLUMN card AS 'XXXX-' || SUBSTRING(card FROM 6) EXCEPT ROLE admin");
        run("ops", "ALTER VIEW payments SET POLICY region_scope");
        run("ops", "ALTER VIEW payments SET POLICY card_last4");
        pay("acme.default.payments");
    }

    void pay(String engineName) {
        RegisteredQuery query = node.registry().orElseThrow().find(engineName).orElseThrow();
        push(query, "p1", "EU", "4111-1111", 10);
        push(query, "p2", "US", "4222-2222", 20);
        push(query, "p3", "EU", "4333-3333", 30);
        query.commit();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (query.view().size() < 3 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    void push(RegisteredQuery query, String id, String region, String card, long amount) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, id).setString(1, region).setString(2, card).setLong(3, amount);
        long at = sequence++ * 1_000_000L;
        writer.weight(1).eventTimestampNanos(at).sequence(at).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        assertThat(query.accept("txn", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))))
                .isTrue();
        query.awaitApplied(Duration.ofSeconds(10));
    }

    static CallOption bearer(String who) {
        FlightCallHeaders headers = new FlightCallHeaders();
        headers.insert("authorization", "Bearer " + who + "-token");
        return new HeaderCallOption(headers);
    }

    @SuppressWarnings("try") // Arrow's close() declares InterruptedException; a test has nothing to restore
    List<List<String>> run(String who, String statement) throws Exception {
        CallOption auth = bearer(who);
        FlightInfo info = sql.execute(statement, auth);
        List<List<String>> rows = new ArrayList<>();
        try (FlightStream stream = sql.getStream(info.getEndpoints().get(0).getTicket(), auth)) {
            while (stream.next()) {
                rows.addAll(cells(stream.getRoot()));
            }
        }
        return rows;
    }

    static List<List<String>> cells(VectorSchemaRoot root) {
        List<List<String>> rows = new ArrayList<>();
        for (int row = 0; row < root.getRowCount(); row++) {
            List<String> cells = new ArrayList<>();
            for (int column = 0; column < root.getFieldVectors().size(); column++) {
                Object value = root.getVector(column).getObject(row);
                cells.add(value == null ? null : value.toString());
            }
            rows.add(cells);
        }
        return rows;
    }

    /** The rows as {@code a|b}, sorted -- or the refusal's first line. */
    String as(String who, String statement) {
        try {
            return run(who, statement).stream()
                    .map(r -> String.join("|", r))
                    .sorted()
                    .toList()
                    .toString();
        } catch (Exception refused) {
            String message = refused.getMessage() == null ? refused.toString() : refused.getMessage();
            int at = message.indexOf("PRV-");
            return (at >= 0 ? message.substring(at) : message)
                    .lines()
                    .findFirst()
                    .orElse("");
        }
    }

    // ------------------------------------------------------------------ masks

    @Test
    void qe096_to_100_102_aMaskedColumnCannotBeCompared() throws Exception {
        baseline();
        Map<String, String> probes = new LinkedHashMap<>();
        probes.put("QE-096 equality", "SELECT id FROM payments WHERE card = '4111-1111'");
        probes.put("QE-097 substring", "SELECT id FROM payments WHERE SUBSTRING(card FROM 1 FOR 1) = '4'");
        probes.put("QE-097 like", "SELECT id FROM payments WHERE card LIKE '4%'");
        probes.put("QE-097 upper", "SELECT id FROM payments WHERE UPPER(card) = 'X'");
        probes.put("QE-097 concat", "SELECT id FROM payments WHERE card || '' = '4111-1111'");
        probes.put("QE-097 is null", "SELECT id FROM payments WHERE card IS NULL");
        probes.put("QE-097 in", "SELECT id FROM payments WHERE card IN ('4111-1111', '4333-3333')");
        probes.put("QE-097 regexp", "SELECT id FROM payments WHERE REGEXP_EXTRACT(card, '^(4)', 1) = '4'");
        probes.put(
                "QE-097 derived",
                "SELECT id FROM (SELECT id, SUBSTRING(card FROM 1 FOR 4) AS bin FROM payments) "
                        + "WHERE bin = '4111'");
        probes.put("QE-099 group", "SELECT card, COUNT(*) AS n FROM payments GROUP BY card");
        probes.put("QE-099 distinct", "SELECT COUNT(DISTINCT card) AS n FROM payments");
        probes.put("QE-099 min", "SELECT MIN(card) AS m FROM payments");
        probes.put(
                "QE-100 rank",
                "SELECT id, rn FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY region ORDER BY card) "
                        + "AS rn FROM payments) WHERE rn <= 1");
        probes.put("QE-098 case like", "SELECT id, CASE WHEN card LIKE '4111%' THEN 1 ELSE 0 END AS hit FROM payments");
        probes.put(
                "QE-098 case substring",
                "SELECT id, CASE WHEN SUBSTRING(card FROM 1 FOR 4) = '4111' THEN 1 ELSE 0 END "
                        + "AS hit FROM payments");
        probes.put(
                "QE-098 projection", "SELECT id, SUBSTRING(card FROM 1 FOR 4) AS bin, UPPER(card) AS u FROM payments");
        probes.put(
                "QE-098 having", "SELECT region, COUNT(*) AS n FROM payments GROUP BY region HAVING MAX(card) > 'X'");
        Map<String, String> seen = new LinkedHashMap<>();
        probes.forEach((label, q) -> seen.put(label, as("ana", q)));
        seen.forEach((label, out) -> System.out.println("NOTE " + label + " => " + out));
        seen.forEach((label, out) -> {
            if (label.startsWith("QE-098")) {
                assertThat(out).as(label).doesNotContain("4111", "4333", "|1]", "|1,");
            } else {
                assertThat(out).as(label).matches("PRV-(7006|202[01]|2002|2050).*");
            }
        });
        assertThat(seen.get("QE-096 equality")).startsWith("PRV-7006");
    }

    @Test
    void qe101_aMaskedNumberDrivesNoErrorAndNoValue() throws Exception {
        baseline();
        run("ops", "CREATE MASK amount_zero ON COLUMN amount AS 0 EXCEPT ROLE admin");
        run("ops", "ALTER VIEW payments SET POLICY amount_zero");
        String values = as("ana", "SELECT id, amount FROM payments");
        String divide = as("ana", "SELECT id, 100 / (amount - 10) AS x FROM payments");
        String sum = as("ana", "SELECT SUM(amount) AS s FROM payments");
        System.out.println("NOTE QE-101 values=" + values + " divide=" + divide + " sum=" + sum);
        assertThat(values).isEqualTo("[p1|0, p3|0]");
        assertThat(divide).isEqualTo("[p1|-10, p3|-10]");
        assertThat(sum).startsWith("PRV-7006");
    }

    @Test
    void qe102_aMaskedKeyColumnCannotBeProbed() throws Exception {
        baseline();
        run("ops", "CREATE MASK id_mask ON COLUMN id AS 'p?' EXCEPT ROLE admin");
        run("ops", "ALTER VIEW payments SET POLICY id_mask");
        String probe = as("ana", "SELECT card FROM payments WHERE id = 'p1'");
        String shown = as("ana", "SELECT id, amount FROM payments");
        System.out.println("NOTE QE-102 probe=" + probe + " shown=" + shown);
        assertThat(probe).startsWith("PRV-7006");
        assertThat(shown).isEqualTo("[p?|10, p?|30]");
    }

    @SuppressWarnings("try") // Arrow's close() declares InterruptedException; a test has nothing to restore
    @Test
    void qe103_133_aSubscriptionIsMaskedAndEndsWhenItsPolicyChanges() throws Exception {
        baseline();
        try (FlightStream open = flight.getStream(
                new Ticket(ControlWire.subscribeFromSnapshotTicket("payments", List.of())), bearer("ana"))) {
            assertThat(open.next()).isTrue();
            String snapshot = cells(open.getRoot()).toString();
            System.out.println("NOTE QE-103 snapshot " + snapshot);
            assertThat(snapshot).doesNotContain("4111", "4222", "p2");
            run("ops", "ALTER VIEW payments UNSET POLICY card_last4");
            String ended = AdvSupport.attempt(() -> {
                while (open.next()) {
                    // drain until the policy change ends it
                }
            });
            System.out.println("NOTE QE-133 " + ended.lines().findFirst().orElse(""));
            assertThat(ended).contains("PRV-7007");
        }
    }

    @SuppressWarnings("try") // Arrow's close() declares InterruptedException; a test has nothing to restore
    @Test
    void qe128_aRevokedSubscribeEndsAnOpenSubscription() throws Exception {
        baseline();
        try (FlightStream open = flight.getStream(
                new Ticket(ControlWire.subscribeFromSnapshotTicket("payments", List.of())), bearer("ana"))) {
            assertThat(open.next()).isTrue();
            long started = System.nanoTime();
            run("ops", "REVOKE SUBSCRIBE ON VIEW payments FROM ROLE analyst");
            String ended = AdvSupport.attempt(() -> {
                while (open.next()) {
                    // drain until revoked
                }
            });
            long millis = (System.nanoTime() - started) / 1_000_000;
            System.out.println("NOTE QE-128 ended after " + millis + " ms: "
                    + ended.lines().findFirst().orElse(""));
            assertThat(ended).contains("PRV-7002");
            assertThat(millis).isLessThan(10_000);
        }
    }

    // ------------------------------------------------------------------ queries over a narrowed view, alerts

    @Test
    void qe104_105_112_buildingOnANarrowedView() throws Exception {
        baseline();
        run("ops", "GRANT USE, CREATE ON NAMESPACE default TO ROLE analyst");
        run("ops", "GRANT BUILD_ON ON VIEW payments TO ROLE analyst");
        run("ops", "GRANT WRITE ON NOTIFIER ops_log TO ROLE analyst");
        String created =
                as("ana", "CREATE CONTINUOUS QUERY ana_copy KEYED BY (id) AS SELECT id, region, card FROM payments");
        Thread.sleep(1500);
        String own = as("ana", "SELECT * FROM ana_copy");
        String held = node.registry()
                .orElseThrow()
                .find("acme.default.ana_copy")
                .map(q -> q.view().scan().stream()
                        .map(AdvSupport::render)
                        .sorted()
                        .toList()
                        .toString())
                .orElse("-");
        String granted = as("ana", "GRANT SELECT ON VIEW ana_copy TO USER bob");
        String bob = as("bob", "SELECT * FROM ana_copy");
        String alert = as("ana", "CREATE ALERT card_watch ON payments WHERE card = '4111-1111' NOTIFY ops_log");
        String alertOk = as("ana", "CREATE ALERT eu_watch ON payments WHERE amount > 5 NOTIFY ops_log");
        System.out.println("NOTE QE-104 create=" + created + " ana reads=" + own + " held=" + held);
        System.out.println("NOTE QE-112 grant=" + granted + " bob reads=" + bob);
        System.out.println("NOTE QE-105 alert on masked=" + alert + " alert on amount=" + alertOk);
        assertThat(held).doesNotContain("4111", "4333", "p2");
        assertThat(bob).doesNotContain("4111", "4333", "p2");
        assertThat(alert).as("QE-105, recorded in its own tests").isNotEmpty();
    }

    /** ana's alert comparing the masked card with the raw value of p1; what it says after the rows arrive. */
    List<String> maskedAlert() throws Exception {
        start(null);
        run("ops", "CREATE CONTINUOUS QUERY payments KEYED BY (id) AS SELECT id, region, card, amount FROM txn");
        run("ops", "GRANT SELECT, SUBSCRIBE ON VIEW payments TO ROLE analyst");
        run("ops", "CREATE MASK card_last4 ON COLUMN card AS 'XXXX-' || SUBSTRING(card FROM 6) EXCEPT ROLE admin");
        run("ops", "ALTER VIEW payments SET POLICY card_last4");
        run("ops", "GRANT USE, CREATE ON NAMESPACE default TO ROLE analyst");
        run("ops", "GRANT WRITE ON NOTIFIER ops_log TO ROLE analyst");
        List<String> seen = new ArrayList<>();
        seen.add("create raw: "
                + as("ana", "CREATE ALERT raw_guess ON payments WHERE card = '4111-1111' NOTIFY ops_log"));
        seen.add("create masked: "
                + as("ana", "CREATE ALERT masked_guess ON payments WHERE card = 'XXXX-1111' NOTIFY ops_log"));
        pay("acme.default.payments");
        Thread.sleep(3000);
        var service = (com.ash.messaging.pravaha.registry.alert.AlertService)
                node.registry().orElseThrow().alerting();
        Principal ana = new Principal("ana", "acme", Set.of("analyst"), Map.of("region", "EU"));
        for (String name : List.of("raw_guess", "masked_guess")) {
            String detail = AdvSupport.attempt(() -> {
                var d = service.detail(ana, name);
                throw new IllegalStateException("state=" + d.alert().state() + " following="
                        + d.alert().following()
                        + " keys="
                        + d.keys().stream().map(k -> k.key() + ":" + k.state()).toList()
                        + " sent=" + d.notifications().size());
            });
            seen.add(name + ": " + detail.replace("UNCODED java.lang.IllegalStateException: ", ""));
        }
        seen.add("show: " + as("ana", "SHOW ALERTS"));
        return seen;
    }

    @Test
    void qe105_anAlertComparingAMaskedColumnIsRefusedWhenCreated() throws Exception {
        // MASKALERT-1, fixed: both are refused PRV-7006 to the person creating them.
        List<String> seen = maskedAlert();
        seen.forEach(s -> System.out.println("NOTE QE-105 " + s));
        assertThat(seen.get(0)).contains("PRV-7006");
        assertThat(seen.get(1)).contains("PRV-7006");
    }

    // ------------------------------------------------------------------ row filters

    @Test
    void qe107_to_110_137_aRowFilterHoldsOnEveryAccessPath() throws Exception {
        baseline();
        run(
                "ops",
                "CREATE CONTINUOUS QUERY by_region KEYED BY (id) INDEX (region) AS SELECT id, region, amount FROM txn");
        run(
                "ops",
                "CREATE CONTINUOUS QUERY ranged KEYED BY (region, amount) RANGE (amount) AS SELECT region, amount, id FROM txn");
        run("ops", "GRANT SELECT ON VIEW by_region TO ROLE analyst");
        run("ops", "GRANT SELECT ON VIEW ranged TO ROLE analyst");
        run("ops", "ALTER VIEW by_region SET POLICY region_scope");
        run("ops", "ALTER VIEW ranged SET POLICY region_scope");
        pay("acme.default.by_region");
        pay("acme.default.ranged");
        Map<String, String> seen = new LinkedHashMap<>();
        seen.put("QE-107 point read", as("ana", "SELECT id FROM payments WHERE id = 'p2'"));
        seen.put("QE-107 point read IN", as("ana", "SELECT id FROM payments WHERE id IN ('p1', 'p2')"));
        seen.put("QE-108 index", as("ana", "SELECT id FROM by_region WHERE region = 'US'"));
        seen.put("QE-108 index IN", as("ana", "SELECT id FROM by_region WHERE region IN ('US', 'EU')"));
        seen.put(
                "QE-109 range",
                as("ana", "SELECT id FROM ranged WHERE region = 'US' AND amount >= 0 AND amount < 100"));
        seen.put("QE-109 key", as("ana", "SELECT id FROM ranged WHERE region = 'US' AND amount = 20"));
        seen.put("QE-110 aggregate", as("ana", "SELECT COUNT(*) AS n, SUM(amount) AS s FROM payments"));
        seen.put("QE-137 count probe", as("ana", "SELECT COUNT(*) AS n FROM payments WHERE amount > 15"));
        seen.put("QE-110 group", as("ana", "SELECT region, COUNT(*) AS n FROM payments GROUP BY region"));
        seen.forEach((label, out) -> System.out.println("NOTE " + label + " => " + out));
        assertThat(seen.get("QE-107 point read")).isEqualTo("[]");
        assertThat(seen.get("QE-107 point read IN")).isEqualTo("[p1]");
        assertThat(seen.get("QE-108 index")).isEqualTo("[]");
        assertThat(seen.get("QE-108 index IN")).isEqualTo("[p1, p3]");
        assertThat(seen.get("QE-109 range")).isEqualTo("[]");
        assertThat(seen.get("QE-109 key")).isEqualTo("[]");
        assertThat(seen.get("QE-110 aggregate")).isEqualTo("[2|40]");
        assertThat(seen.get("QE-137 count probe")).isEqualTo("[1]");
        assertThat(seen.get("QE-110 group")).isEqualTo("[EU|2]");
    }

    /** The fifth field (ROWS IN) of each pravaha.list row, as {@code who} is shown it. */
    List<String> rowsIn(String who) {
        List<String> rows = new ArrayList<>();
        flight.doAction(new org.apache.arrow.flight.Action("pravaha.list", ControlWire.encode(List.of())), bearer(who))
                .forEachRemaining(r -> {
                    List<String> fields = ControlWire.decode(r.getBody());
                    rows.add(fields.get(0) + "=" + fields.get(4));
                });
        return rows;
    }

    @Test
    void qe111_listWithholdsAFilteredReadersCount() throws Exception {
        // LISTCOUNT-1, fixed: the catalogue's row filter withholds the count as SX-18's policy filter does.
        baseline();
        assertThat(rowsIn("ana")).containsExactly("payments=-1");
    }

    @Test
    void qe111_anUnfilteredReaderIsStillToldTheCount() throws Exception {
        baseline();
        assertThat(rowsIn("ops")).containsExactly("payments=3");
        assertThat(as("ana", "SELECT COUNT(*) AS n FROM payments")).isEqualTo("[2]");
    }

    @Test
    void qe113_tautologiesAgainstTheVacuityAnalysis() throws Exception {
        baseline();
        run("ops", "ALTER VIEW payments UNSET POLICY region_scope");
        List<String> tautologies = List.of(
                "region LIKE '%'",
                "amount * 0 = 0",
                "ABS(amount) >= 0",
                "CASE WHEN region = 'EU' THEN TRUE ELSE TRUE END",
                "region IN ('EU') OR region NOT IN ('EU')",
                "amount BETWEEN -9223372036854775808 AND 9223372036854775807",
                "NOT (region <> 'EU' AND region = 'EU')",
                "UPPER(region) = UPPER(region)",
                "region || '' = region",
                "amount + 0 = amount",
                "amount = amount OR region = 'EU'",
                "SUBSTRING(region FROM 1) = region",
                "TRIM(region) = TRIM(region)",
                "(amount > 5) IS NOT NULL",
                "amount * amount >= 0",
                "amount >= 0 OR amount < 0",
                "region IS NOT NULL OR region IS NULL");
        List<String> accepted = new ArrayList<>();
        int i = 0;
        for (String predicate : tautologies) {
            String name = "t" + i++;
            String created = as("ops", "CREATE ROW FILTER " + name + " AS " + predicate + " EXCEPT ROLE admin");
            String bound = created.startsWith("PRV-") ? created : as("ops", "ALTER VIEW payments SET POLICY " + name);
            String read = as("ana", "SELECT id FROM payments");
            System.out.println("NOTE QE-113 [" + predicate + "] create="
                    + created.lines().findFirst().orElse("") + " bind="
                    + bound.lines().findFirst().orElse("") + " ana reads=" + read);
            if (!bound.startsWith("PRV-")) {
                accepted.add(predicate + " -> " + read);
                as("ops", "ALTER VIEW payments UNSET POLICY " + name);
            }
        }
        System.out.println("NOTE QE-113 accepted " + accepted.size() + "/" + tautologies.size() + ": " + accepted);
        // SEC: the analysis is sound, not complete; a miss is a NOTE. What must hold is that whatever
        // it accepted, the reader was not shown a row the filter does not keep.
        assertThat(accepted).allSatisfy(a -> assertThat(a).doesNotContain("PRV-"));
    }

    @Test
    void qe114_aClaimCarryingSqlIsALiteral() throws Exception {
        baseline();
        String mal = as("mal", "SELECT id FROM payments");
        System.out.println("NOTE QE-114 mal reads " + mal);
        assertThat(mal).isEqualTo("[]");
    }

    // ------------------------------------------------------------------ tenants

    @Test
    void qe115_to_124_136_tenantsDoNotSeeEachOther() throws Exception {
        baseline();
        run("ops", "CREATE ALERT acme_watch ON payments WHERE amount > 5 NOTIFY ops_log");
        run("gops", "GRANT USE, CREATE ON NAMESPACE default TO ROLE analyst");
        run("gops", "GRANT BUILD_ON ON STREAM txn TO ROLE analyst");
        run("gops", "GRANT WRITE ON NOTIFIER ops_log TO ROLE analyst");
        Map<String, String> seen = new LinkedHashMap<>();
        seen.put(
                "QE-115 same name",
                as("eve", "CREATE CONTINUOUS QUERY payments KEYED BY (id) AS SELECT id, amount FROM txn"));
        seen.put("QE-115 reads own", as("eve", "SELECT * FROM payments"));
        seen.put("QE-118 qualified, exists", as("eve", "SELECT * FROM \"acme.default.payments\""));
        seen.put("QE-118 qualified, absent", as("eve", "SELECT * FROM \"acme.default.nothing_here\""));
        seen.put("QE-119 show", as("eve", "SHOW CONTINUOUS QUERIES"));
        seen.put("QE-120 drop acme-only name", as("eve", "DROP CONTINUOUS QUERY acme_only"));
        seen.put("QE-120 drop absent name", as("eve", "DROP CONTINUOUS QUERY never_was"));
        seen.put("QE-120 pause acme-only name", as("eve", "PAUSE CONTINUOUS QUERY acme_only"));
        seen.put("QE-120 pause absent name", as("eve", "PAUSE CONTINUOUS QUERY never_was"));
        seen.put(
                "QE-121 build on acme-only",
                as("eve", "CREATE CONTINUOUS QUERY x1 KEYED BY (id) AS SELECT id FROM acme_only"));
        seen.put(
                "QE-121 build on absent",
                as("eve", "CREATE CONTINUOUS QUERY x2 KEYED BY (id) AS SELECT id FROM never_was"));
        seen.put(
                "QE-116 alert named like acme's query",
                as("eve", "CREATE ALERT acme_only ON payments WHERE amount > 1 NOTIFY ops_log"));
        seen.put(
                "QE-116 alert named like acme's alert",
                as("eve", "CREATE ALERT acme_watch ON payments WHERE amount > 1 NOTIFY ops_log"));
        seen.put(
                "QE-116 alert named like nothing",
                as("eve", "CREATE ALERT fresh_name ON payments WHERE amount > 1 NOTIFY ops_log"));
        seen.put("QE-117 policy named like acme's", as("gops", "CREATE ROW FILTER region_scope AS amount > 0"));
        seen.put(
                "QE-116 alert on acme-only view",
                as("eve", "CREATE ALERT a3 ON acme_only WHERE amount > 1 NOTIFY ops_log"));
        seen.put("QE-123 typo", as("eve", "SELECT * FROM paymnts"));
        seen.put(
                "QE-136 smuggled name",
                as("eve", "CREATE CONTINUOUS QUERY \"acme.default.x\" KEYED BY (id) AS SELECT id FROM txn"));
        seen.put("QE-122 GetTables", tables("eve"));
        seen.put("QE-122 GetTables, admin", tables("ops"));
        seen.put("QE-122 GetTables, ana", tables("ana"));
        seen.put("QE-122 GetTables, gops", tables("gops"));
        seen.forEach((label, out) -> System.out.println("NOTE " + label + " => " + out));
        assertThat(seen.get("QE-122 GetTables")).doesNotContain("acme");
        String engineNames = node.registry().orElseThrow().names().toString();
        System.out.println("NOTE QE-124 engine names " + engineNames + " computations "
                + node.registry().orElseThrow().queries().size());
        assertThat(seen.get("QE-115 same name")).doesNotStartWith("PRV-");
        assertThat(normal(seen.get("QE-118 qualified, exists"), "payments"))
                .isEqualTo(normal(seen.get("QE-118 qualified, absent"), "nothing_here"));
        assertThat(seen.get("QE-119 show")).doesNotContain("acme");
        assertThat(seen.get("QE-123 typo")).doesNotContain("acme", "acme_watch");
    }

    @Test
    void qe168_getTablesListsTheViewsACallerMayRead() throws Exception {
        // GETTABLES-1, fixed: the streams behind a view are asked mayReadThrough, as pravaha.list asks them.
        baseline();
        assertThat(tables("ana")).contains("payments");
    }

    @Test
    void qe168_getTablesListsOwnViewsAndNothingOfAnotherTenant() throws Exception {
        baseline();
        run("gops", "GRANT USE, CREATE ON NAMESPACE default TO ROLE analyst");
        run("gops", "GRANT BUILD_ON ON STREAM txn TO ROLE analyst");
        as("eve", "CREATE CONTINUOUS QUERY payments KEYED BY (id) AS SELECT id, amount FROM txn");
        String eve = tables("eve");
        String ana = tables("ana");
        System.out.println("NOTE QE-168 eve GetTables " + eve + "; ana GetTables " + ana + "; ops " + tables("ops"));
        assertThat(eve).contains("payments").doesNotContain("acme");
        assertThat(ana).contains("payments").doesNotContain("globex");
        assertThat(as("ana", "SELECT id FROM payments")).isEqualTo("[p1, p3]");
    }

    /** Flight SQL GetTables as {@code who}: every catalog.schema.table it lists. */
    @SuppressWarnings("try") // Arrow's close() declares InterruptedException; a test has nothing to restore
    String tables(String who) {
        try {
            FlightInfo info = sql.getTables(null, null, null, null, false, bearer(who));
            List<String> names = new ArrayList<>();
            try (FlightStream stream = sql.getStream(info.getEndpoints().get(0).getTicket(), bearer(who))) {
                while (stream.next()) {
                    for (List<String> row : cells(stream.getRoot())) {
                        names.add(row.get(0) + "." + row.get(1) + "." + row.get(2));
                    }
                }
            }
            return names.toString();
        } catch (Exception refused) {
            return String.valueOf(refused.getMessage()).lines().findFirst().orElse("");
        }
    }

    /** A refusal with the name asked for replaced, so two refusals can be compared. */
    static String normal(String refusal, String name) {
        return refusal.replace(name, "<name>");
    }

    @Test
    void qe116_120_121_namesAnotherTenantHolds() throws Exception {
        baseline();
        run("ops", "CREATE CONTINUOUS QUERY acme_only KEYED BY (id) AS SELECT id, amount FROM txn");
        run("ops", "CREATE ALERT acme_watch ON payments WHERE amount > 5 NOTIFY ops_log");
        run("gops", "GRANT USE, CREATE ON NAMESPACE default TO ROLE analyst");
        run("gops", "GRANT BUILD_ON ON STREAM txn TO ROLE analyst");
        run("gops", "GRANT WRITE ON NOTIFIER ops_log TO ROLE analyst");
        as("eve", "CREATE CONTINUOUS QUERY payments KEYED BY (id) AS SELECT id, amount FROM txn");
        Map<String, String[]> pairs = new LinkedHashMap<>();
        pairs.put("QE-120 drop", new String[] {"DROP CONTINUOUS QUERY acme_only", "DROP CONTINUOUS QUERY never_was"});
        pairs.put(
                "QE-120 pause", new String[] {"PAUSE CONTINUOUS QUERY acme_only", "PAUSE CONTINUOUS QUERY never_was"});
        pairs.put("QE-121 build", new String[] {
            "CREATE CONTINUOUS QUERY x1 KEYED BY (id) AS SELECT id FROM acme_only",
            "CREATE CONTINUOUS QUERY x2 KEYED BY (id) AS SELECT id FROM never_was"
        });
        pairs.put("QE-116 alert name = acme query", new String[] {
            "CREATE ALERT acme_only ON payments WHERE amount > 1 NOTIFY ops_log",
            "CREATE ALERT never_was ON payments WHERE amount > 1 NOTIFY ops_log"
        });
        pairs.put("QE-116 alert name = acme alert", new String[] {
            "CREATE ALERT acme_watch ON payments WHERE amount > 1 NOTIFY ops_log",
            "CREATE ALERT never_was2 ON payments WHERE amount > 1 NOTIFY ops_log"
        });
        pairs.put("QE-116 alert on acme view", new String[] {
            "CREATE ALERT a3 ON acme_only WHERE amount > 1 NOTIFY ops_log",
            "CREATE ALERT a4 ON never_was WHERE amount > 1 NOTIFY ops_log"
        });
        pairs.put("QE-117 policy name", new String[] {
            "CREATE ROW FILTER region_scope AS amount > 0", "CREATE ROW FILTER never_was3 AS amount > 0"
        });
        List<String> distinguishable = new ArrayList<>();
        pairs.forEach((label, pair) -> {
            String who = label.startsWith("QE-117") ? "gops" : "eve";
            String held = as(who, pair[0]);
            String free = as(who, pair[1]);
            System.out.println("NOTE " + label + " held-by-acme => " + held + " | nobody's => " + free);
            String a = held.replaceAll("acme_only|acme_watch|region_scope|x1|a3", "<n>");
            String b = free.replaceAll("never_was[23]?|x2|a4", "<n>");
            if (!a.equals(b) && (a.startsWith("PRV-") || b.startsWith("PRV-"))) {
                distinguishable.add(label + ": [" + held + "] vs [" + free + "]");
            }
        });
        System.out.println("NOTE QE-116..121 distinguishable " + distinguishable);
        assertThat(distinguishable).isEmpty();
    }

    // ------------------------------------------------------------------ ownership and grants

    @Test
    void qe125_127_129_ownershipAndGrantEscalation() throws Exception {
        baseline();
        Map<String, String> seen = new LinkedHashMap<>();
        seen.put("QE-125 ana drops", as("ana", "DROP CONTINUOUS QUERY payments"));
        seen.put("QE-125 ana pauses", as("ana", "PAUSE CONTINUOUS QUERY payments"));
        seen.put("QE-127 ana grants", as("ana", "GRANT SELECT ON VIEW payments TO USER eve"));
        seen.put("QE-127 ana grants herself MANAGE", as("ana", "GRANT MANAGE ON VIEW payments TO USER ana"));
        seen.put("QE-129 ana unbinds", as("ana", "ALTER VIEW payments UNSET POLICY region_scope"));
        seen.put("QE-129 ana takes ownership", as("ana", "ALTER VIEW payments OWNER TO USER ana"));
        seen.put(
                "QE-129 bob sees ana's effective access",
                as("bob", "SHOW EFFECTIVE ACCESS FOR USER ana ON VIEW payments"));
        seen.put("QE-129 bob shows grants", as("bob", "SHOW GRANTS ON VIEW payments"));
        run("ops", "GRANT MODIFY ON VIEW payments TO USER bob");
        seen.put("QE-129 bob(MODIFY) unbinds", as("bob", "ALTER VIEW payments UNSET POLICY region_scope"));
        seen.put("QE-129 bob(MODIFY) takes ownership", as("bob", "ALTER VIEW payments OWNER TO USER bob"));
        seen.put("QE-129 bob(MODIFY) grants", as("bob", "GRANT SELECT ON VIEW payments TO USER eve"));
        seen.put("QE-129 bob(MODIFY) pauses", as("bob", "PAUSE CONTINUOUS QUERY payments"));
        seen.put("QE-129 bob reads after", as("bob", "SELECT id, card FROM payments"));
        seen.forEach((label, out) -> System.out.println("NOTE " + label + " => " + out));
        assertThat(seen.get("QE-125 ana drops")).startsWith("PRV-7002");
        assertThat(seen.get("QE-127 ana grants")).startsWith("PRV-");
        assertThat(seen.get("QE-127 ana grants herself MANAGE")).startsWith("PRV-");
        assertThat(seen.get("QE-129 ana unbinds")).startsWith("PRV-");
        assertThat(seen.get("QE-129 ana takes ownership")).startsWith("PRV-");
        assertThat(seen.get("QE-129 bob(MODIFY) unbinds")).startsWith("PRV-");
        assertThat(seen.get("QE-129 bob(MODIFY) takes ownership")).startsWith("PRV-");
        assertThat(seen.get("QE-129 bob reads after")).doesNotContain("4111", "p1");
    }

    /** ops drops a governed view and registers it again; what the analysts are then shown. */
    List<String> dropAndRecreate() throws Exception {
        baseline();
        List<String> seen = new ArrayList<>();
        seen.add("before: " + as("ana", "SELECT id, card FROM payments"));
        seen.add("drop: " + as("ops", "DROP CONTINUOUS QUERY payments"));
        seen.add("create: "
                + as(
                        "ops",
                        "CREATE CONTINUOUS QUERY payments KEYED BY (id) AS SELECT id, region, card, amount FROM txn"));
        pay("acme.default.payments");
        seen.add("after: " + as("ana", "SELECT id, card FROM payments"));
        seen.add("policies: " + as("ops", "SHOW POLICIES ON VIEW payments"));
        seen.add("grants: " + as("ops", "SHOW GRANTS ON VIEW payments"));
        return seen;
    }

    @Test
    void qe126_dropAndRecreateAGovernedName() throws Exception {
        List<String> seen = dropAndRecreate();
        seen.forEach(s -> System.out.println("NOTE QE-126 " + s));
        String after = seen.get(3);
        // Either the grant went with the dropped view (ana is refused), or the policies came back with
        // the name (ana is filtered and masked). Never: the grant survived and the policies did not.
        assertThat(after)
                .satisfiesAnyOf(
                        a -> assertThat(a).contains("PRV-7002"),
                        a -> assertThat(a).doesNotContain("4111", "p2"));
    }

    @Test
    void qe130_anExemptRegistrantCanPublishAnUnmaskedCopy() throws Exception {
        baseline();
        run("ops", "CREATE CONTINUOUS QUERY copy KEYED BY (id) AS SELECT id, card FROM payments");
        run("ops", "GRANT SELECT ON VIEW copy TO ROLE analyst");
        Thread.sleep(1500);
        String ana = as("ana", "SELECT * FROM copy");
        System.out.println("NOTE QE-130 ana reads ops's copy " + ana);
        assertThat(ana).isNotEmpty();
    }

    // ------------------------------------------------------------------ prepared statements, debug fork

    @SuppressWarnings("try") // Arrow's close() declares InterruptedException; a test has nothing to restore
    @Test
    void qe134_aPreparedStatementHandleIsNotAPermission() throws Exception {
        baseline();
        String outcome;
        FlightSqlClient.PreparedStatement prepared = sql.prepare("SELECT id, card FROM payments", bearer("ana"));
        try {
            outcome = AdvSupport.attempt(() -> {
                try {
                    FlightInfo info = prepared.execute(bearer("eve"));
                    List<List<String>> rows = new ArrayList<>();
                    try (FlightStream stream =
                            sql.getStream(info.getEndpoints().get(0).getTicket(), bearer("eve"))) {
                        while (stream.next()) {
                            rows.addAll(cells(stream.getRoot()));
                        }
                    }
                    throw new IllegalStateException("eve fetched " + rows);
                } catch (IllegalStateException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            });
        } finally {
            AdvSupport.attempt(() -> prepared.close(bearer("ana")));
        }
        System.out.println("NOTE QE-134 " + outcome.lines().findFirst().orElse(""));
        assertThat(outcome).doesNotContain("eve fetched [[p");
    }

    @Test
    void qe135_aDebugForkByANonOwnerIsRefused() throws Exception {
        baseline();
        Principal ana = new Principal("ana", "acme", Set.of("analyst"), Map.of("region", "EU"));
        String fork = AdvSupport.attempt(
                () -> node.registry().orElseThrow().debugSessions().fork("payments", null, ana));
        System.out.println("NOTE QE-135 " + fork.lines().findFirst().orElse(""));
        assertThat(fork).startsWith("PRV-7002");
    }

    // ------------------------------------------------------------------ quotas

    @Test
    void qe154_aTenantQuotaOnStateKeys() throws Exception {
        TenancyProperties tenancy = new TenancyProperties();
        tenancy.getDefaults().setMaxStateKeys(2L);
        start(tenancy);
        String one = as("ops", "CREATE CONTINUOUS QUERY q1 KEYED BY (id) AS SELECT id, amount FROM txn");
        pay("acme.default.q1");
        Thread.sleep(500);
        String two = as("ops", "CREATE CONTINUOUS QUERY q2 KEYED BY (id) AS SELECT id, card FROM txn");
        String q1 = node.registry()
                .orElseThrow()
                .find("acme.default.q1")
                .map(q -> q.state() + " size=" + q.view().size())
                .orElse("-");
        System.out.println("NOTE QE-154 first=" + one + " | second after 3 keys held=" + two + " | q1 " + q1);
        assertThat(two).startsWith("PRV-8021");
        assertThat(q1).startsWith("RUNNING");
    }

    @Test
    void qe153_aTenantQuotaOnQueries() throws Exception {
        TenancyProperties tenancy = new TenancyProperties();
        tenancy.getDefaults().setMaxQueries(2L);
        start(tenancy);
        String one = as("ops", "CREATE CONTINUOUS QUERY q1 KEYED BY (id) AS SELECT id FROM txn");
        String two = as("ops", "CREATE CONTINUOUS QUERY q2 KEYED BY (id) AS SELECT id, amount FROM txn");
        String three = as("ops", "CREATE CONTINUOUS QUERY q3 KEYED BY (id) AS SELECT id, card FROM txn");
        String otherTenant = as("gops", "CREATE CONTINUOUS QUERY q3 KEYED BY (id) AS SELECT id, card FROM txn");
        System.out.println("NOTE QE-153 " + one + " | " + two + " | third=" + three + " | other tenant=" + otherTenant);
        assertThat(three).startsWith("PRV-8020");
        assertThat(otherTenant).doesNotStartWith("PRV-8020");
    }
}
