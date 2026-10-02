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

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * {@code SET}, for the handful of settings a real client sends without being asked to.
 *
 * <p>{@code ViewQuery} has no session and nothing here changes how a query runs -- this server has
 * exactly one behaviour per query, for every client, always. So {@code SET} cannot do what it does
 * in real PostgreSQL, and the honest options are the two below: refuse it outright, which is what
 * slice 1 did by letting it fall through to the SQL planner and come back as a confusing syntax
 * error, or accept a small, named list of settings whose value this server already behaves as if it
 * had, and refuse everything else loudly.
 *
 * <p>Slice 2 does the second, and only for that reason. A JDBC connection sends {@code SET
 * extra_float_digits = 3} before it sends anything else -- not optionally, every time, in {@code
 * PgConnection}'s own connection setup -- and refusing it meant no JDBC driver could open a
 * connection to this server at all, whatever query it meant to run. Accepting it costs nothing only
 * because it is already true: see {@link #ALLOWED} below for why each entry on the list is a no-op
 * rather than a lie.
 *
 * <p><strong>The rule this class exists to keep: nothing goes on {@link #ALLOWED} unless accepting
 * it and doing nothing is provably the same as honouring it.</strong> A setting that changes real
 * behaviour in real PostgreSQL and would silently do nothing here is not a convenience, it is a
 * client told its request succeeded when it did not -- the exact failure mode this codebase treats
 * as worse than a refusal. Every other {@code SET} is refused with {@link
 * PgWireErrors#UNSUPPORTED_SET}, naming the setting, so a client relying on one this server does not
 * actually honour finds out immediately rather than downstream of a wrong answer.
 */
final class PgSessionSet {

    private PgSessionSet() {}

    private static final Pattern SET_STATEMENT = Pattern.compile(
            "^SET\\s+(?:SESSION\\s+|LOCAL\\s+)?(\"?)([A-Za-z_][A-Za-z0-9_]*)\\1\\s*(?:TO|=)\\s*(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Settings accepted and ignored, each because the value they would set is the value this server
     * already behaves as if it were set to -- not because the setting is unimportant.
     */
    private enum Allowed {
        /**
         * How many digits {@code libpq} asks a real server to print for a float. {@link PgTypes}
         * encodes every {@code FLOAT4}/{@code FLOAT8} with {@code Float.toString}/{@code
         * Double.toString}, which are already exact and round-trip -- the shortest decimal that
         * reads back to the same bit pattern -- for every value, unconditionally. There is no lower
         * setting to fall back to and no higher precision to ask for: this server does not compute
         * its output precision from this parameter at all, so accepting any value changes nothing
         * about what actually goes on the wire.
         */
        EXTRA_FLOAT_DIGITS("extra_float_digits", value -> true),

        /**
         * Free-text client metadata. Nothing in this server -- not planning, not authorization, not
         * the audit trail, which records the principal and the SQL, not the connection's self
         * description -- ever reads {@code application_name} for any purpose, so setting it or
         * leaving it unset produce identical behaviour by construction, not by coincidence.
         */
        APPLICATION_NAME("application_name", value -> true),

        /**
         * How verbosely the <em>client</em> filters the severities this server already tags on
         * every {@code NoticeResponse} and {@code ErrorResponse}. The filtering is {@code libpq}'s
         * own, applied to messages this server has already sent in full; nothing here changes what
         * gets sent based on this setting, so there is nothing to honour beyond accepting the value.
         */
        CLIENT_MIN_MESSAGES("client_min_messages", value -> true),

        /**
         * The client's declared text encoding. Accepted only when it names UTF-8: this server
         * announces {@code client_encoding=UTF8} at {@code ReadyForQuery} and {@link PgTypes} writes
         * every string as UTF-8 bytes unconditionally, so a client asking for the encoding this
         * server already uses is a no-op. A client asking for anything else is refused rather than
         * silently kept on UTF-8, because that client would decode this server's UTF-8 bytes as
         * whatever it asked for instead -- a real difference in what ends up on the client's screen,
         * which is exactly the kind of accepted-and-ignored setting this class exists to refuse.
         */
        CLIENT_ENCODING(
                "client_encoding",
                value -> normalize(value).equals("UTF8") || normalize(value).equals("UNICODE")),

        /**
         * The client's requested timestamp rendering. Accepted only for {@code ISO}: {@link
         * PgTypes#encode} renders every {@code TIMESTAMP_LTZ} and {@code DATE} in ISO form and
         * announces {@code DateStyle=ISO, MDY} at connect, with no alternate code path -- there is
         * no {@code German} or {@code SQL, DMY} rendering to switch to, so a request for one of
         * those is refused rather than accepted and ignored, which would leave the client parsing
         * ISO-formatted values with a non-ISO parser.
         */
        DATESTYLE("DateStyle", value -> normalize(value).startsWith("ISO")),

        /**
         * Where unqualified names are looked up (PGVALIDATE-1: pools, ORMs and BI tools set it on
         * connect). Accepted only for a path that resolves names exactly as this server already
         * does: every view is a table in {@code public} and no other schema exists, so a path made of
         * {@code public}, {@code "$user"} (a schema that never exists here, which PostgreSQL itself
         * skips) and {@code pg_catalog} -- with {@code public} on it -- or {@code DEFAULT}, changes
         * nothing. A path naming any other schema, or leaving {@code public} off, is refused: it would
         * ask for a resolution this server does not perform.
         */
        SEARCH_PATH("search_path", PgSessionSet::keepsPublicResolution);

        private final String parameter;
        private final java.util.function.Predicate<String> acceptsValue;

        Allowed(String parameter, java.util.function.Predicate<String> acceptsValue) {
            this.parameter = parameter;
            this.acceptsValue = acceptsValue;
        }

        private static String normalize(String value) {
            String stripped = value.trim();
            if (stripped.length() >= 2
                    && stripped.charAt(0) == '\''
                    && stripped.charAt(stripped.length() - 1) == '\'') {
                stripped = stripped.substring(1, stripped.length() - 1);
            }
            return stripped.toUpperCase(Locale.ROOT);
        }
    }

    private static boolean keepsPublicResolution(String value) {
        String trimmed = value.strip();
        if (trimmed.equalsIgnoreCase("DEFAULT")) {
            return true;
        }
        boolean hasPublic = false;
        for (String entry : trimmed.split(",", -1)) {
            String schema = entry.strip();
            if (schema.length() >= 2
                    && (schema.charAt(0) == '\'' || schema.charAt(0) == '"')
                    && schema.charAt(schema.length() - 1) == schema.charAt(0)) {
                schema = schema.substring(1, schema.length() - 1);
            }
            switch (schema.toLowerCase(Locale.ROOT)) {
                case "public" -> hasPublic = true;
                case "$user", "pg_catalog" -> {
                    // Never a schema with views in it here: no effect on resolution.
                }
                default -> {
                    return false;
                }
            }
        }
        return hasPublic;
    }

    private static final Pattern DISCARD_ALL = Pattern.compile("^DISCARD\\s+ALL$", Pattern.CASE_INSENSITIVE);

    /**
     * Whether {@code statement} is {@code DISCARD ALL}, which Npgsql's connection pool sends every
     * time it hands a pooled connection out again -- so every Power BI refresh after the first.
     * {@code PgWireConnection} honours it (see {@code PgExtendedSession.discardAll}); {@code DISCARD
     * PLANS}, {@code SEQUENCES} and {@code TEMP} are not sent by any client this gateway serves and
     * reach the planner as before.
     */
    static boolean isDiscardAll(String statement) {
        return DISCARD_ALL.matcher(statement.strip()).matches();
    }

    /** Whether {@code statement} is a {@code SET} at all -- the gate before {@link #handle}. */
    static boolean isSetStatement(String statement) {
        return SET_STATEMENT.matcher(statement.strip()).matches();
    }

    /**
     * Accepts a {@code SET} on the allow-list, or refuses it by name.
     *
     * @throws PravahaException {@link PgWireErrors#UNSUPPORTED_SET} if this parameter, or this
     *     value for it, is not one this server actually honours
     */
    static void handle(String statement) {
        Matcher match = SET_STATEMENT.matcher(statement.strip());
        if (!match.matches()) {
            // Guarded by isSetStatement at the one call site; a second caller that skipped the gate
            // gets a clear failure rather than a NullPointerException three lines down.
            throw new PravahaException(
                    PgWireErrors.UNSUPPORTED_SET, "not a SET statement this server can parse: " + statement);
        }
        String parameter = match.group(2);
        String value = match.group(3);
        for (Allowed candidate : Allowed.values()) {
            if (candidate.parameter.equalsIgnoreCase(parameter)) {
                if (candidate.acceptsValue.test(value)) {
                    return; // Accepted, and correctly a no-op: see the Allowed entry's own javadoc.
                }
                throw new PravahaException(
                        PgWireErrors.UNSUPPORTED_SET,
                        "SET " + parameter + " = " + value + " asks for a real behaviour change this server "
                                + "does not implement. Accepting it and doing nothing would tell the client its "
                                + "request succeeded when '" + candidate.parameter + "' still behaves the way it "
                                + "did before this statement; refused rather than silently ignored.");
            }
        }
        throw new PravahaException(
                PgWireErrors.UNSUPPORTED_SET,
                "SET " + parameter + " is not one of the settings this gateway accepts (" + allowedNames() + "). "
                        + "This server has no per-session state for a SET to change, so a parameter is only ever "
                        + "accepted when this server already behaves as though it were set that way -- see "
                        + "PgSessionSet's own documentation for the list and why each entry on it is safe. "
                        + "Refused rather than silently accepted, so a client relying on this setting's real "
                        + "effect finds out now rather than from a wrong answer later.");
    }

    private static String allowedNames() {
        StringBuilder names = new StringBuilder();
        for (Allowed candidate : Allowed.values()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(candidate.parameter);
        }
        return names.toString();
    }
}
