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

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.egress.SinkBindingProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The configuration file against what the server actually bound.
 *
 * <p>Built from real YAML through Spring's real loader and real binder, not from a hand-made
 * property map: the whole class of defect here is <em>Spring's own interpretation</em> of a value,
 * and a fixture that skipped the binder would assert about a mechanism that is not the one running.
 */
class ConfigurationCheckTest {

    private static ConfigurationCheck checkOf(String yaml) {
        StandardEnvironment environment = new StandardEnvironment();
        List<PropertySource<?>> sources;
        try {
            sources = new YamlPropertySourceLoader()
                    .load("test", new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("the fixture YAML did not load", e);
        }
        sources.forEach(environment.getPropertySources()::addFirst);

        Binder binder = new Binder(ConfigurationPropertySources.get(environment));
        StreamDeclarationProperties streams =
                binder.bind("pravaha", StreamDeclarationProperties.class).orElseGet(StreamDeclarationProperties::new);
        SourceBindingProperties sources0 =
                binder.bind("pravaha", SourceBindingProperties.class).orElseGet(SourceBindingProperties::new);
        SinkBindingProperties sinks =
                binder.bind("pravaha", SinkBindingProperties.class).orElseGet(SinkBindingProperties::new);
        return new ConfigurationCheck(environment, streams, sources0, sinks);
    }

    // ------------------------------------------------------------------ DOCX-21

    @org.junit.jupiter.api.AfterEach
    void clearTheConfiguredHelpBase() {
        com.ash.messaging.pravaha.api.HelpUrls.configure(null);
    }

    @Test
    void aDocsBaseUrlThatIsNotAUrlIsRefusedAtStartupByName_DOCX21() {
        // The whole point of making this configuration is that the value reaches every error
        // message this node will ever print. A node that started with `docs.example.test/errors/`
        // would emit `docs.example.test/errors/PRV-2002` on every failure -- not a URL, and
        // discovered by whoever clicked it.
        ConfigurationCheck check = checkOf("""
                pravaha:
                  docs:
                    base-url: "docs.example.test/errors/"
                """);

        assertThatThrownBy(check::check)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-1029")
                .hasMessageContaining("pravaha.docs.base-url is 'docs.example.test/errors/'")
                .hasMessageContaining("docs/TROUBLESHOOTING.md");
    }

    @Test
    void aConfiguredDocsBaseUrlReachesEveryErrorCode_DOCX21() {
        ConfigurationCheck check = checkOf("""
                pravaha:
                  docs:
                    base-url: "http://localhost:8088/help/errors"
                """);

        assertThatCode(check::check).doesNotThrowAnyException();
        assertThat(new com.ash.messaging.pravaha.api.ErrorCode(2002, "X").helpUrl())
                .isEqualTo("http://localhost:8088/help/errors/PRV-2002");
    }

    /** Unset is a supported state, and it means no URL rather than the old dead one. */
    @Test
    void withNoDocsBaseUrlTheNodeStartsAndEmitsNoUrl_DOCX21() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv(com.ash.messaging.pravaha.api.HelpUrls.ENVIRONMENT_VARIABLE) == null,
                "PRAVAHA_DOCS_BASE_URL is set in this shell, so 'unset' cannot be observed here");
        ConfigurationCheck check = checkOf("""
                pravaha:
                  streams:
                    txn: {schema: "a:INT64"}
                """);

