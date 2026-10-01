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
package com.ash.messaging.pravaha.server;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Whether the two native libraries this node carries actually load here (ADR-053).
 *
 * <p>snappy-java and zstd-jni arrive with Parquet, which has no other way to read the files every
 * writer produces, and they are the only native code the build allows. A native library loads on the
 * platforms its publisher built for and fails everywhere else -- and, unchecked, fails at the first
 * Parquet file rather than at startup, as snappy-java did on the Alpine image (PORT-1). Each codec
 * is exercised once, by round-tripping a few bytes, and the node says at startup which do not load
 * and why.
 *
 * <p>Reflection, not a compile-time reference: a node assembled without the Parquet plugins has
 * neither class, and that is "absent", not a failure.
 */
public final class NativeCodecs {

    /** One codec's answer: loaded, absent from the classpath, or present and failing. */
    public record Status(String codec, String state, String detail) {

        public boolean failed() {
            return "failed".equals(state);
        }
    }

    private NativeCodecs() {}

    public static List<Status> check() {
        List<Status> out = new ArrayList<>();
        out.add(roundTrip("snappy", "org.xerial.snappy.Snappy", "compress", "uncompress"));
        out.add(roundTrip("zstd", "com.github.luben.zstd.Zstd", "compress", null));
        return out;
    }

    /** The startup warning, when a codec that is present does not load. */
    public static Optional<String> warning() {
        List<String> failed = check().stream()
                .filter(Status::failed)
                .map(s -> s.codec() + " (" + s.detail() + ")")
                .toList();
        if (failed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("native codec(s) " + String.join(", ", failed) + " cannot load on this platform, so a "
                + "Parquet file compressed with them cannot be read or written here. They are built for glibc "
                + "Linux (x86_64, aarch64 and others), macOS and Windows, and unpack into java.io.tmpdir ("
                + System.getProperty("java.io.tmpdir") + "), which must allow executing files: a tmpfs mounted "
                + "noexec is the usual cause on a supported platform (docs/operations/DEPLOYMENT.md, 'Native code')");
    }

    private static Status roundTrip(String codec, String type, String compress, String uncompress) {
        Class<?> c;
        try {
            c = Class.forName(type, true, NativeCodecs.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError absent) {
            return new Status(codec, "absent", "not on this node's classpath");
        }
        try {
            byte[] input = "pravaha native codec check".getBytes(StandardCharsets.UTF_8);
            Method m = c.getMethod(compress, byte[].class);
            byte[] packed = (byte[]) m.invoke(null, (Object) input);
            if (uncompress != null) {
                byte[] back = (byte[]) c.getMethod(uncompress, byte[].class).invoke(null, (Object) packed);
                if (!java.util.Arrays.equals(input, back)) {
                    return new Status(codec, "failed", "a round trip changed the bytes");
                }
            }
            return new Status(codec, "loaded", packed.length + " bytes");
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            return new Status(codec, "failed", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (ReflectiveOperationException | LinkageError broken) {
            return new Status(codec, "failed", broken.getClass().getSimpleName() + ": " + broken.getMessage());
        }
    }

    /** For deploy/docker/smoke.sh: prints each codec's state and exits 1 if any present one fails. */
    public static void main(String[] args) {
        List<Status> all = check();
        all.forEach(s -> System.out.println(s.codec() + " " + s.state() + " " + s.detail()));
        System.exit(all.stream().anyMatch(Status::failed) ? 1 : 0);
    }
}
