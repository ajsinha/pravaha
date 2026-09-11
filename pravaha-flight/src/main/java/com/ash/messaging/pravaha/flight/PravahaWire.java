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
import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * The framing for Pravaha's own Flight actions and tickets.
 *
 * <p>Flight SQL has no vocabulary for "register a continuous query" or "subscribe to one" -- it was
 * designed for asking questions, not for standing up computations. Those arrive as Flight
 * <em>actions</em> and a Flight <em>ticket</em>, which is the extension point the protocol provides
 * for exactly this, so the transport stays one protocol rather than two.
 *
 * <p>The payload is a list of UTF-8 strings behind a magic number and a version, and it is
 * hand-framed rather than protobuf on purpose. The Python SDK carries no protobuf runtime -- that is
 * a deliberate promise about what installing this client costs somebody -- and a format that is
 * four lines to write in either language keeps that promise without a code generator in the build.
 *
 * <p>The magic matters for a second reason: {@code getStream} has to tell a Pravaha subscription
 * ticket from a Flight SQL one, and guessing by trying to parse it as protobuf and seeing what
 * happens is not telling.
 */
final class PravahaWire {

    /** "PRVH" -- lets getStream recognise our tickets without parsing them as something else. */
    static final int MAGIC = 0x50525648;

    static final byte VERSION = 1;

    /** Actions this server answers beyond Flight SQL's own. */
    static final String REGISTER = "pravaha.register";

    static final String DROP = "pravaha.drop";

    static final String LIST = "pravaha.list";

    static final String PAUSE = "pravaha.pause";

    static final String RESUME = "pravaha.resume";

    private PravahaWire() {}

    /** Encodes a list of strings. Nulls are encoded as absent and decode as empty. */
    static byte[] encode(List<String> fields) {
        int size = 4 + 1 + 4;
        List<byte[]> encoded = new ArrayList<>(fields.size());
        for (String field : fields) {
            byte[] bytes = (field == null ? "" : field).getBytes(StandardCharsets.UTF_8);
            encoded.add(bytes);
            size += 4 + bytes.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.putInt(MAGIC);
        buffer.put(VERSION);
        buffer.putInt(encoded.size());
        for (byte[] bytes : encoded) {
            buffer.putInt(bytes.length);
            buffer.put(bytes);
        }
        return buffer.array();
    }

    static byte[] encode(String... fields) {
        return encode(List.of(fields));
    }

    /** True if these bytes are ours, without attempting to parse them as anything else. */
    static boolean isOurs(byte[] bytes) {
        return bytes != null && bytes.length >= 5 && ByteBuffer.wrap(bytes).getInt() == MAGIC;
    }

    /**
     * Decodes, bounds-checking every read.
     *
     * <p>These bytes crossed a network and may have been truncated, cached wrong, or kept across an
     * upgrade. A malformed payload is a refusal with a code, never an exception with an array index
     * in it.
     */
    static List<String> decode(byte[] bytes) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            if (buffer.remaining() < 9 || buffer.getInt() != MAGIC) {
                throw new PravahaException(FlightErrors.BAD_HANDLE, "this is not a Pravaha request");
            }
            byte version = buffer.get();
            if (version != VERSION) {
                throw new PravahaException(
                        FlightErrors.BAD_HANDLE,
                        "this request was built by a different version of the client; upgrade one of them");
            }
            int count = buffer.getInt();
            if (count < 0 || count > 1024) {
                throw new PravahaException(FlightErrors.BAD_HANDLE, "this Pravaha request is malformed");
            }
            List<String> fields = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int length = buffer.getInt();
                if (length < 0 || length > buffer.remaining()) {
                    throw new PravahaException(FlightErrors.BAD_HANDLE, "this Pravaha request is malformed");
                }
                byte[] field = new byte[length];
                buffer.get(field);
                fields.add(new String(field, StandardCharsets.UTF_8));
            }
            return fields;
        } catch (PravahaException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PravahaException(FlightErrors.BAD_HANDLE, "this Pravaha request is malformed", e);
        }
    }

    /** The ticket a subscriber returns with: a view name, then alternating filter column and value. */
    static byte[] subscribeTicket(String view, List<String> filterPairs) {
        List<String> fields = new ArrayList<>();
        fields.add("subscribe");
        fields.add(view);
        fields.addAll(filterPairs);
        return encode(fields);
    }
}
