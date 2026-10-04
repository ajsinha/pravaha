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
package com.ash.messaging.pravaha.sdk.flight;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightEndpoint;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightServer;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.NoOpFlightProducer;
import org.apache.arrow.flight.Result;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.sdk.ClientErrors;
import com.ash.messaging.pravaha.sdk.ClientOptions;
import com.ash.messaging.pravaha.sdk.PravahaClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDKDEADLINE-1: a node that accepts a call and never answers no longer holds the caller for ever.
 *
 * <p>The server is a bare Arrow Flight server rather than Pravaha's, because the point is a server
 * that misbehaves on purpose: its handlers park until the test ends, or open a stream and then say
 * nothing. Every unary call gives up at {@code requestTimeout} with {@code PRV-1045} naming the call
 * and the deadline; a stream's opening is bounded the same way; and a subscription that opened is
 * not cut off by a deadline it has long outlived.
 */
@Timeout(60)
class JavaSdkDeadlineTest {

    private static final Duration DEADLINE = Duration.ofMillis(500);
    private static final Schema SCHEMA = new Schema(List.of(Field.nullable("n", new ArrowType.Int(64, true))));

    /** What the server does with a stream; set per test. */
    private enum StreamMode {
        /** Accepts the stream and never sends its schema. */
        SILENT,
        /** Sends the schema at once, then one batch every 300 ms -- for well past the deadline. */
        SLOW_BUT_ALIVE
    }

    private final CountDownLatch released = new CountDownLatch(1);
    private volatile StreamMode streamMode = StreamMode.SILENT;
    private volatile boolean answerFlightInfo;

    private @Nullable BufferAllocator allocator;
    private @Nullable FlightServer server;
    private @Nullable PravahaFlightClient client;

    @BeforeEach
    void start() throws Exception {
        allocator = new RootAllocator(Long.MAX_VALUE);
        server = FlightServer.builder(allocator, Location.forGrpcInsecure("localhost", 0), new Stalling())
                .build()
                .start();
        client = PravahaFlightClient.connect(ClientOptions.builder("grpc://localhost:" + server.getPort())
                .requestTimeout(DEADLINE)
                .build());
    }

    @AfterEach
    void stop() throws Exception {
        released.countDown();
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.shutdown();
            server.awaitTermination(10, TimeUnit.SECONDS);
            server.close();
        }
        if (allocator != null) {
            allocator.close();
        }
    }

    @Test
    void aQueryTheServerNeverPlansFailsAtTheDeadlineNamingTheCallAndTheDeadline() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.query("SELECT n FROM anything"))
                .isInstanceOfSatisfying(PravahaClientException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ClientErrors.DEADLINE_EXCEEDED);
                    assertThat(e.errorCode().code()).isEqualTo("PRV-1045");
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.getMessage())
                            .contains("query (planning)")
                            .contains("0.5 s")
                            .contains("requestTimeout");
                });
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void anActionTheServerNeverAnswersFailsAtTheDeadline() {
        assertThatThrownBy(() -> client.queries()).isInstanceOfSatisfying(PravahaClientException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ClientErrors.DEADLINE_EXCEEDED);
            assertThat(e.getMessage()).contains("action ");
        });
    }

    @Test
    void aQueryWhoseResultNeverOpensFailsAtTheDeadline() {
        answerFlightInfo = true;
        assertThatThrownBy(() -> client.query("SELECT n FROM anything"))
                .isInstanceOfSatisfying(PravahaClientException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ClientErrors.DEADLINE_EXCEEDED);
                    assertThat(e.getMessage()).contains("query (opening its result)");
                });
    }

    @Test
    void aSubscriptionThatNeverOpensFailsAtTheDeadline() {
        Subscription subscription = client.subscribe("v", batch -> {});
        assertThatThrownBy(subscription::awaitOpen).isInstanceOfSatisfying(PravahaClientException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ClientErrors.DEADLINE_EXCEEDED);
            assertThat(e.getMessage()).contains("subscribe(v)");
        });
        subscription.close();
    }

    @Test
    void aSubscriptionThatOpenedRunsPastTheDeadline() {
        streamMode = StreamMode.SLOW_BUT_ALIVE;
        AtomicLong rows = new AtomicLong();
        long started = System.nanoTime();
        try (Subscription subscription =
                client.subscribe("v", batch -> rows.addAndGet(batch.rows().size()))) {
            subscription.run();
        }
        // Five batches, 300 ms apart: three deadlines' worth, and every one of them delivered.
        assertThat(rows.get()).isEqualTo(5);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThan(DEADLINE.multipliedBy(2));
    }

    @Test
    void theDefaultDeadlineIsThirtySeconds() {
        assertThat(ClientOptions.builder("grpc://localhost:1").build().requestTimeout())
                .isEqualTo(Duration.ofSeconds(30));
    }

    private final class Stalling extends NoOpFlightProducer {

        @Override
        public FlightInfo getFlightInfo(CallContext context, FlightDescriptor descriptor) {
            if (!answerFlightInfo) {
                park();
            }
            return new FlightInfo(
                    SCHEMA,
                    descriptor,
                    List.of(new FlightEndpoint(new Ticket("result".getBytes(StandardCharsets.UTF_8)))),
                    -1,
                    -1);
        }

        @Override
        public void doAction(CallContext context, Action action, StreamListener<Result> listener) {
            park();
            listener.onCompleted();
        }

        @Override
        public void getStream(CallContext context, Ticket ticket, ServerStreamListener listener) {
            if (streamMode == StreamMode.SILENT) {
                park();
                listener.completed();
                return;
            }
            try (VectorSchemaRoot root = VectorSchemaRoot.create(SCHEMA, allocator)) {
                listener.start(root);
                BigIntVector n = (BigIntVector) root.getVector(0);
                for (int i = 0; i < 5; i++) {
                    Thread.sleep(300);
                    n.setSafe(0, i);
                    root.setRowCount(1);
                    listener.putNext();
                }
                listener.completed();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                listener.error(e);
            }
        }

        private void park() {
            try {
                released.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
