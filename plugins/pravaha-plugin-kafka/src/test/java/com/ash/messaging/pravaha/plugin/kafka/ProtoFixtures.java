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
package com.ash.messaging.pravaha.plugin.kafka;

import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.TimestampProto;

/**
 * The descriptor set a {@code format: protobuf} test reads with, built here with {@code
 * DescriptorProtos} rather than checked in as a {@code .desc} file: a test that writes its own
 * schema is a test nobody has to regenerate, and the build needs no {@code protoc}.
 *
 * <p>It is the same bytes {@code protoc --include_imports --descriptor_set_out} writes -- a
 * {@code FileDescriptorSet} -- which is what the plugin reads in production.
 */
final class ProtoFixtures {

    private ProtoFixtures() {}

    static final String ORDER = "pravaha.test.Order";

    /**
     * {@code pravaha.test.Order}: one field of every kind the source maps, plus a repeated field no
     * column names and a proto3 {@code optional} one, which is the only kind of scalar that can be
     * absent.
     */
    static FileDescriptorProto orderFile() {
        EnumDescriptorProto colour = EnumDescriptorProto.newBuilder()
                .setName("Colour")
                .addValue(EnumValueDescriptorProto.newBuilder().setName("RED").setNumber(0))
                .addValue(EnumValueDescriptorProto.newBuilder().setName("GREEN").setNumber(1))
                .addValue(EnumValueDescriptorProto.newBuilder().setName("BLUE").setNumber(2))
                .build();
        var order = com.google.protobuf.DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Order")
                .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_INT64))
                .addField(field("name", 2, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(field("flag", 3, FieldDescriptorProto.Type.TYPE_BOOL))
                .addField(field("ratio", 4, FieldDescriptorProto.Type.TYPE_FLOAT))
                .addField(field("weight", 5, FieldDescriptorProto.Type.TYPE_DOUBLE))
                .addField(field("blob", 6, FieldDescriptorProto.Type.TYPE_BYTES))
                .addField(field("colour", 7, FieldDescriptorProto.Type.TYPE_ENUM).toBuilder()
                        .setTypeName(".pravaha.test.Order.Colour")
                        .build())
                .addField(field("amount", 8, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(field("day", 9, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(field("clock", 10, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(field("at", 11, FieldDescriptorProto.Type.TYPE_MESSAGE).toBuilder()
                        .setTypeName(".google.protobuf.Timestamp")
                        .build())
                .addField(field("count", 12, FieldDescriptorProto.Type.TYPE_UINT32))
                .addField(field("big", 13, FieldDescriptorProto.Type.TYPE_UINT64))
                .addField(field("maybe", 14, FieldDescriptorProto.Type.TYPE_STRING).toBuilder()
                        .setProto3Optional(true)
                        .setOneofIndex(0)
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("tags")
                        .setNumber(15)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                        .build())
                .addOneofDecl(OneofDescriptorProto.newBuilder().setName("_maybe"))
                .addEnumType(colour)
                .build();
        return FileDescriptorProto.newBuilder()
                .setName("pravaha/test/order.proto")
                .setSyntax("proto3")
                .setPackage("pravaha.test")
                .addDependency("google/protobuf/timestamp.proto")
                .addMessageType(order)
                .build();
    }

    /** The bytes of a descriptor set holding {@code files}, and the well-known timestamp file. */
    static byte[] descriptorSet(boolean includeImports, FileDescriptorProto... files) {
        FileDescriptorSet.Builder set = FileDescriptorSet.newBuilder();
        if (includeImports) {
            set.addFile(TimestampProto.getDescriptor().toProto());
        }
        for (FileDescriptorProto file : files) {
            set.addFile(file);
        }
        return set.build().toByteArray();
    }

    /** The order file's descriptor set, imports included, as {@code protoc} would write it. */
    static byte[] orderDescriptorSet() {
        return descriptorSet(true, orderFile());
    }

    private static FieldDescriptorProto field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }
}
