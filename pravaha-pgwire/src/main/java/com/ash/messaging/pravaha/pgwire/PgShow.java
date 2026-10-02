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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.serving.ViewQuery;

/**
 * {@code SHOW}, for the settings drivers and ORMs probe and whose value this server has fixed.
 *
 * <p>Two families. The parameters announced at connect as {@code ParameterStatus} -- {@link
 * #announced} is the one list both the handshake and this class read, so the two cannot disagree --
 * and the transaction settings a driver asks about once it opens transactions (SQLAlchemy runs {@code
 * SHOW TRANSACTION ISOLATION LEVEL} and {@code SHOW standard_conforming_strings} on its first
 * connection). Each answer is what this server actually does, not PostgreSQL's default: isolation is
 * {@code read committed} because every statement reads the views as they are when it runs (see
 * {@link PgTransactionBlock}), and {@code transaction_read_only} is {@code on} because this gateway
 * writes nothing, whatever {@code BEGIN READ WRITE} asked for.
 *
 * <p>Anything else is not answered here and reaches the planner exactly as before.
 */
final class PgShow {

    private PgShow() {}

    private static final Pattern SHOW = Pattern.compile(
            "^SHOW\\s+(TRANSACTION\\s+ISOLATION\\s+LEVEL|\"?[A-Za-z_][A-Za-z0-9_]*\"?)$", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> TRANSACTION_SETTINGS = transactionSettings();

    private static Map<String, String> transactionSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("transaction_isolation", "read committed");
        settings.put("default_transaction_isolation", "read committed");
        settings.put("transaction_read_only", "on");
        settings.put("default_transaction_read_only", "on");
        settings.put("transaction_deferrable", "off");
        settings.put("default_transaction_deferrable", "off");
        return Collections.unmodifiableMap(settings);
    }

    /**
     * Name resolution, which pools and BI tools ask about (PGVALIDATE-1): every view is a table in
     * schema {@code public} and no other schema exists, so PostgreSQL's default path is exactly how
     * this server resolves names. {@link PgSessionSet} accepts a {@code SET search_path} that keeps
     * that resolution and refuses one that would change it.
     */
    static final String SEARCH_PATH = "\"$user\", public";

    /**
     * The {@code ParameterStatus} values every session is told at connect, in the order they are
     * sent. Each is a promise {@link PgTypes} keeps -- see {@code PgWireConnection.ready}.
     */
    static Map<String, String> announced(String serverVersion) {
        Map<String, String> announced = new LinkedHashMap<>();
        announced.put("server_version", serverVersion);
        announced.put("server_encoding", "UTF8");
        announced.put("client_encoding", "UTF8");
        announced.put("DateStyle", "ISO, MDY");
        announced.put("TimeZone", "UTC");
        announced.put("integer_datetimes", "on");
        announced.put("standard_conforming_strings", "on");
        return announced;
    }

    /** The one-row answer to {@code statement}, or empty if it is not a {@code SHOW} this class answers. */
    static Optional<ViewQuery.Result> answer(String statement, String serverVersion) {
        Matcher m = SHOW.matcher(statement.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        // SHOW TRANSACTION ISOLATION LEVEL is the SQL-standard spelling of SHOW transaction_isolation.
        String asked = m.group(1).matches("(?s).*\\s.*")
                ? "transaction_isolation"
                : m.group(1).replace("\"", "");
        Map<String, String> known = new LinkedHashMap<>(announced(serverVersion));
        known.putAll(TRANSACTION_SETTINGS);
        known.put("search_path", SEARCH_PATH);
        for (Map.Entry<String, String> entry : known.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(asked)) {
                // PostgreSQL names the column after the setting's own spelling -- `SHOW datestyle`
                // answers a column called DateStyle -- and every SHOW value is text.
                StreamSchema schema = StreamSchema.builder("show")
                        .field(entry.getKey(), Types.string())
                        .build();
                return Optional.of(
                        new ViewQuery.Result(schema, Collections.singletonList(new Object[] {entry.getValue()})));
            }
        }
        return Optional.empty();
    }
}
