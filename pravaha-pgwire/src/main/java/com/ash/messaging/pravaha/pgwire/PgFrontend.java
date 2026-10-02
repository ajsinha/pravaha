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

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Messages arriving from a PostgreSQL client, read off the socket and no further.
 *
 * <p>Deliberately dumb. It frames bytes and it bounds them; it does not know what a query is. The
 * decision about what a message <em>means</em> is {@link PgWireConnection}'s, so that the parsing
 * of hostile input and the handling of trusted input are separate pieces of code with separate
 * tests.
 *
 * <h2>Why the caps are here</h2>
 *
 * <p>Every frontend message declares its own length before it declares anything else, so an
 * unauthenticated peer would otherwise choose the size of the array this server allocates. Every cap
 * below is enforced on the declared length, before a single byte of payload is read, and even an
 * accepted length is allocated as its bytes arrive ({@link #READ_CHUNK_BYTES} at a time), not on the
 * peer's word. PostgreSQL's own startup cap is 10000 bytes and this matches it; before authentication
 * a message may be {@link #MAX_PASSWORD_BYTES}, enough for any credential; after it, {@code
 * pravaha.pgwire.limits.max-message-size} (PGPREAUTH-1).
 */
final class PgFrontend {

    /** {@code SSLRequest}: a magic length-8 packet in place of a startup message. */
    static final int SSL_REQUEST_CODE = 80877103;

    /** {@code GSSENCRequest}: the same idea for GSSAPI encryption. */
    static final int GSSENC_REQUEST_CODE = 80877104;

    /** {@code CancelRequest}: arrives on a second connection, carrying a key from {@code BackendKeyData}. */
    static final int CANCEL_REQUEST_CODE = 80877102;

    /** Protocol 3.0, which is every PostgreSQL client since 7.4. */
    static final int PROTOCOL_VERSION_3_0 = 196_608;

    /** PostgreSQL's own limit on a startup packet. */
    static final int MAX_STARTUP_BYTES = 10_000;

    /**
     * As much as a message may be before the connection has authenticated: a {@code PasswordMessage}
     * carrying a session token, an API key or a JWT. Generous for the largest of those and far short
     * of what used to be allowed here -- 16 MiB, allocated on the word of a peer that had sent nothing
     * but a startup packet, which is how sixty sockets ran a 1 GiB node out of heap (PGPREAUTH-1).
     */
    static final int MAX_PASSWORD_BYTES = 16 * 1024;

    /**
     * How much of a message is allocated before its bytes have actually arrived. A larger message
     * grows its buffer as it is read, so a peer that declares a large message and then sends nothing
     * holds this much, not what it declared.
     */
    static final int READ_CHUNK_BYTES = 64 * 1024;

    /** The exact length of {@code SSLRequest} and {@code GSSENCRequest}: a length and a code. */
    private static final int ENCRYPTION_REQUEST_BYTES = 8;

    /** The exact length of {@code CancelRequest}: a length, a code, a process id and a key. */
    private static final int CANCEL_REQUEST_BYTES = 16;

    /** A framed frontend message: its one-byte type, and its payload with the length stripped. */
    @SuppressWarnings("ArrayRecordComponent") // carries the array; nothing compares or hashes one
    record Message(char type, byte[] payload) {

        /** The payload read as a single null-terminated string, which most of them are. */
        String asString() {
            return cstring(payload, 0);
        }

        /**
         * A cursor over this message's payload, for the extended query protocol's structured
         * bodies -- {@code Parse}, {@code Bind}, {@code Describe}, {@code Execute}, {@code Close} --
         * which are more than the one {@code cstring} {@link #asString} answers for.
         */
        MessageReader reader() {
            return new MessageReader(payload);
        }
    }

    /**
     * Reads the fields of one message's payload in order, advancing as it goes.
     *
     * <p>The protocol's own field types only: null-terminated strings, {@code int16}, {@code int32},
     * and a length-prefixed byte string with {@code -1} meaning SQL NULL (a {@code Bind} parameter's
     * own encoding). Nothing here interprets a value -- that is {@link PgTypes#decodeParameter}'s
     * job, kept separate for the same reason {@link PgFrontend} itself is dumb: parsing the shape of
     * hostile input and deciding what a value means are different kinds of mistake to make.
     */
    static final class MessageReader {

        private final byte[] payload;
        private int at;

        private MessageReader(byte[] payload) {
            this.payload = payload;
        }

        String cstring() {
            String value = PgFrontend.cstring(payload, at);
            at += value.getBytes(StandardCharsets.UTF_8).length + 1;
            return value;
        }

        /** One raw byte, as a char -- {@code Describe}/{@code Close}'s {@code 'S'}/{@code 'P'} kind tag. */
        char char8() {
            requireBytes(1);
            char value = (char) (payload[at] & 0xff);
            at += 1;
            return value;
        }

        short int16() {
            requireBytes(2);
            short value = (short) (((payload[at] & 0xff) << 8) | (payload[at + 1] & 0xff));
            at += 2;
            return value;
        }

        int int32() {
            requireBytes(4);
            int value = ((payload[at] & 0xff) << 24)
                    | ((payload[at + 1] & 0xff) << 16)
                    | ((payload[at + 2] & 0xff) << 8)
                    | (payload[at + 3] & 0xff);
            at += 4;
            return value;
        }

        /**
         * One {@code Bind} parameter value: an {@code int32} length, {@code -1} for SQL NULL,
         * followed by that many bytes for anything else -- the protocol's own encoding, so this is
         * the one composite field {@link MessageReader} knows the shape of rather than leaving to
         * the caller.
         */
        byte[] lengthPrefixedValueOrNull() {
            int length = int32();
            if (length == -1) {
                return null;
            }
            if (length < 0) {
                throw new PravahaException(
                        PgWireErrors.PROTOCOL_VIOLATION,
                        "a parameter declared a negative length (" + length + ") that is not -1; -1 is the "
                                + "protocol's only meaning for a negative length, which is SQL NULL");
            }
            requireBytes(length);
            byte[] value = java.util.Arrays.copyOfRange(payload, at, at + length);
            at += length;
            return value;
        }

        /** Whether this message has more fields, for a repeated-field loop at the tail of a body. */
        boolean hasMore() {
            return at < payload.length;
        }

        private void requireBytes(int n) {
            if (at + n > payload.length || n < 0) {
                throw new PravahaException(
                        PgWireErrors.PROTOCOL_VIOLATION, "a message ended before an expected field did");
            }
        }
    }

    /**
     * A startup packet, or one of the three magic packets that take its place.
     *
     * @param code the protocol version, or one of the {@code *_REQUEST_CODE} constants
     * @param parameters the client's startup parameters; empty for a magic packet
     */
    record Startup(int code, Map<String, String> parameters) {

        boolean isSslRequest() {
            return code == SSL_REQUEST_CODE;
        }

        boolean isGssEncRequest() {
            return code == GSSENC_REQUEST_CODE;
        }

        boolean isCancelRequest() {
            return code == CANCEL_REQUEST_CODE;
        }
    }

    private final DataInputStream in;
    private int maxMessageBytes = MAX_PASSWORD_BYTES;
    private boolean authenticated;

    /** A frontend that accepts only what an unauthenticated peer may send; see {@link #afterAuthentication}. */
    PgFrontend(InputStream in) {
        this.in = new DataInputStream(in);
    }

    /** Raises the per-message cap to the signed-in one, once the connection has authenticated. */
    void afterAuthentication(int maxMessageBytes) {
        this.maxMessageBytes = maxMessageBytes;
        this.authenticated = true;
    }

    /**
     * Reads one startup packet.
     *
     * <p>Called more than once per connection on purpose: a client that opens with {@code
     * SSLRequest} sends a real startup packet afterwards, once it has been told there is no TLS.
     */
    Startup readStartup() throws IOException {
        int length = in.readInt();
        if (length < 8 || length > MAX_STARTUP_BYTES) {
            throw new PravahaException(
                    PgWireErrors.PROTOCOL_VIOLATION,
                    "a startup packet declared " + length + " bytes; the protocol allows 8 to " + MAX_STARTUP_BYTES
                            + ". This is not a PostgreSQL client, or not a PostgreSQL port.");
        }
        int code = in.readInt();
        int expected = code == SSL_REQUEST_CODE || code == GSSENC_REQUEST_CODE
                ? ENCRYPTION_REQUEST_BYTES
                : code == CANCEL_REQUEST_CODE ? CANCEL_REQUEST_BYTES : -1;
        if (expected > 0 && length != expected) {
            // A magic packet has one length. Reading whatever else it declared would be reading an
            // unauthenticated peer's padding into memory for no reason the protocol has.
            throw new PravahaException(
                    PgWireErrors.PROTOCOL_VIOLATION,
                    "a request with code " + code + " declared " + length + " bytes; the protocol says " + expected);
        }
        byte[] rest = readPayload(length - 8);
        if (expected > 0) {
            return new Startup(code, Map.of());
        }
        return new Startup(code, parameters(rest));
    }

    /**
     * Reads one regular message, or {@code null} when the client has closed the socket.
     *
     * <p>A {@code null} rather than an exception for end of input: a client that has sent {@code
     * Terminate} and closed, and a client that just closed, are the same event as far as this
     * server is concerned, and neither is a failure worth a stack trace in an operator's log.
     */
    Message readMessage() throws IOException {
        int type = in.read();
        if (type < 0) {
            return null;
        }
        int length;
        try {
            length = in.readInt();
        } catch (EOFException truncated) {
            throw new PravahaException(
                    PgWireErrors.PROTOCOL_VIOLATION,
                    "a '" + (char) type + "' message ended before its length field did");
        }
        if (length < 4) {
            throw new PravahaException(
                    PgWireErrors.PROTOCOL_VIOLATION,
                    "a '" + (char) type + "' message declared " + length + " bytes; the length counts itself, "
                            + "so the least it can be is 4");
        }
        if (length > maxMessageBytes) {
            // Refused on the declaration, before a byte of it is read or allocated.
            throw new PravahaException(
                    PgWireErrors.MESSAGE_TOO_LARGE,
                    "a '" + (char) type + "' message declared " + length + " bytes; "
                            + (!authenticated
                                    ? "before authentication this server accepts at most " + MAX_PASSWORD_BYTES
                                    : "this server accepts at most " + maxMessageBytes
                                            + " (pravaha.pgwire.limits.max-message-size)"));
        }
        return new Message((char) type, readPayload(length - 4));
    }

    /**
     * Reads exactly {@code size} bytes, allocating as they arrive rather than all at once on the
     * peer's say-so: a buffer of at most {@link #READ_CHUNK_BYTES} to start, doubled as it fills.
     */
    private byte[] readPayload(int size) throws IOException {
        if (size <= READ_CHUNK_BYTES) {
            byte[] payload = new byte[size];
            in.readFully(payload);
            return payload;
        }
        byte[] buffer = new byte[READ_CHUNK_BYTES];
        int filled = 0;
        while (filled < size) {
            if (filled == buffer.length) {
                buffer = java.util.Arrays.copyOf(buffer, (int) Math.min(size, 2L * buffer.length));
            }
            int read = in.read(buffer, filled, buffer.length - filled);
            if (read < 0) {
                throw new EOFException("the client closed the connection " + (size - filled) + " bytes into a message");
            }
            filled += read;
        }
        return buffer;
    }

    /** Startup parameters: {@code key\0value\0} pairs, ended by an empty key. */
    private static Map<String, String> parameters(byte[] bytes) {
        Map<String, String> parameters = new LinkedHashMap<>();
        int at = 0;
        while (at < bytes.length) {
            String key = cstring(bytes, at);
            if (key.isEmpty()) {
                break;
            }
            at += key.getBytes(StandardCharsets.UTF_8).length + 1;
            if (at >= bytes.length) {
                // A key with no value at all. Refused rather than defaulted: a truncated startup
                // packet means the client and this server disagree about the framing, and carrying
                // on would mean guessing at the rest of the connection too.
                throw new PravahaException(
                        PgWireErrors.PROTOCOL_VIOLATION, "startup parameter '" + key + "' has no value");
            }
            String value = cstring(bytes, at);
            at += value.getBytes(StandardCharsets.UTF_8).length + 1;
            parameters.put(key, value);
        }
        return parameters;
    }

    /**
     * The null-terminated string starting at {@code offset}.
     *
     * <p>An unterminated string is a protocol violation and not "the rest of the buffer". Treating
     * it as the latter is how a malformed packet turns into a password that happens to match.
     */
    static String cstring(byte[] bytes, int offset) {
        int end = offset;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        if (end >= bytes.length) {
            throw new PravahaException(
                    PgWireErrors.PROTOCOL_VIOLATION, "a string in a frontend message was not null-terminated");
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }
}
