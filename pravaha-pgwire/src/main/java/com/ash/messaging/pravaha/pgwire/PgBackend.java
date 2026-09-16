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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.ash.messaging.pravaha.api.data.Field;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;

/**
 * Messages this server sends to a PostgreSQL client.
 *
 * <p>Every message is built whole in memory and then written, rather than streamed field by field
 * onto the socket. The length prefix comes first and is only knowable once the body exists; the
 * alternative is to seek backwards over a socket, which is not a thing. A {@code DataRow} is small
 * and this costs one array per row -- the same trade {@code ValueCollectingWriter} makes one layer
 * down, and for the same reason: a request/response query is dominated by the client's round trip.
 *
 * <p><strong>Text format only.</strong> Every {@code RowDescription} declares format code 0 and
 * every {@code DataRow} honours it. Binary format is an optimisation for a later slice; declaring
 * text and sending binary is the specific way to make a driver read a float as garbage.
 */
final class PgBackend {

    // Backend message types, by their protocol letters, so that a reader can match this code
    // against the protocol documentation without a decoder ring.
    private static final char AUTHENTICATION = 'R';
    private static final char PARAMETER_STATUS = 'S';
    private static final char BACKEND_KEY_DATA = 'K';
    private static final char READY_FOR_QUERY = 'Z';
    private static final char ROW_DESCRIPTION = 'T';
    private static final char DATA_ROW = 'D';
    private static final char COMMAND_COMPLETE = 'C';
    private static final char EMPTY_QUERY_RESPONSE = 'I';
    private static final char ERROR_RESPONSE = 'E';
    private static final char NOTICE_RESPONSE = 'N';

    // The extended query protocol's own replies, one per frontend message they answer.
    private static final char PARSE_COMPLETE = '1';
    private static final char BIND_COMPLETE = '2';
    private static final char CLOSE_COMPLETE = '3';
    private static final char PARAMETER_DESCRIPTION = 't';
    private static final char NO_DATA = 'n';
    private static final char PORTAL_SUSPENDED = 's';

    /** The authentication sub-codes this server uses. The rest of the family is not implemented. */
    private static final int AUTH_OK = 0;

    private static final int AUTH_CLEARTEXT_PASSWORD = 3;

    /** Text, as opposed to binary. Slice 1 sends nothing else. */
    static final short FORMAT_TEXT = 0;

    /** {@code ReadyForQuery}'s transaction status: idle, and this server is always idle. */
    static final char STATUS_IDLE = 'I';

    private final OutputStream out;

    PgBackend(OutputStream out) {
        this.out = out;
    }

    /**
     * Declines an {@code SSLRequest} with the protocol's single byte {@code 'N'}.
     *
     * <p>Not an error, and not silence. {@code 'N'} is the protocol's own "encryption not offered",
     * and a client that gets it retries in plaintext immediately -- which is what makes {@code psql}
     * connect to this server without {@code sslmode=disable} on the command line. Closing the
     * socket instead, or sending an {@code ErrorResponse} here, both produce a hang or a confusing
     * failure at a point in the handshake where the client is not yet expecting either.
     *
     * <p>This byte has no length prefix. Nothing in the handshake does until the client's startup
     * packet arrives.
     */
    void declineEncryption() throws IOException {
        out.write('N');
        out.flush();
    }

    /**
     * Accepts an {@code SSLRequest} with the protocol's single byte {@code 'S'}.
     *
     * <p>The counterpart to {@link #declineEncryption()}, sent only when this server has a
     * certificate configured. {@code 'S'} is the client's signal to stop speaking plaintext on this
     * socket and start a TLS handshake instead -- {@code PgWireConnection} layers an {@link
     * javax.net.ssl.SSLSocket} over the connection immediately after this byte is flushed, before
     * reading anything else.
     */
    void acceptEncryption() throws IOException {
        out.write('S');
        out.flush();
    }

    void authenticationOk() throws IOException {
        send(AUTHENTICATION, body -> body.writeInt(AUTH_OK));
    }

    void authenticationCleartextPassword() throws IOException {
        send(AUTHENTICATION, body -> body.writeInt(AUTH_CLEARTEXT_PASSWORD));
    }

    void parameterStatus(String name, String value) throws IOException {
        send(PARAMETER_STATUS, body -> {
            cstring(body, name);
            cstring(body, value);
        });
    }

    /**
     * The process id and secret a {@code CancelRequest} would carry.
     *
     * <p>Sent because clients expect it -- several drivers treat its absence as a broken handshake
     * -- and honoured by nothing: this slice does not implement cancellation. The secret is still
     * unpredictable rather than zero, so that the day cancellation does arrive, the wire format
     * does not have to change and no deployment is left with a cancel key that anybody can guess.
     */
    void backendKeyData(int processId, int secret) throws IOException {
        send(BACKEND_KEY_DATA, body -> {
            body.writeInt(processId);
            body.writeInt(secret);
        });
    }

    void readyForQuery(char status) throws IOException {
        send(READY_FOR_QUERY, body -> body.writeByte(status));
        out.flush();
    }

    /**
     * The shape of the rows that follow.
     *
     * <p>Table OID and column attribute number go out as zero, which the protocol defines as "not a
     * column of a table". They are real values in PostgreSQL and would be a fiction here: a view's
     * column has no {@code pg_attribute} row to point at, and inventing numbers would let a client
     * that looks them up find something else entirely.
     */
    void rowDescription(StreamSchema schema) throws IOException {
        send(ROW_DESCRIPTION, body -> {
            body.writeShort(schema.fields().size());
            for (Field field : schema.fields()) {
                cstring(body, field.name());
                body.writeInt(0); // table OID: not a table
                body.writeShort(0); // column attribute number: not a table
                body.writeInt(PgTypes.oidOf(field));
                body.writeShort(PgTypes.typeSizeOf(field.type().typeName()));
                body.writeInt(PgTypes.typeModifierOf(field));
                body.writeShort(FORMAT_TEXT);
            }
        });
    }

