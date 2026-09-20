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
package com.ash.messaging.pravaha.server.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import com.ash.messaging.pravaha.api.HelpUrls;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.ConfigErrors;

/**
 * What the configuration file says against what the server actually bound.
 *
 * <p>Three defects in one place, because they are one defect: a value that is present in the file,
 * correct-looking, and reaching nothing. Nobody finds those by reading the file -- the file is
 * where they look, and the file is right.
 *
 * <dl>
 *   <dt>CFG-3(a) -- a declared key the binder dropped
 *   <dd>Spring canonicalises a map key before binding it, and silently discards one it cannot.
 *       Seven streams declared, five in the catalog: {@code txn } (trailing space) and {@code txnü}
 *       vanished <strong>with no message at any level</strong>, and the first query against one
 *       failed with "Object 'txnü' not found. Known streams: [...]" -- accurate, and impossible to
 *       act on next to a file that clearly declares it. The raw property names are still in the
 *       {@code Environment}, so the difference between what was written and what was bound is
 *       readable, and Spring's own bracket form is the remedy the message names.
 *   <dt>CFG-15 -- a bare number on a Duration
 *   <dd>Spring reads {@code interval: 2} as two <em>milliseconds</em>. The engine's own
 *       {@code ConfigParsers.parseDuration} refuses a bare number precisely so this cannot happen,
 *       and the two dialects meet in one YAML file: a node written for two seconds produced 6,409
 *       checkpoints in twenty seconds, with no warning. Refused here by reading the value as the
 *       operator wrote it, before the binder's interpretation can hide it.
 *   <dt>CFG-8 -- streams and sources never reconciled
 *   <dd>Nothing compared {@code pravaha.sources} with {@code pravaha.streams}. A node could log
 *       "sources bound: [txn &lt;- filesystem[…]]" and then answer {@code GET /api/v1/streams} with
 *       {@code []}; {@code txn} declared and {@code txns} bound paired in no line anywhere; and two
 *       schemas for one stream -- the declaration's and the binding's own {@code schema} option --
 *       diverged until the first registration blamed a column name on the wrong one.
 * </dl>
 *
 * <p>All of it runs while this bean is initialised, before the node starts and before the web
 * server: one bad value is one startup failure, rather than every registration failing separately
 * on a node that passes each probe. That is the argument {@code PravahaNode} already made for
 * {@code pravaha.watermark.idle-after} and made for nothing else.
 */
