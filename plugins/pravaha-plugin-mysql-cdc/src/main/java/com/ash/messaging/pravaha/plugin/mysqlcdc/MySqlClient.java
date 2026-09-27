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
package com.ash.messaging.pravaha.plugin.mysqlcdc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.github.shyiko.mysql.binlog.network.Authenticator;
import com.github.shyiko.mysql.binlog.network.ServerException;
import com.github.shyiko.mysql.binlog.network.protocol.ErrorPacket;
import com.github.shyiko.mysql.binlog.network.protocol.GreetingPacket;
import com.github.shyiko.mysql.binlog.network.protocol.PacketChannel;
import com.github.shyiko.mysql.binlog.network.protocol.ResultSetRowPacket;
import com.github.shyiko.mysql.binlog.network.protocol.command.QueryCommand;

/**
 * A plain MySQL connection for the few text queries this plugin asks -- server variables, grants,
 * the table's columns, binlog positions -- over the binlog library's own protocol classes, so the
 * plugin needs no JDBC driver. Result values arrive as text; a query here never selects a NULL.
 */
final class MySqlClient implements AutoCloseable {

    private final PacketChannel channel;

    private MySqlClient(PacketChannel channel) {
        this.channel = channel;
    }

    static MySqlClient connect(MySqlCdcOptions options) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(options.host(), options.port()), (int)
                options.startTimeout().toMillis());
        socket.setSoTimeout((int) Math.max(1_000L, options.startTimeout().toMillis()));
        PacketChannel channel = new PacketChannel(socket);
        try {
            byte[] first = channel.read();
            if (first[0] == (byte) 0xFF) {
                throw error(first);
            }
            new Authenticator(new GreetingPacket(first), channel, null, options.user(), options.password())
                    .authenticate();
            channel.authenticationComplete();
            return new MySqlClient(channel);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    /** Runs one statement and returns its rows, each as its columns' text. */
    List<String[]> query(String sql) throws IOException {
        channel.write(new QueryCommand(sql));
        byte[] packet = channel.read();
        if (packet[0] == (byte) 0xFF) {
            throw error(packet);
        }
        List<String[]> rows = new ArrayList<>();
        if (packet[0] == 0x00) {
            return rows;
        }
        while (!isEof(channel.read())) {
            // Column definitions; the queries here know their columns by position.
        }
        for (packet = channel.read(); !isEof(packet); packet = channel.read()) {
            if (packet[0] == (byte) 0xFF) {
                throw error(packet);
            }
            rows.add(new ResultSetRowPacket(packet).getValues());
        }
        return rows;
    }

    /** The first column of the first row, or empty when there is none. */
    String single(String sql) throws IOException {
        List<String[]> rows = query(sql);
        return rows.isEmpty() ? "" : rows.get(0)[0];
    }

    private static boolean isEof(byte[] packet) {
        return packet[0] == (byte) 0xFE && packet.length < 9;
    }

    private static ServerException error(byte[] packet) throws IOException {
        ErrorPacket error = new ErrorPacket(Arrays.copyOfRange(packet, 1, packet.length));
        return new ServerException(error.getErrorMessage(), error.getErrorCode(), error.getSqlState());
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Closing; a connection already gone owes nothing.
        }
    }
}