    /** No rows: what {@link #rowDescription} answers with for a statement that returns none. */
    void noData() throws IOException {
        send(NO_DATA, body -> {});
    }

    /**
     * The type of each placeholder a prepared statement needs bound, in order -- {@code Describe}'s
     * answer for a statement, always sent immediately before that same call's {@link
     * #rowDescription}/{@link #noData}.
     *
     * <p>OIDs from {@link PgTypes#oidOf(TypeName)}, the same mapping {@link #rowDescription} uses
     * for output columns: a placeholder's type and a column's type are the same question asked in
     * the two directions, and answering it two different ways would be a way for them to disagree.
     */
    void parameterDescription(List<TypeName> types) throws IOException {
        send(PARAMETER_DESCRIPTION, body -> {
            body.writeShort(types.size());
            for (TypeName type : types) {
                body.writeInt(PgTypes.oidOf(type));
            }
        });
    }

    /** {@code Parse} succeeded: the statement is planned and named. */
    void parseComplete() throws IOException {
        send(PARSE_COMPLETE, body -> {});
    }

    /** {@code Bind} succeeded: the portal exists, planned and parameterised. */
    void bindComplete() throws IOException {
        send(BIND_COMPLETE, body -> {});
    }

    /** {@code Close} succeeded, whether or not the name it closed existed -- see {@code PgExtendedSession}. */
    void closeComplete() throws IOException {
        send(CLOSE_COMPLETE, body -> {});
    }

    /**
     * {@code Execute}'s row limit was reached with more rows still to come. The client's own signal
     * to send another {@code Execute} for the same portal rather than treat this as the whole
     * answer -- {@link #commandComplete} is what says "that was all of it".
     */
    void portalSuspended() throws IOException {
        send(PORTAL_SUSPENDED, body -> {});
    }

    /** One row, text format, {@code -1} for a NULL. */
    void dataRow(Object[] values, StreamSchema schema) throws IOException {
        send(DATA_ROW, body -> {
            body.writeShort(values.length);
            for (int ordinal = 0; ordinal < values.length; ordinal++) {
                TypeName type = schema.field(ordinal).type().typeName();
                byte[] encoded = PgTypes.encode(type, values[ordinal]);
                if (encoded == null) {
                    // -1, the protocol's NULL. Distinct from a zero length, which is the empty
                    // string: two different values that a client must be able to tell apart.
                    body.writeInt(-1);
                } else {
                    body.writeInt(encoded.length);
                    body.write(encoded);
                }
            }
        });
    }

    /** {@code SELECT n}, the tag a client reads a row count out of. */
    void commandComplete(String tag) throws IOException {
        send(COMMAND_COMPLETE, body -> cstring(body, tag));
    }

    /** The answer to an empty query string, which is not an error and is not a result either. */
    void emptyQueryResponse() throws IOException {
        send(EMPTY_QUERY_RESPONSE, body -> {});
    }

    /**
     * A failure, carrying the SQLSTATE a driver switches on and the PRV code an operator searches
     * for.
     *
     * <p>Both, because they are read by different readers. {@code message} arrives with the engine's
     * own {@code PRV-} code already at the front of it -- {@link
     * com.ash.messaging.pravaha.api.PravahaException} puts it there -- and it is also repeated in
     * the {@code 'D'} detail field so that a client which shows only the primary message still lets
     * a person copy the code out of the detail line.
     *
     * @param severity {@code ERROR} for something the connection survives, {@code FATAL} for
     *     something it does not
     */
    void errorResponse(String severity, String sqlState, String message, String detail) throws IOException {
        send(ERROR_RESPONSE, body -> {
            field(body, 'S', severity);
            // 'V' is the never-localized severity, added in protocol 3.0's later revisions. A client
            // that localizes 'S' needs this one to switch on.
            field(body, 'V', severity);
            field(body, 'C', sqlState);
            field(body, 'M', message);
            if (detail != null && !detail.isBlank()) {
                field(body, 'D', detail);
            }
            body.writeByte(0); // no more fields
        });
        out.flush();
    }

    /** A remark that is not a failure; the connection carries on. */
    void noticeResponse(String message) throws IOException {
        send(NOTICE_RESPONSE, body -> {
            field(body, 'S', "NOTICE");
            field(body, 'V', "NOTICE");
            field(body, 'C', "00000");
            field(body, 'M', message);
            body.writeByte(0);
        });
        out.flush();
    }

    void flush() throws IOException {
        out.flush();
    }

    // -------------------------------------------------------------------------------------
    // Framing.

    /** What a message body is written by. Checked exceptions are the only reason this is named. */
    @FunctionalInterface
    private interface Body {
        void write(DataOutputStream out) throws IOException;
    }

    /**
     * Writes one message: type byte, then a length that counts itself, then the body.
     *
     * <p>The length includes its own four bytes and excludes the type byte. That off-by-one is the
     * single most common way to write this protocol wrong, so it is written once, here, and every
     * message goes through it.
     */
    private void send(char type, Body body) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
        DataOutputStream data = new DataOutputStream(buffer);
        body.write(data);
        data.flush();
        byte[] payload = buffer.toByteArray();
        DataOutputStream message = new DataOutputStream(out);
        message.writeByte(type);
        message.writeInt(payload.length + 4);
        message.write(payload);
        message.flush();
    }

    private static void cstring(DataOutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.writeByte(0);
    }

    private static void field(DataOutputStream out, char code, String value) throws IOException {
        out.writeByte(code);
        cstring(out, value);
    }
}
