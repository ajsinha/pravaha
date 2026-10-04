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
package com.ash.messaging.pravaha.spring;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.spring.PravahaListenerErrorHandler.Decision;
import com.ash.messaging.pravaha.spring.PravahaListenerErrorHandler.Failure;
import com.ash.messaging.pravaha.spring.test.PravahaTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code @PravahaListener} method that throws: by default logged with its query and change and
 * delivered past; stopped when configured to be; handed to the application's handler when it has
 * one. In no case silent.
 */
@ExtendWith(OutputCaptureExtension.class)
class ListenerErrorHandlerTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PravahaAutoConfiguration.class))
            .withPropertyValues(
                    "pravaha.streams.txn.schema=user_id:STRING,amount:INT64",
                    "pravaha.queries.all_txn.sql=SELECT user_id, amount FROM txn",
                    "pravaha.queries.all_txn.keys=user_id")
            .withUserConfiguration(ListenerConfiguration.class);

    record Txn(String userId, long amount) {}

    /** Throws on user "bad"; records everyone else. */
    static class Picky {
        final List<String> seen = new CopyOnWriteArrayList<>();

        @PravahaListener(query = "all_txn")
        void on(Txn txn, boolean retraction) {
            if (txn.userId().equals("bad")) {
                throw new IllegalStateException("cannot take " + txn.userId());
            }
            seen.add(txn.userId());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ListenerConfiguration {
        @Bean
        Picky picky() {
            return new Picky();
        }
    }

    /** Collects what it is handed and answers with a fixed decision. */
    static final class Recording implements PravahaListenerErrorHandler {
        final List<Failure> failures = new CopyOnWriteArrayList<>();
        final Decision decision;

        Recording(Decision decision) {
            this.decision = decision;
        }

        @Override
        public Decision handle(Failure failure) {
            failures.add(failure);
            return decision;
        }
    }

    private static PravahaTester tester(AssertableApplicationContext context) {
        return new PravahaTester(context.getBean(PravahaEngine.class), context.getBean(PravahaListenerProcessor.class));
    }

    private static ListenerContainer container(AssertableApplicationContext context) {
        return context.getBean(PravahaListenerProcessor.class).containers().get(0);
    }

    @Test
    void byDefaultAFailureIsLoggedWithTheQueryAndTheChangeAndDeliveryGoesOn(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            tester(context)
                    .push("txn", new Object[] {"u1", 1L})
                    .push("txn", new Object[] {"bad", 2L})
                    .push("txn", new Object[] {"u2", 3L})
                    .awaitListeners("all_txn");

            assertThat(context.getBean(Picky.class).seen)
                    .as("the change after the failure is still delivered")
                    .containsExactly("u1", "u2");
            ListenerContainer container = container(context);
            assertThat(container.isRunning()).isTrue();
            assertThat(container.isStopped()).isFalse();
            assertThat(container.failures()).isEqualTo(1);
            assertThat(container.delivered()).isEqualTo(2);
            assertThat(container.lastFailure()).hasValueSatisfying(failure -> {
                assertThat(failure.queryName()).isEqualTo("all_txn");
                assertThat(failure.listener()).isEqualTo("picky.on");
                assertThat(failure.exception()).hasMessage("cannot take bad");
            });
        });
        assertThat(output.getOut() + output.getErr())
                .as("never silently swallowed: the query, the change and the exception are logged")
                .contains("picky.on on query 'all_txn' threw on [+{user_id=bad, amount=2}]")
                .contains("goes on to the next one")
                .contains("cannot take bad");
    }

    @Test
    void configuredToStopTheListenerReceivesNothingMoreAfterItThrows(CapturedOutput output) {
        runner.withPropertyValues("pravaha.listener.on-error=stop").run(context -> {
            assertThat(context).hasNotFailed();
            PravahaTester pravaha = tester(context);
            pravaha.push("txn", new Object[] {"u1", 1L})
                    .push("txn", new Object[] {"bad", 2L})
                    .awaitListeners();
            pravaha.push("txn", new Object[] {"u2", 3L}).awaitListeners();

            assertThat(context.getBean(Picky.class).seen).containsExactly("u1");
            ListenerContainer container = container(context);
            assertThat(container.isStopped()).isTrue();
            assertThat(container.isRunning()).isFalse();
            assertThat(container.failures()).isEqualTo(1);
            assertThat(context.getBean(PravahaEngine.class)
                            .find("all_txn")
                            .orElseThrow()
                            .subscriberCount())
                    .as("detached from the query")
                    .isZero();
        });
        assertThat(output.getOut() + output.getErr())
                .contains("picky.on on query 'all_txn' threw on [+{user_id=bad, amount=2}]")
                .contains("pravaha.listener.on-error=stop")
                .contains("is stopped by its error handler");
    }

    @Test
    void theApplicationsHandlerReplacesTheDefaultAndIsHandedTheWholeFailure() {
        Recording handler = new Recording(Decision.CONTINUE);
        runner.withBean("myHandler", PravahaListenerErrorHandler.class, () -> handler)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    tester(context)
                            .push("txn", new Object[] {"bad", 7L})
                            .push("txn", new Object[] {"u9", 1L})
                            .awaitListeners();

                    assertThat(context.getBean(Picky.class).seen).containsExactly("u9");
                    assertThat(handler.failures).singleElement().satisfies(failure -> {
                        assertThat(failure.queryName()).isEqualTo("all_txn");
                        assertThat(failure.listener()).isEqualTo("picky.on");
                        assertThat(failure.changes()).singleElement().satisfies(change -> {
                            assertThat(change.get("user_id")).isEqualTo("bad");
                            assertThat(change.isRetraction()).isFalse();
                        });
                        assertThat(failure.exception())
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage("cannot take bad");
                    });
                });
    }

    /** Names its own handler; the other listener gets the application's. */
    static class TwoListeners {
        final List<String> strict = new CopyOnWriteArrayList<>();
        final List<String> lenient = new CopyOnWriteArrayList<>();

        @PravahaListener(query = "all_txn", errorHandler = "stopper")
        void strict(Txn txn, boolean retraction) {
            if (txn.userId().equals("bad")) {
                throw new IllegalStateException("strict");
            }
            strict.add(txn.userId());
        }

        @PravahaListener(query = "all_txn")
        void lenient(Txn txn, boolean retraction) {
            if (txn.userId().equals("bad")) {
                throw new IllegalStateException("lenient");
            }
            lenient.add(txn.userId());
        }
    }

    @Test
    void aListenerThatNamesAHandlerGetsThatOneAndTheRestGetTheApplications() {
        Recording stopper = new Recording(Decision.STOP);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PravahaAutoConfiguration.class))
                .withPropertyValues(
                        "pravaha.streams.txn.schema=user_id:STRING,amount:INT64",
                        "pravaha.queries.all_txn.sql=SELECT user_id, amount FROM txn",
                        "pravaha.queries.all_txn.keys=user_id")
                .withBean(TwoListeners.class)
                .withBean("stopper", PravahaListenerErrorHandler.class, () -> stopper)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    tester(context)
                            .push("txn", new Object[] {"bad", 1L})
                            .push("txn", new Object[] {"u1", 1L})
                            .awaitListeners();
                    TwoListeners listeners = context.getBean(TwoListeners.class);
                    assertThat(listeners.strict)
                            .as("stopped by its own handler")
                            .isEmpty();
                    assertThat(listeners.lenient)
                            .as("the named handler is not the application's: the default goes on")
                            .containsExactly("u1");
                    assertThat(stopper.failures)
                            .singleElement()
                            .extracting(Failure::listener)
                            .asString()
                            .endsWith(".strict");
                });
    }

    @Test
    @SuppressWarnings("NullAway") // a handler that returns no decision, on purpose
    void aHandlerThatThrowsOrDecidesNothingStopsTheListenerLoudly(CapturedOutput output) {
        runner.withBean(PravahaListenerErrorHandler.class, () -> failure -> {
                    throw new IllegalArgumentException("handler broke");
                })
                .run(context -> {
                    tester(context)
                            .push("txn", new Object[] {"bad", 1L})
                            .push("txn", new Object[] {"u1", 1L})
                            .awaitListeners();
                    assertThat(context.getBean(Picky.class).seen).isEmpty();
                    assertThat(container(context).isStopped()).isTrue();
                });
        assertThat(output.getOut() + output.getErr())
                .contains("and its error handler threw too; the listener is stopped")
                .contains("handler broke");

        runner.withBean(PravahaListenerErrorHandler.class, () -> failure -> null)
                .run(context -> {
                    tester(context).push("txn", new Object[] {"bad", 1L}).awaitListeners();
                    assertThat(container(context).isStopped()).isTrue();
                });
        assertThat(output.getOut() + output.getErr()).contains("returned no decision; the listener is stopped");
    }

    @Test
    void anAmbiguousOrMissingHandlerFailsTheStartup() {
        runner.withBean("one", PravahaListenerErrorHandler.class, PravahaListenerErrorHandler::logAndStop)
                .withBean("two", PravahaListenerErrorHandler.class, PravahaListenerErrorHandler::logAndContinue)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("2 PravahaListenerErrorHandler beans")
                            .hasMessageContaining("@Primary");
                });

        runner.withUserConfiguration(PrimaryHandler.class)
                .withBean("two", PravahaListenerErrorHandler.class, PravahaListenerErrorHandler::logAndContinue)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    tester(context).push("txn", new Object[] {"bad", 1L}).awaitListeners();
                    assertThat(container(context).isStopped())
                            .as("the @Primary handler, which stops")
                            .isTrue();
                });

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PravahaAutoConfiguration.class))
                .withPropertyValues(
                        "pravaha.streams.txn.schema=user_id:STRING,amount:INT64",
                        "pravaha.queries.all_txn.sql=SELECT user_id, amount FROM txn",
                        "pravaha.queries.all_txn.keys=user_id")
                .withBean(TwoListeners.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("names errorHandler 'stopper'");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class PrimaryHandler {
        @Bean
        @Primary
        PravahaListenerErrorHandler one() {
            return PravahaListenerErrorHandler.logAndStop();
        }
    }

    @Test
    void aWholeCommitListenerIsHandedTheWholeCommit() {
        Recording handler = new Recording(Decision.CONTINUE);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PravahaAutoConfiguration.class))
                .withPropertyValues(
                        "pravaha.streams.txn.schema=user_id:STRING,amount:INT64",
                        "pravaha.queries.all_txn.sql=SELECT user_id, amount FROM txn",
                        "pravaha.queries.all_txn.keys=user_id")
                .withBean(CommitListener.class)
                .withBean(PravahaListenerErrorHandler.class, () -> handler)
                .run(context -> {
                    tester(context)
                            .push("txn", new Object[] {"a", 1L}, new Object[] {"b", 2L})
                            .awaitListeners();
                    assertThat(handler.failures)
                            .singleElement()
                            .satisfies(failure -> assertThat(failure.changes()).hasSize(2));
                    assertThat(handler.failures.get(0).describeChanges()).contains("user_id=a", "user_id=b");
                });
    }

    static class CommitListener {
        @PravahaListener(query = "all_txn")
        void on(List<com.ash.messaging.pravaha.embedded.RowChange> commit) {
            throw new IllegalStateException("never");
        }
    }
}