        assertThatCode(check::check).doesNotThrowAnyException();
        assertThat(com.ash.messaging.pravaha.api.HelpUrls.configured()).isFalse();
        assertThat(new com.ash.messaging.pravaha.api.ErrorCode(2002, "X").helpUrl())
                .isEmpty();
    }

    // ------------------------------------------------------------------ CFG-3(a)

    @Test
    void aStreamTheBinderSilentlyDroppedIsRefusedByName_CFG3() {
        // Seven names declared, five in the catalog. `txn ` and `txnü` are discarded by Spring's
        // relaxed map-key canonicalisation before StreamDeclarationProperties sees them, so the
        // declaration is in the file, absent from the node, and reported at NO LOG LEVEL AT ALL.
        // The first query against one then fails with "Object 'txnü' not found. Known streams:
        // [...]" -- accurate, and unactionable beside a file that clearly declares it.
        ConfigurationCheck check = checkOf("""
                pravaha:
                  streams:
                    txn: {schema: "a:INT64"}
                    "txn ": {schema: "a:INT64"}
                    "txnü": {schema: "a:INT64"}
                """);

        assertThatThrownBy(check::check)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-1027")
                .hasMessageContaining("pravaha.streams.txn ")
                .hasMessageContaining("pravaha.streams.txnü")
                // And it names the remedy, which is Spring's own bracket form.
                .hasMessageContaining("[txnü]");
    }

    @Test
    void theNamesThatDoBindAreNotAccusedOfAnything_CFG3() {
        // V-control, and the one that matters: a refusal that fires on `my-stream`, `1txn`,
        // `select`, `TXN` or a camelCase key would be worse than the silence it replaces. `TXN` and
        // `txn` coexisting is the proof the catalog does not fold case.
        ConfigurationCheck check = checkOf("""
                pravaha:
                  streams:
                    my-stream: {schema: "a:INT64"}
                    1txn: {schema: "a:INT64"}
                    select: {schema: "a:INT64"}
                    TXN: {schema: "a:INT64"}
                    txn: {schema: "a:INT64"}
                    camelCase: {schema: "a:INT64"}
                """);

        assertThatCode(check::check).doesNotThrowAnyException();
    }

    @Test
    void aBracketedKeyBindsVerbatimAndIsAccepted_CFG3() {
        // The remedy the message offers has to work, or the message is worse than nothing.
        ConfigurationCheck check = checkOf("""
                pravaha:
                  streams:
                    "[txnü]": {schema: "a:INT64"}
                """);

        assertThatCode(check::check).doesNotThrowAnyException();
    }

    @Test
    void aDroppedSourceOrSinkKeyIsRefusedToo_CFG3() {
        assertThatThrownBy(() -> checkOf("""
                                pravaha:
                                  sources:
                                    "tx n": {plugin: filesystem}
                                """).check())
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("pravaha.sources.tx n");
    }

    // ------------------------------------------------------------------ CFG-15

    @Test
    void aBareNumberOnADurationIsRefusedRatherThanReadAsMilliseconds_CFG15() {
        // CFG-15. Spring's binder reads a bare number on a Duration field as MILLISECONDS, and the
        // engine's own ConfigParsers.parseDuration refuses one precisely so this cannot happen. A
        // node written for two seconds and given `interval: 2` left checkpoint-6407/6408/6409.bin
        // after twenty seconds, against checkpoint-8/9/10.bin for `interval: 2s` on identical
        // timing -- with no warning, and nothing in the log naming the interval in force.
        assertThatThrownBy(() -> checkOf("""
                                pravaha:
                                  checkpoint:
                                    interval: 2
                                """).check())
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-1023")
                .hasMessageContaining("pravaha.checkpoint.interval")
                .hasMessageContaining("MILLISECONDS")
                .hasMessageContaining("2s");
    }

    @Test
    void everyUnitedSpellingOfTheSameDurationIsAccepted_CFG15() {
        // V-control. PT2S and 2s produce identical results and must both stay legal; refusing the
        // bare number must not turn into refusing the forms that work.
        for (String written : List.of("2s", "PT2S", "2000ms", "1m")) {
            assertThatCode(() -> checkOf("pravaha:\n  checkpoint:\n    interval: " + written + "\n")
                            .check())
                    .as("interval: %s", written)
                    .doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------ CFG-8

    @Test
    void oneStreamWithTwoDisagreeingSchemasIsRefused_CFG8() {
        // CFG-8. pravaha.streams.<n>.schema is what a query is planned against and the binding's
        // own `schema` option is what the plugin decodes rows with, and nothing compared them. The
        // divergence surfaced at the first registration as PRV-5040 "event.time names 'event_time',
        // which is not a column of stream 'txn'" -- a message about the binding's schema wearing
        // the declaration's column name.
        assertThatThrownBy(() -> checkOf("""
                                pravaha:
                                  streams:
                                    txn: {schema: "id:INT64,user_id:STRING,event_time:TIMESTAMP"}
                                  sources:
                                    txn:
                                      plugin: filesystem
                                      options: {path: /tmp/txn.csv, schema: "id:INT64,user_id:STRING"}
                                """).check())
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("two schemas")
                .hasMessageContaining("txn");
    }

    @Test
    void twoSpellingsOfOneSchemaAreTheSameSchema_CFG8() {
        // V-control. Spaces between columns and the case of a type name are not a divergence, and
        // a check that said they were would be the new defect.
        assertThatCode(() -> checkOf("""
                                pravaha:
                                  streams:
                                    txn: {schema: "id:INT64, user_id:STRING"}
                                  sources:
                                    txn:
                                      plugin: filesystem
                                      options: {path: /tmp/txn.csv, schema: "id:int64,user_id:string"}
                                """).check()).doesNotThrowAnyException();
    }

    @Test
    void aSourceBoundToAnUndeclaredStreamStartsAndIsReported_CFG8() {
        // Warned, not refused: a stream can also be declared over POST /api/v1/streams after the
        // node is up, and refusing would break every deployment that does. What must not happen
        // again is the node logging "sources bound: [txn <- filesystem[...]]" and then answering
        // GET /api/v1/streams with [] and register with "Known streams: []", with no line anywhere
        // pairing the two facts.
        assertThatCode(() -> checkOf("""
                                pravaha:
                                  streams:
                                    s1: {schema: "a:INT64"}
                                  sources:
                                    txns:
                                      plugin: filesystem
                                      options: {path: /tmp/txn.csv}
                                """).check()).doesNotThrowAnyException();
    }

    @Test
    void aFileThatIsConsistentPassesEveryCheck_CFG8() {
        ConfigurationCheck check = checkOf("""
                pravaha:
                  checkpoint:
                    interval: 1m
                  streams:
                    txn: {schema: "id:INT64,user_id:STRING", event-time: ts}
                  sources:
                    txn:
                      plugin: filesystem
                      options: {path: /tmp/txn.csv, schema: "id:INT64,user_id:STRING"}
                """);

        assertThatCode(check::check).doesNotThrowAnyException();
    }

    @Test
    void theseChecksRunWhileTheBeanIsBuilt_CFG3_CFG8_CFG15() throws Exception {
        // One bad value should be one startup failure, before the node starts and before the web
        // server -- not a query refused per client on a node that passed every probe.
        assertThat(ConfigurationCheck.class
                        .getMethod("check")
                        .isAnnotationPresent(jakarta.annotation.PostConstruct.class))
                .as("ConfigurationCheck.check must run at bean initialisation")
                .isTrue();
    }

    @Test
    void anEmptyEnvironmentIsNotAnError() {
        // The default node configures none of this, and a check that refused a node with no
        // streams block would be found by every deployment at once.
        ConfigurationCheck check = checkOf("pravaha:\n  node:\n    id: n1\n");
        assertThatCode(check::check).doesNotThrowAnyException();
        assertThat(check).isNotNull();
    }
}
