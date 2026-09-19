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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.protobuf.AnyProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DurationProto;
import com.google.protobuf.EmptyProto;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.StructProto;
import com.google.protobuf.TimestampProto;
import com.google.protobuf.WrappersProto;

import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Unmappable;

/**
 * The {@code FileDescriptorSet} a deployment supplies ({@code schema.descriptor}), turned into the
 * {@link Descriptor} of one message.
 *
 * <p>A descriptor set is what {@code protoc --descriptor_set_out=x.desc --include_imports} writes: the
 * parsed form of one or more {@code .proto} files, which is exactly what {@code DynamicMessage} needs
 * to read a message with no generated class. Protobuf's binary encoding carries field <em>numbers</em>
 * and wire types and no names at all, so there is no reading it without one -- which is why this
 * format takes the descriptor from the deployment rather than from the registry, whose Protobuf
 * schemas are {@code .proto} <em>source</em> that only {@code protoc} can turn into this.
 *
 * <p>Files import each other, so each is built after the files it depends on. An import the set does
 * not carry (a set written without {@code --include_imports}) is taken from protobuf-java's own
 * descriptors when it is one of the well-known types -- {@code google/protobuf/timestamp.proto} and
 * its neighbours -- and is otherwise named in the refusal, with the flag that would have included it.
 */
final class ProtobufSchemas {

    /** The well-known files a descriptor set may leave out, from the runtime's own copies. */
    private static final Map<String, FileDescriptor> WELL_KNOWN = Map.of(
            "google/protobuf/timestamp.proto", TimestampProto.getDescriptor(),
            "google/protobuf/duration.proto", DurationProto.getDescriptor(),
            "google/protobuf/wrappers.proto", WrappersProto.getDescriptor(),
            "google/protobuf/struct.proto", StructProto.getDescriptor(),
            "google/protobuf/any.proto", AnyProto.getDescriptor(),
            "google/protobuf/empty.proto", EmptyProto.getDescriptor());

    private final Map<String, FileDescriptorProto> sources = new LinkedHashMap<>();
    private final Map<String, FileDescriptor> built = new HashMap<>();
    private final Set<String> building = new LinkedHashSet<>();

    private ProtobufSchemas() {}

    /**
     * The message called {@code messageName} in {@code descriptorSet} -- by full name, or by simple
     * name when only one message has it.
     *
     * @throws Unmappable if the bytes are not a descriptor set, an import is missing, or no message
     *     has that name (the refusal lists the ones that do)
     */
    static Descriptor message(byte[] descriptorSet, String messageName) {
        ProtobufSchemas schemas = new ProtobufSchemas();
        FileDescriptorSet set;
        try {
            set = FileDescriptorSet.parseFrom(descriptorSet);
        } catch (InvalidProtocolBufferException e) {
            throw new Unmappable("schema.descriptor is not a protobuf FileDescriptorSet: " + e.getMessage()
                    + ". Write one with: protoc --include_imports --descriptor_set_out=x.desc your.proto");
        }
        if (set.getFileCount() == 0) {
            throw new Unmappable("schema.descriptor holds no .proto file");
        }
        for (FileDescriptorProto file : set.getFileList()) {
            schemas.sources.put(file.getName(), file);
        }
        Map<String, Descriptor> messages = new LinkedHashMap<>();
        for (FileDescriptorProto file : set.getFileList()) {
            for (Descriptor message : schemas.build(file.getName()).getMessageTypes()) {
                collect(message, messages);
            }
        }
        Descriptor exact = messages.get(messageName);
        if (exact != null) {
            return exact;
        }
        List<Descriptor> bySimpleName = messages.values().stream()
                .filter(message -> message.getName().equals(messageName))
                .toList();
        if (bySimpleName.size() == 1) {
            return bySimpleName.get(0);
        }
        if (bySimpleName.size() > 1) {
            throw new Unmappable("schema.message '" + messageName + "' names "
                    + bySimpleName.stream().map(Descriptor::getFullName).toList()
                    + "; write the full name, package and all");
        }
        throw new Unmappable("schema.descriptor has no message called '" + messageName + "'. It holds "
                + List.copyOf(messages.keySet()));
    }

    private static void collect(Descriptor message, Map<String, Descriptor> into) {
        into.put(message.getFullName(), message);
        for (Descriptor nested : message.getNestedTypes()) {
            collect(nested, into);
        }
    }

    private FileDescriptor build(String name) {
        FileDescriptor done = built.get(name);
        if (done != null) {
            return done;
        }
        FileDescriptor wellKnown = WELL_KNOWN.get(name);
        FileDescriptorProto proto = sources.get(name);
        if (proto == null) {
            if (wellKnown != null) {
                built.put(name, wellKnown);
                return wellKnown;
            }
            throw new Unmappable("schema.descriptor imports '" + name + "' and does not carry it. Write the "
                    + "descriptor set with protoc --include_imports");
        }
        if (!building.add(name)) {
            throw new Unmappable("the .proto files in schema.descriptor import each other in a cycle: " + building);
        }
        List<FileDescriptor> dependencies = new ArrayList<>();
        for (String dependency : proto.getDependencyList()) {
            dependencies.add(build(dependency));
        }
        try {
            FileDescriptor file = FileDescriptor.buildFrom(proto, dependencies.toArray(new FileDescriptor[0]));
            built.put(name, file);
            building.remove(name);
            return file;
        } catch (DescriptorValidationException e) {
            throw new Unmappable(
                    "the .proto file '" + name + "' in schema.descriptor does not validate: " + e.getMessage());
        }
    }
}
