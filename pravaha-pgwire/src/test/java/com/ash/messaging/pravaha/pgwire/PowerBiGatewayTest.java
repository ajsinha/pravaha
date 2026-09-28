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
package com.ash.messaging.pravaha.pgwire;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What Power BI's PostgreSQL connector (Npgsql 4.0.17 inside) sends, replayed as raw protocol by
 * {@link PgTestClient}, which knows nothing of this module's encoder -- so these run on every machine,
 * where {@link NpgsqlClientTest} needs a dotnet SDK.
 *
 * <p>The query texts are verbatim: Npgsql's type loading from its 4.0.17 {@code
 * PostgresDatabaseInfo.cs}, its {@code GetSchema} from {@code NpgsqlSchema.cs}, and Power BI's
 * navigator from a real PostgreSQL statement log of Power BI Desktop connecting
 * (datafusion-contrib/datafusion-postgres issue 218). Every listing is checked against a policy that
 * denies one view, because a catalogue query is a read (SX-5).
 */
class PowerBiGatewayTest {

    private static final StreamSchema REVENUE = StreamSchema.builder("region_revenue")
            .field("region", Types.string())
            .field("revenue", Types.int64())
            .field("avg_ticket", Types.decimal(10, 2))
            .field("updated_at", Types.timestamp())
            .field("share", Types.float64().withNullable(true))
            .build();

    private static final StreamSchema PAYROLL = StreamSchema.builder("payroll")
            .field("employee_id", Types.string())
            .field("salary", Types.int64())
            .build();

    private static final SecurityPolicy DENY_PAYROLL = (principal, view) ->
            "payroll".equals(view) ? AccessDecision.deny("not for this test") : AccessDecision.allow();

    private PravahaPgWireServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private void start() {
        ServedView revenue = new ServedView("region_revenue", REVENUE, List.of(0), 10_000);
        revenue.applyValues(
                new Object[] {"EMEA", 982_300L, new BigDecimal("222.74"), 1_789_808_400_123_456_789L, 0.25}, 1, 100);
        revenue.applyValues(
                new Object[] {"APAC", 611_850L, new BigDecimal("-205.18"), 1_789_808_400_000_000_000L, null}, 1, 100);
        revenue.applyValues(
                new Object[] {"AMER", 1_204_990L, new BigDecimal("0.05"), 1_789_808_400_000_000_000L, 1.5}, 1, 100);
        revenue.commit(100);
        ServedView payroll = new ServedView("payroll", PAYROLL, List.of(0), 10_000);
        payroll.applyValues(new Object[] {"e1", 100L}, 1, 100);
        payroll.commit(100);
        ViewCatalog catalog = new ViewCatalog().register(revenue).register(payroll);
        server = new PravahaPgWireServer(catalog)
                .authorizedBy(DENY_PAYROLL, AuditSink.NONE)
                .start("127.0.0.1", 0);
    }

    private PgTestClient connected() throws IOException {
        PgTestClient client = new PgTestClient(server.port());
        client.startup(Map.of("user", "ann", "database", "pravaha"));
        assertThat(PgTestClient.shape(client.readHandshake())).endsWith("Z");
        return client;
    }

    private static List<List<String>> rows(List<PgTestClient.Message> reply) {
        List<List<String>> rows = new ArrayList<>();
        for (PgTestClient.Message row : PgTestClient.ofType(reply, 'D')) {
            rows.add(PgTestClient.columns(row));
        }
        return rows;
    }

    private static List<String> names(List<PgTestClient.Message> reply) {
        List<PgTestClient.Message> descriptions = PgTestClient.ofType(reply, 'T');
        assertThat(descriptions).hasSize(1);
        return PgTestClient.described(descriptions.get(0)).stream()
                .map(PgTestClient.Described::name)
                .toList();
    }

    // ------------------------------------------------------------------ Npgsql opening a connection

