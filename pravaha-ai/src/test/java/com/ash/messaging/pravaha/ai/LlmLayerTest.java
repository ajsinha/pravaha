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
package com.ash.messaging.pravaha.ai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The provider layer with no network (Drishti's {@code LlmLayerTest}): provider files, the registry and its
 * reload, the mock, the master switch, the policy (roles, per-minute, per-day and cost budgets, masking, an
 * audit row that never carries the prompt or the answer), literal keys refused, failed providers retried.
 */
class LlmLayerTest {

    @TempDir
    Path dir;

    private LlmRegistry registry(Map<String, String> env) {
        LlmRegistry r = new LlmRegistry(
                ProviderFactories.discover(List.of()), () -> ProviderConfigLoader.load(dir, env::get), "");
        r.reload();
        return r;
    }

    private void write(String name, String text) throws Exception {
        Files.writeString(dir.resolve(name), text);
    }

    private static final String MOCK = """
            type: mock
            default-model: m
            models:
              - name: m
                cost-per-1k-input: 1.0
                cost-per-1k-output: 1.0
              - name: vec
                kind: embedding
            mock:
              rules:
                - match: "(?i)hello"
                  reply: "Hi there"
                - match: "weather"
                  tool: get_weather
                  tool-args: '{"city":"Oslo"}'
                - match: "boom"
                  error: "scripted"
            """;

    @Test
    void mockProviderAnswersByRuleEchoToolsErrorsAndEmbeds() throws Exception {
        write("mock.yaml", MOCK);
        LlmRegistry reg = registry(Map.of());
        LlmGateway gw = new LlmGateway(reg, LlmPolicy.none());
        CallContext ctx = new CallContext("ann", Set.of(), "test");
        assertThat(gw.chat(ctx, ChatRequest.of(null, "sys", "Hello you", 50, null))
                        .text())
                .isEqualTo("Hi there");
        assertThat(gw.chat(ctx, ChatRequest.of("mock/m", "", "plain", 50, null)).text())
                .isEqualTo("echo: plain");
        ChatResponse tool = gw.chat(ctx, ChatRequest.of("m", "", "the weather", 50, null));
        assertThat(tool.toolCalls())
                .singleElement()
                .satisfies(c -> assertThat(c.name()).isEqualTo("get_weather"));
        assertThat(tool.usage().cost()).isGreaterThan(0);
        assertThatThrownBy(() -> gw.chat(ctx, ChatRequest.of(null, "", "boom", 5, null)))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.detail()).isEqualTo("scripted");
                    assertThat(e.getMessage()).startsWith("PRV-1087");
                });
        assertThat(reg.embedding("mock/vec")
                        .orElseThrow()
                        .embed(List.of("abc", "abc"))
                        .get(0))
                .isEqualTo(
                        reg.embedding("vec").orElseThrow().embed(List.of("abc")).get(0));
        assertThat(reg.health(reg.provider("mock").orElseThrow()).status())
                .isEqualTo(LlmRegistry.Status.DOWN); // the last call failed
    }

    @Test
    void theMasterSwitchOffRefusesEveryCallWithItsOwnCode() throws Exception {
        write("mock.yaml", MOCK);
        AtomicBoolean on = new AtomicBoolean(false);
        LlmGateway gw = new LlmGateway(registry(Map.of()), LlmPolicy.none(), on::get);
        assertThat(gw.enabled()).isFalse();
        assertThatThrownBy(
                        () -> gw.chat(new CallContext("a", Set.of(), "t"), ChatRequest.of(null, "", "hello", 5, null)))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(AiErrors.DISABLED);
                    assertThat(e.getMessage()).startsWith("PRV-1080").contains("pravaha.ai.enabled");
                });
        on.set(true);
        assertThat(gw.chat(new CallContext("a", Set.of(), "t"), ChatRequest.of(null, "", "hello", 5, null))
                        .text())
                .isEqualTo("Hi there");
    }

    @Test
    void timeoutIsHonouredAndReportedAsTimeout() throws Exception {
        write("slow.yaml", "type: mock\nmodels:\n  - name: m\nmock:\n  latency-ms: 2000\n");
        LlmGateway gw = new LlmGateway(registry(Map.of()), LlmPolicy.none());
        assertThatThrownBy(() -> gw.chat(
                        new CallContext("a", Set.of(), "t"), ChatRequest.of(null, "", "x", 5, Duration.ofMillis(100))))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.timeout()).isTrue();
                    assertThat(e.errorCode()).isEqualTo(AiErrors.TIMEOUT);
                });
    }

    @Test
    void configProblemsAreReportedPerFileAndOthersStillLoad() throws Exception {
        write("good.yaml", "type: mock\nmodels:\n  - name: m\n");
        write("literal.yaml", "type: openai\napi-key: sk-123\n");
        write("literal-secret.yaml", "type: bedrock\naws-secret-access-key: wJalrXUtnFEMI\n");
        write("literal-header.yaml", "type: mock\nheader:\n  Authorization: Bearer abc\n");
        write("notype.yaml", "models: []\n");
        write("unknown.yaml", "type: nonesuch\nmodels:\n  - name: x\n");
        write("off.yaml", "type: mock\nenabled: false\nmodels:\n  - name: y\n");
        write("ignored.yaml.example", "type: mock\nmodels:\n  - name: z\n");
        LlmRegistry reg = registry(Map.of());
        assertThat(reg.fileErrors())
                .containsKeys("literal.yaml", "literal-secret.yaml", "literal-header.yaml", "notype.yaml");
        assertThat(reg.fileErrors().get("literal.yaml")).contains("placeholder").doesNotContain("sk-123");
        assertThat(reg.fileErrors().get("literal-secret.yaml"))
                .contains("placeholder")
                .doesNotContain("wJalr");
        assertThat(reg.fileErrors().get("literal-header.yaml"))
                .contains("placeholder")
                .doesNotContain("abc");
        assertThat(reg.health(reg.provider("unknown").orElseThrow()).status()).isEqualTo(LlmRegistry.Status.ERROR);
        assertThat(reg.health(reg.provider("off").orElseThrow()).status()).isEqualTo(LlmRegistry.Status.OFF);
        assertThat(reg.health(reg.provider("good").orElseThrow()).status()).isEqualTo(LlmRegistry.Status.UNKNOWN);
        assertThat(reg.models()).extracting(ModelInfo::ref).containsExactly("good/m");
        assertThat(reg.provider("ignored")).isEmpty();
    }

    @Test
    void aKeyComesFromTheEnvironmentAndNeverAppearsInTheConfigsText() throws Exception {
        write("oai.yaml", "type: openai\napi-key: ${OAI_KEY}\nmodels:\n  - name: m\n");
        write("dflt.yaml", "type: openai\napi-key: ${MISSING_KEY:-fallback-key}\nmodels:\n  - name: n\n");
        LlmRegistry reg = registry(Map.of("OAI_KEY", "sekret"));
        ProviderConfig c = reg.provider("oai").orElseThrow().config();
        assertThat(c.apiKey()).isEqualTo("sekret");
        assertThat(c.toString()).contains("apiKey=set").doesNotContain("sekret");
        assertThat(reg.provider("dflt").orElseThrow().config().apiKey()).isEqualTo("fallback-key");
    }

    @Test
    void reloadPicksUpAddedChangedAndRemovedFiles() throws Exception {
        write("a.yaml", "type: mock\nmodels:\n  - name: one\n");
        LlmRegistry reg = registry(Map.of());
        assertThat(reg.models()).extracting(ModelInfo::ref).containsExactly("a/one");
        write("b.yaml", "type: mock\nmodels:\n  - name: two\n");
        Files.delete(dir.resolve("a.yaml"));
        reg.reload();
        assertThat(reg.models()).extracting(ModelInfo::ref).containsExactly("b/two");
        write("b.yaml", "type: mock\nmodels:\n  - name: three\n");
        reg.reload();
        assertThat(reg.models()).extracting(ModelInfo::ref).containsExactly("b/three");
    }

    @Test
    void aProviderThatCouldNotBeBuiltIsRetriedOnItsScheduleNotAtEveryLook() throws Exception {
        MutableClock clock = new MutableClock();
        RetryBoard board = new RetryBoard(Duration.ofSeconds(5), Duration.ofMinutes(5), clock);
        Map<String, String> env = new java.util.HashMap<>();
        write("az.yaml", "type: azure_openai\napi-key: ${AZ_KEY}\nmodels:\n  - name: m\n");
        LlmRegistry reg = new LlmRegistry(
                ProviderFactories.discover(List.of()), () -> ProviderConfigLoader.load(dir, env::get), "", board);
        reg.reload();
        assertThat(reg.health(reg.provider("az").orElseThrow()).status()).isEqualTo(LlmRegistry.Status.ERROR);
        assertThat(board.failing("az")).isTrue();
        assertThat(board.statuses().get("az").permanent())
                .as("a missing base-url will not mend by waiting")
                .isTrue();
        env.put("AZURE_OPENAI_ENDPOINT", "https://example.openai.azure.com");
        env.put("AZ_KEY", "k");
        reg.reload(); // the file's effective configuration changed: a fresh start
        assertThat(reg.health(reg.provider("az").orElseThrow()).status()).isEqualTo(LlmRegistry.Status.UNKNOWN);
        assertThat(board.failing("az")).isFalse();

        board.failed("x", "temporary", false);
        assertThat(board.due()).isEmpty();
        clock.advance(Duration.ofSeconds(6));
        assertThat(board.due()).containsExactly("x");
        board.failed("x", "temporary", false);
        assertThat(board.statuses().get("x").attempts()).isEqualTo(2);
        assertThat(board.retryAllNow()).isEqualTo(1);
        assertThat(board.due()).containsExactly("x");
    }

    @Test
    void ambiguousAndMissingModelNames() throws Exception {
        write("a.yaml", "type: mock\nmodels:\n  - name: same\n  - name: vec\n    kind: embedding\n");
        write("b.yaml", "type: mock\nmodels:\n  - name: same\n");
        LlmRegistry reg = registry(Map.of());
        assertThatThrownBy(() -> reg.chat("same")).hasMessageContaining("several providers");
        assertThatThrownBy(() -> reg.chat("nope"))
                .isInstanceOfSatisfying(
                        LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.NOT_FOUND));
        assertThatThrownBy(() -> reg.chat("a/vec"))
                .isInstanceOfSatisfying(
                        LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.UNSUPPORTED));
        assertThatThrownBy(() -> reg.chat(null)).hasMessageContaining("several chat models");
        assertThat(reg.chat("a/same").info().ref()).isEqualTo("a/same");
    }

    @Test
    void jsonModeAndToolsNeedTheCapability() throws Exception {
        write(
                "plain.yaml",
                "type: mock\nmodels:\n  - name: m\n    capabilities: [chat]\n  - name: n\n    capabilities: [chat, streaming]\n");
        LlmGateway gw = new LlmGateway(registry(Map.of()), LlmPolicy.none());
        CallContext c = new CallContext("a", Set.of(), "t");
        ChatRequest json = new ChatRequest("n", "", List.of(ChatMessage.user("x")), List.of(), null, null, true, null);
        assertThatThrownBy(() -> gw.chat(c, json)).hasMessageContaining("json-mode");
        ChatRequest tools = new ChatRequest(
                "n",
                "",
                List.of(ChatMessage.user("x")),
                List.of(new ToolSpec("t", "d", null)),
                null,
                null,
                false,
                null);
        assertThatThrownBy(() -> gw.chat(c, tools)).hasMessageContaining("tool-calls");
        assertThat(gw.chat(c, json.withModel("m")).text())
                .isEqualTo("echo: x"); // chat-only models get the type's defaults
    }

    @Test
    void policyMasksLimitsAuditsAndGatesFeaturesByRole() throws Exception {
        write("mock.yaml", "type: mock\nmodels:\n  - name: m\n    cost-per-1k-input: 1000\n");
        List<DefaultLlmPolicy.AuditRecord> audit = new ArrayList<>();
        PolicySettings ps = new PolicySettings(
                true, List.of("\\b\\d{16}\\b"), true, 2, 0, 0, Map.of("admin-only", Set.of("admin")), Map.of());
        LlmGateway gw = new LlmGateway(
                registry(Map.of()),
                new DefaultLlmPolicy(
                        ps, new PromptMasker(List.of("salary"), ps.maskPatterns()), audit::add, Clock.systemUTC()));
        CallContext ann = new CallContext("ann", Set.of("user"), "help");
        ChatResponse r = gw.chat(ann, ChatRequest.of(null, "", "salary: 90000 card 1234567812345678 ok", 5, null));
        assertThat(r.text()).isEqualTo("echo: salary: ••• card ••• ok"); // the model never saw the values
        assertThat(audit).singleElement().satisfies(a -> {
            assertThat(a.user()).isEqualTo("ann");
            assertThat(a.feature()).isEqualTo("help");
            assertThat(a.provider()).isEqualTo("mock");
            assertThat(a.model()).isEqualTo("m");
            assertThat(a.outcome()).isEqualTo("ok");
            assertThat(a.promptTokens()).isPositive();
            assertThat(a.toString())
                    .as("an audit row never carries the prompt or the answer")
                    .doesNotContain("salary")
                    .doesNotContain("card")
                    .doesNotContain("echo");
        });
        assertThatThrownBy(() -> gw.chat(
                        new CallContext("ann", Set.of("user"), "admin-only"), ChatRequest.of(null, "", "x", 5, null)))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(LlmException.Kind.POLICY);
                    assertThat(e.errorCode()).isEqualTo(AiErrors.POLICY);
                });
        assertThat(gw.chat(
                                new CallContext("root", Set.of("admin"), "admin-only"),
                                ChatRequest.of(null, "", "x", 5, null))
                        .text())
                .isEqualTo("echo: x");
        gw.chat(ann, ChatRequest.of(null, "", "again", 5, null));
        assertThatThrownBy(() -> gw.chat(ann, ChatRequest.of(null, "", "third", 5, null)))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(LlmException.Kind.RATE_LIMITED);
                    assertThat(e.detail()).contains("try again in");
                });
        assertThat(gw.chat(
                                new CallContext("ann", Set.of("user"), "other"),
                                ChatRequest.of(null, "", "another feature", 5, null))
                        .text())
                .startsWith("echo:");
    }

    @Test
    void perDayAndDailyCostLimitsStopFurtherCalls() throws Exception {
        write("mock.yaml", "type: mock\nmodels:\n  - name: m\n    cost-per-1k-input: 1000\n");
        PolicySettings day = new PolicySettings(true, List.of(), false, 0, 2, 0, Map.of(), Map.of());
        LlmGateway daily = new LlmGateway(
                registry(Map.of()), new DefaultLlmPolicy(day, PromptMasker.none(), null, Clock.systemUTC()));
        CallContext d = new CallContext("dee", Set.of(), "f");
        daily.chat(d, ChatRequest.of(null, "", "one", 5, null));
        daily.chat(d, ChatRequest.of(null, "", "two", 5, null));
        assertThatThrownBy(() -> daily.chat(d, ChatRequest.of(null, "", "three", 5, null)))
                .hasMessageContaining("daily limit of 2");

        MutableClock clock = new MutableClock();
        PolicySettings cost = new PolicySettings(true, List.of(), false, 0, 0, 0.5, Map.of(), Map.of());
        LlmGateway gw =
                new LlmGateway(registry(Map.of()), new DefaultLlmPolicy(cost, PromptMasker.none(), null, clock));
        CallContext c = new CallContext("bo", Set.of(), "f");
        gw.chat(c, ChatRequest.of(null, "", "a long enough prompt to cost something", 5, null));
        assertThatThrownBy(() -> gw.chat(c, ChatRequest.of(null, "", "x", 5, null)))
                .hasMessageContaining("cost limit");
        clock.advance(Duration.ofDays(1));
        assertThat(gw.chat(c, ChatRequest.of(null, "", "a new day", 5, null)).text())
                .isEqualTo("echo: a new day");
    }

    @Test
    void aFailedCallIsAuditedWithItsKindAndAnAuditSinkThatThrowsNeverFailsTheCall() throws Exception {
        write("mock.yaml", MOCK);
        List<DefaultLlmPolicy.AuditRecord> audit = new ArrayList<>();
        LlmGateway gw = new LlmGateway(
                registry(Map.of()),
                new DefaultLlmPolicy(PolicySettings.defaults(), PromptMasker.none(), audit::add, Clock.systemUTC()));
        assertThatThrownBy(
                () -> gw.chat(new CallContext("a", Set.of(), "t"), ChatRequest.of(null, "", "boom", 5, null)));
        assertThat(audit).singleElement().satisfies(a -> assertThat(a.outcome()).isEqualTo("failed"));
        LlmGateway noisy = new LlmGateway(
                registry(Map.of()),
                new DefaultLlmPolicy(
                        PolicySettings.defaults(),
                        PromptMasker.none(),
                        a -> {
                            throw new IllegalStateException("sink down");
                        },
                        Clock.systemUTC()));
        assertThat(noisy.chat(new CallContext("a", Set.of(), "t"), ChatRequest.of(null, "", "hello", 5, null))
                        .text())
                .isEqualTo("Hi there");
    }

    @Test
    void maskingKeepsQuotedValuesQuotedAndMasksNothingWhenOff() {
        PromptMasker m = new PromptMasker(List.of("ssn"), List.of());
        assertThat(m.mask("{\"ssn\":\"123-45-6789\", \"name\":\"ann\"}"))
                .isEqualTo("{\"ssn\":\"•••\", \"name\":\"ann\"}");
        assertThat(m.mask("ssn=123456789;")).isEqualTo("ssn=•••;");
        assertThat(PromptMasker.none().active()).isFalse();
        PolicySettings off = new PolicySettings(false, List.of(), true, 0, 0, 0, Map.of(), Map.of());
        ChatRequest r = ChatRequest.of(null, "", "ssn: 1", null, null);
        assertThat(new DefaultLlmPolicy(off, m, null, Clock.systemUTC())
                        .before(new CallContext(null, null, null), info(), r))
                .isSameAs(r);
    }

    private static ModelInfo info() {
        return new ModelInfo("m", "p", "m", "chat", Set.of(Capability.CHAT), null, null, 0, 0);
    }

    @Test
    void capabilityNamesRoundTripAndTheContextHasDefaults() {
        assertThat(Capability.of("tool-calls")).isEqualTo(Capability.TOOL_CALLS);
        assertThat(Capability.JSON_MODE.id()).isEqualTo("json-mode");
        CallContext c = new CallContext(null, null, " ");
        assertThat(c.user()).isEqualTo("anonymous");
        assertThat(c.feature()).isEqualTo("other");
        assertThat(LlmException.Kind.RATE_LIMITED.id()).isEqualTo("rate_limited");
        for (LlmException.Kind k : LlmException.Kind.values()) {
            assertThat(AiErrors.of(k).number()).isBetween(1081, 1087);
        }
    }

    /** A clock a test moves. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-12T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
