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
package com.ash.messaging.pravaha.registry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.ContinuousStatements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * ADR-060: a view name is unique within its tenant, and every name is resolved in the caller's
 * tenant -- so two tenants each have their own {@code orders}, and a name another tenant holds is, to
 * a caller, a name nothing holds (TEN-1).
 *
 * <p>Under {@code PERMISSIVE}, deliberately: it lets every caller read every view, which is exactly
 * the policy under which only the name space stood between one tenant and another's names.
 */
class TenantViewNamesTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final String BIG = "SELECT user_id, amount FROM txn WHERE amount > 100";
    private static final String SMALL = "SELECT user_id, amount FROM txn WHERE amount <= 100";
    private static final String TOTALS = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn";

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());
    private static final Principal OMAR = new Principal("omar", "globex", Set.of("analyst"), Map.of());
    private static final Principal PAT = new Principal("pat", "public", Set.of("analyst"), Map.of());
    private static final Principal ROOT = new Principal("root", "public", Set.of("admin"), Map.of());

    @TempDir
    Path root;

    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    private final List<QueryRegistry> registries = new ArrayList<>();
    private final ViewCatalog views = new ViewCatalog();

    @AfterEach
    void tearDown() {
        registries.forEach(QueryRegistry::close);
        arena.close();
    }

    // ------------------------------------------------------------------ each sees its own

    @Test
    void twoTenantsRegisterTheSameNameAndEachSeesReadsAndListsOnlyItsOwn() {
        QueryRegistry registry = registry();
        RegisteredQuery danas = registry.register("orders", BIG, List.of(0), DANA);
        RegisteredQuery omars = registry.register("orders", SMALL, List.of(0), OMAR);
        for (RegisteredQuery each : List.of(danas, omars)) {
            txn(each, "u1", 500);
            txn(each, "u2", 50);
            each.commit();
        }

        assertThat(danas).isNotSameAs(omars);
        assertThat(registry.find(DANA, "orders")).containsSame(danas);
        assertThat(registry.find(OMAR, "orders")).containsSame(omars);
        assertThat(registry.find(PAT, "orders"))
                .as("the default tenant holds no orders")
                .isEmpty();

        // Read: the same text, each tenant's own view.
        ViewQuery reads = new ViewQuery(views);
        assertThat(reads.execute("SELECT user_id FROM orders", DANA).rows())
                .extracting(row -> row[0])
                .containsExactly("u1");
        assertThat(reads.execute("SELECT user_id FROM orders", OMAR).rows())
                .extracting(row -> row[0])
                .containsExactly("u2");

        // List and describe: each tenant's own, under the bare name.
        QueryListing listing = listing(registry);
        assertThat(listing.list(DANA, "list")).singleElement().satisfies(entry -> {
            assertThat(entry.name()).isEqualTo("orders");
            assertThat(entry.query()).isSameAs(danas);
            assertThat(entry.engineName()).isEqualTo("acme.default.orders");
        });
        assertThat(listing.find(OMAR, "orders", "describe"))
                .hasValueSatisfying(entry -> assertThat(entry.query()).isSameAs(omars));
        assertThat(listing.list(PAT, "list")).isEmpty();
        assertThat(sql(registry, OMAR, "SHOW CONTINUOUS QUERIES").rows())
                .singleElement()
                .satisfies(row -> assertThat(row[2]).isEqualTo(SMALL));

        // Drop: one tenant's name, the other's untouched.
        sql(registry, DANA, "DROP CONTINUOUS QUERY orders");
        assertThat(registry.find(DANA, "orders")).isEmpty();
        assertThat(registry.find(OMAR, "orders")).containsSame(omars);
    }

    // ------------------------------------------------------------------ the oracle is closed

    @Test
    void aNameAnotherTenantHoldsAnswersEveryLookupExactlyAsANameNobodyHolds() {
        QueryRegistry registry = registry();
        registry.register("payroll", BIG, List.of(0), DANA);
        QueryListing listing = listing(registry);
        DeadLetters deadLetters = new DeadLetters(registry, SecurityPolicy.PERMISSIVE, AuditSink.NONE, null);
        ViewQuery reads = new ViewQuery(views);
        // Something omar may read, so an empty node is not what tells the two apart.
        registry.register("mine", SMALL, List.of(0), OMAR);

        Map<String, java.util.function.Function<String, Object>> probes = new java.util.LinkedHashMap<>();
        probes.put("describe", name -> listing.find(OMAR, name, "describe"));
        probes.put("require", name -> registry.require(OMAR, name));
        probes.put("find", name -> registry.find(OMAR, name));
        probes.put("drop", name -> sql(registry, OMAR, "DROP CONTINUOUS QUERY " + name));
        probes.put("pause", name -> sql(registry, OMAR, "PAUSE CONTINUOUS QUERY " + name));
        probes.put("dead letters", name -> deadLetters.page(OMAR, name, 0, 10));
        probes.put("read", name -> reads.execute("SELECT * FROM " + name, OMAR));
        probes.put("schema", name -> reads.schemaOf("SELECT * FROM " + name, OMAR));
        probes.put("prepare", name -> reads.prepare("SELECT * FROM " + name, OMAR));
        probes.forEach(
                (what, probe) -> assertThat(answer(() -> probe.apply("payroll")).replace("payroll", "NAME"))
                        .as("%s of another tenant's name answers as for a name nobody holds", what)
                        .isEqualTo(answer(() -> probe.apply("nothing")).replace("nothing", "NAME")));

        // Registering it is not refused, so a refusal cannot say the name is taken.
        assertThat(registry.register("payroll", SMALL, List.of(0), OMAR)).isNotNull();
        assertThat(registry.find(DANA, "payroll").orElseThrow().sql()).isEqualTo(BIG);
    }

    @Test
    void aQualifiedNameIsRefusedToAnyoneButAnAdminAlikeWhetherItIsHeldOrNot() {
        QueryRegistry registry = registry();
        registry.register("payroll", BIG, List.of(0), DANA);

        PravahaException held = catchThrowableOfType(
                PravahaException.class, () -> QueryRegistry.engineName(OMAR, "acme.default.payroll"));
        PravahaException nobody = catchThrowableOfType(
                PravahaException.class, () -> QueryRegistry.engineName(OMAR, "acme.default.nothing"));
        assertThat(held.errorCode()).isEqualTo(SecurityErrors.FORBIDDEN);
        assertThat(held.getMessage().replace("payroll", "X"))
                .isEqualTo(nobody.getMessage().replace("nothing", "X"));
        assertThat(QueryRegistry.engineName(DANA, "acme.default.payroll"))
                .as("a caller's own tenant, qualified, is still its own")
                .isEqualTo("acme.default.payroll");
        assertThatThrownBy(() -> new ViewQuery(views).execute("SELECT * FROM \"acme.default.payroll\"", OMAR))
                .isInstanceOfSatisfying(
                        PravahaException.class, e -> assertThat(e.errorCode()).isEqualTo(SecurityErrors.FORBIDDEN));
    }

    // ------------------------------------------------------------------ admins

    @Test
    void anAdminAddressesAnotherTenantsViewByItsCatalogueName() {
        QueryRegistry registry = registry();
        RegisteredQuery danas = registry.register("orders", BIG, List.of(0), DANA);
        RegisteredQuery pats = registry.register("orders", SMALL, List.of(0), PAT);
        txn(danas, "u1", 500);
        danas.commit();

        // Unqualified, a name is the admin's own tenant's.
        assertThat(registry.find(ROOT, "orders")).containsSame(pats);
        assertThat(registry.find(ROOT, "acme.default.orders")).containsSame(danas);

        QueryListing listing = listing(registry);
        assertThat(listing.list(ROOT, "list"))
                .extracting(QueryListing.Entry::name)
                .containsExactlyInAnyOrder("orders", "acme.default.orders");
        assertThat(listing.find(ROOT, "acme.default.orders", "describe"))
                .hasValueSatisfying(entry -> assertThat(entry.query()).isSameAs(danas));
        assertThat(new ViewQuery(views)
                        .execute("SELECT user_id FROM \"acme.default.orders\"", ROOT)
                        .rows())
                .extracting(row -> row[0])
                .containsExactly("u1");

        sql(registry, ROOT, "DROP CONTINUOUS QUERY \"acme.default.orders\"");
        assertThat(registry.find(DANA, "orders")).isEmpty();
        assertThat(registry.find(PAT, "orders")).containsSame(pats);
    }

    // ------------------------------------------------------------------ queries on queries (ADR-056)

    @Test
    void anUpstreamIsResolvedInTheDownstreamRegistrantsTenant() {
        QueryRegistry registry = registry();
        RegisteredQuery danas = registry.register("orders", BIG, List.of(0), DANA);
        RegisteredQuery omars = registry.register("orders", SMALL, List.of(0), OMAR);
        RegisteredQuery counted =
                registry.register("order_count", "SELECT COUNT(*) AS n FROM orders", List.of(0), OMAR);

        assertThat(registry.readsFrom("globex.default.order_count")).containsExactly("globex.default.orders");
        assertThat(registry.dependantsOf("globex.default.orders")).containsExactly("globex.default.order_count");
        assertThat(registry.dependantsOf("acme.default.orders")).isEmpty();

        txn(danas, "u1", 500);
        danas.commit();
        txn(omars, "u2", 50);
        txn(omars, "u3", 60);
        omars.commit();
        await(() -> counted.view().size() == 1
                && Long.valueOf(2L).equals(counted.view().scan().get(0)[0]));

        // And a downstream cannot reach another tenant's view by naming it.
        assertThatThrownBy(() -> registry.register(
                        "theirs", "SELECT COUNT(*) AS n FROM \"acme.default.orders\"", List.of(0), OMAR))
                .isInstanceOf(PravahaException.class);
    }

    // ------------------------------------------------------------------ restart

    @Test
    void aRestartRestoresBothTenantsSameNamedViewsEachWithItsOwnState() {
        Path journal = root.resolve("registry.journal");
        QueryRegistry first = checkpointed(journal);
        RegisteredQuery danas = first.register("totals", TOTALS, List.of(0), DANA);
        RegisteredQuery omars = first.register("totals", TOTALS, List.of(0), OMAR);
        txn(danas, "u1", 100);
        txn(danas, "u2", 250);
        danas.commit();
        txn(omars, "u9", 7);
        omars.commit();
        checkpointerOf(danas).checkpointNow();
        checkpointerOf(omars).checkpointNow();
        restart(first);

        QueryRegistry second = checkpointed(journal);
        QueryRegistry.Recovery recovery = second.recover(RegistryJournalOwners.of(DANA, OMAR));
        assertThat(recovery.complete()).isTrue();
        assertThat(recovery.recovered()).containsExactly("acme.default.totals", "globex.default.totals");
        assertThat(rows(second.require(DANA, "totals"))).containsExactly(List.of(2L, 350L));
        assertThat(rows(second.require(OMAR, "totals"))).containsExactly(List.of(1L, 7L));
    }

    @Test
    void stateTheCurrentDevelopWroteLoadsTheDefaultTenantUnchangedAndAnotherTenantsUnderItsTenant() throws Exception {
        // Written by Adr060DevelopFixture at develop 1f52ecf2, before names were per tenant: pat (the
        // default tenant) registered "spend", dana (acme) registered "totals" under its bare name, and
        // each checkpointed [u1 100, u2 250].
        Path copy = root.resolve("state");
        copyFixture(copy);
        Path journal = copy.resolve("registry.journal");
        Principal pat = new Principal("pat", "public", Set.of("analyst"), Map.of());

        QueryRegistry first = checkpointed(journal, copy.resolve("checkpoints"));
        QueryRegistry.Recovery recovery = first.recover(RegistryJournalOwners.of(pat, DANA));
        assertThat(recovery.complete()).isTrue();
        assertThat(recovery.recovered()).containsExactly("spend", "acme.default.totals");
        assertThat(rows(first.require(pat, "spend"))).containsExactly(List.of("u1", 100L), List.of("u2", 250L));
        assertThat(rows(first.require(DANA, "totals"))).containsExactly(List.of(2L, 350L));
        assertThat(first.find("totals")).as("not the default tenant's any more").isEmpty();
        assertThat(copy.resolve("checkpoints"))
                .as("nothing was moved: the state is where the old name put it")
                .isDirectoryContaining(path -> path.getFileName().toString().equals("totals"));

        // The default tenant may now take the bare name, and the two are journalled apart.
        RegisteredQuery patsTotals = first.register("totals", TOTALS, List.of(0), pat);
        txn(patsTotals, "p1", 1);
        patsTotals.commit();
        checkpointerOf(patsTotals).checkpointNow();
        restart(first);
        assertThat(copy.resolve("checkpoints"))
                .as("the default tenant's totals checkpoints beside acme's, not into the directory acme's keeps")
                .isDirectoryContaining(path -> path.getFileName().toString().equals("totals-1"));
        assertThat(new RegistryJournal(journal).replay())
                .extracting(RegistryJournal.Entry::name)
                .containsExactly("spend", "acme.default.totals", "totals");

        QueryRegistry second = checkpointed(journal, copy.resolve("checkpoints"));
        QueryRegistry.Recovery again = second.recover(RegistryJournalOwners.of(pat, DANA));
        assertThat(again.complete()).isTrue();
        assertThat(again.recovered()).containsExactlyInAnyOrder("spend", "acme.default.totals", "totals");
        assertThat(rows(second.require(DANA, "totals"))).containsExactly(List.of(2L, 350L));
        assertThat(rows(second.require(pat, "totals"))).containsExactly(List.of(1L, 1L));

        // A drop of acme's is journalled against acme's entry, and leaves the default tenant's.
        sql(second, DANA, "DROP CONTINUOUS QUERY totals");
        restart(second);
        QueryRegistry third = checkpointed(journal, copy.resolve("checkpoints"));
        assertThat(third.recover(RegistryJournalOwners.of(pat, DANA)).recovered())
                .containsExactlyInAnyOrder("spend", "totals");
        assertThat(rows(third.require(pat, "totals"))).containsExactly(List.of(1L, 1L));
    }

    // ------------------------------------------------------------------ helpers

    /** Resolves a journalled owner id to one of the given principals. */
    private static final class RegistryJournalOwners {
        static java.util.function.Function<String, Optional<Principal>> of(Principal... principals) {
            return id -> java.util.Arrays.stream(principals)
                    .filter(p -> p.id().equals(id))
                    .findFirst();
        }
    }

    private static String answer(Supplier<Object> call) {
        try {
            Object result = call.get();
            if (result instanceof Optional<?> optional) {
                return optional.isPresent() ? "present" : "empty";
            }
            return "answered " + (result == null ? "null" : result.getClass().getSimpleName());
        } catch (PravahaException e) {
            return e.getMessage();
        }
    }

    private QueryRegistry registry() {
        QueryRegistry registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN);
        registries.add(registry);
        return registry;
    }

    private QueryRegistry checkpointed(Path journal) {
        return checkpointed(journal, root.resolve("checkpoints"));
    }

    private QueryRegistry checkpointed(Path journal, Path checkpoints) {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)
                .journalTo(new RegistryJournal(journal))
                .checkpointingTo(
                        checkpoints,
                        Configuration.builder()
                                .set("pravaha.checkpoint.interval", "1h")
                                .set("pravaha.checkpoint.timeout", "10s")
                                .build());
        registries.add(registry);
        return registry;
    }

    private void restart(QueryRegistry registry) {
        registry.close();
        registries.remove(registry);
    }

    private static QueryListing listing(QueryRegistry registry) {
        return new QueryListing(registry, SecurityPolicy.PERMISSIVE, AuditSink.NONE);
    }

    private static ViewQuery.Result sql(QueryRegistry registry, Principal who, String statement) {
        return new ContinuousQueryStatements(registry, SecurityPolicy.PERMISSIVE, AuditSink.NONE)
                .execute(ContinuousStatements.recognize(statement).orElseThrow(), who);
    }

    private static void copyFixture(Path into) throws Exception {
        Path from = Path.of(TenantViewNamesTest.class
                        .getResource("/adr060/develop-1f52ecf2/registry.journal")
                        .toURI())
                .getParent();
        try (var files = Files.walk(from)) {
            for (Path each : files.toList()) {
                Path target = into.resolve(from.relativize(each).toString());
                if (Files.isDirectory(each)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(each, target);
                }
            }
        }
    }

    private static List<List<Object>> rows(RegisteredQuery query) {
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] row : query.view().scan()) {
            rows.add(List.of(row));
        }
        rows.sort(Comparator.comparing(Object::toString));
        return rows;
    }

    private static PeriodicCheckpointer checkpointerOf(RegisteredQuery query) {
        try {
            java.lang.reflect.Field field = RegisteredQuery.class.getDeclaredField("checkpointer");
            field.setAccessible(true);
            return (PeriodicCheckpointer) field.get(query);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void txn(RegisteredQuery query, String user, long amount) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setLong(1, amount);
        writer.weight(1L).eventTimestampNanos(amount).sequence(amount).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept("txn", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