    /** Npgsql 4.0.17's GenerateTypesQuery(withRange, withEnum, withEnumSortOrder, loadTableComposites: false). */
    static final String NPGSQL_TYPES = """
            /*** Load all supported types ***/
            SELECT ns.nspname, a.typname, a.oid, a.typrelid, a.typbasetype,
            CASE WHEN pg_proc.proname='array_recv' THEN 'a' ELSE a.typtype END AS type,
            CASE
              WHEN pg_proc.proname='array_recv' THEN a.typelem
              WHEN a.typtype='r' THEN rngsubtype
              ELSE 0
            END AS elemoid,
            CASE
              WHEN pg_proc.proname IN ('array_recv','oidvectorrecv') THEN 3    /* Arrays last */
              WHEN a.typtype='r' THEN 2                                        /* Ranges before */
              WHEN a.typtype='d' THEN 1                                        /* Domains before */
              ELSE 0                                                           /* Base types first */
            END AS ord
            FROM pg_type AS a
            JOIN pg_namespace AS ns ON (ns.oid = a.typnamespace)
            JOIN pg_proc ON pg_proc.oid = a.typreceive
            LEFT OUTER JOIN pg_class AS cls ON (cls.oid = a.typrelid)
            LEFT OUTER JOIN pg_type AS b ON (b.oid = a.typelem)
            LEFT OUTER JOIN pg_class AS elemcls ON (elemcls.oid = b.typrelid)
            LEFT OUTER JOIN pg_range ON (pg_range.rngtypid = a.oid)\s
            WHERE
              a.typtype IN ('b', 'r', 'e', 'd') OR         /* Base, range, enum, domain */
              (a.typtype = 'c' AND cls.relkind='c') OR /* User-defined free-standing composites (not table composites) by default */
              (pg_proc.proname='array_recv' AND (
                b.typtype IN ('b', 'r', 'e', 'd') OR       /* Array of base, range, enum, domain */
                (b.typtype = 'p' AND b.typname IN ('record', 'void')) OR /* Arrays of special supported pseudo-types */
                (b.typtype = 'c' AND elemcls.relkind='c')  /* Array of user-defined free-standing composites (not table composites) */
              )) OR
              (a.typtype = 'p' AND a.typname IN ('record', 'void'))  /* Some special supported pseudo-types */
            ORDER BY ord""";

    static final String NPGSQL_COMPOSITES = """
            /*** Load field definitions for (free-standing) composite types ***/
            SELECT typ.oid, att.attname, att.atttypid
            FROM pg_type AS typ
            JOIN pg_namespace AS ns ON (ns.oid = typ.typnamespace)
            JOIN pg_class AS cls ON (cls.oid = typ.typrelid)
            JOIN pg_attribute AS att ON (att.attrelid = typ.typrelid)
            WHERE
              (typ.typtype = 'c' AND cls.relkind='c') AND
              attnum > 0 AND     /* Don't load system attributes */
              NOT attisdropped
            ORDER BY typ.oid, att.attnum""";

    static final String NPGSQL_ENUMS = """
            /*** Load enum fields ***/
            SELECT pg_type.oid, enumlabel
            FROM pg_enum
            JOIN pg_type ON pg_type.oid=enumtypid
            ORDER BY oid, enumsortorder""";

    /**
     * The three statements as Npgsql pipelines them: Parse/Bind/Describe/Execute each, one Sync.
     * Answered, the connection opens; refused, Power BI never gets past "connecting".
     */
    @Test
    void npgsqlsTypeLoadingBatchIsAnsweredWithTheTypesTheGatewaySends() throws Exception {
        start();
        try (PgTestClient client = connected()) {
            for (String sql : List.of(NPGSQL_TYPES, NPGSQL_COMPOSITES, NPGSQL_ENUMS)) {
                client.parse("", sql);
                client.bind("", "", List.of()); // AllResultTypesAreUnknown: text
                client.describePortal("");
                client.execute("", 0);
            }
            client.sync();
            List<PgTestClient.Message> reply = client.readUntilReady();

            assertThat(PgTestClient.shape(reply)).doesNotContain("E").endsWith("Z");
            List<List<String>> types = new ArrayList<>();
            for (List<String> row : rows(reply)) {
                types.add(row);
            }
            // Ten base types, then no composite fields and no enum labels.
            assertThat(types).hasSize(10);
            assertThat(types)
                    .extracting(row -> row.get(1) + "=" + row.get(2))
                    .containsExactlyInAnyOrder(
                            "bool=16",
                            "int8=20",
                            "int2=21",
                            "int4=23",
                            "text=25",
                            "float4=700",
                            "float8=701",
                            "date=1082",
                            "timestamptz=1184",
                            "numeric=1700");
            assertThat(types).allSatisfy(row -> {
                assertThat(row.get(0)).isEqualTo("pg_catalog");
                assertThat(row.get(5)).isEqualTo("b");
            });
        }
    }

