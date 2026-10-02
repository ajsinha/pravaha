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
package com.ash.messaging.pravaha.registry;

import java.util.concurrent.atomic.AtomicInteger;

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * The identities rows carry to say which input they came from (W9-9): the registry's streams, and
 * the views other queries read (ADR-056).
 *
 * <p>Moved out of {@link QueryRegistry}, which held the stream half as a private static.
 */
final class StreamIdentities {

    /**
     * Where a view read as an input starts numbering: far above any stream catalogue, so a view's
     * rows can never carry a stream's identity. Never zero, which means "nobody assigned one".
     */
    private static final int FIRST_VIEW_ID = 1 << 24;

    private static final AtomicInteger NEXT_VIEW_ID = new AtomicInteger(FIRST_VIEW_ID);

    private StreamIdentities() {}

    /**
     * Gives every stream in this catalogue an identity, so a row can say which one it came from.
     *
     * <p>Sequential from one, in the order the registry was given them. Zero is reserved for "nobody
     * assigned one" and is what a schema built by hand carries, which is why it is reserved rather
     * than simply unused: the one consumer that dispatches by this value refuses zero instead of
     * treating it as a stream (W9-9).
     *
     * <p>Assigned here rather than derived from the name, and the alternative is worth naming
     * because it is the tempting one. Hashing a stream name needs no plumbing at all and is how two
     * streams come to share an identity silently -- one stream's rows delivered to the other's
     * queries, the same defect as PF-10 and W8-8. A counter cannot collide.
     *
     * <p>A schema that already carries an id keeps it: a registry that re-wraps a schema another
     * registry identified must not renumber it underneath the rows already written.
     */
    static StreamSchema[] identify(StreamSchema[] given) {
        StreamSchema[] identified = new StreamSchema[given.length];
        int next = 1;
        for (int i = 0; i < given.length; i++) {
            identified[i] =
                    given[i].streamId() == StreamSchema.UNASSIGNED_STREAM_ID ? given[i].withStreamId(next++) : given[i];
        }
        return identified;
    }

    /**
     * {@code known} with {@code declared} added, or put in place of the stream of the same name
     * (DECLSTREAM-1: a stream declared after the registry was built, by {@code POST /api/v1/streams}).
     *
     * <p>A new version of a stream keeps the identity of the one it replaces -- it is the same input,
     * and a running query keeps the schema it was planned against. A new name takes the next identity
     * after every one in use, so it cannot collide with a stream already carrying rows.
     */
    static StreamSchema[] declare(StreamSchema[] known, StreamSchema declared) {
        int next = 1;
        for (int i = 0; i < known.length; i++) {
            if (known[i].name().equals(declared.name())) {
                StreamSchema[] replaced = known.clone();
                replaced[i] = declared.withStreamId(known[i].streamId());
                return replaced;
            }
            next = Math.max(next, known[i].streamId() + 1);
        }
        StreamSchema[] grown = java.util.Arrays.copyOf(known, known.length + 1);
        grown[known.length] = declared.streamId() == StreamSchema.UNASSIGNED_STREAM_ID
                        || java.util.Arrays.stream(known).anyMatch(s -> s.streamId() == declared.streamId())
                ? declared.withStreamId(next)
                : declared;
        return grown;
    }

    /** An identity for a view read as an input, distinct from every stream's and every other view's. */
    static int nextViewId() {
        int id = NEXT_VIEW_ID.getAndIncrement();
        if (id < FIRST_VIEW_ID) {
            throw new IllegalStateException("this process has handed out every view input identity there is");
        }
        return id;
    }
}
