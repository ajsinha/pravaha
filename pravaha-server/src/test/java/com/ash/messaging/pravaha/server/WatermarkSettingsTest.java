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
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.Principal;
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

    private static final String WINDOWED =
            "SELECT user_id, COUNT(*) AS n FROM txn " + "GROUP BY user_id, TUMBLE(event_time, INTERVAL '10' SECOND)";

    private static StreamDeclarationProperties declared(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("pravaha", StreamDeclarationProperties.class)
                .get();
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
        StreamDeclarationProperties.Declaration unitless = declared(Map.of(
                        "pravaha.streams.txn.schema", SCHEMA,
                        "pravaha.streams.txn.event-time", "event_time",
                        "pravaha.streams.txn.out-of-orderness", "60"))
                .getStreams()
                .get("txn");
        assertThat(unitless.getOutOfOrderness())
                .as("a unitless number means the unit lateness is discussed in")
                .isEqualTo(Duration.ofMinutes(1));

        // And every explicit spelling is untouched, which is what makes this safe to change.
        assertThat(declared(Map.of(
                                "pravaha.streams.txn.schema", SCHEMA,
                                "pravaha.streams.txn.event-time", "event_time",
                                "pravaha.streams.txn.out-of-orderness", "60ms"))
                        .getStreams()
                        .get("txn")
                        .getOutOfOrderness())
                .isEqualTo(Duration.ofMillis(60));
        assertThat(declared(Map.of(
                                "pravaha.streams.txn.schema", SCHEMA,
                                "pravaha.streams.txn.event-time", "event_time",
                                "pravaha.streams.txn.out-of-orderness", "PT1M"))
                        .getStreams()
                        .get("txn")
                        .getOutOfOrderness())
                .isEqualTo(Duration.ofMinutes(1));

        // Its twin, one key over, had the same defect and gets the same answer.
        assertThat(declared(Map.of(
                                "pravaha.streams.txn.schema", SCHEMA,
                                "pravaha.streams.txn.event-time", "event_time",
                                "pravaha.streams.txn.allowed-lateness", "30"))
                        .getStreams()
                        .get("txn")
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
                .hasMessageContaining("PRV-2002")
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
                    .hasMessageContaining("PRV-2002")
                    .hasMessageContaining("pravaha.watermark.tick");
        }
    }

    @Test
    void time6AWindowedQueryOverAStreamWithNoDeclaredEventTimeIsRefusedAtRegistration() {
        // TIME-6. Four causes each produced state=RUNNING, a climbing ROWS IN, an empty view, a NaN
        // lag gauge and not one log line; two of them are this one -- no event-time declaration,
        // and a blank one. The planner holds the StreamSchema and eventTimeOrdinal() is one call
        // away, so the query that can never emit is decidable before it is accepted.
        PravahaNode started = node(declared(Map.of("pravaha.streams.txn.schema", SCHEMA)))
                .withNodeId("no-event-time")
                .build();
        started.start();
        try {
            Principal dana = new Principal("dana", "acme", Set.of("analyst"), Map.of());
            assertThatThrownBy(() -> started.registry().orElseThrow().register("counts", WINDOWED, List.of(0), dana))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-2002")
                    .hasMessageContaining("declares no event-time column")
                    .hasMessageContaining("pravaha.streams.txn.event-time");
        } finally {
            started.stop();
        }

        // A blank declaration is the same mistake spelled differently, and reaches the same answer.
        PravahaNode blank = node(declared(
                        Map.of("pravaha.streams.txn.schema", SCHEMA, "pravaha.streams.txn.event-time", "")))
                .withNodeId("blank-event-time")
                .build();
        blank.start();
        try {
            Principal dana = new Principal("dana", "acme", Set.of("analyst"), Map.of());
            assertThatThrownBy(() -> blank.registry().orElseThrow().register("counts", WINDOWED, List.of(0), dana))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("declares no event-time column");
        } finally {
            blank.stop();
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
        // node, under PRV-2002. The two raised from StreamSchema.Builder.build were bare
        // IllegalArgumentExceptions wearing a Spring stack trace, with no code, no stream name in
        // one and no configuration key in either -- while a *misspelt* column one line away in the
        // same method already met the bar.
        StreamSchema wrongType = StreamSchema.builder("ev")
                .field("id", Types.int64())
                .field("usr", Types.string())
                .build();
        assertThatThrownBy(() -> StreamCatalog.withEventTime(wrongType, "usr", null))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2002")
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
                .hasMessageContaining("PRV-2002")
                .hasMessageContaining("stream 'ev'")
                .hasMessageContaining("negative out-of-orderness")
                .hasMessageContaining("pravaha.streams.ev.out-of-orderness");

        assertThatCode(() -> StreamCatalog.withEventTime(timed, "at", Duration.ofSeconds(10)))
                .as("the good case is untouched")
                .doesNotThrowAnyException();
    }
}
