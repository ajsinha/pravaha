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

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.catalog.Catalog;
import com.ash.messaging.pravaha.catalog.CatalogPolicy;
import com.ash.messaging.pravaha.catalog.CatalogService;
import com.ash.messaging.pravaha.catalog.Grantee;
import com.ash.messaging.pravaha.catalog.ObjectKind;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.ContinuousStatements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A registry governed by the catalogue (ADR-059): registering makes the creator the owner, a query over
 * a view needs BUILD_ON on the view and not on the stream behind it (ADR-056's chains), and the
 * catalogue's statements run through the same door as CREATE CONTINUOUS QUERY.
 */
@Timeout(60)
class CatalogGovernedRegistryTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("region", Types.string())
            .field("amount", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    private static final Principal OPS = new Principal("ops", "acme", Set.of("admin"), Map.of());
    private static final Principal ANA = new Principal("ana", "acme", Set.of("analyst"), Map.of());

    private static final String CLEANED = "SELECT user_id, region, amount FROM txn WHERE amount > 0";
    private static final String BY_REGION = "SELECT region, SUM(amount) AS total FROM cleaned GROUP BY region";

    private Catalog catalog;
    private QueryRegistry registry;
    private ContinuousQueryStatements statements;

    @BeforeEach
    void setUp() {
        catalog = Catalog.inMemory(Clock.systemUTC());
        catalog.infrastructure(() -> Map.<ObjectKind, Collection<String>>of(ObjectKind.STREAM, List.of("txn")));
        SecurityPolicy policy = new CatalogPolicy(new CatalogService(catalog, AuditSink.NONE), List.of());
        registry = new QueryRegistry(new ViewCatalog(), policy, AuditSink.NONE, TXN);
        statements = new ContinuousQueryStatements(registry, policy, AuditSink.NONE);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    private ViewQuery.Result sql(Principal who, String text) {
        return statements.execute(ContinuousStatements.recognize(text).orElseThrow(), who);
    }

    @Test
    void buildingOnAViewNeedsBuildOnTheViewAndNotTheStreamBehindIt() {
        registry.register("cleaned", CLEANED, List.of(0), OPS);
        assertThat(catalog.object("acme.default.cleaned").orElseThrow().owner()).isEqualTo(Grantee.user("ops"));

        // ana may use acme.default but may create nothing in it yet.
        assertThatThrownBy(() -> registry.register("by_region", BY_REGION, List.of(0), ANA))
                .hasMessageContaining("PRV-7002")
                .hasMessageContaining("CREATE");
        sql(OPS, "GRANT CREATE ON NAMESPACE default TO ROLE analyst");
        assertThatThrownBy(() -> registry.register("by_region", BY_REGION, List.of(0), ANA))
                .hasMessageContaining("PRV-7002")
                .hasMessageContaining("BUILD_ON");

        sql(OPS, "GRANT BUILD_ON ON VIEW cleaned TO ROLE analyst");
        registry.register("by_region", BY_REGION, List.of(0), ANA);
        assertThat(catalog.object("acme.default.by_region").orElseThrow().owner())
                .isEqualTo(Grantee.user("ana"));

        // Over the stream itself, BUILD_ON the stream is what is asked.
        assertThatThrownBy(() -> registry.register("raw", CLEANED, List.of(0), ANA))
                .hasMessageContaining("BUILD_ON on node.streams.txn");
        sql(OPS, "GRANT BUILD_ON ON STREAM txn TO ROLE analyst");
        registry.register("raw", "SELECT user_id, amount FROM txn", List.of(0), ANA);
    }

    @Test
    void theOwnerAdministersWhatTheyRegisteredAndNobodyElseDoes() {
        sql(OPS, "GRANT CREATE ON NAMESPACE default TO ROLE analyst");
        sql(OPS, "GRANT BUILD_ON ON STREAM txn TO ROLE analyst");
        registry.register("mine", "SELECT user_id, amount FROM txn", List.of(0), ANA);
        registry.register("theirs", CLEANED, List.of(0), OPS);

        assertThatThrownBy(() -> sql(ANA, "DROP CONTINUOUS QUERY theirs")).hasMessageContaining("PRV-7002");
        sql(ANA, "PAUSE CONTINUOUS QUERY mine");
        sql(ANA, "DROP CONTINUOUS QUERY mine");
        // The drop forgot it and its grants.
        assertThat(catalog.byEngineName(ObjectKind.VIEW, "mine")).isEmpty();
    }

    @Test
    void theCatalogueStatementsAnswerAsResultSets() {
        registry.register("cleaned", CLEANED, List.of(0), OPS);
        ViewQuery.Result granted = sql(OPS, "GRANT SELECT, SUBSCRIBE ON VIEW cleaned TO ROLE analyst");
        assertThat(granted.schema().field(2).name()).isEqualTo("action");
        assertThat(granted.rows().get(0)[2]).isEqualTo("GRANTED");
        ViewQuery.Result shown = sql(ANA, "SHOW EFFECTIVE ACCESS FOR USER ana ON VIEW cleaned");
        assertThat(shown.rows()).anySatisfy(row -> {
            assertThat(row[2]).isEqualTo("SUBSCRIBE");
            assertThat(row[3]).isEqualTo("true");
        });
        assertThat(ContinuousQueryStatements.resultSchemaOf(
                                ContinuousStatements.recognize("SHOW GRANTS TO ROLE analyst")
                                        .orElseThrow())
                        .fieldCount())
                .isEqualTo(6);
    }

    @Test
    void aNodeWithoutTheCatalogueRefusesItsStatementsByName() {
        QueryRegistry plain = new QueryRegistry(new ViewCatalog(), TXN);
        try {
            ContinuousQueryStatements open = new ContinuousQueryStatements(plain, SecurityPolicy.PERMISSIVE, null);
            assertThatThrownBy(() -> open.execute(
                            ContinuousStatements.recognize("GRANT SELECT ON VIEW x TO ROLE r")
                                    .orElseThrow(),
                            OPS))
                    .hasMessageContaining("PRV-7030");
            assertThatThrownBy(() -> ContinuousStatements.recognize("GRANT SELECT ON VIEW x"))
                    .hasMessageContaining("PRV-2070");
        } finally {
            plain.close();
        }
    }
}
