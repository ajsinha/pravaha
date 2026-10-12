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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * A provider with no network: deterministic answers for tests, demos and features built before a real
 * model is chosen (Drishti's {@code MockProvider}; it plays the part the assistant's {@code fake} plays in
 * the Python SDK). Configured under {@code mock.*} in its file:
 *
 * <pre>
 * type: mock
 * mock:
 *   reply: "a fixed answer"      # omit to echo the last user message
 *   latency-ms: 0                # simulated delay (interruptible, so timeouts work)
 *   fail: ""                     # every call fails with this message
 *   fail-every: 0                # every Nth call fails
 *   dimensions: 8                # size of embedding vectors
 *   rules:                       # first rule whose match (regex, found in the last user message) applies
 *     - match: "(?i)hello"
 *       reply: "Hi there"
 *     - match: "weather"
 *       tool: get_weather
 *       tool-args: '{"city":"Oslo"}'
 *     - match: "boom"
 *       error: "scripted failure"
 * </pre>
 *
 * <p>Usage is characters / 4, so cost limits and the audit row can be exercised.
 */
public final class MockProvider extends BaseProvider {

    private record Rule(Pattern match, String reply, String tool, String toolArgs, String error, long latencyMs) {}

    private final List<Rule> rules = new ArrayList<>();
    private final @org.jspecify.annotations.Nullable String reply;
    private final long latencyMs;
    private final String fail;
    private final int failEvery;
    private final int dimensions;
    private final AtomicLong calls = new AtomicLong();

    MockProvider(ProviderConfig c) {
        super(c, Set.of(Capability.TOOL_CALLS, Capability.JSON_MODE));
        this.reply = c.settings().get("mock.reply");
        this.latencyMs = num(c.setting("mock.latency-ms", "0"));
        this.fail = c.setting("mock.fail", "");
        this.failEvery = (int) num(c.setting("mock.fail-every", "0"));
        this.dimensions = (int) Math.max(1, num(c.setting("mock.dimensions", "8")));
        for (int i = 0; c.settings().containsKey("mock.rules." + i + ".match"); i++) {
            String p = "mock.rules." + i + ".";
            rules.add(new Rule(
                    Pattern.compile(c.setting(p + "match", "")),
                    c.setting(p + "reply", ""),
                    c.setting(p + "tool", ""),
                    c.setting(p + "tool-args", "{}"),
                    c.setting(p + "error", ""),
                    num(c.setting(p + "latency-ms", "-1"))));
        }
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            throw new LlmException(LlmException.Kind.CONFIG, "mock setting '" + s + "' is not a number");
        }
    }

    @Override
    public Optional<LlmModel> chatModel(String logicalName) {
        return find(logicalName, false).map(info -> new LlmModel() {
            @Override
            public ModelInfo info() {
                return info;
            }

            @Override
            public ChatResponse chat(ChatRequest r) {
                return answer(info, r);
            }
        });
    }

    @Override
    public Optional<EmbeddingModel> embeddingModel(String logicalName) {
        return find(logicalName, true).map(info -> new EmbeddingModel() {
            @Override
            public ModelInfo info() {
                return info;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                List<float[]> out = new ArrayList<>();
                for (String t : texts) {
                    float[] v = new float[dimensions];
                    for (int i = 0; i < t.length(); i++) {
                        v[i % dimensions] += (t.charAt(i) % 31) / 31f;
                    }
                    out.add(v);
                }
                return out;
            }
        });
    }

    private ChatResponse answer(ModelInfo info, ChatRequest r) {
        long n = calls.incrementAndGet();
        String last = r.messages().isEmpty()
                ? ""
                : r.messages().get(r.messages().size() - 1).content();
        Rule hit = rules.stream()
                .filter(x -> x.match().matcher(last).find())
                .findFirst()
                .orElse(null);
        pause(hit != null && hit.latencyMs() >= 0 ? hit.latencyMs() : latencyMs);
        if (!fail.isEmpty() || (failEvery > 0 && n % failEvery == 0)) {
            throw new LlmException(LlmException.Kind.FAILED, fail.isEmpty() ? "mock: scheduled failure" : fail);
        }
        if (hit != null && !hit.error().isEmpty()) {
            throw new LlmException(LlmException.Kind.FAILED, hit.error());
        }
        String fixed = reply;
        String text = hit != null ? hit.reply() : fixed != null ? fixed : "echo: " + last;
        List<ToolCall> tools = hit != null && !hit.tool().isEmpty()
                ? List.of(new ToolCall("mock-" + n, hit.tool(), hit.toolArgs()))
                : List.of();
        int in = (r.promptChars() + 3) / 4;
        int out = (text.length() + 3) / 4;
        return new ChatResponse(
                text,
                tools,
                info.id(),
                tools.isEmpty() ? "stop" : "tool_calls",
                new Usage(in, out, info.cost(in, out)));
    }

    private static void pause(long ms) {
        if (ms > 0) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException(LlmException.Kind.FAILED, "the model call was interrupted", e);
            }
        }
    }

    /** {@code type: mock}. */
    public static final class Factory implements ProviderFactory {
        @Override
        public String type() {
            return "mock";
        }

        @Override
        public LlmProvider create(ProviderConfig config) {
            return new MockProvider(config);
        }
    }
}
