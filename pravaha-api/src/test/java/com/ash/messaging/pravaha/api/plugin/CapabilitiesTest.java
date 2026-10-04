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
package com.ash.messaging.pravaha.api.plugin;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.ash.messaging.pravaha.api.data.EmitMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilitiesTest {

    // ------------------------------------------------------------------ delivery guarantees

    @Test
    void theWeakestGuaranteeWins() {
        // How an end-to-end promise is computed: source, engine and every sink, weakest link.
        assertThat(DeliveryGuarantee.EXACTLY_ONCE.weakest(DeliveryGuarantee.AT_LEAST_ONCE))
                .isEqualTo(DeliveryGuarantee.AT_LEAST_ONCE);
        assertThat(DeliveryGuarantee.AT_LEAST_ONCE.weakest(DeliveryGuarantee.AT_MOST_ONCE))
                .isEqualTo(DeliveryGuarantee.AT_MOST_ONCE);
        assertThat(DeliveryGuarantee.EXACTLY_ONCE.weakest(DeliveryGuarantee.EXACTLY_ONCE))
                .isEqualTo(DeliveryGuarantee.EXACTLY_ONCE);
    }

    @ParameterizedTest
    @EnumSource(DeliveryGuarantee.class)
    void weakestIsCommutativeAndIdempotent(DeliveryGuarantee g) {
        for (DeliveryGuarantee other : DeliveryGuarantee.values()) {
            assertThat(g.weakest(other)).isEqualTo(other.weakest(g));
        }
        assertThat(g.weakest(g)).isEqualTo(g);
    }

    // ------------------------------------------------------------------ source capabilities

    @Test
    void exactlyOnceWithoutReplayableOffsetsIsRefusedAtDeclaration() {
        // Caught here rather than discovered during a recovery that silently loses records.
        assertThatThrownBy(() -> new SourceCapabilities(
                        false,
                        true,
                        true,
                        true,
                        DeliveryGuarantee.EXACTLY_ONCE,
                        EnumSet.noneOf(PushdownKind.class),
                        Duration.ofMillis(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replayable offsets");
    }

    @Test
    void aSourceThatRepeatsRowsCannotOfferExactlyOnce() {
        // SCAN-1: every repeat is a row counted twice, whatever the offsets say.
        assertThatThrownBy(() -> new SourceCapabilities(
                        true,
                        false,
                        false,
                        false,
                        DeliveryGuarantee.EXACTLY_ONCE,
                        EnumSet.noneOf(PushdownKind.class),
                        Duration.ofSeconds(1),
                        true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repeats rows");
    }

    @Test
    // Nulls on purpose: what is tested is what a caller outside NullAway meets, refusal or default.
    @SuppressWarnings("NullAway")
    void aSourceDeclaredWithoutSayingWhetherItRepeatsDoesNot() {
        // The seven-argument form is what every plugin wrote before the flag existed, and the flag
        // must not change what any of them means.
        SourceCapabilities before = new SourceCapabilities(
                true, true, false, false, DeliveryGuarantee.AT_LEAST_ONCE, null, Duration.ofSeconds(1));
        assertThat(before.repeatsRows()).isFalse();
        assertThat(SourceCapabilities.minimal().repeatsRows()).isFalse();
        SourceCapabilities scan = new SourceCapabilities(
                true, false, false, false, DeliveryGuarantee.AT_LEAST_ONCE, null, Duration.ofSeconds(60), true);
        assertThat(scan.repeatsRows()).isTrue();
    }

    @Test
    void aSourceReportsWhatItCanPushDown() {
        SourceCapabilities caps = new SourceCapabilities(
                true,
                true,
                true,
                false,
                DeliveryGuarantee.EXACTLY_ONCE,
                EnumSet.of(PushdownKind.FILTER, PushdownKind.PROJECT),
                Duration.ofMillis(50));

        assertThat(caps.supports(PushdownKind.FILTER)).isTrue();
        assertThat(caps.supports(PushdownKind.PARTIAL_AGGREGATE)).isFalse();
        assertThat(caps.pushdown()).containsExactlyInAnyOrder(PushdownKind.FILTER, PushdownKind.PROJECT);
    }

    @Test
    void theMinimalSourceIsHonestAboutHavingNoCapabilities() {
        // What a CE scan-based Aerospike source looks like: no rewind, no deletes, no before-image.
        SourceCapabilities caps = SourceCapabilities.minimal();
        assertThat(caps.replayableOffsets()).isFalse();
        assertThat(caps.emitsDeletes()).isFalse();
        assertThat(caps.emitsBeforeImage()).isFalse();
        assertThat(caps.guarantee()).isEqualTo(DeliveryGuarantee.AT_LEAST_ONCE);
        assertThat(caps.pushdown()).isEmpty();
    }

    @Test
    // Nulls on purpose: what is tested is what a caller outside NullAway meets, refusal or default.
    @SuppressWarnings("NullAway")
    void aNullPushdownSetBecomesEmptyRatherThanFailingLater() {
        SourceCapabilities caps =
                new SourceCapabilities(false, true, false, false, DeliveryGuarantee.AT_LEAST_ONCE, null, Duration.ZERO);
        assertThat(caps.pushdown()).isEmpty();
        assertThat(caps.supports(PushdownKind.FILTER)).isFalse();
    }

    // ------------------------------------------------------------------ sink capabilities

    @Test
    void aTransactionalSinkCanOfferExactlyOnce() {
        SinkCapabilities caps = new SinkCapabilities(EnumSet.of(EmitMode.UPSERT), true, false, 500);
        assertThat(caps.guarantee()).isEqualTo(DeliveryGuarantee.EXACTLY_ONCE);
        assertThat(caps.maxBatchRows()).isEqualTo(500);
    }

    @Test
    void anIdempotentSinkAlsoReachesExactlyOnceWithoutTransactions() {
        // "Effectively once": a replay overwrites with identical values, so the end state is right
        // even though the write happened more than once.
        SinkCapabilities caps = new SinkCapabilities(EnumSet.of(EmitMode.UPSERT), false, true, 0);
        assertThat(caps.guarantee()).isEqualTo(DeliveryGuarantee.EXACTLY_ONCE);
    }

    @Test
    void anAppendOnlySinkCanOnlyPromiseAtLeastOnce() {
        SinkCapabilities caps = SinkCapabilities.appendOnly();
        assertThat(caps.guarantee()).isEqualTo(DeliveryGuarantee.AT_LEAST_ONCE);
        assertThat(caps.accepts(EmitMode.APPEND)).isTrue();
        assertThat(caps.accepts(EmitMode.UPSERT)).isFalse();
        assertThat(caps.accepts(EmitMode.RETRACT)).isFalse();
    }

    @Test
    // Nulls on purpose: what is tested is what a caller outside NullAway meets, refusal or default.
    @SuppressWarnings("NullAway")
    void anEmptyEmitModeSetDefaultsToAppendRatherThanAcceptingNothing() {
        // A sink that accepts nothing would be silently unusable; append-only is the honest default.
        assertThat(new SinkCapabilities(EnumSet.noneOf(EmitMode.class), false, false, 0).emitModes())
                .containsExactly(EmitMode.APPEND);
        assertThat(new SinkCapabilities(null, false, false, 0).accepts(EmitMode.APPEND))
                .isTrue();
    }

    @Test
    void aNegativeBatchSizeIsRejected() {
        assertThatThrownBy(() -> new SinkCapabilities(EnumSet.of(EmitMode.APPEND), false, false, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ versions and manifests

    @Test
    void versionsParseCompareAndRender() {
        assertThat(Version.parse("1.2.3")).isEqualTo(new Version(1, 2, 3));
        assertThat(Version.parse("2.0")).isEqualTo(new Version(2, 0, 0));
        assertThat(Version.parse(" 1.0.0 ")).isEqualTo(new Version(1, 0, 0));
        assertThat(new Version(1, 2, 3)).hasToString("1.2.3");
        assertThat(new Version(1, 2, 0)).isLessThan(new Version(1, 3, 0));
        assertThat(new Version(1, 2, 3)).isGreaterThan(new Version(1, 2, 2));
        assertThat(new Version(2, 0, 0)).isGreaterThan(new Version(1, 9, 9));
    }

    @Test
    void malformedVersionsAreRejected() {
        assertThatThrownBy(() -> Version.parse("1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Version.parse("x.y")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Version(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compatibilityFollowsOrdinarySemver() {
        // A plugin built against 1.2 runs on 1.5 and not on 2.0.
        assertThat(new Version(1, 5, 0).isCompatibleWith(new Version(1, 2, 0))).isTrue();
        assertThat(new Version(1, 2, 0).isCompatibleWith(new Version(1, 5, 0))).isFalse();
        assertThat(new Version(2, 0, 0).isCompatibleWith(new Version(1, 9, 0))).isFalse();
        assertThat(Version.apiVersion()).isNotNull();
    }

    @Test
    void aManifestCarriesWhatIsNeededBeforeAnyClassIsLoaded() {
        PluginManifest m = new PluginManifest(
                "aerospike",
                new Version(1, 0, 0),
                new Version(0, 1, 0),
                "com.example.AerospikePlugin",
                Map.of("hosts", "Comma-separated host:port list"));

        assertThat(m.isCompatibleWith(new Version(0, 1, 0))).isTrue();
        assertThat(m.isCompatibleWith(new Version(1, 0, 0))).isFalse();
        assertThat(m.configSchema()).containsKey("hosts");
    }

    @Test
    // Nulls on purpose: what is tested is what a caller outside NullAway meets, refusal or default.
    @SuppressWarnings("NullAway")
    void aManifestRejectsMissingEssentials() {
        assertThatThrownBy(() -> new PluginManifest(" ", new Version(1, 0, 0), new Version(0, 1, 0), "X", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PluginManifest("a", null, new Version(0, 1, 0), "X", Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThat(new PluginManifest("a", new Version(1, 0, 0), new Version(0, 1, 0), "X", null).configSchema())
                .isEmpty();
    }

    // ------------------------------------------------------------------ health, partitions, offsets

    @Test
    // Nulls on purpose: what is tested is what a caller outside NullAway meets, refusal or default.
    @SuppressWarnings("NullAway")
    void healthDistinguishesDegradedFromDown() {
        // A plugin failing 40% of writes is neither healthy nor down, and collapsing that into a
        // boolean means an operator sees "up" while data quietly goes missing.
        assertThat(HealthStatus.healthy().state()).isEqualTo(HealthStatus.State.HEALTHY);
        assertThat(HealthStatus.degraded("40% write failures").isUsable()).isTrue();
        assertThat(HealthStatus.unhealthy("connection refused").isUsable()).isFalse();
        assertThat(HealthStatus.degraded("x").detail()).isEqualTo("x");
        assertThat(new HealthStatus(HealthStatus.State.HEALTHY, null).detail()).isEmpty();
    }

    @Test
    void partitionsAndOffsetsRoundTrip() {
        SourcePartition p = new SourcePartition("txn", 3, Map.of("range", "0-1023"));
        assertThat(p).hasToString("txn[3]");
        assertThat(p.properties()).containsEntry("range", "0-1023");
        assertThat(SourcePartition.of("s", 0).properties()).isEmpty();
        assertThatThrownBy(() -> SourcePartition.of("s", -1)).isInstanceOf(IllegalArgumentException.class);

        assertThat(SourceOffset.BEGINNING.isBeginning()).isTrue();
        assertThat(SourceOffset.BEGINNING).hasToString("SourceOffset[beginning]");
        assertThat(new SourceOffset("lsn:4471").isBeginning()).isFalse();
        assertThat(new SourceOffset("lsn:4471")).hasToString("SourceOffset[lsn:4471]");
    }

    @ParameterizedTest
    @EnumSource(EmitMode.class)
    void everyEmitModeIsReachable(EmitMode mode) {
        assertThat(new SinkCapabilities(EnumSet.of(mode), false, false, 0).accepts(mode))
                .isTrue();
    }
}
