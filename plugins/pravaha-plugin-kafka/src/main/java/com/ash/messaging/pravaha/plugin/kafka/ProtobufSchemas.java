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
import java.util.Objects;
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
 * The {@code FileDescriptorSet} a deployment supplies ({@code schema.descriptor}), or the serialized
 * descriptors a schema registry serves ({@link #registryMessage}), turned into the {@link Descriptor}
 * of one message.
 *
 * <p>A descriptor set is what {@code protoc --descriptor_set_out=x.desc --include_imports} writes: the
 * parsed form of one or more {@code .proto} files, which is exactly what {@code DynamicMessage} needs
 * to read a message with no generated class. Protobuf's binary encoding carries field <em>numbers</em>
 * and wire types and no names at all, so there is no reading it without one. A registry's {@code .proto}
 * <em>source</em> is never parsed here: the registry path asks for the serialized form instead.
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
    /** Where the files came from, as a refusal names it. */
    private final String origin;
    /** What would have carried a missing import, said after the refusal. */
    private final String fix;

    private ProtobufSchemas() {
        this("schema.descriptor", "Write the descriptor set with protoc --include_imports");
    }

    private ProtobufSchemas(String origin, String fix) {
        this.origin = origin;
        this.fix = fix;
    }

    /**
     * The message a registry-framed record names: the file {@code root} of {@code files} (keyed by the
     * name the importing files use), and in it the message the record's Confluent message-index array
     * selects -- the first index into the file's top-level messages, each next one into the nested
     * messages of the one before.
     *
     * @throws Unmappable if a file does not build, an import is missing, or the indexes select nothing
     */
    static Descriptor registryMessage(
            Map<String, FileDescriptorProto> files, String root, List<Integer> indexes, String origin) {
        ProtobufSchemas schemas = new ProtobufSchemas(
                origin, "The registry lists every import as a reference; this one was not among them");
        schemas.sources.putAll(files);
        FileDescriptor file = schemas.build(root);
        if (indexes.isEmpty()) {
            throw new Unmappable(origin + ": the record's message-index array is empty");
        }
        List<Descriptor> level = file.getMessageTypes();
        Descriptor selected = null;
        for (int index : indexes) {
            if (index < 0 || index >= level.size()) {
                throw new Unmappable(origin + ": the record's message indexes " + indexes + " select nothing; the "
                        + "level at " + index + " holds "
                        + level.stream().map(Descriptor::getName).toList());
            }
            selected = level.get(index);
            level = selected.getNestedTypes();
        }
        return Objects.requireNonNull(selected, "the index array is not empty");
    }

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

    /** A message of a registered schema and its Confluent message-index path in the schema's root file. */
    record Located(Descriptor message, List<Integer> indexes) {}

    /**
     * The message called {@code messageName} -- by full name, or by simple name when only one has it
     * -- among the messages of the registered schema's root file {@code root}, top-level and nested,
     * with the index path the Confluent framing names it by (KSF-2).
     *
     * @throws Unmappable if a file does not build, or the root file has no such message
     */
    static Located locate(Map<String, FileDescriptorProto> files, String root, String messageName, String origin) {
        ProtobufSchemas schemas = new ProtobufSchemas(
                origin, "The registry lists every import as a reference; this one was not among them");
        schemas.sources.putAll(files);
        FileDescriptor file = schemas.build(root);
        Map<String, Located> found = new LinkedHashMap<>();
        List<Descriptor> top = file.getMessageTypes();
        for (int i = 0; i < top.size(); i++) {
            index(top.get(i), List.of(i), found);
        }
        Located exact = found.get(messageName);
        if (exact != null) {
            return exact;
        }
        List<Located> bySimpleName = found.values().stream()
                .filter(located -> located.message().getName().equals(messageName))
                .toList();
        if (bySimpleName.size() == 1) {
            return bySimpleName.get(0);
        }
        throw new Unmappable(origin + (bySimpleName.isEmpty() ? " has no message called '" : " has more than one '")
                + messageName + "'. Its root file holds " + List.copyOf(found.keySet()));
    }

    private static void index(Descriptor message, List<Integer> path, Map<String, Located> into) {
        into.put(message.getFullName(), new Located(message, path));
        List<Descriptor> nested = message.getNestedTypes();
        for (int i = 0; i < nested.size(); i++) {
            List<Integer> next = new ArrayList<>(path);
            next.add(i);
            index(nested.get(i), List.copyOf(next), into);
        }
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
            throw new Unmappable(origin + " imports '" + name + "' and does not carry it. " + fix);
        }
        if (!building.add(name)) {
            throw new Unmappable("the .proto files in " + origin + " import each other in a cycle: " + building);
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
                    "the .proto file '" + name + "' in " + origin + " does not validate: " + e.getMessage());
        }
    }
}
