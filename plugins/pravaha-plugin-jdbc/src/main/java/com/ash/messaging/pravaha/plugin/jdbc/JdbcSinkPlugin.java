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
package com.ash.messaging.pravaha.plugin.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.api.plugin.Version;
import com.ash.messaging.pravaha.plugin.jdbc.JdbcSinkRows.Change;

/**
 * A continuous query's answer, maintained in a relational table.
 *
 * <p><strong>Two modes.</strong> {@code mode: upsert} (the default) keeps the table equal to the
 * query's view: a row is inserted or replaced by its key, and a retraction -- a row with a negative
 * weight -- deletes the record its key names. {@code key.columns} names the key, and the registry
 * refuses a registration whose {@code --keys} differ (PRV-8010), because a sink keyed differently
 * from the view deletes the wrong record. {@code mode: append} inserts every row and accepts only an
 * append-only query.
 *
 * <p><strong>Exactly once, and how.</strong> With {@code transactional: true} (the default) the sink
 * implements the SPI's two-phase protocol with a <em>staging table</em> rather than with the
 * database's own prepared transactions:
 *
 * <ol>
 *   <li>{@link #write} does not touch the target table. It stores the batch, as bytes, in the staging
 *       table under this sink's {@code transaction.id} and the open transaction's label, and commits
 *       that. Staged rows are durable and invisible to anybody reading the target table, which is
 *       what {@link #prepare} promises -- so prepare has nothing left to do but name the label.
 *   <li>{@link #commit} applies every batch staged under the label to the target table, deletes them
 *       from the staging table, and commits -- in <em>one</em> database transaction. So a commit is
 *       atomic (a reader sees all of a checkpoint's changes or none), and it is idempotent without
 *       any bookkeeping: once it has committed, the label has nothing staged and a second commit finds
 *       nothing to do; if it died half way, the database rolled all of it back, staged rows included,
 *       and the next commit does the whole of it.
 *   <li>{@link #abortAfter} deletes everything staged under a label greater than the restored
 *       checkpoint, which the replay is about to write again.
 * </ol>
 *
 * <p>Why not {@code PREPARE TRANSACTION}: it exists only on some databases, needs {@code
 * max_prepared_transactions} raised on PostgreSQL, and holds every row lock the transaction took
 * until the checkpoint is durable -- so a slow checkpoint store would block every other writer of
 * the table. And a JDBC connection cannot hold two open transactions, where the protocol needs the
 * next transaction open while the last is prepared. A staging table works on any database with a
 * byte-string column, holds no lock between calls, and its guarantee can be stated and tested: every
 * change the engine commits reaches the table exactly once, and a checkpoint's changes become visible
 * together. What it costs: every row is written twice, and the table lags the view by one checkpoint
 * interval.
 *
 * <p>The guarantee assumes one writer per {@code transaction.id} (default: the binding's name), since
 * the staging rows are found by it, and that nothing else writes the target table's keys. With
 * {@code transactional: false} each batch is written straight to the table in its own transaction:
 * effectively once in upsert mode (a replay rewrites records with the values they hold), at least
 * once in append mode.
 *
 * <p><strong>Checked before a row moves.</strong> The table and every declared column must exist, and
 * each column must be able to hold its declared type; PostgreSQL additionally needs a primary key or
 * unique index on exactly the key. Each refusal is {@link JdbcErrors#SINK_TABLE_MISMATCH} and names
 * the column. The sink never creates or alters the target table; it creates the staging table if it
 * is missing.
 *
 * <p>Configuration: {@code url} (required, TLS in the URL -- the shared {@code tls.*} options are
 * refused), {@code user}, {@code password}, {@code table} (required, {@code name} or {@code
 * schema.name}), {@code schema} (required, {@code name:TYPE,...}), {@code key.columns} (required in
 * upsert mode), {@code mode} ({@code upsert} | {@code append}), {@code transactional} (default
 * {@code true}), {@code transaction.id} (default the binding's name), {@code staging.table} (default
 * {@code pravaha_sink_staging}), {@code dialect} ({@code auto} | {@code postgresql} | {@code h2} |
 * {@code portable}).
 */
