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

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * The policy every call goes through: allowed features per role, per-user and per-feature rate limits, a
 * per-user daily cost limit, masking of the prompt, and an audit record per call. The audit sink and the
 * masker are given, so the server wires them to its audit trail and to {@code pravaha.ai.policy.redact}.
 */
public final class DefaultLlmPolicy implements LlmPolicy {

    /**
     * What the audit sink receives: who, what, how big, how long, what it cost. Never the prompt or the answer.
     *
     * @param user the caller
     * @param feature the feature that asked
     * @param provider the provider's name
     * @param model the logical model
     * @param promptChars the prompt's size in characters (after masking)
     * @param answerChars the answer's size in characters
     * @param promptTokens tokens the prompt counted
     * @param completionTokens tokens the answer counted
     * @param cost money spent
     * @param outcome {@code ok}, or the failure's kind ({@code rate_limited}, {@code timeout}, ...)
     * @param millis how long the call took
     */
    public record AuditRecord(
            String user,
            String feature,
            String provider,
            String model,
            int promptChars,
            int answerChars,
            int promptTokens,
            int completionTokens,
            double cost,
            String outcome,
            long millis) {}

    private static final long MINUTE_MS = 60_000L;

    private final PolicySettings settings;
    private final PromptMasker masker;
    private final @Nullable Consumer<AuditRecord> audit;
    private final Clock clock;
    private final Map<String, Deque<Long>> minute = new ConcurrentHashMap<>();
    private final Map<String, long[]> day = new ConcurrentHashMap<>(); // user -> {epochDay, calls}
    private final Map<String, double[]> spend = new ConcurrentHashMap<>(); // user -> {epochDay, cost}

    public DefaultLlmPolicy(
            PolicySettings settings, PromptMasker masker, @Nullable Consumer<AuditRecord> audit, Clock clock) {
        this.settings = settings;
        this.masker = settings.maskRedacted() ? masker : PromptMasker.none();
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public ChatRequest before(CallContext ctx, ModelInfo model, ChatRequest request) {
        var roles = settings.featureRoles().get(ctx.feature());
        if (roles != null && !roles.isEmpty() && roles.stream().noneMatch(ctx.roles()::contains)) {
            throw new LlmException(
                    LlmException.Kind.POLICY, "feature '" + ctx.feature() + "' is not open to your roles");
        }
        long now = clock.millis();
        long today = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toEpochDay();
        int perMin = settings.featurePerUserPerMinute().getOrDefault(ctx.feature(), settings.perUserPerMinute());
        if (perMin > 0) {
            Deque<Long> q = minute.computeIfAbsent(ctx.user() + "|" + ctx.feature(), k -> new ArrayDeque<>());
            synchronized (q) {
                while (!q.isEmpty() && now - q.getFirst() >= MINUTE_MS) {
                    q.removeFirst();
                }
                if (q.size() >= perMin) {
                    long wait = Math.max(1, (MINUTE_MS - (now - q.getFirst())) / 1000);
                    throw new LlmException(
                            LlmException.Kind.RATE_LIMITED, "too many calls; try again in " + wait + " seconds");
                }
                q.addLast(now);
            }
        }
        if (settings.perUserPerDay() > 0) {
            long[] d = day.compute(
                    ctx.user(),
                    (k, v) -> v == null || v[0] != today ? new long[] {today, 1} : new long[] {today, v[1] + 1});
            if (d[1] > settings.perUserPerDay()) {
                throw new LlmException(
                        LlmException.Kind.RATE_LIMITED,
                        "the daily limit of " + settings.perUserPerDay() + " calls is used up");
            }
        }
        if (settings.costPerUserPerDay() > 0) {
            double[] s = spend.get(ctx.user());
            if (s != null && (long) s[0] == today && s[1] >= settings.costPerUserPerDay()) {
                throw new LlmException(LlmException.Kind.RATE_LIMITED, "the daily cost limit is used up");
            }
        }
        return masker.mask(request);
    }

    @Override
    public void after(
            CallContext ctx,
            ModelInfo model,
            ChatRequest request,
            @Nullable ChatResponse response,
            @Nullable LlmException failure,
            long millis) {
        double cost = response == null ? 0 : response.usage().cost();
        if (cost > 0) {
            long today = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toEpochDay();
            spend.compute(
                    ctx.user(),
                    (k, v) -> v == null || (long) v[0] != today
                            ? new double[] {today, cost}
                            : new double[] {today, v[1] + cost});
        }
        Consumer<AuditRecord> sink = audit;
        if (settings.audit() && sink != null) {
            Usage u = response == null ? Usage.NONE : response.usage();
            try {
                sink.accept(new AuditRecord(
                        ctx.user(),
                        ctx.feature(),
                        model.provider(),
                        model.name(),
                        request.promptChars(),
                        response == null ? 0 : response.text().length(),
                        u.promptTokens(),
                        u.completionTokens(),
                        cost,
                        failure == null ? "ok" : failure.kind().id(),
                        millis));
            } catch (RuntimeException e) {
                // an audit sink must never fail a call
            }
        }
    }
}
