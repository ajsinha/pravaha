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
package com.ash.messaging.pravaha.server.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.ai.CallContext;
import com.ash.messaging.pravaha.ai.ChatMessage;
import com.ash.messaging.pravaha.ai.ChatRequest;
import com.ash.messaging.pravaha.ai.ChatResponse;
import com.ash.messaging.pravaha.ai.LlmException;
import com.ash.messaging.pravaha.ai.LlmGateway;
import com.ash.messaging.pravaha.ai.LlmRegistry;
import com.ash.messaging.pravaha.ai.ModelInfo;
import com.ash.messaging.pravaha.ai.RetryBoard;
import com.ash.messaging.pravaha.ai.Usage;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.ai.AiProperties;
import com.ash.messaging.pravaha.server.ai.features.AiFeatures;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

/**
 * The AI layer's REST face (ADR-063, Drishti's {@code AiController}). For features, the console and the
 * SDK's {@code server} provider: {@code POST /api/v1/ai/chat} (the caller's feature is {@code api:<name>},
 * so it cannot pose as a built-in one) and {@code GET /api/v1/ai/models}, both through the policy. For an
 * administrator: {@code GET /api/v1/admin/ai} (the layer on or off, every provider with its health --
 * OFF, ERROR, UNKNOWN, UP, DOWN, from configuration and the calls seen, never a network call -- and the
 * files that could not be read) and {@code POST /api/v1/admin/ai/test} (one real call, capped at 15
 * seconds and {@code pravaha.ai.test-per-minute} per administrator). Every refusal carries its code:
 * {@code PRV-1080} off, {@code PRV-1081} to {@code PRV-1087} one per kind of failure.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "AI", description = "The AI layer: chat through the policy, and its administration (ADR-063)")
public class AiController {

    /** The most a test connection may take. */
    static final Duration TEST_CAP = Duration.ofSeconds(15);

    /** The most a chat call may take, whatever it asks for. */
    static final int CHAT_CAP_SECONDS = 120;

    /**
     * A chat request.
     *
     * @param model {@code provider/model}, a unique model name, or null for the default
     * @param system fixed instructions
     * @param messages the conversation, oldest first: role system, user, assistant or tool
     * @param prompt one more user turn, after the messages
     * @param temperature sampling temperature
     * @param maxTokens the most the answer may be
     * @param jsonMode ask for a JSON object
     * @param timeoutSeconds how long to wait, 1 to 120 (30 when absent)
     * @param feature the caller's feature, for the policy; prefixed {@code api:}
     */
    public record ChatBody(
            @Nullable String model,
            @Nullable String system,
            @Nullable List<Msg> messages,
            @Nullable String prompt,
            @Nullable Double temperature,
            @Nullable Integer maxTokens,
            @Nullable Boolean jsonMode,
            @Nullable Integer timeoutSeconds,
            @Nullable String feature) {}

    /**
     * One turn.
     *
     * @param role system, user, assistant or tool
     * @param content its text
     */
    public record Msg(@Nullable String role, @Nullable String content) {}

    /**
     * A test connection.
     *
     * @param model the model to test, or null for the default
     * @param prompt what to ask; a one-word request when absent
     */
    public record TestBody(@Nullable String model, @Nullable String prompt) {}

    /**
     * What a test connection answered.
     *
     * @param ok always true: a failure is an error with its code
     * @param text the answer
     * @param model the model that answered
     * @param usage tokens and cost
     * @param millis how long it took
     */
    public record TestAnswer(boolean ok, String text, String model, Usage usage, long millis) {}

    /**
     * The layer as an administrator sees it.
     *
     * @param enabled whether the master switch is on
     * @param dir the folder of provider files
     * @param types the provider types installed
     * @param defaultModel the default model, or empty
     * @param providers every provider file that could be read
     * @param fileErrors files that could not, and why
     * @param loadedAt when the folder was last read
     */
    public record AdminStatus(
            boolean enabled,
            String dir,
            List<String> types,
            String defaultModel,
            List<ProviderRow> providers,
            Map<String, String> fileErrors,
            Instant loadedAt) {}

    /**
     * One provider.
     *
     * @param name its name (the file's)
     * @param type its type
     * @param enabled {@code enabled:} in the file
     * @param baseUrl its endpoint
     * @param keySet whether a key was resolved (never the key)
     * @param health OFF, ERROR, UNKNOWN, UP or DOWN, and why
     * @param retry when a provider that could not be built is tried again, or null
     * @param models its models
     */
    public record ProviderRow(
            String name,
            String type,
            boolean enabled,
            String baseUrl,
            boolean keySet,
            LlmRegistry.Health health,
            RetryBoard.@Nullable Status retry,
            List<ModelInfo> models) {}

    /**
     * What the plain-English features are to this caller, so a screen can leave out the controls it may not use.
     *
     * @param enabled whether the AI layer is on ({@code pravaha.ai.enabled})
     * @param values what a model may see of the caller's data: {@code labels-only} or {@code aggregates}
     * @param features each feature ({@code ask}, {@code draft}, {@code alert}, {@code explain}, {@code narrative},
     *     {@code page}) and whether the AI policy's roles open it to this caller; all false while the layer is off
     */
    public record FeatureStatus(boolean enabled, String values, Map<String, Boolean> features) {}

    /** The plain-English features, in the order the guide lists them. */
    static final List<String> FEATURES = List.of(
            AiFeatures.ASK,
            AiFeatures.DRAFT,
            AiFeatures.ALERT,
            AiFeatures.EXPLAIN,
            AiFeatures.NARRATIVE,
            AiFeatures.PAGE);

    private final AiProperties ai;
    private final LlmGateway gateway;
    private final HttpAuthorizer authorizer;
    private final Map<String, Deque<Long>> tests = new ConcurrentHashMap<>();

    public AiController(AiProperties ai, LlmGateway gateway, HttpAuthorizer authorizer) {
        this.ai = ai;
        this.gateway = gateway;
        this.authorizer = authorizer;
    }

    @GetMapping("/admin/ai")
    @Operation(summary = "The AI layer: on or off, providers with their health (no network call), file errors (admin)")
    public AdminStatus overview(HttpServletRequest http) {
        authorizer.requireNodeAdmin(http, "the AI layer's status");
        LlmRegistry reg = gateway.registry();
        Map<String, RetryBoard.Status> retry = reg.retry().statuses();
        List<ProviderRow> rows = new ArrayList<>();
        for (LlmRegistry.Entry e : reg.entries()) {
            var p = e.provider();
            rows.add(new ProviderRow(
                    e.config().name(),
                    e.config().type(),
                    e.config().enabled(),
                    e.config().baseUrl(),
                    !e.config().apiKey().isEmpty(),
                    reg.health(e),
                    retry.get(e.config().name()),
                    p == null ? List.of() : p.models()));
        }
        return new AdminStatus(
                gateway.enabled(),
                ai.getDir(),
                reg.types(),
                ai.getDefaultModel(),
                rows,
                reg.fileErrors(),
                reg.loadedAt());
    }

    @PostMapping("/admin/ai/test")
    @Operation(summary = "One real call to a model, capped at 15 seconds and rate-limited per administrator (admin)")
    public TestAnswer test(@RequestBody(required = false) @Nullable TestBody body, HttpServletRequest http) {
        authorizer.requireNodeAdmin(http, "an AI test connection");
        gateway.requireEnabled();
        Principal p = authorizer.principalOf(http);
        throttle(p.id());
        String model = body == null || body.model() == null ? "" : body.model().strip();
        String prompt = body == null || body.prompt() == null || body.prompt().isBlank()
                ? "Reply with the single word: ready"
                : body.prompt();
        long t0 = System.nanoTime();
        ChatResponse r = gateway.chat(
                context(p, "admin-test"), ChatRequest.of(model.isEmpty() ? null : model, "", prompt, 64, TEST_CAP));
        return new TestAnswer(true, r.text(), r.model(), r.usage(), (System.nanoTime() - t0) / 1_000_000L);
    }

    private void throttle(String user) {
        long now = System.currentTimeMillis();
        Deque<Long> q = tests.computeIfAbsent(user, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.getFirst() >= 60_000L) {
                q.removeFirst();
            }
            if (q.size() >= ai.getTestPerMinute()) {
                throw new LlmException(
                        LlmException.Kind.RATE_LIMITED,
                        "at most " + ai.getTestPerMinute() + " test connections a minute (pravaha.ai.test-per-minute)");
            }
            q.addLast(now);
        }
    }

    @GetMapping("/ai/features")
    @Operation(
            summary =
                    "Which plain-English features this caller may use: the layer on or off, the values mode, "
                            + "each feature by the AI policy's roles. Never refused for the layer being off; no model is called")
    public FeatureStatus features(HttpServletRequest http) {
        Principal p = authorizer.principalOf(http);
        boolean on = gateway.enabled();
        Map<String, AiProperties.Feature> rules = ai.getPolicy().getFeatures();
        Map<String, Boolean> open = new LinkedHashMap<>();
        for (String f : FEATURES) {
            AiProperties.Feature rule = rules.get(f);
            List<String> roles = rule == null ? List.of() : rule.getRoles();
            open.put(f, on && (roles.isEmpty() || roles.stream().anyMatch(p.roles()::contains)));
        }
        return new FeatureStatus(on, ai.getValues(), open);
    }

    @GetMapping("/ai/models")
    @Operation(summary = "The models callers may name: provider/model, kind, capabilities")
    public List<ModelInfo> models(HttpServletRequest http) {
        gateway.requireEnabled();
        return gateway.registry().models();
    }

    @PostMapping("/ai/chat")
    @Operation(summary = "One chat call through the AI policy (roles, limits, masking, audit); feature is api:<name>")
    public ChatResponse chat(@RequestBody ChatBody b, HttpServletRequest http) {
        gateway.requireEnabled();
        List<ChatMessage> msgs = new ArrayList<>();
        List<Msg> given = b.messages();
        if (given != null) {
            for (Msg m : given) {
                msgs.add(new ChatMessage(role(m.role()), m.content()));
            }
        }
        String prompt = b.prompt();
        if (prompt != null && !prompt.isBlank()) {
            msgs.add(ChatMessage.user(prompt));
        }
        if (msgs.isEmpty()) {
            throw new PravahaException(ApiErrors.MISSING_FIELD, "send messages or a prompt");
        }
        Integer asked = b.timeoutSeconds();
        Duration t = Duration.ofSeconds(Math.min(CHAT_CAP_SECONDS, Math.max(1, asked == null ? 30 : asked)));
        String feature = b.feature();
        ChatRequest req = new ChatRequest(
                b.model(),
                b.system(),
                msgs,
                List.of(),
                b.temperature(),
                b.maxTokens(),
                Boolean.TRUE.equals(b.jsonMode()),
                t);
        return gateway.chat(
                context(
                        authorizer.principalOf(http),
                        "api:" + (feature == null || feature.isBlank() ? "other" : feature.strip())),
                req);
    }

    private static ChatMessage.Role role(@Nullable String role) {
        try {
            return ChatMessage.Role.valueOf(String.valueOf(role).strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PravahaException(
                    ApiErrors.INVALID_PARAMETER, "a message's role is system, user, assistant or tool");
        }
    }

    private static CallContext context(Principal p, String feature) {
        return new CallContext(p.id(), Set.copyOf(p.roles()), feature);
    }
}