@org.springframework.stereotype.Component
public class ConfigurationCheck {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationCheck.class);

    /** Map-valued blocks whose keys an operator writes, and which the binder may therefore drop. */
    private static final List<String> MAP_PREFIXES =
            List.of("pravaha.streams.", "pravaha.sources.", "pravaha.lookups.", "pravaha.sinks.");

    /** Duration keys a bare number silently reinterprets. Spring reads one as milliseconds. */
    private static final List<String> DURATION_KEYS = List.of(
            "pravaha.checkpoint.interval",
            "pravaha.checkpoint.timeout",
            "pravaha.watermark.idle-after",
            "pravaha.watermark.tick");

    private final Environment environment;
    private final com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties declaredStreams;
    private final com.ash.messaging.pravaha.server.ingest.SourceBindingProperties sources;
    private final com.ash.messaging.pravaha.server.egress.SinkBindingProperties sinks;

    public ConfigurationCheck(
            Environment environment,
            com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties declaredStreams,
            com.ash.messaging.pravaha.server.ingest.SourceBindingProperties sources,
            com.ash.messaging.pravaha.server.egress.SinkBindingProperties sinks) {
        this.environment = environment;
        this.declaredStreams = declaredStreams;
        this.sources = sources;
        this.sinks = sinks;
    }

    @jakarta.annotation.PostConstruct
    public void check() {
        applyDocsBaseUrl();
        refuseKeysThatReachedNothing();
        refuseBareNumberDurations();
        reconcileStreamsAndSources();
    }

    // ---------------------------------------------------------------- DOCX-21

    /**
     * Publishes {@code pravaha.docs.base-url} to the engine, and refuses a value that is not a URL.
     *
     * <p>Here rather than in a {@code @ConfigurationProperties} bean because it has to run before
     * anything can fail: a node whose first act is to report a misconfiguration must not report it
     * with a help link built out of a second misconfiguration. Unset is a supported state -- no
     * base means no URL anywhere, and every message that would have carried one says how to look
     * the code up offline instead.
     */
    private void applyDocsBaseUrl() {
        String written = environment.getProperty(HelpUrls.KEY);
        HelpUrls.configureOrFromEnvironment(written);
        if (HelpUrls.configured()) {
            log.info("help pages: {} -> {}PRV-nnnn", HelpUrls.KEY, HelpUrls.base());
        } else {
            log.info(
                    "help pages: {} is unset, so failures carry no help URL; {}",
                    HelpUrls.KEY,
                    HelpUrls.lookupHint(null));
        }
    }

    // ---------------------------------------------------------------- CFG-3(a)

    /**
     * Refuses a map key that is in the file and not in the bound map.
     *
     * <p>Compared against the bound maps rather than against a list of allowed characters, so the
     * check says what Spring actually did rather than a second guess at it that can drift from it.
     */
    private void refuseKeysThatReachedNothing() {
        List<String> lost = new ArrayList<>();
        for (String prefix : MAP_PREFIXES) {
            Set<String> bound = boundKeysUnder(prefix);
            for (String written : keysWrittenUnder(prefix)) {
                if (!bound.contains(written)) {
                    lost.add(prefix + written);
                }
            }
        }
        if (lost.isEmpty()) {
            return;
        }
        throw new PravahaException(
                ConfigErrors.KEY_UNREACHABLE,
                "these keys are in the configuration and reached nothing: " + lost + ". Spring binds a "
                        + "map key only after canonicalising it, and discards one it cannot -- a trailing "
                        + "space, a non-ASCII letter -- without a message at any level, so the stream or "
                        + "binding is in the file, absent from the node, and the first query against it "
                        + "reports it as unknown. Quote the key in brackets to bind it verbatim, for "
                        + "example \"[txnü]\": {...}, or rename it to letters, digits and hyphens.");
    }

    /** The first path segment under {@code prefix} of every property name the environment carries. */
    private Set<String> keysWrittenUnder(String prefix) {
        Set<String> written = new LinkedHashSet<>();
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return written;
        }
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                // Exact, lower-case prefix. An environment variable spells the same setting
                // PRAVAHA_STREAMS_TXN_SCHEMA, which is a different name and not one an operator
                // can get wrong in this way, so it is left out rather than guessed at.
                if (!name.startsWith(prefix) || name.length() == prefix.length()) {
                    continue;
                }
                written.add(firstSegment(name.substring(prefix.length())));
            }
        }
        return written;
    }

    /** {@code txn.schema} -> {@code txn}; {@code [txn ].schema} -> {@code txn }. */
    private static String firstSegment(String rest) {
        if (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            return close < 0 ? rest.substring(1) : rest.substring(1, close);
        }
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    private Set<String> boundKeysUnder(String prefix) {
        return switch (prefix) {
            case "pravaha.streams." -> declaredStreams.getStreams().keySet();
            case "pravaha.sources." -> sources.getSources().keySet();
            case "pravaha.lookups." -> sources.getLookups().keySet();
            case "pravaha.sinks." -> sinks.getSinks().keySet();
            default -> Set.of();
        };
    }

    // ---------------------------------------------------------------- CFG-15

    /** Refuses {@code interval: 2}, which Spring reads as two milliseconds and nobody ever means. */
    private void refuseBareNumberDurations() {
        for (String key : DURATION_KEYS) {
            String written = environment.getProperty(key);
            if (written == null || !written.strip().matches("-?\\d+")) {
                continue;
            }
            throw new PravahaException(
                    com.ash.messaging.pravaha.common.config.ConfigErrors.NOT_A_DURATION,
                    key + " is '" + written.strip() + "', a number with no unit, which Spring reads as "
                            + written.strip() + " MILLISECONDS. Almost nobody means that: the engine's own "
                            + "duration parser refuses a bare number for this reason, and a node written "
                            + "for two seconds and given '2' took 6,409 checkpoints in twenty seconds with "
                            + "no warning. Write the unit -- 2s, 500ms, 1m -- or ISO-8601, PT2S.");
        }
    }

    // ---------------------------------------------------------------- CFG-8

    /**
     * Says how the two blocks line up, and refuses the one pairing that is silently wrong.
     *
     * <p>A source bound to an undeclared stream is a <em>warning</em>, not a refusal: a stream can
     * also be declared over {@code POST /api/v1/streams} after the node is up, and refusing would
     * break the deployments that do. A declaration with no binding is a warning too -- plenty of
     * queries are fed by a client pushing rows. What is refused is the third case, two schemas for
     * one stream that disagree, because there the node has both answers and picks one per surface:
     * the divergence used to surface at the first registration as {@code PRV-5040 event.time names
     * 'event_time', which is not a column of stream 'txn'} -- a message about the binding's schema
     * wearing the declaration's column name.
     */
    private void reconcileStreamsAndSources() {
        Set<String> declared = declaredStreams.getStreams().keySet();
        Set<String> bound = sources.getSources().keySet();

        List<String> boundButNotDeclared =
                bound.stream().filter(name -> !declared.contains(name)).sorted().toList();
        if (!boundButNotDeclared.isEmpty()) {
            log.warn(
                    "bound and undeclared: {} -- pravaha.sources names these and pravaha.streams does not, "
                            + "so the node reads rows into streams no query can be planned against and "
                            + "GET /api/v1/streams will not list them. Declare each under pravaha.streams "
                            + "with its schema, or POST /api/v1/streams before registering",
                    boundButNotDeclared);
        }
        List<String> declaredButNotBound =
                declared.stream().filter(name -> !bound.contains(name)).sorted().toList();
        if (!declaredButNotBound.isEmpty()) {
            log.info(
                    "declared and unbound: {} -- these streams can be planned against and nothing feeds "
                            + "them, which is correct for a stream a client pushes rows into and is silence "
                            + "otherwise. Bind one under pravaha.sources.<stream>",
                    declaredButNotBound);
        }
        refuseDivergentSchemas();
    }

    private void refuseDivergentSchemas() {
        List<String> divergent = new ArrayList<>();
        sources.getSources().forEach((stream, spec) -> {
            String fromBinding =
                    spec.getOptions() == null ? null : spec.getOptions().get("schema");
            var declaration = declaredStreams.getStreams().get(stream);
            String fromDeclaration = declaration == null ? null : declaration.getSchema();
            if (fromBinding == null || fromDeclaration == null) {
                return;
            }
            if (!normalised(fromBinding).equals(normalised(fromDeclaration))) {
                divergent.add("'" + stream + "': pravaha.streams." + stream + ".schema is \"" + fromDeclaration
                        + "\" and pravaha.sources." + stream + ".options.schema is \"" + fromBinding + "\"");
            }
        });
        if (divergent.isEmpty()) {
            return;
        }
        throw new PravahaException(
                com.ash.messaging.pravaha.sql.SqlErrors.VALIDATION_FAILED,
                "one stream, two schemas: " + divergent + ". The declaration is what a query is planned "
                        + "against and the binding's option is what the plugin decodes rows with, so a "
                        + "divergence is a node that plans one shape and reads another -- and it used to "
                        + "surface at the first registration as a complaint about a column name, blaming "
                        + "whichever of the two the message happened to be built from. Write the schema "
                        + "once, under pravaha.streams, and leave the binding's option out.");
    }

    /** Column order and names matter; the spaces an operator puts between them do not. */
    private static String normalised(String schema) {
        return schema.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
    }
}