public final class JdbcSinkPlugin implements StreamSinkPlugin {

    /** The staging table's default name. */
    public static final String DEFAULT_STAGING_TABLE = "pravaha_sink_staging";

    private static final int MAX_BATCH_ROWS = 1000;
    private static final String HANDLE_PREFIX = "jdbc-sink:v1:";

    private String instanceName = "jdbc-sink";
    private String url;
    private String user;
    private String password;
    private String tableSetting;
    private StreamSchema schema;
    private List<String> keyNames = List.of();
    private int[] keyOrdinals = new int[0];
    private boolean append;
    private boolean transactional;
    private String transactionId;
    private String stagingTable;
    private JdbcDialect dialectSetting;

    private Connection connection;
    private JdbcDialect dialect;
    private JdbcSinkTable table;
    private JdbcSinkRows rows;
    private String upsertSql;
    private String updateSql;
    private String insertSql;
    private String deleteSql;

    /** The open transaction's label, or -1 when none is open. */
    private long openLabel = -1;

    private int nextSeq;
    private long rowsApplied;

    @Override
    public String name() {
        return "jdbc-sink";
    }

    @Override
    public Version version() {
        return new Version(0, 1, 0);
    }

    @Override
    public void configure(PluginContext context) {
        this.instanceName = context.instanceName();
        JdbcSourcePlugin.refuseSharedTlsOptions(context);
        this.url = context.require("url");
        this.user = context.get("user", "");
        this.password = context.get("password", "");
        this.tableSetting = context.require("table").strip();
        this.schema = JdbcSinkSchema.parse(tableSetting, context.require("schema"));

        String mode = context.get("mode", "upsert").strip().toLowerCase(Locale.ROOT);
        switch (mode) {
            case "upsert" -> this.append = false;
            case "append" -> this.append = true;
            default ->
                throw new ConfigurationException(
                        JdbcErrors.BAD_CONFIGURATION,
                        "plugin '" + instanceName + "' mode '" + mode + "' is not upsert or append");
        }
        String keys = context.get("key.columns", "").strip();
        if (append && !keys.isEmpty()) {
            throw new ConfigurationException(
                    JdbcErrors.BAD_CONFIGURATION,
                    "plugin '" + instanceName + "' is in append mode, which inserts every row and deletes none, so "
                            + "key.columns would mean nothing. Remove it, or use mode: upsert to keep the table equal "
                            + "to the query's view by key.");
        }
        if (!append && keys.isEmpty()) {
            throw new ConfigurationException(
                    JdbcErrors.BAD_CONFIGURATION,
                    "plugin '" + instanceName + "' needs key.columns in upsert mode: a retraction deletes the record "
                            + "its key names, and without one there is nothing to name. It must be the query's "
                            + "--keys.");
        }
        List<String> names = new ArrayList<>();
        for (String part : keys.split(",")) {
            if (!part.isBlank()) {
                names.add(part.strip());
            }
        }
        this.keyNames = List.copyOf(names);
        this.keyOrdinals = new int[keyNames.size()];
        for (int k = 0; k < keyNames.size(); k++) {
            keyOrdinals[k] = keyOrdinal(keyNames.get(k));
        }

        this.transactional =
                Boolean.parseBoolean(context.get("transactional", "true").strip());
        this.transactionId = context.get("transaction.id", instanceName).strip();
        if (transactionId.isEmpty() || transactionId.length() > 200) {
            throw new ConfigurationException(
                    JdbcErrors.BAD_CONFIGURATION,
                    "transaction.id must be 1 to 200 characters, got '" + transactionId + "'");
        }
        this.stagingTable = context.get("staging.table", DEFAULT_STAGING_TABLE).strip();
        if (!stagingTable.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?")) {
            throw new ConfigurationException(
                    JdbcErrors.BAD_CONFIGURATION,
                    "staging.table '" + stagingTable + "' must be a plain name or schema.name of letters, digits and "
                            + "underscores; it is written into SQL unquoted, so the database folds it the same way "
                            + "wherever it appears");
        }
        this.dialectSetting = JdbcDialect.named(context.get("dialect", "auto"));
    }

