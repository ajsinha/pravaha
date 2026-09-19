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
package com.ash.messaging.pravaha.api.data;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The defaults {@link RowWriter} gives every implementation: {@code setUnread} and {@code rowKind}. */
class RowWriterTest {

    private static StreamSchema notNull(PravahaType... types) {
        StreamSchema.Builder b = StreamSchema.builder("s");
        for (int i = 0; i < types.length; i++) {
            b.field("c" + i, types[i].withNullable(false));
        }
        return b.build();
    }

    /** A writer that records each abstract call as "method(args)" and runs the defaults for real. */
    private static RowWriter recording(StreamSchema schema, List<String> calls) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            if (method.getName().equals("schema")) {
                return schema;
            }
            calls.add(method.getName() + Arrays.deepToString(args));
            return proxy;
        };
        return (RowWriter)
                Proxy.newProxyInstance(RowWriter.class.getClassLoader(), new Class<?>[] {RowWriter.class}, handler);
    }

    @Test
    void anUnreadNullableColumnIsNull() {
        List<String> calls = new ArrayList<>();
        StreamSchema schema = StreamSchema.builder("s")
                .field("a", Types.int64().withNullable(true))
                .build();
        recording(schema, calls).setUnread(0);
        assertThat(calls).containsExactly("setNull[0]");
    }

    @Test
    void anUnreadNotNullColumnHoldsItsTypesZero() {
        List<String> calls = new ArrayList<>();
        StreamSchema schema = notNull(
                Types.bool(),
                Types.int8(),
                Types.int16(),
                Types.int32(),
                Types.date(),
                Types.int64(),
                Types.time(),
                Types.timestamp(),
                Types.float32(),
                Types.float64(),
                Types.decimal(18, 4),
                Types.string(),
                Types.bytes());
        RowWriter writer = recording(schema, calls);
        for (int i = 0; i < schema.fields().size(); i++) {
            writer.setUnread(i);
        }
        assertThat(calls)
                .containsExactly(
                        "setBoolean[0, false]",
                        "setByte[1, 0]",
                        "setShort[2, 0]",
                        "setInt[3, 0]",
                        "setInt[4, 0]",
                        "setLong[5, 0]",
                        "setLong[6, 0]",
                        "setLong[7, 0]",
                        "setFloat[8, 0.0]",
                        "setDouble[9, 0.0]",
                        "setDecimal[10, 0, 0]",
                        "setString[11, ]",
                        "setBytes[12, []]");
    }

    @Test
    void rowKindWritesItsWeight() {
        List<String> calls = new ArrayList<>();
        recording(notNull(Types.int32()), calls).rowKind(RowKind.DELETE);
        assertThat(calls).containsExactly("weight[-1]");
    }
}
