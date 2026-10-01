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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

import org.apache.kafka.common.TopicPartition;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Which Kafka compression codecs this plugin reads and writes, and the refusals for the rest (ADR-053).
 *
 * <p>{@code none} and {@code gzip} are the JDK's. {@code snappy} and {@code zstd} are snappy-java and
 * zstd-jni, the two native libraries the build allows because Parquet needs them too; each loads on
 * the platforms its publisher built for (glibc Linux, macOS, Windows, FreeBSD), from a library it
 * unpacks into {@code java.io.tmpdir}. {@code lz4} would need lz4-java, a third native family the
 * root pom's {@code enforce-portable-native-code} rule refuses, so it is refused here by name: by
 * the sink when it is configured, by the source at the first lz4 batch it fetches.
 */
final class KafkaCodecs {

    /** The one codec refused whatever the platform. */
    static final String LZ4 = "lz4";

    private KafkaCodecs() {}

    /**
     * Refuses a {@code kafka.compression.type} the sink cannot write here, loading snappy or zstd once
     * so that a platform without the native library is told now rather than at the first batch.
     *
     * @return the reason, or null when the codec writes here
     */
    static String whySinkCannotWrite(String codec) {
        String name = codec.strip().toLowerCase(Locale.ROOT);
        return switch (name) {
            case LZ4 -> lz4Refusal("'kafka.compression.type: " + codec + "' is refused");
            case "snappy", "zstd" -> {
                String failure = loadFailure(name);
                yield failure == null
                        ? null
                        : "'kafka.compression.type: " + codec + "' is refused: " + failure
                                + ". Use none or gzip here, or run the node on a platform the codec is built for";
            }
            default -> null;
        };
    }

    /**
     * The named refusal for a fetch that failed because a batch's codec cannot be decompressed here,
     * or null when {@code failure} is something else.
     */
    static PravahaException readRefusal(TopicPartition partition, Throwable failure) {
        String codec = missingCodec(failure);
        if (codec == null) {
            return null;
        }
        String where = "records in " + partition + " are compressed with " + codec;
        String message = codec.equals(LZ4)
                ? lz4Refusal(where + ", which this source cannot read")
                        + ". Have the producers use none, gzip, snappy or zstd (their compression.type), and "
                        + "set the topic's compression.type to producer or one of those; records already "
                        + "written in lz4 stay unreadable here"
                : where + ", and the native " + codec + " library does not load on this platform: " + loadFailure(codec)
                        + ". See docs/operations/DEPLOYMENT.md, 'Native code' (ADR-053)";
        return new PravahaException(KafkaErrors.READ_FAILED, message, failure);
    }

    /** snappy or zstd's reason for not loading here, or null when it round-trips bytes. */
    static String loadFailure(String codec) {
        byte[] input = "pravaha kafka codec check".getBytes(StandardCharsets.UTF_8);
        try {
            byte[] back;
            if (codec.equals("snappy")) {
                back = org.xerial.snappy.Snappy.uncompress(org.xerial.snappy.Snappy.compress(input));
            } else {
                byte[] packed = com.github.luben.zstd.Zstd.compress(input);
                back = com.github.luben.zstd.Zstd.decompress(packed, input.length);
            }
            return Arrays.equals(input, back) ? null : "a round trip through " + codec + " changed the bytes";
        } catch (Exception | LinkageError e) {
            return "the native " + codec + " library did not load ("
                    + e.getClass().getSimpleName() + ": "
                    + e.getMessage() + "); it unpacks into java.io.tmpdir ("
                    + System.getProperty("java.io.tmpdir") + "), which must allow executing files";
        }
    }

    /** lz4, snappy or zstd when a class of that codec's library is what failed, else null. */
    static String missingCodec(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof LinkageError || cause instanceof ClassNotFoundException) {
                String text = String.valueOf(cause.getMessage());
                if (text.contains("net/jpountz") || text.contains("net.jpountz")) {
                    return LZ4;
                }
                if (text.contains("org/xerial/snappy") || text.contains("org.xerial.snappy")) {
                    return "snappy";
                }
                if (text.contains("com/github/luben") || text.contains("com.github.luben")) {
                    return "zstd";
                }
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }

    private static String lz4Refusal(String what) {
        return what + ": lz4 needs lz4-java, a native library outside the two ADR-053 allows (snappy-java "
                + "and zstd-jni), and the build refuses it. none, gzip, snappy and zstd work";
    }
}