    private int keyOrdinal(String key) {
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            if (schema.field(ordinal).name().equalsIgnoreCase(key)) {
                TypeName type = schema.field(ordinal).type().typeName();
                if (type == TypeName.FLOAT32 || type == TypeName.FLOAT64) {
                    throw new ConfigurationException(
                            JdbcErrors.BAD_CONFIGURATION,
                            "key column '" + key + "' is " + type + ". A floating-point key makes two values that "
                                    + "print alike two records, and a retraction that misses by a rounding error "
                                    + "deletes nothing.");
                }
                if (schema.field(ordinal).type().nullable()) {
                    throw new ConfigurationException(
                            JdbcErrors.BAD_CONFIGURATION,
                            "key column '" + key + "' is declared nullable; a record cannot be keyed by nothing, and "
                                    + "SQL's key = NULL matches no row, so its retraction would delete nothing");
                }
                return ordinal;
            }
        }
        throw new ConfigurationException(
                JdbcErrors.BAD_CONFIGURATION,
                "key column '" + key + "' is not in the declared schema, which has "
                        + schema.fields().stream().map(f -> f.name()).toList());
    }

    @Override
    public void open() {
        Properties properties = new Properties();
        if (!user.isBlank()) {
            properties.setProperty("user", user);
        }
        if (!password.isBlank()) {
            properties.setProperty("password", password);
        }
        try {
            this.connection = DriverManager.getConnection(url, properties);
        } catch (SQLException e) {
            throw new PravahaException(
                    JdbcErrors.CONNECT_FAILED,
                    "plugin '" + instanceName + "' cannot connect to " + url + ": " + e.getMessage()
                            + ". The JDBC driver is supplied by the deployment, not by the engine -- check it is on "
                            + "this plugin's classpath.",
                    e);
        }
        try {
            connection.setAutoCommit(false);
            this.dialect = dialectSetting != null
                    ? dialectSetting
                    : JdbcDialect.forProduct(connection.getMetaData().getDatabaseProductName());
            this.table = JdbcSinkTable.resolve(connection, tableSetting, schema);
            this.rows = new JdbcSinkRows(schema);
            buildStatements();
            if (transactional) {
                ensureStagingTable();
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            closeQuietly();
            if (e instanceof PravahaException coded) {
                throw coded;
            }
            throw new PravahaException(
                    JdbcErrors.QUERY_FAILED,
                    "plugin '" + instanceName + "' cannot read table '" + tableSetting + "': " + e.getMessage(),
                    e);
        }
    }

    private void buildStatements() {
        List<String> columns =
                table.columns().stream().map(JdbcSinkTable.Column::quoted).toList();
        String target = table.qualified();
        this.insertSql = JdbcDialect.insert(target, columns);
        if (append) {
            return;
        }
        List<String> keys = new ArrayList<>();
        List<String> keyCatalogueNames = new ArrayList<>();
        for (int ordinal : keyOrdinals) {
            keys.add(table.columns().get(ordinal).quoted());
            keyCatalogueNames.add(table.columns().get(ordinal).name());
        }
        if (dialect == JdbcDialect.POSTGRESQL && !table.hasUniqueKeyOn(keyCatalogueNames)) {
            throw new PravahaException(
                    JdbcErrors.SINK_TABLE_MISMATCH,
                    "table '" + tableSetting + "' has no primary key or unique index on exactly " + keyCatalogueNames
                            + ". INSERT ... ON CONFLICT needs one to find the row to replace; add it (ALTER TABLE ... "
                            + "ADD PRIMARY KEY (...)) -- it is also what stops a second writer duplicating a key.");
        }
        this.upsertSql = dialect.upsert(target, columns, keys);
        this.updateSql = JdbcDialect.update(target, columns, keys);
        this.deleteSql = JdbcDialect.delete(target, keys);
    }

    /**
     * Creates the staging table when it is missing. Probed with a query that returns nothing rather
     * than looked up in the catalogue, so it is found under exactly the spelling every statement
     * here will use.
     */
    private void ensureStagingTable() throws SQLException {
        try (Statement probe = connection.createStatement()) {
            probe.executeQuery("SELECT sink_id, label, seq, payload FROM " + stagingTable + " WHERE 1 = 0")
                    .close();
            connection.commit();
            return;
        } catch (SQLException missing) {
            // PostgreSQL aborts the whole transaction on an error, so the probe's must be undone
            // before anything else can run on this connection.
            connection.rollback();
        }
        try (Statement create = connection.createStatement()) {
            create.execute("CREATE TABLE " + stagingTable + " (sink_id VARCHAR(200) NOT NULL, label BIGINT NOT NULL, "
                    + "seq INTEGER NOT NULL, payload " + dialect.payloadType() + " NOT NULL, "
                    + "PRIMARY KEY (sink_id, label, seq))");
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw new PravahaException(
                    JdbcErrors.SINK_TABLE_MISMATCH,
                    "staging table '" + stagingTable + "' does not exist and cannot be created: " + e.getMessage()
                            + ". A transactional JDBC sink stages each checkpoint's changes there; create it (sink_id "
                            + "VARCHAR(200), label BIGINT, seq INTEGER, payload " + dialect.payloadType()
                            + ", primary key (sink_id, label, seq)), or set transactional: false.",
                    e);
        }
    }

    @Override
    public Optional<StreamSchema> schema() {
        return Optional.ofNullable(schema);
    }

    @Override
    public List<String> keyColumns() {
        return keyNames;
    }

    /**
     * Upsert mode takes a revising changelog and is idempotent; append mode takes inserts only. Either
     * is transactional when configured so -- see the class comment for what that is built on.
     */
    @Override
    public SinkCapabilities capabilities() {
        return new SinkCapabilities(
                append ? EnumSet.of(EmitMode.APPEND) : EnumSet.of(EmitMode.UPSERT, EmitMode.RETRACT),
                transactional,
                !append,
                MAX_BATCH_ROWS);
    }

    /**
     * Stages the batch in the open transaction, or -- not transactional, or written outside any
     * transaction -- applies it to the table and commits.
     */
    @Override
    public int write(List<RowView> batch) {
        requireOpen();
        List<Change> changes = new ArrayList<>(batch.size());
        for (RowView row : batch) {
            changes.add(rows.read(row));
        }
        if (changes.isEmpty()) {
            return 0;
        }
        try {
            if (transactional && openLabel >= 0) {
                stage(changes);
            } else {
                apply(changes);
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            throw failed("write to '" + tableSetting + "'", e);
        }
        return changes.size();
    }

    private void stage(List<Change> changes) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + stagingTable + " (sink_id, label, seq, payload) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, transactionId);
            insert.setLong(2, openLabel);
            insert.setInt(3, nextSeq);
            insert.setBytes(4, rows.encode(changes));
            insert.executeUpdate();
        }
        nextSeq++;
    }

    /** Applies changes to the target table, inside the connection's current transaction. */
    private void apply(List<Change> changes) throws SQLException {
        if (append) {
            appendAll(changes);
        } else {
            Map<List<Object>, Change> last = new LinkedHashMap<>();
            collapse(changes, last);
            upsertAndDelete(last.values());
        }
    }

    private void appendAll(List<Change> changes) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
            int pending = 0;
            for (Change change : changes) {
                if (change.weight() < 0) {
                    throw new PravahaException(
                            JdbcErrors.WRITE_FAILED,
                            "an append-mode sink was sent a retraction for '" + tableSetting
                                    + "'. It inserts and never "
                                    + "deletes, so the table would keep a row the query withdrew; bind it with mode: "
                                    + "upsert and key.columns for a query whose rows change.");
                }
                // A weight above one is that many copies of the row, which is what a multiset means.
                for (long copy = 0; copy < change.weight(); copy++) {
                    bindAll(insert, change.values(), 1);
                    insert.addBatch();
                    pending++;
                }
                if (pending >= MAX_BATCH_ROWS) {
                    insert.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                insert.executeBatch();
            }
            rowsApplied += changes.size();
        }
    }

    /**
     * Keeps each key's last change, in order.
     *
     * <p>An upsert replaces the whole record and a delete removes it, so of everything that happens
     * to one key only the last change decides what the table holds -- and changes to different keys
     * touch different records and commute. That is what lets a batch be written as one batch of
     * deletes and one of upserts rather than a statement at a time in the order the engine sent
     * them, with the same result.
     */
    private void collapse(List<Change> changes, Map<List<Object>, Change> last) {
        for (Change change : changes) {
            List<Object> key = new ArrayList<>(keyOrdinals.length);
            for (int ordinal : keyOrdinals) {
                Object value = change.values()[ordinal];
                if (value == null) {
                    throw new PravahaException(
                            JdbcErrors.WRITE_FAILED,
                            "key column '" + schema.field(ordinal).name() + "' is null in a row for '" + tableSetting
                                    + "'; a record cannot be keyed by nothing");
                }
                key.add(JdbcSinkRows.comparable(value));
            }
            // Removed first, so a key changed again moves to the back and the order stays the order
            // of each key's final change.
            last.remove(key);
            last.put(key, change);
        }
    }

    private void upsertAndDelete(Iterable<Change> changes) throws SQLException {
        List<Change> upserts = new ArrayList<>();
        List<Change> deletes = new ArrayList<>();
        for (Change change : changes) {
            (change.weight() < 0 ? deletes : upserts).add(change);
        }
        if (!deletes.isEmpty()) {
            try (PreparedStatement delete = connection.prepareStatement(deleteSql)) {
                for (Change change : deletes) {
                    bindKeys(delete, change.values(), 1);
                    delete.addBatch();
                }
                delete.executeBatch();
            }
        }
        if (upserts.isEmpty()) {
            rowsApplied += deletes.size();
            return;
        }
        if (upsertSql != null) {
            try (PreparedStatement upsert = connection.prepareStatement(upsertSql)) {
                for (Change change : upserts) {
                    bindAll(upsert, change.values(), 1);
                    upsert.addBatch();
                }
                upsert.executeBatch();
            }
        } else {
            portableUpsert(upserts);
        }
        rowsApplied += deletes.size() + upserts.size();
    }

    /**
     * {@code UPDATE}, then {@code INSERT} for every row the update did not find. A driver that
     * answers a batch with {@link Statement#SUCCESS_NO_INFO} has not said which rows it found, so
     * those are updated again one at a time, where the count is always reported.
     */
    private void portableUpsert(List<Change> upserts) throws SQLException {
        List<Change> missing = new ArrayList<>();
        try (PreparedStatement update = connection.prepareStatement(updateSql)) {
            for (Change change : upserts) {
                bindUpdate(update, change.values());
                update.addBatch();
            }
            int[] counts = update.executeBatch();
            for (int i = 0; i < upserts.size(); i++) {
                int count = i < counts.length ? counts[i] : Statement.SUCCESS_NO_INFO;
                if (count == Statement.SUCCESS_NO_INFO) {
                    bindUpdate(update, upserts.get(i).values());
                    count = update.executeUpdate();
                }
                if (count == 0) {
                    missing.add(upserts.get(i));
                }
            }
        }
        if (!missing.isEmpty()) {
            try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                for (Change change : missing) {
                    bindAll(insert, change.values(), 1);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    private void bindAll(PreparedStatement statement, Object[] values, int first) throws SQLException {
        for (int ordinal = 0; ordinal < values.length; ordinal++) {
            rows.bind(
                    statement,
                    first + ordinal,
                    ordinal,
                    values[ordinal],
                    table.columns().get(ordinal));
        }
    }

    private int bindKeys(PreparedStatement statement, Object[] values, int first) throws SQLException {
        int index = first;
        for (int ordinal : keyOrdinals) {
            rows.bind(
                    statement,
                    index++,
                    ordinal,
                    values[ordinal],
                    table.columns().get(ordinal));
        }
        return index;
    }

    /** Parameters of {@link JdbcDialect#update}: the non-key columns, then the key. */
    private void bindUpdate(PreparedStatement statement, Object[] values) throws SQLException {
        int index = 1;
        boolean anyRest = false;
        for (int ordinal = 0; ordinal < values.length; ordinal++) {
            if (!isKey(ordinal)) {
                rows.bind(
                        statement,
                        index++,
                        ordinal,
                        values[ordinal],
                        table.columns().get(ordinal));
                anyRest = true;
            }
        }
        if (!anyRest) {
            int first = keyOrdinals[0];
            rows.bind(statement, index++, first, values[first], table.columns().get(first));
        }
        bindKeys(statement, values, index);
    }

    private boolean isKey(int ordinal) {
        for (int key : keyOrdinals) {
            if (key == ordinal) {
                return true;
            }
        }
        return false;
    }

    /** Nothing is buffered: every write has committed, to the staging table or to the target. */
    @Override
    public void flush() {}

    /**
     * Opens transaction {@code checkpointId}, first discarding anything staged under that label.
     *
     * <p>Labels only increase, across restarts too, so a label being begun has never been prepared
     * and whatever is staged under it is the remains of a process that died before preparing it.
     */
    @Override
    public void beginTransaction(long checkpointId) {
        if (!transactional) {
            return;
        }
        requireOpen();
        try {
            deleteStaged("label = ?", checkpointId);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            throw failed("begin transaction " + checkpointId + " for '" + tableSetting + "'", e);
        }
        this.openLabel = checkpointId;
        this.nextSeq = 0;
    }

    /**
     * Names the open transaction. Everything it holds is already durable in the staging table and
     * invisible in the target, so there is nothing else to do.
     */
    @Override
    public String prepare(long checkpointId) {
        if (!transactional) {
            return "";
        }
        if (openLabel < 0) {
            throw new PravahaException(
                    JdbcErrors.WRITE_FAILED,
                    "prepare(" + checkpointId + ") for '" + tableSetting + "' with no transaction begun");
        }
        String handle = HANDLE_PREFIX + openLabel + ":" + transactionId;
        openLabel = -1;
        return handle;
    }

    /**
     * Applies what the handle's transaction staged, and removes it from staging, atomically.
     * Idempotent: a transaction already committed has nothing staged, and committing it again does
     * nothing.
     */
    @Override
    public void commit(String handle) {
        if (!transactional || handle == null || handle.isEmpty()) {
            return;
        }
        requireOpen();
        long label = labelOf(handle);
        try {
            List<Integer> seqs = new ArrayList<>();
            try (PreparedStatement list = connection.prepareStatement(
                    "SELECT seq FROM " + stagingTable + " WHERE sink_id = ? AND label = ? ORDER BY seq")) {
                list.setString(1, transactionId);
                list.setLong(2, label);
                try (ResultSet rs = list.executeQuery()) {
                    while (rs.next()) {
                        seqs.add(rs.getInt(1));
                    }
                }
            }
            if (seqs.isEmpty()) {
                // Committed already -- by this process before it died, or by an earlier restore --
                // or never written to. Either way the table already holds everything it should.
                connection.rollback();
                return;
            }
            Map<List<Object>, Change> last = new LinkedHashMap<>();
            try (PreparedStatement read = connection.prepareStatement(
                    "SELECT payload FROM " + stagingTable + " WHERE sink_id = ? AND label = ? AND seq = ?")) {
                for (int seq : seqs) {
                    read.setString(1, transactionId);
                    read.setLong(2, label);
                    read.setInt(3, seq);
                    List<Change> staged;
                    try (ResultSet rs = read.executeQuery()) {
                        rs.next();
                        staged = rows.decode(rs.getBytes(1));
                    }
                    if (append) {
                        appendAll(staged);
                    } else {
                        // Collapsed across the whole transaction, not batch by batch: a key that
                        // changed a hundred times between checkpoints is written once.
                        collapse(staged, last);
                    }
                }
            }
            if (!append) {
                upsertAndDelete(last.values());
            }
            deleteStaged("label = ?", label);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            throw failed("commit " + handle, e);
        }
    }

    @Override
    public void abort(String handle) {
        if (!transactional || handle == null || handle.isEmpty()) {
            return;
        }
        requireOpen();
        long label = labelOf(handle);
        try {
            deleteStaged("label = ?", label);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            throw failed("abort " + handle, e);
        }
        if (label == openLabel) {
            openLabel = -1;
        }
    }

    /**
     * Discards everything staged under a label after the restored checkpoint: the open transaction
     * of the process that died, and any transaction prepared at a checkpoint that never became
     * durable. The replay writes all of it again.
     */
    @Override
    public void abortAfter(long checkpointId) {
        if (!transactional) {
            return;
        }
        requireOpen();
        try {
            deleteStaged("label > ?", checkpointId);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            throw failed("abort transactions after " + checkpointId, e);
        }
    }

    private void deleteStaged(String condition, long label) throws SQLException {
        try (PreparedStatement delete =
                connection.prepareStatement("DELETE FROM " + stagingTable + " WHERE sink_id = ? AND " + condition)) {
            delete.setString(1, transactionId);
            delete.setLong(2, label);
            delete.executeUpdate();
        }
    }

    private long labelOf(String handle) {
        if (handle.startsWith(HANDLE_PREFIX)) {
            String rest = handle.substring(HANDLE_PREFIX.length());
            int colon = rest.indexOf(':');
            if (colon > 0) {
                String owner = rest.substring(colon + 1);
                if (!owner.equals(transactionId)) {
                    throw new PravahaException(
                            JdbcErrors.WRITE_FAILED,
                            "handle '" + handle + "' belongs to transaction.id '" + owner + "', and this sink is '"
                                    + transactionId + "'. Committing it here would apply another sink's rows to this "
                                    + "table; keep transaction.id the same across restarts of one binding.");
                }
                try {
                    return Long.parseLong(rest.substring(0, colon));
                } catch (NumberFormatException ignored) {
                    // Falls through to the refusal.
                }
            }
        }
        throw new PravahaException(JdbcErrors.WRITE_FAILED, "'" + handle + "' is not a handle this sink wrote");
    }

    private void requireOpen() {
        if (connection == null) {
            throw new PravahaException(
                    JdbcErrors.WRITE_FAILED, "sink '" + instanceName + "' is not open; call open() before writing");
        }
    }

    private PravahaException failed(String what, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException | RuntimeException ignored) {
            // The connection may be the thing that failed; the original cause is what matters.
        }
        if (cause instanceof PravahaException coded) {
            return coded;
        }
        return new PravahaException(
                JdbcErrors.WRITE_FAILED,
                "sink '" + instanceName + "' could not " + what + ": " + cause.getMessage(),
                cause);
    }

    /** Rows applied to the target table by this instance: written straight, or by a commit. */
    public long rowsApplied() {
        return rowsApplied;
    }

    /** The dialect in use, once open. */
    JdbcDialect dialect() {
        return dialect;
    }

    @Override
    public HealthStatus health() {
        try {
            return connection != null && connection.isValid(1)
                    ? HealthStatus.healthy()
                    : HealthStatus.unhealthy("the JDBC connection is closed or not responding");
        } catch (SQLException e) {
            return HealthStatus.unhealthy("connection check failed: " + e.getMessage());
        }
    }

    /**
     * Closes the connection. Nothing is pending on it: an open transaction's rows are already in the
     * staging table, where a restore's {@code abortAfter} or a later commit finds them.
     */
    @Override
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        Connection closing = connection;
        connection = null;
        openLabel = -1;
        if (closing != null) {
            try {
                closing.rollback();
            } catch (SQLException ignored) {
                // Closing anyway.
            }
            try {
                closing.close();
            } catch (SQLException ignored) {
                // Nothing a caller could do about a connection that will not close.
            }
        }
    }
}
