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

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;

/**
 * Resolves the sink a registration names, without the registry knowing how sinks are discovered.
 *
 * <p>The mirror of {@link SourceFeedFactory}, and for the same reason: {@code PluginSinks} lives in
 * {@code pravaha-server} and knows about {@code ServiceLoader}, configuration and plugin
 * classloaders, none of which the registry has any business depending on. The registry knows only
 * that a name resolves to something it can ask about and something it can write to.
 *
 * <p>{@link #NONE} is the default, so an embedded engine with no sinks configured needs no null
 * check anywhere -- the same choice {@code SourceFeedFactory.NONE} makes for the ingest side.
 */
public interface SinkFactory {

    /** A factory that has no sinks, which is correct for an embedded engine and for most tests. */
    SinkFactory NONE = new SinkFactory() {
        @Override
        public SinkCapabilities capabilitiesOf(@Nullable String sinkName) {
            throw new IllegalArgumentException("no sink named '" + sinkName
                    + "' is bound: this engine has no sink factory, so nothing can be written out. "
                    + "Bind one under pravaha.sinks.<name>.");
        }

        @Override
        @SuppressWarnings("NullAway") // unreachable: capabilitiesOf refuses first, so nothing is returned
        public StreamSinkPlugin open(@Nullable String sinkName) {
            // Unreachable in practice: capabilitiesOf refuses first, and a registration is refused
            // before anything tries to open what it named.
            var unused = capabilitiesOf(sinkName);
            return null;
        }
    };

    /**
     * What a sink can promise, <em>without opening it</em>.
     *
     * <p>Asked at registration so {@code ChangelogAnalysis.checkAgainst} can refuse a query whose
     * changelog the sink cannot take, before a row is produced and before a connection is paid for.
     * Design section 15.5 is why the check has to happen here: a revising query pointed at an
     * append-only sink corrupts it <em>silently</em>, with rows that are each individually correct
     * and a total that is wrong for ever.
     */
    SinkCapabilities capabilitiesOf(@Nullable String sinkName);

    /**
     * Everything a registration checks about a sink before opening it: what it accepts, the row shape
     * it was configured with, and the columns it keys records by.
     *
     * <p>Defaults to the capabilities alone, for a factory whose sinks declare no shape.
     */
    default Description describe(String sinkName) {
        return new Description(capabilitiesOf(sinkName), java.util.Optional.empty(), java.util.List.of());
    }

    /**
     * What a sink declares about itself, read after configuration and without opening it.
     *
     * @param schema the row shape it reads rows through, or empty when it takes any shape
     * @param keyColumns the columns it keys records by, or empty for an append-only sink
     */
    record Description(
            SinkCapabilities capabilities,
            java.util.Optional<com.ash.messaging.pravaha.api.data.StreamSchema> schema,
            java.util.List<String> keyColumns) {

        public Description {
            schema = schema == null ? java.util.Optional.empty() : schema;
            keyColumns = keyColumns == null ? java.util.List.of() : java.util.List.copyOf(keyColumns);
        }
    }

    /**
     * {@code text} with this factory's binding option values struck out of it.
     *
     * <p>A sink failure's message is the plugin's own exception text, and a plugin that echoes its
     * connection string or its password into an exception is common enough that the text cannot be
     * trusted not to. {@code PRV-8009} carries that text to the Flight listing, {@code pravaha
     * queries}, both SDKs and the console, so it is struck out here, once, where the failure is
     * recorded -- the same place and the same rule as a stopped source feed's ({@code
     * FeedRedaction}), and for the same reason: a surface can forget, and the place the failure is
     * written down cannot.
     *
     * <p>Unchanged by default. A factory with no configured options -- every test's, and the
     * embedded engine's -- has nothing to strike, and the HTTP API redacts once more over the top
     * from the bindings it can see.
     */
    default String redact(String text) {
        return text;
    }

    /** The sink itself, opened and ready to be written to. */
    StreamSinkPlugin open(@Nullable String sinkName);

    /**
     * Lets go of a sink {@link #open} returned, when the registration writing to it is dropped.
     *
     * <p>Closes it by default. A factory that tracks what it opened -- so it can close everything at
     * shutdown -- overrides this to forget it as well, or a node that registers and drops queries
     * all day holds every sink it ever opened.
     */
    default void release(StreamSinkPlugin sink) {
        try {
            sink.close();
        } catch (Exception e) {
            // Closing a sink nobody writes to any more; a failure here has nobody to tell.
        }
    }
}
