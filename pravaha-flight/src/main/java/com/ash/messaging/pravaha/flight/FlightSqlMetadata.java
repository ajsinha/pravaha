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
package com.ash.messaging.pravaha.flight;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.arrow.flight.FlightProducer.ServerStreamListener;
import org.apache.arrow.flight.sql.FlightSqlProducer;
import org.apache.arrow.flight.sql.SqlInfoBuilder;
import org.apache.arrow.flight.sql.impl.FlightSql;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.types.pojo.Schema;

import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.ViewNames;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

/**
 * The Flight SQL metadata surface: what a SQL client asks before it will show a table list.
 *
 * <p>P-6. Every one of these commands used to answer its {@code getFlightInfo} and then fail its
 * {@code getStream} with Arrow's default {@code UNIMPLEMENTED "Not implemented."} -- the worst of
 * the three possible behaviours, because the client is told the call is supported, comes back for
 * the rows, and only then finds out. A JDBC or ADBC client calls {@code getSqlInfo},
 * {@code getTables}, {@code getPrimaryKeys} and usually {@code getImportedKeys} before it will show
 * anything at all, so "SQL works over Flight" was true of a hand-written client and false of every
 * off-the-shelf one -- which is exactly what ADR-030 chose Flight SQL to buy.
 *
 * <p><strong>Empty is an answer; UNIMPLEMENTED is not.</strong> Pravaha has no foreign keys, so
 * {@code getImportedKeys} returns zero rows rather than refusing: a client reading zero rows draws
 * the right conclusion, and a client handed an error usually abandons the connection.
 *
 * <p><strong>There is no catalog and no schema, and that is reported rather than papered over.</strong>
 * The engine resolves bare names -- {@code SELECT ... FROM user_volume}. Inventing a catalogue to
 * fill a tool's tree in would make it generate {@code pravaha.public.user_volume}, which the planner
 * refuses; so a flat namespace is reported as a flat namespace: zero catalogs, zero schemas, and a
 * null catalog and schema against every table.
 *
 * <p>Every listing is filtered by {@link SecurityPolicy#mayRead}, for the reason the control-plane
 * {@code LIST} is (S-4, SX-11): a principal must not learn from a table list that a view they cannot
 * read exists, and the column names of a payroll view are a disclosure on their own.
 */
final class FlightSqlMetadata {

    /**
     * What a Pravaha view is called in a client's table list.
     *
     * <p>{@code TABLE} rather than {@code VIEW}, deliberately. The object answers
     * {@code SELECT ... FROM name} with maintained rows and has no underlying table to be a view
     * *of*, and a great many tools request only {@code TABLE} by default -- reporting {@code VIEW}
     * would leave those users looking at an empty database.
     */
    private static final String TABLE_TYPE = "TABLE";

    private final ViewCatalog catalog;
    private final SecurityPolicy policy;
    private final BufferAllocator allocator;
    private final SqlInfoBuilder sqlInfo;
    private final Set<Integer> publishedSqlInfo;

    FlightSqlMetadata(ViewCatalog catalog, SecurityPolicy policy, BufferAllocator allocator) {
        this.catalog = catalog;
        this.policy = policy;
        this.allocator = allocator;
        this.publishedSqlInfo = new LinkedHashSet<>();
        this.sqlInfo = buildSqlInfo(publishedSqlInfo);
    }

    // ------------------------------------------------------------------------------------------
    // Catalogs, schemas, table types
    // ------------------------------------------------------------------------------------------

    void catalogs(ServerStreamListener listener) {
        empty(FlightSqlProducer.Schemas.GET_CATALOGS_SCHEMA, listener);
    }

    void schemas(ServerStreamListener listener) {
        empty(FlightSqlProducer.Schemas.GET_SCHEMAS_SCHEMA, listener);
    }

