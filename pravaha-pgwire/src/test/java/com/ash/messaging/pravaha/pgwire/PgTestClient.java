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
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A PostgreSQL client that speaks bytes, for driving the server over a real socket.
 *
 * <p><strong>What this is and is not.</strong> It is not a second implementation of the protocol
 * that happens to agree with the first: it knows nothing about {@link PgBackend} and imports
 * nothing from it. It reads a type byte and a length and hands back the raw payload, and every
 * assertion about what is <em>in</em> that payload is written out by hand in the test, against the
 * PostgreSQL protocol documentation rather than against this repository's idea of it. A test that
 * decoded with the same code that encoded would pass just as happily if both were wrong in the same
 * way, which is the specific failure this avoids.
 *
 * <p>It is still not {@code psql}. See {@code PsqlSessionTest} for the test that is, and which runs
 * only where {@code psql} is installed.
 */
final class PgTestClient implements AutoCloseable {

    /** One backend message, framed and no further interpreted. */
    record Message(char type, byte[] payload) {

        /** The payload as a sequence of null-terminated strings. */
        List<String> strings() {
            List<String> values = new ArrayList<>();
            int at = 0;
            while (at < payload.length) {
                int end = at;
                while (end < payload.length && payload[end] != 0) {
                    end++;
                }
                values.add(new String(payload, at, end - at, StandardCharsets.UTF_8));
                at = end + 1;
            }
            return values;
        }

        int int32At(int offset) {
            return ((payload[offset] & 0xff) << 24)
                    | ((payload[offset + 1] & 0xff) << 16)
                    | ((payload[offset + 2] & 0xff) << 8)
                    | (payload[offset + 3] & 0xff);
        }

        short int16At(int offset) {
            return (short) (((payload[offset] & 0xff) << 8) | (payload[offset + 1] & 0xff));
        }
    }

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;

    PgTestClient(int port) throws IOException {
        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        this.socket.setSoTimeout(15_000);
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
    }

    /** Sends an SSLRequest and returns the single byte the server answers with. */
    char sslRequest() throws IOException {
        out.writeInt(8);
        out.writeInt(80877103);
        out.flush();
        return (char) in.readUnsignedByte();
    }

    /** Sends a CancelRequest, which this server is expected to answer by closing. */
    void cancelRequest(int processId, int secret) throws IOException {
        out.writeInt(16);
        out.writeInt(80877102);
        out.writeInt(processId);
        out.writeInt(secret);
        out.flush();
    }

    /** Sends a protocol 3.0 startup packet with the given parameters. */
    void startup(Map<String, String> parameters) throws IOException {
        startup(196_608, parameters);
    }

