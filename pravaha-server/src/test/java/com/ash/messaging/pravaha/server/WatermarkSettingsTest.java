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

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The event-time settings a node is configured with, and what it does with one it cannot honour.
 *
 * <p>Five findings meet here because they are five symptoms of one habit: a watermark setting that
 * was accepted, silently altered or never mentioned again. {@code TIME-3} is a unit, {@code TIME-5}
 * and {@code TIME-11} are bounds applied in the wrong place or not at all, {@code TIME-6} is a query
 * that can never emit and looks exactly like one that is working, and {@code TIME-9} is two
 * refusals in the same YAML block getting two different classes of answer.
 *
 * <p>Nothing here sleeps or times anything: every assertion is about a value bound, a refusal
 * raised or a line logged.
 */
@ExtendWith(OutputCaptureExtension.class)
class WatermarkSettingsTest {

    private static final String SCHEMA = "user_id:STRING,amount:INT64,event_time:TIMESTAMP";

    private static StreamDeclarationProperties declared(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("pravaha", StreamDeclarationProperties.class)
                .get();
    }

    /** The stream these cases declare, which binding them always gives. */
    private static StreamDeclarationProperties.Declaration txn(Map<String, String> properties) {
        return java.util.Objects.requireNonNull(
                declared(properties).getStreams().get("txn"));
    }

    private static PravahaNode.Builder node(StreamDeclarationProperties streams) {
        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal("");
        return PravahaNode.builder()
                .withDeclaredStreams(streams)
                .withSecurity(security)
                .withFlight(false, "127.0.0.1", 0)
                .withPersistence(persistence);
    }

    @Test
    void time3AUnitlessOutOfOrdernessIsSecondsRatherThanMilliseconds() {
        // TIME-3. Spring's relaxed binding reads a unitless number into a Duration as
        // milliseconds, and this property carried no @DurationUnit -- so `out-of-orderness: 60`
        // was sixty *milliseconds*, gave 11 windows and a last total of 1045, which are the same
        // two numbers the ten-second default gives. The operator who meant a minute could not see
        // the difference at the only surface that could have shown it, and no bound can catch it
        // either, because sixty milliseconds is a legitimate out-of-orderness.
        StreamDeclarationProperties.Declaration unitless = txn(Map.of(
                "pravaha.streams.txn.schema", SCHEMA,
                "pravaha.streams.txn.event-time", "event_time",
                "pravaha.streams.txn.out-of-orderness", "60"));
        assertThat(java.util.Objects.requireNonNull(unitless).getOutOfOrderness())
                .as("a unitless number means the unit lateness is discussed in")
                .isEqualTo(Duration.ofMinutes(1));

        // And every explicit spelling is untouched, which is what makes this safe to change.
        assertThat(txn(Map.of(
                                "pravaha.streams.txn.schema", SCHEMA,
                                "pravaha.streams.txn.event-time", "event_time",
                                "pravaha.streams.txn.out-of-orderness", "60ms"))
                        .getOutOfOrderness())
                .isEqualTo(Duration.ofMillis(60));
        assertThat(txn(Map.of(
                                "pravaha.streams.txn.schema", SCHEMA,
                                "pravaha.streams.txn.event-time", "event_time",
                                "pravaha.streams.txn.out-of-orderness", "PT1M"))
                        .getOutOfOrderness())
                .isEqualTo(Duration.ofMinutes(1));

        // Its twin, one key over, had the same defect and gets the same answer.
        assertThat(txn(Map.of(
                                "pravaha.streams.txn.schema", SCHEMA,
                                "pravaha.streams.txn.event-time", "event_time",
                                "pravaha.streams.txn.allowed-lateness", "30"))
                        .getAllowedLateness())
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void time5ATickLongerThanTheIdleTimeoutRefusesTheNodeRatherThanEveryRegistration() {
        // TIME-5. `tick: 5m` with `idle-after: 30s` started, logged both settings as in force,
        // reported UP, recovered its journal and refused every registration with PRV-1041 -- the
        // check lived in QueryExecution.generatingWatermarks and therefore fired once per
        // registration. PravahaNode's own comment states the rule this violated: one bad value is
        // one startup failure, rather than at registration where it is every query failing
        // separately.
        PravahaNode stopped = node(declared(
                        Map.of("pravaha.streams.txn.schema", SCHEMA, "pravaha.streams.txn.event-time", "event_time")))
                .withNodeId("tick-too-coarse")
                .withWatermark(Duration.ofSeconds(30), Duration.ofMinutes(5))
                .build();
        assertThatThrownBy(stopped::start)
                .isInstanceOf(PravahaException.class)
                // DOCX-19: PRV-1026, the configuration range's "outside a bound", where this was
                // PRV-2002 -- an operator whose node would not boot over a duration was pointed
                // at the SQL range.
                .hasMessageContaining("PRV-1026")
                .hasMessageContaining("pravaha.watermark.tick")
                .hasMessageContaining("longer than the idle timeout");
    }

    @Test
    void time11ATickTheTimerCannotCountRefusesTheNodeRatherThanBecomingAMillisecond() {
        // TIME-11. All three of these started a node on which the tick was one millisecond while
        // the only line that mentions the tick printed what was asked for. A zero tick is a
        // thousand passes a second over every lane for ever; a negative one was accepted because
        // tick.compareTo(idleAfter) > 0 is false for a negative and nothing else looked at the
        // sign.
        for (Duration tick : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(500_000))) {
            PravahaNode refused = node(declared(Map.of(
                            "pravaha.streams.txn.schema", SCHEMA, "pravaha.streams.txn.event-time", "event_time")))
                    .withNodeId("tick-" + Math.abs(tick.toNanos()))
                    .withWatermark(Duration.ofSeconds(30), tick)
                    .build();
            assertThatThrownBy(refused::start)
                    .as("tick %s", tick)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-1026")
                    .hasMessageContaining("pravaha.watermark.tick");
        }
    }