    @Test
    void discardAllIsAcceptedAndForgetsNamedStatements() throws Exception {
        start();
        try (PgTestClient client = connected()) {
            client.parse("s1", "SELECT region FROM region_revenue");
            client.sync();
            assertThat(PgTestClient.shape(client.readUntilReady())).isEqualTo("1Z");

            client.query("DISCARD ALL");
            List<PgTestClient.Message> reply = client.readUntilReady();
            assertThat(PgTestClient.shape(reply)).isEqualTo("CZ");
            assertThat(PgTestClient.ofType(reply, 'C').get(0).strings()).containsExactly("DISCARD ALL");

            client.bind("", "s1", List.of());
            client.sync();
            reply = client.readUntilReady();
            assertThat(PgTestClient.shape(reply)).isEqualTo("EZ");
            assertThat(PgTestClient.errorFields(reply.get(0)).get('M')).contains("does not exist");
        }
    }

    // ------------------------------------------------------------------ the navigator

    @Test
    void powerBisTableListingShowsOnlyReadableViewsAsBaseTables() throws Exception {
        start();
        try (PgTestClient client = connected()) {
            client.query("""
                    select TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE
                            from INFORMATION_SCHEMA.tables
                            where TABLE_SCHEMA not in ('information_schema', 'pg_catalog')
                            order by TABLE_SCHEMA, TABLE_NAME""");
            List<PgTestClient.Message> reply = client.readUntilReady();

            assertThat(names(reply)).containsExactly("table_schema", "table_name", "table_type");
            assertThat(rows(reply)).containsExactly(List.of("public", "region_revenue", "BASE TABLE"));
        }
    }

    @Test
    void powerBisColumnQueryDescribesAReadableViewAndNothingOfADeniedOne() throws Exception {
        start();
        try (PgTestClient client = connected()) {
            client.query(powerBiColumns("region_revenue"));
            List<PgTestClient.Message> reply = client.readUntilReady();
            assertThat(names(reply)).containsExactly("column_name", "ordinal_position", "is_nullable", "data_type");
            assertThat(rows(reply))
                    .containsExactly(
                            List.of("region", "1", "NO", "text"),
                            List.of("revenue", "2", "NO", "bigint"),
                            List.of("avg_ticket", "3", "NO", "numeric"),
                            List.of("updated_at", "4", "NO", "timestamp with time zone"),
                            List.of("share", "5", "YES", "double precision"));

            client.query(powerBiColumns("payroll"));
            assertThat(rows(client.readUntilReady())).isEmpty();
        }
    }

    private static String powerBiColumns(String table) {
        return "select COLUMN_NAME, ORDINAL_POSITION, IS_NULLABLE, case when (data_type like '%unsigned%') then "
                + "DATA_TYPE || ' unsigned' else DATA_TYPE end as DATA_TYPE\n"
                + "        from INFORMATION_SCHEMA.columns\n"
                + "        where TABLE_SCHEMA = 'public' and TABLE_NAME = '" + table + "'\n"
                + "        order by TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION";
    }

    @Test
    void powerBisCharacterSetKeyAndForeignKeyQueriesAreAnswered() throws Exception {
        start();
        try (PgTestClient client = connected()) {
            client.query("select character_set_name from INFORMATION_SCHEMA.character_sets");
            assertThat(rows(client.readUntilReady())).containsExactly(List.of("UTF8"));

            client.query("select i.CONSTRAINT_SCHEMA || '_' || i.CONSTRAINT_NAME as INDEX_NAME, ii.COLUMN_NAME, "
                    + "ii.ORDINAL_POSITION, case when i.CONSTRAINT_TYPE = 'PRIMARY KEY' then 'Y' else 'N' end as "
                    + "PRIMARY_KEY\n        from INFORMATION_SCHEMA.table_constraints i inner join "
                    + "INFORMATION_SCHEMA.key_column_usage ii on i.CONSTRAINT_SCHEMA = ii.CONSTRAINT_SCHEMA and "
                    + "i.CONSTRAINT_NAME = ii.CONSTRAINT_NAME and i.TABLE_SCHEMA = ii.TABLE_SCHEMA and "
                    + "i.TABLE_NAME = ii.TABLE_NAME\n        where i.TABLE_SCHEMA = 'public' and i.TABLE_NAME = "
                    + "'region_revenue'\n        and i.CONSTRAINT_TYPE in ('PRIMARY KEY', 'UNIQUE')\n        order by "
                    + "i.CONSTRAINT_SCHEMA || '_' || i.CONSTRAINT_NAME, ii.TABLE_SCHEMA, ii.TABLE_NAME, "
                    + "ii.ORDINAL_POSITION");
            List<PgTestClient.Message> keys = client.readUntilReady();
            assertThat(names(keys)).containsExactly("index_name", "column_name", "ordinal_position", "primary_key");
            assertThat(rows(keys)).isEmpty();

            for (String first : List.of(
                    "pkcol.COLUMN_NAME as PK_COLUMN_NAME,\n fkcol.TABLE_SCHEMA AS FK_TABLE_SCHEMA",
                    "pkcol.TABLE_SCHEMA AS PK_TABLE_SCHEMA,\n pkcol.TABLE_NAME AS PK_TABLE_NAME")) {
                client.query("select\n            " + first + ", fkcol.ORDINAL_POSITION as ORDINAL\n"
                        + "        from\n            (select distinct constraint_catalog, constraint_schema, "
                        + "unique_constraint_schema, constraint_name, unique_constraint_name\n                "
                        + "from INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS) fkcon\n inner join "
                        + "INFORMATION_SCHEMA.KEY_COLUMN_USAGE fkcol on fkcon.CONSTRAINT_NAME = fkcol.CONSTRAINT_NAME");
                List<PgTestClient.Message> reply = client.readUntilReady();
                assertThat(PgTestClient.shape(reply)).isEqualTo("TCZ");
                assertThat(names(reply)).hasSize(6).contains("ordinal", "fk_name");
            }
        }
    }

