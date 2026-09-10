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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * What a prepared-statement handle contains: the statement, and once bound, its values (ADR-032).
 *
 * <p>The handle carries everything needed to run the statement, so the server keeps nothing between
 * the call that prepares one and the call that fetches its rows. That is the same choice the plain
 * statement ticket already makes, and it is worth keeping for the same reasons: there is no session
 * table to size, nothing to expire, no client that comes back after a restart to find its handle
 * gone, and no requirement that the second call reach the same node as the first.
 *
 * <p>Flight SQL is built for this. A server may hand back an <em>updated</em> handle when parameters
 * are bound, and the stock client -- which is what the JDBC driver and the Python and Go clients are
 * built on -- uses the updated one for the fetch. So statelessness here costs no compatibility.
 *
 * <p>The bound values travel as an Arrow IPC batch, the same bytes the client sent, rather than
 * being decoded and re-encoded into a format of our own. Fewer conversions is fewer places for a
 * type to change on the way through.
 */
record StatementHandle(String sql, Optional<byte[]> boundParameters) {

    /** A ceiling on the bound batch, since the handle travels on every subsequent call. */
    static final int MAX_PARAMETER_BYTES = 1 << 20;

    private static final byte VERSION = 1;

    static StatementHandle unbound(String sql) {
        return new StatementHandle(sql, Optional.empty());
    }

    StatementHandle boundTo(byte[] parameters) {
        if (parameters.length > MAX_PARAMETER_BYTES) {
            throw new PravahaException(
                    FlightErrors.PARAMETERS_TOO_LARGE,
                    "the bound parameters are " + parameters.length + " bytes, over the "
                            + MAX_PARAMETER_BYTES + "-byte ceiling. A handle travels on every call that "
                            + "uses it, so a large binding is paid for repeatedly; send the values as data "
                            + "rather than as parameters");
        }
        return new StatementHandle(sql, Optional.of(parameters));
    }

    byte[] encode() {
        byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
        byte[] params = boundParameters.orElse(new byte[0]);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + sqlBytes.length + 4 + params.length);
        buffer.put(VERSION);
        buffer.putInt(sqlBytes.length);
        buffer.put(sqlBytes);
        buffer.putInt(params.length);
        buffer.put(params);
        return buffer.array();
    }

    static StatementHandle decode(byte[] bytes) {
        // A handle a client returns is a handle this server issued, but it arrives over a network
        // and may have been truncated, cached wrong, or kept across an upgrade. Every read is
        // bounds-checked, and a malformed one is a refusal rather than an exception with an index
        // in it.
        try {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            byte version = buffer.get();
            if (version != VERSION) {
                throw new PravahaException(
                        FlightErrors.BAD_HANDLE,
                        "this handle was issued by a different version of the server; prepare the "
                                + "statement again");
            }
            int sqlLength = buffer.getInt();
            if (sqlLength < 0 || sqlLength > buffer.remaining()) {
                throw new PravahaException(FlightErrors.BAD_HANDLE, "this prepared-statement handle is malformed");
            }
            byte[] sqlBytes = new byte[sqlLength];
            buffer.get(sqlBytes);
            int paramLength = buffer.getInt();
            if (paramLength < 0 || paramLength > buffer.remaining()) {
                throw new PravahaException(FlightErrors.BAD_HANDLE, "this prepared-statement handle is malformed");
            }
            String sql = new String(sqlBytes, StandardCharsets.UTF_8);
            if (paramLength == 0) {
                return unbound(sql);
            }
            byte[] params = new byte[paramLength];
            buffer.get(params);
            return new StatementHandle(sql, Optional.of(params));
        } catch (PravahaException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PravahaException(FlightErrors.BAD_HANDLE, "this prepared-statement handle is malformed", e);
        }
    }
}