    void startup(int protocolVersion, Map<String, String> parameters) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(body);
        data.writeInt(protocolVersion);
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            cstring(data, parameter.getKey());
            cstring(data, parameter.getValue());
        }
        data.writeByte(0);
        data.flush();
        out.writeInt(body.size() + 4);
        out.write(body.toByteArray());
        out.flush();
    }

    /** Sends a PasswordMessage. */
    void password(String password) throws IOException {
        sendTyped('p', password);
    }

    /** Sends a simple Query. */
    void query(String sql) throws IOException {
        sendTyped('Q', sql);
    }

    /** Sends a Parse, which this slice is expected to refuse by name. */
    void parse(String name, String sql) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(body);
        cstring(data, name);
        cstring(data, sql);
        data.writeShort(0);
        data.flush();
        sendTyped('P', body.toByteArray());
    }

    void terminate() throws IOException {
        sendTyped('X', new byte[0]);
    }

    /** Raw bytes, for the tests that want to see what a malformed frame does. */
    void raw(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    /** Reads one backend message, or {@code null} at end of stream. */
    Message read() throws IOException {
        int type = in.read();
        if (type < 0) {
            return null;
        }
        int length = in.readInt();
        byte[] payload = new byte[length - 4];
        in.readFully(payload);
        return new Message((char) type, payload);
    }

    /** Reads messages up to and including the next ReadyForQuery. */
    List<Message> readUntilReady() throws IOException {
        List<Message> messages = new ArrayList<>();
        Message message;
        while ((message = read()) != null) {
            messages.add(message);
            if (message.type() == 'Z') {
                return messages;
            }
        }
        return messages;
    }

    /** Everything from AuthenticationOk through the first ReadyForQuery. */
    List<Message> readHandshake() throws IOException {
        return readUntilReady();
    }

    /** The ParameterStatus values the server announced, by name. */
    static Map<String, String> parameterStatuses(List<Message> messages) {
        Map<String, String> statuses = new LinkedHashMap<>();
        for (Message message : messages) {
            if (message.type() == 'S') {
                List<String> pair = message.strings();
                statuses.put(pair.get(0), pair.size() > 1 ? pair.get(1) : "");
            }
        }
        return statuses;
    }

    /** The messages of one type, in order. */
    static List<Message> ofType(List<Message> messages, char type) {
        return messages.stream().filter(m -> m.type() == type).toList();
    }

    /** The letters of the messages, as a string, which is the cheapest way to assert a sequence. */
    static String shape(List<Message> messages) {
        StringBuilder shape = new StringBuilder();
        messages.forEach(m -> shape.append(m.type()));
        return shape.toString();
    }

    /**
     * A DataRow's column values as text, with {@code null} for a SQL NULL.
     *
     * <p>Decoded here rather than by the server's own code: Int16 count, then per column an Int32
     * length where -1 means NULL, then that many bytes. Written from the protocol, not from
     * {@link PgBackend}.
     */
    static List<String> columns(Message dataRow) {
        int count = dataRow.int16At(0);
        List<String> values = new ArrayList<>(count);
        int at = 2;
        for (int i = 0; i < count; i++) {
            int length = dataRow.int32At(at);
            at += 4;
            if (length == -1) {
                values.add(null);
            } else {
                values.add(new String(dataRow.payload(), at, length, StandardCharsets.UTF_8));
                at += length;
            }
        }
        return values;
    }

    /** One column of a RowDescription. */
    record Described(
            String name, int tableOid, short column, int typeOid, short typeSize, int modifier, short format) {}

    /** A RowDescription's columns, decoded from the protocol's own field layout. */
    static List<Described> described(Message rowDescription) {
        int count = rowDescription.int16At(0);
        List<Described> columns = new ArrayList<>(count);
        int at = 2;
        for (int i = 0; i < count; i++) {
            int end = at;
            byte[] payload = rowDescription.payload();
            while (payload[end] != 0) {
                end++;
            }
            String name = new String(payload, at, end - at, StandardCharsets.UTF_8);
            at = end + 1;
            columns.add(new Described(
                    name,
                    rowDescription.int32At(at),
                    rowDescription.int16At(at + 4),
                    rowDescription.int32At(at + 6),
                    rowDescription.int16At(at + 10),
                    rowDescription.int32At(at + 12),
                    rowDescription.int16At(at + 16)));
            at += 18;
        }
        return columns;
    }

    /** An ErrorResponse's fields, keyed by their one-letter codes. */
    static Map<Character, String> errorFields(Message error) {
        Map<Character, String> fields = new LinkedHashMap<>();
        byte[] payload = error.payload();
        int at = 0;
        while (at < payload.length && payload[at] != 0) {
            char code = (char) payload[at];
            int end = ++at;
            while (end < payload.length && payload[end] != 0) {
                end++;
            }
            fields.put(code, new String(payload, at, end - at, StandardCharsets.UTF_8));
            at = end + 1;
        }
        return fields;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private void sendTyped(char type, String body) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(buffer);
        cstring(data, body);
        data.flush();
        sendTyped(type, buffer.toByteArray());
    }

    private void sendTyped(char type, byte[] body) throws IOException {
        out.writeByte(type);
        out.writeInt(body.length + 4);
        out.write(body);
        out.flush();
    }

    private static void cstring(DataOutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.writeByte(0);
    }
}