    /** Npgsql's GetSchema("Columns", {null, null, "..."}): the restriction arrives as a bound $1. */
    @Test
    void npgsqlGetSchemaColumnsWithABoundTableName() throws Exception {
        start();
        try (PgTestClient client = connected()) {
            client.parse(
                    "",
                    "SELECT table_catalog, table_schema, table_name, column_name, ordinal_position, "
                            + "column_default, is_nullable, udt_name AS data_type, character_maximum_length, "
                            + "character_octet_length, numeric_precision, numeric_precision_radix, numeric_scale, "
                            + "datetime_precision, character_set_catalog, character_set_schema, character_set_name, "
                            + "collation_catalog FROM information_schema.columns WHERE table_name = $1");
            client.bindText("", "", "region_revenue");
            client.describePortal("");
            client.execute("", 0);
            client.parse(
                    "",
                    "SELECT table_catalog, table_schema, table_name, column_name, ordinal_position, "
                            + "column_default, is_nullable, udt_name AS data_type, character_maximum_length, "
                            + "character_octet_length, numeric_precision, numeric_precision_radix, numeric_scale, "
                            + "datetime_precision, character_set_catalog, character_set_schema, character_set_name, "
                            + "collation_catalog FROM information_schema.columns WHERE table_name = $1");
            client.bindText("", "", "payroll");
            client.describePortal("");
            client.execute("", 0);
            client.sync();
            List<PgTestClient.Message> reply = client.readUntilReady();

            assertThat(PgTestClient.shape(reply)).doesNotContain("E");
            List<List<String>> rows = rows(reply);
            assertThat(rows)
                    .extracting(row -> row.get(3) + ":" + row.get(7))
                    .containsExactly(
                            "region:text",
                            "revenue:int8",
                            "avg_ticket:numeric",
                            "updated_at:timestamptz",
                            "share:float8");
            assertThat(rows.get(2).subList(10, 13)).containsExactly("10", "10", "2"); // numeric(10,2)
        }
    }

    @Test
    void npgsqlGetSchemaTablesListsBaseTablesOnly() throws Exception {
        start();
        try (PgTestClient client = connected()) {
            client.query(
                    "\nSELECT table_catalog, table_schema, table_name, table_type\nFROM information_schema.tables\n"
                            + "WHERE table_type IN ('BASE TABLE', 'FOREIGN', 'FOREIGN TABLE') AND table_schema NOT IN "
                            + "('pg_catalog', 'information_schema')");
            assertThat(rows(client.readUntilReady()))
                    .containsExactly(List.of("pravaha", "public", "region_revenue", "BASE TABLE"));

            // GetSchema("Views") asks for a type there is none of.
            client.query("SELECT table_catalog, table_schema, table_name, table_type FROM information_schema.tables "
                    + "WHERE table_type = 'VIEW'");
            assertThat(rows(client.readUntilReady())).isEmpty();
        }
    }

    // ------------------------------------------------------------------ reading: binary, LIMIT, public.