    @Test
    void time6TheLatenessInForceIsStatedPerStreamAtStartup(CapturedOutput output) {
        // TIME-6's other half, and the one that covers the two causes a refusal cannot: an
        // out-of-orderness larger than the data's span is a legitimate setting that happens to
        // empty the view. `sources bound:` naming event.time among a stream's options was the only
        // statement the engine ever made about any of this, and `grep -icE "out-of-orderness"`
        // over a full startup log was 0 on every configuration tried.
        PravahaNode started = node(declared(Map.of(
                        "pravaha.streams.txn.schema", SCHEMA,
                        "pravaha.streams.txn.event-time", "event_time",
                        "pravaha.streams.txn.out-of-orderness", "10m",
                        "pravaha.streams.txn.allowed-lateness", "30s")))
                .withNodeId("lateness-logged")
                .build();
        started.start();
        try {
            assertThat(output.getAll())
                    .contains("stream txn: event-time=event_time, out-of-orderness=PT10M, allowed-lateness=PT30S");
        } finally {
            started.stop();
        }
    }

    @Test
    void time9TheEventTimeRefusalsCarryTheirCodeTheStreamAndTheKey() {
        // TIME-9. Four refusals were compared side by side in one harness: idle-after's each named
        // the key, the rejected duration, the bound violated and what it would do to a running
        // node, under what was then PRV-2002 and is PRV-1013 since DOCX-19. The two raised from
        // StreamSchema.Builder.build were bare
        // IllegalArgumentExceptions wearing a Spring stack trace, with no code, no stream name in
        // one and no configuration key in either -- while a *misspelt* column one line away in the
        // same method already met the bar.
        StreamSchema wrongType = StreamSchema.builder("ev")
                .field("id", Types.int64())
                .field("usr", Types.string())
                .build();
        assertThatThrownBy(() -> StreamCatalog.withEventTime(wrongType, "usr", null))
                .isInstanceOf(PravahaException.class)
                // DOCX-19: PRV-1013. Every refusal in this case names a configuration key, and
                // PRV-2002 put them in the range the ranges table calls SQL.
                .hasMessageContaining("PRV-1013")
                .hasMessageContaining("stream 'ev'")
                .hasMessageContaining("must be TIMESTAMP")
                .hasMessageContaining("pravaha.streams.ev.event-time")
                .hasMessageContaining("POST /api/v1/streams");

        StreamSchema timed = StreamSchema.builder("ev")
                .field("id", Types.int64())
                .field("at", Types.timestamp())
                .build();
        assertThatThrownBy(() -> StreamCatalog.withEventTime(timed, "at", Duration.ofSeconds(-1)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-1013")
                .hasMessageContaining("stream 'ev'")
                .hasMessageContaining("negative out-of-orderness")
                .hasMessageContaining("pravaha.streams.ev.out-of-orderness");

        assertThatCode(() -> StreamCatalog.withEventTime(timed, "at", Duration.ofSeconds(10)))
                .as("the good case is untouched")
                .doesNotThrowAnyException();
    }

    /**
     * T-6, second half. {@code out-of-orderness} declared without {@code event-time} was read from
     * the file and dropped on the floor.
     *
     * <p>{@code StreamCatalog.withEventTime} has always refused that combination by name — lateness
     * needs an event time to be about — and {@code PravahaNode.withEventTime} returned early
     * before reaching it, because its guard asked only about {@code event-time} and
     * {@code allowed-lateness}. So the operator wrote a number, the node started, and nothing said
     * the number had been discarded.
     */
    @Test
    void t6_anOutOfOrdernessWithNoEventTimeIsRefusedRatherThanDropped() {
        PravahaNode node = node(declared(
                        Map.of("pravaha.streams.txn.schema", SCHEMA, "pravaha.streams.txn.out-of-orderness", "30s")))
                .withNodeId("t6-no-event-time")
                .build();

        assertThatThrownBy(node::start)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("stream 'txn'")
                .hasMessageContaining("out-of-orderness")
                .hasMessageContaining("no event-time column");
    }

    /**
     * DOCX-6 / T-6, first half. {@code pravaha.watermark.out-of-orderness} is the node's default.
     *
     * <p>It shipped in {@code application.yaml} with a default of 10s, was documented in
     * {@code CONCEPTS.md} and {@code OPERATIONS.md} and named in {@code StreamSchema}'s javadoc as
     * the way a deployment moves this — and nothing read it. Proved by experiment rather than by
     * grep: four nodes over one out-of-order fixture gave an identical view at {@code 0s} and at
     * {@code 10m} here, while the per-stream key at {@code 0s} and {@code 10m} differed.
     *
     * <p>Its default is {@link StreamSchema#DEFAULT_OUT_OF_ORDERNESS}, the same 10s a schema
     * already took, so giving it a reader changes nothing for a deployment that has not set it.
     */
    @Test
    void docx6_theNodeWideOutOfOrdernessReachesAStreamThatDeclaresNoneOfItsOwn() {
        StreamCatalog catalog = new StreamCatalog();
        PravahaNode node = node(declared(
                        Map.of("pravaha.streams.txn.schema", SCHEMA, "pravaha.streams.txn.event-time", "event_time")))
                .withCatalog(catalog)
                .defaultOutOfOrderness(Duration.ofMinutes(10))
                .withNodeId("docx6-node-default")
                .build();
        node.start();
        try {
            assertThat(catalog.require("txn").outOfOrderness())
                    .as("the key three documents describe now decides the answer")
                    .isEqualTo(Duration.ofMinutes(10));
        } finally {
            node.stop();
        }
    }

    /** DOCX-6's other half: the stream's own key still wins, and a node that sets nothing is 10s. */
    @Test
    void docx6_aStreamsOwnOutOfOrdernessOverridesTheNodeDefault() {
        StreamCatalog overriddenCatalog = new StreamCatalog();
        PravahaNode overridden = node(declared(Map.of(
                        "pravaha.streams.txn.schema", SCHEMA,
                        "pravaha.streams.txn.event-time", "event_time",
                        "pravaha.streams.txn.out-of-orderness", "45s")))
                .withCatalog(overriddenCatalog)
                .defaultOutOfOrderness(Duration.ofMinutes(10))
                .withNodeId("docx6-stream-wins")
                .build();
        overridden.start();
        try {
            assertThat(overriddenCatalog.require("txn").outOfOrderness()).isEqualTo(Duration.ofSeconds(45));
        } finally {
            overridden.stop();
        }

        StreamCatalog untouchedCatalog = new StreamCatalog();
        PravahaNode untouched = node(declared(
                        Map.of("pravaha.streams.txn.schema", SCHEMA, "pravaha.streams.txn.event-time", "event_time")))
                .withCatalog(untouchedCatalog)
                .withNodeId("docx6-untouched")
                .build();
        untouched.start();
        try {
            assertThat(untouchedCatalog.require("txn").outOfOrderness())
                    .as("a deployment that sets neither sees what it always saw")
                    .isEqualTo(StreamSchema.DEFAULT_OUT_OF_ORDERNESS);
        } finally {
            untouched.stop();
        }
    }
}
