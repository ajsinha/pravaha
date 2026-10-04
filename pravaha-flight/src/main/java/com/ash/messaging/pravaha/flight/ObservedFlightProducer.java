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
package com.ash.messaging.pravaha.flight;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.ActionType;
import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallInfo;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.Criteria;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightProducer;
import org.apache.arrow.flight.FlightServerMiddleware;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.PollInfo;
import org.apache.arrow.flight.PutResult;
import org.apache.arrow.flight.RequestContext;
import org.apache.arrow.flight.Result;
import org.apache.arrow.flight.SchemaResult;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.dictionary.DictionaryProvider;
import org.apache.arrow.vector.ipc.message.IpcOption;
import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.wire.ControlWire;

/**
 * The Flight producer, with every call reported to a {@link FlightObservation}: which operation it is,
 * which query it concerns, and the propagation headers the client sent -- so a trace started in a
 * client continues through the node.
 *
 * <p>A decorator rather than lines in {@link PravahaFlightSqlProducer}, which is at the project's file
 * size limit, and because the question "what kind of call is this" has one answer for every call and
 * belongs in one place. The work is the producer's; this only brackets it.
 *
 * <p>A subscription is one call for as long as it is open, so its span lasts as long as the stream.
 */
final class ObservedFlightProducer implements FlightProducer {

    /** The headers a client may propagate context in, lower case as gRPC delivers them. */
    static final List<String> PROPAGATED = List.of("traceparent", "tracestate", "baggage", "x-correlation-id");

    /** Captures {@link #PROPAGATED} at the start of each call, for the producer to read. */
    static final FlightServerMiddleware.Key<Headers> KEY = FlightServerMiddleware.Key.of("pravaha-trace-headers");

    /** Every {@code pravaha.*} action this engine knows, so an unknown one cannot become a label. */
    private static final Set<String> KNOWN_ACTIONS = knownActions();

    /** Actions whose first field is the query's name. */
    private static final Set<String> NAMED =
            Set.of("register", "replace", "drop", "pause", "resume", "cutover", "rollback", "abandon", "finish");

    private final FlightProducer delegate;
    private final FlightObservation observation;

    ObservedFlightProducer(FlightProducer delegate, FlightObservation observation) {
        this.delegate = delegate;
        this.observation = observation;
    }

    /** The middleware holding one call's propagation headers. */
    static final class Headers implements FlightServerMiddleware {
        private final Map<String, String> values;

        Headers(Map<String, String> values) {
            this.values = values;
        }

        @Nullable
        String get(String name) {
            return values.get(name);
        }

        @Override
        public void onBeforeSendingHeaders(CallHeaders outgoing) {}

        @Override
        public void onCallCompleted(CallStatus status) {}

        @Override
        public void onCallErrored(Throwable err) {}
    }

    /** Builds a {@link Headers} per call. */
    static final class HeadersFactory implements FlightServerMiddleware.Factory<Headers> {
        @Override
        public Headers onCallStarted(CallInfo info, CallHeaders incoming, RequestContext context) {
            Map<String, String> values = new HashMap<>();
            for (String name : PROPAGATED) {
                String value = incoming.get(name);
                if (value != null && !value.isBlank()) {
                    values.put(name, value);
                }
            }
            return new Headers(values);
        }
    }

    // ------------------------------------------------------------------ classifying

    /** The operation label for an action type: {@code register}, {@code sql.action}, {@code action.unknown}. */
    static String operationOf(String actionType) {
        if (actionType == null || !actionType.startsWith("pravaha.")) {
            return "sql.action";
        }
        return KNOWN_ACTIONS.contains(actionType) ? actionType.substring("pravaha.".length()) : "action.unknown";
    }