    /** Bind with the single result format code 1 -- every ordinary Npgsql query. */
    @Test
    void binaryResultsAreServedInPostgresOwnBinaryFormats() throws Exception {
        start();
        try (PgTestClient client = connected()) {
            client.parse(
                    "",
                    "SELECT region, revenue, avg_ticket, updated_at, share FROM region_revenue "
                            + "WHERE region = 'EMEA'");
            client.bind("", "", List.of(), new short[] {1});
            client.describePortal("");
            client.execute("", 0);
            client.sync();
            List<PgTestClient.Message> reply = client.readUntilReady();

            assertThat(PgTestClient.shape(reply)).isEqualTo("12TDCZ");
            assertThat(PgTestClient.described(PgTestClient.ofType(reply, 'T').get(0)))
                    .extracting(PgTestClient.Described::format)
                    .containsOnly((short) 1);
            List<byte[]> values = binaryColumns(PgTestClient.ofType(reply, 'D').get(0));
            assertThat(new String(values.get(0), StandardCharsets.UTF_8)).isEqualTo("EMEA");
            assertThat(ByteBuffer.wrap(values.get(1)).getLong()).isEqualTo(982_300L);
            // numeric 222.74: one... two base-10000 digits [222, 7400], weight 0, positive, dscale 2.
            ByteBuffer numeric = ByteBuffer.wrap(values.get(2));
            assertThat(new short[] {
                        numeric.getShort(),
                        numeric.getShort(),
                        numeric.getShort(),
                        numeric.getShort(),
                        numeric.getShort(),
                        numeric.getShort()
                    })
                    .containsExactly((short) 2, (short) 0, (short) 0, (short) 2, (short) 222, (short) 7400);
            // timestamptz: microseconds since 2000-01-01, the sub-microsecond 789 truncated.
            long micros = ByteBuffer.wrap(values.get(3)).getLong();
            assertThat(micros).isEqualTo(1_789_808_400_123_456L - 946_684_800_000_000L);
            assertThat(ByteBuffer.wrap(values.get(4)).getDouble()).isEqualTo(0.25);
        }
    }

    private static List<byte[]> binaryColumns(PgTestClient.Message dataRow) {
        ByteBuffer buffer = ByteBuffer.wrap(dataRow.payload());
        int count = buffer.getShort();
        List<byte[]> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int length = buffer.getInt();
            if (length < 0) {
                values.add(null);
                continue;
            }
            byte[] value = new byte[length];
            buffer.get(value);
            values.add(value);
        }
        return values;
    }

    @Test
    void aTrailingLimitAndAPublicQualifierReadTheViewAndCutTheAnswer() throws Exception {
        start();
        try (PgTestClient client = connected()) {
            client.query("select \"_\".\"region\", \"_\".\"revenue\"\nfrom \"public\".\"region_revenue\" \"_\"\n"
                    + "limit 2");
            assertThat(rows(client.readUntilReady())).hasSize(2);

            client.query("select \"rows\".\"region\" as \"region\", sum(\"rows\".\"revenue\") as \"a0\"\nfrom\n(\n"
                    + "    select \"_\".\"region\", \"_\".\"revenue\"\n    from \"public\".\"region_revenue\" \"_\"\n"
                    + ") \"rows\"\ngroup by \"region\"\nlimit 1000001");
            assertThat(rows(client.readUntilReady()))
                    .containsExactlyInAnyOrder(
                            List.of("EMEA", "982300"), List.of("APAC", "611850"), List.of("AMER", "1204990"));
        }
    }

    @Test
    void anOrderByOrALimitInsideADerivedTableIsStillRefusedByThePlanner() throws Exception {
        start();
        try (PgTestClient client = connected()) {
            client.query("select \"_\".\"region\" from \"public\".\"region_revenue\" \"_\" "
                    + "order by \"_\".\"revenue\" desc limit 2");
            List<PgTestClient.Message> reply = client.readUntilReady();
            assertThat(PgTestClient.shape(reply)).isEqualTo("EZ");
            assertThat(PgTestClient.errorFields(reply.get(0)).get('M')).startsWith("PRV-2020");

            client.query("select count(*) from (select region from region_revenue limit 1) x");
            reply = client.readUntilReady();
            assertThat(PgTestClient.shape(reply)).isEqualTo("EZ");
            assertThat(PgTestClient.errorFields(reply.get(0)).get('M')).startsWith("PRV-2020");
        }
    }

    @Test
    void aPublicQualifiedDeniedViewIsStillDenied() throws Exception {
        start();
        try (PgTestClient client = connected()) {
            client.query("select \"_\".\"salary\" from \"public\".\"payroll\" \"_\" limit 1000001");
            List<PgTestClient.Message> reply = client.readUntilReady();
            assertThat(PgTestClient.shape(reply)).isEqualTo("EZ");
            assertThat(PgTestClient.errorFields(reply.get(0))).containsEntry('C', "42501");
        }
    }
}
