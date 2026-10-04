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

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The codec seam (ADR-053): snappy and zstd load on the build platform, lz4 is refused by name on
 * both sides, and a codec that cannot decompress a batch stops the reader with a named failure
 * instead of killing its fetch thread silently.
 */
class KafkaCodecsTest {

    private static final TopicPartition P0 = new TopicPartition("txn", 0);

    private final FakeTopic topic = new FakeTopic("txn", 1);

    @SuppressWarnings("NullAway.Init") // set by the test that uses it; @AfterEach closes what was set
    private KafkaSourcePlugin plugin;

    @AfterEach
    void tearDown() {
        if (plugin != null) {
            plugin.close();
        }
    }

    @Test
    void snappyAndZstdLoadOnTheBuildPlatform() {
        assertThat(KafkaCodecs.loadFailure("snappy")).isNull();
        assertThat(KafkaCodecs.loadFailure("zstd")).isNull();
        assertThat(KafkaCodecs.whySinkCannotWrite("snappy")).isNull();
        assertThat(KafkaCodecs.whySinkCannotWrite(" Zstd ")).isNull();
        assertThat(KafkaCodecs.whySinkCannotWrite("gzip")).isNull();
        assertThat(KafkaCodecs.whySinkCannotWrite("none")).isNull();
    }

    @Test
    void lz4IsRefusedByNameWhateverIsOnTheClasspath() {
        assertThat(KafkaCodecs.whySinkCannotWrite("LZ4"))
                .contains("'kafka.compression.type: LZ4' is refused")
                .contains("lz4-java")
                .contains("ADR-053");
    }

    @Test
    void theMissingCodecIsFoundAnywhereInTheCauseChain() {
        assertThat(KafkaCodecs.missingCodec(new KafkaException(
                        "Received exception when fetching the next record",
                        new NoClassDefFoundError("net/jpountz/lz4/LZ4Exception"))))
                .isEqualTo("lz4");
        assertThat(KafkaCodecs.missingCodec(new NoClassDefFoundError("org/xerial/snappy/SnappyOutputStream")))
                .isEqualTo("snappy");
        assertThat(KafkaCodecs.missingCodec(new UnsatisfiedLinkError("no com.github.luben.zstd in path")))
                .isEqualTo("zstd");
        assertThat(KafkaCodecs.missingCodec(new ClassNotFoundException("net.jpountz.xxhash.XXHashFactory")))
                .isEqualTo("lz4");
        assertThat(KafkaCodecs.missingCodec(new NoClassDefFoundError("com/example/Other")))
                .isNull();
        assertThat(KafkaCodecs.missingCodec(new KafkaException("authorization failed")))
                .isNull();
        assertThat(KafkaCodecs.readRefusal(P0, new IllegalStateException("x"))).isNull();
    }

    @Test
    void anLz4BatchIsANamedReadFailure() {
        PravahaException refused =
                KafkaCodecs.readRefusal(P0, new KafkaException("fetch", new NoClassDefFoundError("net/jpountz/lz4")));

        assertThat(Objects.requireNonNull(refused).errorCode()).isEqualTo(KafkaErrors.READ_FAILED);
        assertThat(Objects.requireNonNull(refused).getMessage())
                .contains("records in txn-0 are compressed with lz4")
                .contains("ADR-053")
                .contains("none, gzip, snappy or zstd");
    }

    @Test
    void aSnappyOrZstdBatchThatCannotBeDecompressedPointsAtThePlatform() {
        PravahaException refused = KafkaCodecs.readRefusal(
                P0, new UnsatisfiedLinkError("org.xerial.snappy.SnappyNative.maxCompressedLength"));

        assertThat(Objects.requireNonNull(refused).getMessage())
                .contains("compressed with snappy")
                .contains("does not load on this platform")
                .contains("Native code");
    }

    /**
     * What a snappy topic did before this plugin shipped the codec: the consumer's poll threw a
     * NoClassDefFoundError, which no catch in the fetch loop took, so the thread died with the
     * failure unset -- no rows, no error, and health said HEALTHY for ever.
     */
    @Test
    void anErrorFromTheConsumerStopsTheReaderByNameRatherThanKillingItsThreadSilently() {
        topic.append(0, "k", "{\"user_id\":\"u0\",\"amount\":0}");
        plugin = new KafkaSourcePlugin(topic);
        plugin.configure(new KafkaSourcePluginTest.Ctx("txn", options()));
        plugin.open();

        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(new SourcePartition("txn", 0, Map.of()), null)) {
            topic.pollThrows(new NoClassDefFoundError("net/jpountz/lz4/LZ4Exception"));
            assertThatThrownBy(() -> drain(reader, rows))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-5107")
                    .hasMessageContaining("compressed with lz4");
            HealthStatus health = plugin.health();
            assertThat(health.state()).isEqualTo(HealthStatus.State.UNHEALTHY);
            assertThat(health.detail()).contains("lz4");
        }
    }

    @Test
    void anErrorThatIsNotACodecIsStillRecordedAsAReadFailure() {
        topic.append(0, "k", "{\"user_id\":\"u0\",\"amount\":0}");
        plugin = new KafkaSourcePlugin(topic);
        plugin.configure(new KafkaSourcePluginTest.Ctx("txn", options()));
        plugin.open();

        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(new SourcePartition("txn", 0, Map.of()), null)) {
            topic.pollThrows(new NoClassDefFoundError("com/example/Missing"));
            assertThatThrownBy(() -> drain(reader, rows))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-5107")
                    .hasMessageContaining("com/example/Missing");
        }
    }

    private static void drain(PartitionReader reader, Collected rows) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            reader.poll(rows, 64);
            Thread.sleep(5);
        }
    }

    private static Map<String, String> options() {
        Map<String, String> options = new HashMap<>();
        options.put("bootstrap.servers", "localhost:9");
        options.put("topic", "txn");
        options.put("schema", "user_id:STRING,amount:INT64");
        options.put("start.timeout", "2s");
        return options;
    }
}