    private static Set<String> knownActions() {
        Set<String> known = new TreeSet<>();
        for (Field field : ControlWire.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    Object value = field.get(null);
                    if (value instanceof String text && text.startsWith("pravaha.")) {
                        known.add(text.toLowerCase(Locale.ROOT));
                    }
                } catch (IllegalAccessException ignored) {
                    // a public field is readable; nothing else is a candidate
                }
            }
        }
        return Set.copyOf(known);
    }

    private static @Nullable String firstField(byte[] body) {
        try {
            List<String> fields = ControlWire.decode(body);
            return fields.isEmpty() ? null : fields.get(0);
        } catch (RuntimeException notOurs) {
            return null;
        }
    }

    private static @Nullable String subscribedView(byte[] ticket) {
        try {
            List<String> fields = ControlWire.decode(ticket);
            return fields.size() >= 2 ? fields.get(1) : null;
        } catch (RuntimeException notOurs) {
            return null;
        }
    }

    // ------------------------------------------------------------------ bracketing

    private FlightObservation.Call begin(CallContext context, String operation, @Nullable String query) {
        Headers headers = context == null ? null : context.getMiddleware(KEY);
        try {
            return observation.begin(operation, query, name -> headers == null ? null : headers.get(name));
        } catch (RuntimeException observerFailed) {
            // An observer that fails must not fail the call it was observing.
            return FlightObservation.NO_CALL;
        }
    }

    /**
     * Runs {@code work} as one observed call. The work receives the call so it can wrap the call's
     * listener: the producer answers most refusals through {@code listener.onError}, not by throwing,
     * and a failure only a throw could mark would read as a success.
     */
    private <T> T observed(
            CallContext context, String operation, @Nullable String query, Function<FlightObservation.Call, T> work) {
        FlightObservation.Call call = begin(context, operation, query);
        try {
            return work.apply(call);
        } catch (RuntimeException | Error failure) {
            call.failed(failure);
            throw failure;
        } finally {
            call.end();
        }
    }

    private void observedVoid(
            CallContext context, String operation, @Nullable String query, Consumer<FlightObservation.Call> work) {
        observed(context, operation, query, call -> {
            work.accept(call);
            return null;
        });
    }

    /** A listener that marks the call failed when the producer answers with an error. */
    private static <T> StreamListener<T> marking(StreamListener<T> listener, FlightObservation.Call call) {
        return new StreamListener<>() {
            @Override
            public void onNext(T value) {
                listener.onNext(value);
            }

            @Override
            public void onError(Throwable failure) {
                call.failed(failure);
                listener.onError(failure);
            }

            @Override
            public void onCompleted() {
                listener.onCompleted();
            }
        };
    }

    /** As {@link #marking(StreamListener, FlightObservation.Call)}, for a stream of batches. */
    private static ServerStreamListener marking(ServerStreamListener listener, FlightObservation.Call call) {
        return new ServerStreamListener() {
            @Override
            public boolean isCancelled() {
                return listener.isCancelled();
            }

            @Override
            public void setOnCancelHandler(Runnable handler) {
                listener.setOnCancelHandler(handler);
            }

            @Override
            public boolean isReady() {
                return listener.isReady();
            }

            @Override
            public void setOnReadyHandler(Runnable handler) {
                listener.setOnReadyHandler(handler);
            }

            @Override
            public void start(VectorSchemaRoot root) {
                listener.start(root);
            }

            @Override
            public void start(VectorSchemaRoot root, DictionaryProvider dictionaries) {
                listener.start(root, dictionaries);
            }

            @Override
            public void start(VectorSchemaRoot root, DictionaryProvider dictionaries, IpcOption option) {
                listener.start(root, dictionaries, option);
            }

            @Override
            public void putNext() {
                listener.putNext();
            }

            @Override
            public void putNext(ArrowBuf metadata) {
                listener.putNext(metadata);
            }

            @Override
            public void putMetadata(ArrowBuf metadata) {
                listener.putMetadata(metadata);
            }

            @Override
            public void error(Throwable failure) {
                call.failed(failure);
                listener.error(failure);
            }

            @Override
            public void completed() {
                listener.completed();
            }

            @Override
            public void setUseZeroCopy(boolean enabled) {
                listener.setUseZeroCopy(enabled);
            }
        };
    }

    // ------------------------------------------------------------------ FlightProducer

    @Override
    public void getStream(CallContext context, Ticket ticket, ServerStreamListener listener) {
        byte[] bytes = ticket.getBytes();
        boolean subscription = ControlWire.isOurs(bytes);
        observedVoid(
                context,
                subscription ? "subscribe" : "query",
                subscription ? subscribedView(bytes) : null,
                call -> delegate.getStream(context, ticket, marking(listener, call)));
    }

    @Override
    public void listFlights(CallContext context, Criteria criteria, StreamListener<FlightInfo> listener) {
        observedVoid(
                context,
                "list.flights",
                null,
                call -> delegate.listFlights(context, criteria, marking(listener, call)));
    }

    @Override
    public FlightInfo getFlightInfo(CallContext context, FlightDescriptor descriptor) {
        return observed(context, "query.plan", null, call -> delegate.getFlightInfo(context, descriptor));
    }

    @Override
    public PollInfo pollFlightInfo(CallContext context, FlightDescriptor descriptor) {
        return observed(context, "query.poll", null, call -> delegate.pollFlightInfo(context, descriptor));
    }

    @Override
    public SchemaResult getSchema(CallContext context, FlightDescriptor descriptor) {
        return observed(context, "schema", null, call -> delegate.getSchema(context, descriptor));
    }

    @Override
    public Runnable acceptPut(CallContext context, FlightStream flightStream, StreamListener<PutResult> ackStream) {
        // The upload is read when the returned work runs, on the call's thread: that is the call.
        return () -> observedVoid(
                context,
                "put",
                null,
                call -> delegate.acceptPut(context, flightStream, marking(ackStream, call))
                        .run());
    }

    @Override
    public void doExchange(CallContext context, FlightStream reader, ServerStreamListener writer) {
        observedVoid(context, "exchange", null, call -> delegate.doExchange(context, reader, marking(writer, call)));
    }

    @Override
    public void doAction(CallContext context, Action action, StreamListener<Result> listener) {
        String operation = operationOf(action.getType());
        String query = NAMED.contains(operation) ? firstField(action.getBody()) : null;
        observedVoid(context, operation, query, call -> delegate.doAction(context, action, marking(listener, call)));
    }

    @Override
    public void listActions(CallContext context, StreamListener<ActionType> listener) {
        observedVoid(context, "list.actions", null, call -> delegate.listActions(context, marking(listener, call)));
    }
}
