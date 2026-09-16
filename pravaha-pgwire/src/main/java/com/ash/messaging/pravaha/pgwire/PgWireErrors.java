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

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;

/**
 * pgwire gateway error codes, PRV-62nn.
 *
 * <p><strong>On the number range.</strong> {@code ErrorCode.Category} calls 6000-6999 FLIGHT,
 * because when it was written the Flight gateway was the only gateway. It is really the gateway
 * band, and these codes sit at its top end, clear of Flight's 61nn. Renaming the category would be
 * right and would mean editing {@code pravaha-api}, which is a change with its own blast radius --
 * so the tension is recorded here rather than resolved quietly. A reader who sees {@code
 * PRV-6200.category() == FLIGHT} should read it as "gateway", not as "this came from Arrow".
 */
public final class PgWireErrors {

    /** A column type this gateway will not put on the wire. */
    public static final ErrorCode UNSUPPORTED_TYPE = new ErrorCode(6200, "PGWIRE_UNSUPPORTED_TYPE");

    /** A protocol message this slice does not implement -- the extended query protocol, or COPY. */
    public static final ErrorCode UNSUPPORTED_REQUEST = new ErrorCode(6201, "PGWIRE_UNSUPPORTED_REQUEST");

    /**
     * Bytes arrived that are not a well-formed message.
     *
     * <p>Includes a length field that is absurd. A frontend message says how long it is before it
     * says anything else, so a hostile or confused client can ask this server to allocate whatever
     * it likes; the length is bounded and a violation refused rather than honoured.
     */
    public static final ErrorCode PROTOCOL_VIOLATION = new ErrorCode(6202, "PGWIRE_PROTOCOL_VIOLATION");

    /** A startup packet asking for a protocol version this server does not speak. */
    public static final ErrorCode UNSUPPORTED_PROTOCOL_VERSION = new ErrorCode(6203, "PGWIRE_UNSUPPORTED_PROTOCOL");

    /**
     * A {@code SET} naming a parameter this gateway does not accept.
     *
     * <p>Deliberately not the same refusal a Calcite parse failure would give: {@code SET} is valid
     * PostgreSQL, understood and refused by name here, rather than run through a SQL planner that has
     * never heard of it and would answer with a syntax error about the wrong thing. See {@link
     * PgSessionSet} for the allow-list and, for every entry on it, why accepting and ignoring that
     * one specific setting is safe -- this code is for everything not on that list.
     */
    public static final ErrorCode UNSUPPORTED_SET = new ErrorCode(6204, "PGWIRE_UNSUPPORTED_SET");

    /**
     * A {@code pg_catalog} query this shim recognises as catalog introspection and cannot answer.
     *
     * <p>{@link PgCatalogShim} answers a small, named set of query shapes -- the ones a {@code
     * psql}, at the server version this gateway announces, or a JDBC driver actually sends -- and
     * refuses anything else in this family by name rather than guessing at a join or a function this
     * server has never modelled. A wrong catalog answer is worse than none: {@code psql} would print
     * it as fact.
     */
    public static final ErrorCode UNSUPPORTED_CATALOG_QUERY = new ErrorCode(6205, "PGWIRE_UNSUPPORTED_CATALOG_QUERY");

    /**
     * The five-character SQLSTATE a Pravaha failure should arrive as.
     *
     * <p>The message always carries the engine's own PRV code -- {@link PravahaException} puts it
     * at the front of every message -- but SQLSTATE is what a <em>driver</em> acts on before any
     * human reads anything: JDBC's {@code SQLTransientConnectionException}, psycopg's exception
     * hierarchy and every ORM's retry logic are switches on these five characters. Answering every
     * failure with the generic {@code XX000} means a saturated node looks like an internal error
     * and the client that should have backed off files a bug instead.
     *
     * <p>Deliberately the same taxonomy as {@code FlightErrors.statusFor}, code for code, so that
     * the two gateways cannot come to disagree about what a refusal means. Where they are updated
     * separately they will diverge; that is worth knowing about rather than hiding.
     */
    public static String sqlStateFor(PravahaException e) {
        return switch (e.errorCode().code()) {
            // 28000 invalid_authorization_specification: the credential, not the permission.
            case "PRV-7001" -> "28000";
            // 42501 insufficient_privilege. PRV-7003 -- a row filter that cannot be enforced -- is
            // here too: like PRV-7002 it means this caller may not have these rows, and neither is
            // fixed by presenting a fresh credential.
            case "PRV-7002", "PRV-7003" -> "42501";
            // 53400 configuration_limit_exceeded / 53000 insufficient_resources: admission refused
            // the read. Retryable, and saying so is the difference between a client that backs off
            // and one that hammers a node that is already full.
            case "PRV-4026", "PRV-4027", "PRV-4028" -> "53000";
            // 57014 query_canceled: a deadline stopped it mid-scan, which is what a cancel is.
            case "PRV-4021", "PRV-4029" -> "57014";
            // 42P01 undefined_table. A view is a table here, so this is the code a client's
            // "relation does not exist" handling is already written for.
            //
            // Worth knowing: this is *not* the code a client usually gets for a name that is not
            // there. Since SX-5, `SELECT * FROM no_such_view` is refused by the validator with
            // PRV-2002 ("Object not found") before the serving layer is consulted at all, so
            // PRV-4023 reaches a client only on the paths that resolve through the catalogue
            // directly. PRV-2002 deliberately does *not* map to 42P01 below: the same code is
            // thrown for an unknown column and for a type error, and telling a client "that table
            // does not exist" when the user mistyped a column name is a confident wrong answer.
            case "PRV-4023" -> "42P01";
            // 0A000 feature_not_supported, for the things this gateway and this engine decline.
            case "PRV-6200", "PRV-6201", "PRV-6203", "PRV-6204", "PRV-6205", "PRV-4025" -> "0A000";
            case "PRV-6202" -> "08P01"; // protocol_violation
            // 54000 program_limit_exceeded: the result was larger than one response may carry.
            case "PRV-4024" -> "54000";
            // Everything else is the query's fault as far as the client can tell: a name that does
            // not resolve, a shape the planner refuses, a type error. 42000 is the class, and
            // saying "syntax or access rule violation" is more honest than guessing which.
            default -> "42000";
        };
    }

    private PgWireErrors() {}
}