    void tableTypes(ServerStreamListener listener) {
        try (VectorSchemaRoot root =
                VectorSchemaRoot.create(FlightSqlProducer.Schemas.GET_TABLE_TYPES_SCHEMA, allocator)) {
            listener.start(root);
            text(root, "table_type", 0, TABLE_TYPE);
            root.setRowCount(1);
            listener.putNext();
            listener.completed();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Tables
    // ------------------------------------------------------------------------------------------

    void tables(FlightSql.CommandGetTables command, Principal principal, ServerStreamListener listener) {
        boolean includeSchema = command.getIncludeSchema();
        Schema wire = includeSchema
                ? FlightSqlProducer.Schemas.GET_TABLES_SCHEMA
                : FlightSqlProducer.Schemas.GET_TABLES_SCHEMA_NO_SCHEMA;

        // A catalog or schema filter that names something can only fail to match, because there is
        // no catalogue and no schema. Answering the filter rather than ignoring it is the
        // difference between an honest empty result and a client believing it has scoped a listing
        // it has not.
        boolean scopedAway = named(command.hasCatalog() ? command.getCatalog() : null)
                || (command.hasDbSchemaFilterPattern() && !matches(command.getDbSchemaFilterPattern(), ""));
        boolean typeWanted =
                command.getTableTypesCount() == 0 || command.getTableTypesList().contains(TABLE_TYPE);

        try (VectorSchemaRoot root = VectorSchemaRoot.create(wire, allocator)) {
            listener.start(root);
            int index = 0;
            if (!scopedAway && typeWanted) {
                for (String name : visible(principal)) {
                    if (command.hasTableNameFilterPattern() && !matches(command.getTableNameFilterPattern(), name)) {
                        continue;
                    }
                    ServedView view = catalog.find(engineName(principal, name)).orElse(null);
                    if (view == null) {
                        continue;
                    }
                    root.getVector("catalog_name").setNull(index);
                    root.getVector("db_schema_name").setNull(index);
                    text(root, "table_name", index, name);
                    text(root, "table_type", index, TABLE_TYPE);
                    if (includeSchema) {
                        // The IPC-serialised Arrow schema, which is what the field is defined to
                        // carry. A client builds its ResultSetMetaData from it without a round trip
                        // per table.
                        ((VarBinaryVector) root.getVector("table_schema"))
                                .setSafe(
                                        index,
                                        ArrowSchemas.toArrow(view.schema()).serializeAsMessage());
                    }
                    index++;
                }
            }
            root.setRowCount(index);
            listener.putNext();
            listener.completed();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------------------------------

    /**
     * The view's key columns, in key order.
     *
     * <p>A maintained view is keyed -- that is what makes a point read a hash probe rather than a
     * scan (ADR-014) -- so this is a real primary key and not a convention invented to fill the
     * protocol's field in. It is also the one piece of metadata a client cannot infer from the
     * schema, which is why answering {@code UNIMPLEMENTED} here was worse than it looked.
     */
    void primaryKeys(FlightSql.CommandGetPrimaryKeys command, Principal principal, ServerStreamListener listener) {
        String table = command.getTable();
        boolean scopedAway = named(command.hasCatalog() ? command.getCatalog() : null)
                || named(command.hasDbSchema() ? command.getDbSchema() : null);

        try (VectorSchemaRoot root =
                VectorSchemaRoot.create(FlightSqlProducer.Schemas.GET_PRIMARY_KEYS_SCHEMA, allocator)) {
            listener.start(root);
            int index = 0;
            String engine = engineName(principal, table);
            ServedView view = scopedAway || engine == null || !mayRead(principal, engine)
                    ? null
                    : catalog.find(engine).orElse(null);
            if (view != null) {
                StreamSchema schema = view.schema();
                for (int ordinal : view.keyOrdinals()) {
                    if (ordinal < 0 || ordinal >= schema.fields().size()) {
                        continue;
                    }
                    root.getVector("catalog_name").setNull(index);
                    root.getVector("db_schema_name").setNull(index);
                    text(root, "table_name", index, table);
                    text(root, "column_name", index, schema.field(ordinal).name());
                    // One-based, as every other SQL surface counts key positions.
                    ((IntVector) root.getVector("key_sequence")).setSafe(index, index + 1);
                    text(root, "key_name", index, "pk_" + table);
                    index++;
                }
            }
            root.setRowCount(index);
            listener.putNext();
            listener.completed();
        }
    }

    /**
     * Foreign keys: none, stated as none.
     *
     * <p>Pravaha maintains views over streams and has no referential constraints to report. A client
     * that asks gets an empty result, which is the true answer; refusing would fail a driver that
     * populates its object tree eagerly against a database that is merely simple.
     */
    void noForeignKeys(ServerStreamListener listener) {
        empty(FlightSqlProducer.Schemas.GET_IMPORTED_KEYS_SCHEMA, listener);
    }

    // ------------------------------------------------------------------------------------------
    // Type info
    // ------------------------------------------------------------------------------------------

    /**
     * The types this server actually puts on the wire.
     *
     * <p>Exactly the set {@link ArrowSchemas} maps and no more: listing a type here that the wire
     * refuses is the same defect as a documented-but-unreachable feature one layer down. DECIMAL is
     * listed from 2.1, when it began to travel as Arrow's Decimal128 (FLIGHTDECIMAL-1), with the
     * precision and scale range a column may declare.
     */
    void typeInfo(FlightSql.CommandGetXdbcTypeInfo command, ServerStreamListener listener) {
        try (VectorSchemaRoot root =
                VectorSchemaRoot.create(FlightSqlProducer.Schemas.GET_TYPE_INFO_SCHEMA, allocator)) {
            listener.start(root);
            int index = 0;
            for (XdbcType type : XdbcType.values()) {
                if (command.hasDataType() && command.getDataType() != type.jdbcType) {
                    continue;
                }
                text(root, "type_name", index, type.typeName);
                ((IntVector) root.getVector("data_type")).setSafe(index, type.jdbcType);
                if (type.columnSize < 0) {
                    root.getVector("column_size").setNull(index);
                } else {
                    ((IntVector) root.getVector("column_size")).setSafe(index, type.columnSize);
                }
                textOrNull(root, "literal_prefix", index, type.literalQuote);
                textOrNull(root, "literal_suffix", index, type.literalQuote);
                // Absent rather than empty. DECIMAL takes a precision and a scale, and says so through
                // fixed_prec_scale and the scale range below rather than through this list.
                ((ListVector) root.getVector("create_params")).setNull(index);
                // 1 == NULLABLE. Nullability in Pravaha belongs to the column, not to the type.
                ((IntVector) root.getVector("nullable")).setSafe(index, 1);
                bit(root, "case_sensitive", index, type.caseSensitive);
                // 3 == SEARCHABLE: usable anywhere in a WHERE clause.
                ((IntVector) root.getVector("searchable")).setSafe(index, 3);
                bitOrNull(root, "unsigned_attribute", index, type.numeric ? Boolean.FALSE : null);
                bit(root, "fixed_prec_scale", index, type == XdbcType.DECIMAL);
                bitOrNull(root, "auto_increment", index, type.numeric ? Boolean.FALSE : null);
                text(root, "local_type_name", index, type.typeName);
                if (type == XdbcType.DECIMAL) {
                    ((IntVector) root.getVector("minimum_scale")).setSafe(index, 0);
                    ((IntVector) root.getVector("maximum_scale")).setSafe(index, DecimalType.MAX_PRECISION);
                } else {
                    root.getVector("minimum_scale").setNull(index);
                    root.getVector("maximum_scale").setNull(index);
                }
                ((IntVector) root.getVector("sql_data_type")).setSafe(index, type.jdbcType);
                root.getVector("datetime_subcode").setNull(index);
                if (type.numeric) {
                    ((IntVector) root.getVector("num_prec_radix")).setSafe(index, 10);
                } else {
                    root.getVector("num_prec_radix").setNull(index);
                }
                root.getVector("interval_precision").setNull(index);
                index++;
            }
            root.setRowCount(index);
            listener.putNext();
            listener.completed();
        }
    }

    // ------------------------------------------------------------------------------------------
    // SQL info
    // ------------------------------------------------------------------------------------------

    /**
     * What this server is, and what its SQL does.
     *
     * <p>The first call a Flight SQL JDBC or ADBC connection makes. Codes this server does not
     * publish are dropped rather than passed to {@link SqlInfoBuilder}, whose {@code send}
     * dereferences a provider it does not have -- so one unrecognised code from a client would
     * otherwise fail the call, and with it the connection.
     */
    void sqlInfo(FlightSql.CommandGetSqlInfo command, ServerStreamListener listener) {
        List<Integer> wanted = new ArrayList<>();
        for (int info : command.getInfoList()) {
            if (publishedSqlInfo.contains(info)) {
                wanted.add(info);
            }
        }
        if (!command.getInfoList().isEmpty() && wanted.isEmpty()) {
            // Asked only about codes this server does not publish. Zero rows is the answer; handing
            // send() an empty list would instead return everything, which is a different one.
            empty(FlightSqlProducer.Schemas.GET_SQL_INFO_SCHEMA, listener);
            return;
        }
        sqlInfo.send(wanted, listener);
    }

    /**
     * The server's self-description, and the set of codes it covers.
     *
     * <p>The set is maintained beside the calls rather than read back out of the builder, because
     * the builder does not expose it. Adding a {@code with...} below without adding its code here
     * makes that fact invisible to a client that asks for it by number -- which is the narrow
     * failure this whole method exists to avoid.
     */
    private static SqlInfoBuilder buildSqlInfo(Set<Integer> published) {
        SqlInfoBuilder builder = new SqlInfoBuilder()
                .withFlightSqlServerName("Pravaha")
                .withFlightSqlServerVersion(version())
                .withFlightSqlServerArrowVersion(arrowVersion())
                // Read-only, and not as a hedge: ADR-030 scopes Flight SQL to asking questions of
                // maintained state. There is no INSERT, UPDATE or DELETE to be wrong about.
                .withFlightSqlServerReadOnly(true)
                .withFlightSqlServerSql(true)
                .withFlightSqlServerSubstrait(false)
                .withFlightSqlServerTransaction(FlightSql.SqlSupportedTransaction.SQL_SUPPORTED_TRANSACTION_NONE)
                .withFlightSqlServerCancel(false)
                .withFlightSqlServerBulkIngestion(false)
                .withSqlIdentifierQuoteChar("\"")
                // UNKNOWN, and it is the accurate value rather than a shrug. The planner stores
                // identifiers unchanged and compares them case-sensitively (SqlPlanner: caseSensitive
                // with Casing.UNCHANGED both quoted and unquoted), and this enum offers only
                // "insensitive", "folded to upper" and "folded to lower". Claiming one of the folding
                // values would have a client rewrite an identifier the planner then cannot resolve,
                // which is a worse failure than saying the question has no answer here.
                .withSqlIdentifierCase(FlightSql.SqlSupportedCaseSensitivity.SQL_CASE_SENSITIVITY_UNKNOWN)
                .withSqlQuotedIdentifierCase(FlightSql.SqlSupportedCaseSensitivity.SQL_CASE_SENSITIVITY_UNKNOWN)
                .withSqlSearchStringEscape("\\")
                .withSqlExtraNameCharacters("")
                .withSqlCatalogTerm("")
                .withSqlSchemaTerm("")
                .withSqlProcedureTerm("")
                .withSqlDdlCatalog(false)
                .withSqlDdlSchema(false)
                .withSqlDdlTable(false)
                .withSqlAllTablesAreSelectable(true)
                .withSqlSupportsColumnAliasing(true)
                .withSqlNullPlusNullIsNull(true)
                .withSqlSupportsTableCorrelationNames(true)
                .withSqlSupportsDifferentTableCorrelationNames(false)
                .withSqlSupportsExpressionsInOrderBy(true)
                .withSqlSupportsOrderByUnrelated(true)
                .withSqlSupportsLikeEscapeClause(true)
                .withSqlSupportsNonNullableColumns(true)
                .withSqlSupportsIntegrityEnhancementFacility(false)
                .withSqlCatalogAtStart(false)
                .withSqlSelectForUpdateSupported(false)
                .withSqlStoredProceduresSupported(false)
                .withSqlCorrelatedSubqueriesSupported(false)
                .withSqlTransactionsSupported(false)
                .withSqlBatchUpdatesSupported(false)
                .withSqlSavepointsSupported(false)
                .withSqlNamedParametersSupported(false)
                .withSqlLocatorsUpdateCopy(false)
                .withSqlStoredFunctionsUsingCallSyntaxSupported(false);
        published.addAll(List.of(
                FlightSql.SqlInfo.FLIGHT_SQL_SERVER_NAME_VALUE,
                FlightSql.SqlInfo.FLIGHT_SQL_SERVER_VERSION_VALUE,
                FlightSql.SqlInfo.FLIGHT_SQL_SERVER_ARROW_VERSION_VALUE,
                FlightSql.SqlInfo.FLIGHT_SQL_SERVER_READ_ONLY_VALUE,
                FlightSql.SqlInfo.FLIGHT_SQL_SERVER_SQL_VALUE,
                FlightSql.SqlInfo.FLIGHT_SQL_SERVER_SUBSTRAIT_VALUE,
                FlightSql.SqlInfo.FLIGHT_SQL_SERVER_TRANSACTION_VALUE,
                FlightSql.SqlInfo.FLIGHT_SQL_SERVER_CANCEL_VALUE,
                FlightSql.SqlInfo.FLIGHT_SQL_SERVER_BULK_INGESTION_VALUE,
                FlightSql.SqlInfo.SQL_IDENTIFIER_QUOTE_CHAR_VALUE,
                FlightSql.SqlInfo.SQL_IDENTIFIER_CASE_VALUE,
                FlightSql.SqlInfo.SQL_QUOTED_IDENTIFIER_CASE_VALUE,
                FlightSql.SqlInfo.SQL_SEARCH_STRING_ESCAPE_VALUE,
                FlightSql.SqlInfo.SQL_EXTRA_NAME_CHARACTERS_VALUE,
                FlightSql.SqlInfo.SQL_CATALOG_TERM_VALUE,
                FlightSql.SqlInfo.SQL_SCHEMA_TERM_VALUE,
                FlightSql.SqlInfo.SQL_PROCEDURE_TERM_VALUE,
                FlightSql.SqlInfo.SQL_DDL_CATALOG_VALUE,
                FlightSql.SqlInfo.SQL_DDL_SCHEMA_VALUE,
                FlightSql.SqlInfo.SQL_DDL_TABLE_VALUE,
                FlightSql.SqlInfo.SQL_ALL_TABLES_ARE_SELECTABLE_VALUE,
                FlightSql.SqlInfo.SQL_SUPPORTS_COLUMN_ALIASING_VALUE,
                FlightSql.SqlInfo.SQL_NULL_PLUS_NULL_IS_NULL_VALUE,
                FlightSql.SqlInfo.SQL_SUPPORTS_TABLE_CORRELATION_NAMES_VALUE,
                FlightSql.SqlInfo.SQL_SUPPORTS_DIFFERENT_TABLE_CORRELATION_NAMES_VALUE,
                FlightSql.SqlInfo.SQL_SUPPORTS_EXPRESSIONS_IN_ORDER_BY_VALUE,
                FlightSql.SqlInfo.SQL_SUPPORTS_ORDER_BY_UNRELATED_VALUE,
                FlightSql.SqlInfo.SQL_SUPPORTS_LIKE_ESCAPE_CLAUSE_VALUE,
                FlightSql.SqlInfo.SQL_SUPPORTS_NON_NULLABLE_COLUMNS_VALUE,
                FlightSql.SqlInfo.SQL_SUPPORTS_INTEGRITY_ENHANCEMENT_FACILITY_VALUE,
                FlightSql.SqlInfo.SQL_CATALOG_AT_START_VALUE,
                FlightSql.SqlInfo.SQL_SELECT_FOR_UPDATE_SUPPORTED_VALUE,
                FlightSql.SqlInfo.SQL_STORED_PROCEDURES_SUPPORTED_VALUE,
                FlightSql.SqlInfo.SQL_CORRELATED_SUBQUERIES_SUPPORTED_VALUE,
                FlightSql.SqlInfo.SQL_TRANSACTIONS_SUPPORTED_VALUE,
                FlightSql.SqlInfo.SQL_BATCH_UPDATES_SUPPORTED_VALUE,
                FlightSql.SqlInfo.SQL_SAVEPOINTS_SUPPORTED_VALUE,
                FlightSql.SqlInfo.SQL_NAMED_PARAMETERS_SUPPORTED_VALUE,
                FlightSql.SqlInfo.SQL_LOCATORS_UPDATE_COPY_VALUE,
                FlightSql.SqlInfo.SQL_STORED_FUNCTIONS_USING_CALL_SYNTAX_SUPPORTED_VALUE));
        return builder;
    }

    private static String version() {
        String implementation = FlightSqlMetadata.class.getPackage().getImplementationVersion();
        return implementation == null ? "0.1.0-SNAPSHOT" : implementation;
    }

    private static String arrowVersion() {
        String implementation = VectorSchemaRoot.class.getPackage().getImplementationVersion();
        return implementation == null ? "unknown" : implementation;
    }

    // ------------------------------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------------------------------

    /**
     * The views this principal may see, in a stable order so two listings agree.
     *
     * <p>The name and the lineage both decide, as they do on the control-plane listing: SX-11 was a
     * principal denied "payroll" being shown six payroll-reading views because somebody else had
     * chosen their names.
     */
    private List<String> visible(Principal principal) {
        // ADR-060: the caller's tenant's views under their own names -- every tenant's, by catalogue name,
        // for an admin -- and nothing of any other tenant's.
        List<String> names = new ArrayList<>();
        for (String engine : catalog.names()) {
            if (ViewNames.visibleTo(principal, engine)
                    && mayRead(principal, engine)
                    && mayReadEverythingBehind(principal, engine)) {
                names.add(ViewNames.shown(principal, engine));
            }
        }
        java.util.Collections.sort(names);
        return names;
    }

    /** The engine name {@code table} means to {@code principal}, or null for one it may not name. */
    private static String engineName(Principal principal, String table) {
        try {
            return ViewNames.resolve(principal, table);
        } catch (com.ash.messaging.pravaha.api.PravahaException outsideTheTenant) {
            return null;
        }
    }

    private boolean mayRead(Principal principal, String name) {
        return policy.mayRead(principal, name).allowed();
    }

    /**
     * Whether every stream behind {@code name} may be read through it, asked as the control-plane
     * listing asks it ({@link SecurityPolicy#mayReadThrough}). GETTABLES-1: this asked {@code mayRead}
     * of each stream, which the catalogue (ADR-059 §2) answers with the stream's own {@code SELECT} --
     * a grant a reader of the view does not hold and need not -- so every non-admin was listed nothing,
     * not even a view they own. A configured policy answers {@code mayReadThrough} as {@code mayRead},
     * so SX-11's rule there is unchanged.
     */
    private boolean mayReadEverythingBehind(Principal principal, String name) {
        ServedView view = catalog.find(name).orElse(null);
        if (view == null) {
            return false;
        }
        for (String stream : view.derivedFrom()) {
            if (!stream.equals(name)
                    && !policy.mayReadThrough(principal, stream).allowed()) {
                return false;
            }
        }
        return true;
    }

    /** Whether a filter field names something, as opposed to being absent or empty. */
    private static boolean named(String filter) {
        return filter != null && !filter.isEmpty();
    }

    private void empty(Schema schema, ServerStreamListener listener) {
        try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator)) {
            listener.start(root);
            root.setRowCount(0);
            listener.putNext();
            listener.completed();
        }
    }

    private static void text(VectorSchemaRoot root, String field, int index, String value) {
        ((VarCharVector) root.getVector(field)).setSafe(index, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void textOrNull(VectorSchemaRoot root, String field, int index, String value) {
        if (value == null) {
            root.getVector(field).setNull(index);
        } else {
            text(root, field, index, value);
        }
    }

    private static void bit(VectorSchemaRoot root, String field, int index, boolean value) {
        ((BitVector) root.getVector(field)).setSafe(index, value ? 1 : 0);
    }

    private static void bitOrNull(VectorSchemaRoot root, String field, int index, Boolean value) {
        if (value == null) {
            root.getVector(field).setNull(index);
        } else {
            bit(root, field, index, value);
        }
    }

    /**
     * A SQL {@code LIKE} pattern, which is what the Flight SQL filter fields are defined to carry.
     *
     * <p>{@code %} and {@code _} are the wildcards and everything else is literal, so the regex has
     * to be built rather than the pattern handed to {@link Pattern}: a view named {@code v.1} would
     * otherwise match a search for {@code v_1}, and a pattern containing a bracket would throw out
     * of a metadata call for no reason a user could act on.
     */
    static boolean matches(String pattern, String value) {
        StringBuilder regex = new StringBuilder(pattern.length() + 8);
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '%' || c == '_') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '%' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(value).matches();
    }

    /**
     * One {@code getXdbcTypeInfo} row per type Pravaha puts on the wire.
     *
     * <p>{@code jdbcType} values are {@link java.sql.Types} constants written out as numbers, so
     * that a table of integers does not drag {@code java.sql} onto this module's path.
     */
    private enum XdbcType {
        BOOLEAN("BOOLEAN", 16, 1, null, false, false),
        TINYINT("TINYINT", -6, 3, null, false, true),
        SMALLINT("SMALLINT", 5, 5, null, false, true),
        INTEGER("INTEGER", 4, 10, null, false, true),
        BIGINT("BIGINT", -5, 19, null, false, true),
        DECIMAL("DECIMAL", 3, DecimalType.MAX_PRECISION, null, false, true),
        REAL("REAL", 7, 7, null, false, true),
        DOUBLE("DOUBLE", 8, 15, null, false, true),
        VARCHAR("VARCHAR", 12, -1, "'", true, false),
        VARBINARY("VARBINARY", -3, -1, "'", false, false),
        DATE("DATE", 91, 10, "'", false, false),
        TIME("TIME", 92, 18, "'", false, false),
        TIMESTAMP("TIMESTAMP", 93, 29, "'", false, false);

        private final String typeName;
        private final int jdbcType;
        private final int columnSize;
        private final String literalQuote;
        private final boolean caseSensitive;
        private final boolean numeric;

        XdbcType(
                String typeName,
                int jdbcType,
                int columnSize,
                String literalQuote,
                boolean caseSensitive,
                boolean numeric) {
            this.typeName = typeName;
            this.jdbcType = jdbcType;
            this.columnSize = columnSize;
            this.literalQuote = literalQuote;
            this.caseSensitive = caseSensitive;
            this.numeric = numeric;
        }
    }
}
