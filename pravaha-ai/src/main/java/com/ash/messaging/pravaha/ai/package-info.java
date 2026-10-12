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
/**
 * Pravaha's AI layer: the provider and model interfaces, the registry of configured providers, the
 * policy around every call, and the one gateway features use (ADR-063).
 *
 * <p><b>The design is Drishti's</b> ({@code drishti-ai}, package {@code com.ash.drishti.ai}), ported with
 * its names: {@link com.ash.messaging.pravaha.ai.LlmProvider}, {@link com.ash.messaging.pravaha.ai.LlmModel},
 * {@link com.ash.messaging.pravaha.ai.LlmRegistry}, {@link com.ash.messaging.pravaha.ai.LlmPolicy} and
 * {@link com.ash.messaging.pravaha.ai.LlmGateway} here, and the OpenAI-style second layer in
 * {@link com.ash.messaging.pravaha.ai.llm}. A feature calls exactly one thing:
 *
 * <pre>{@code
 * ChatResponse r = gateway.chat(new CallContext(user, roles, "explain"), ChatRequest.of(null, system, question, 512, timeout));
 * }</pre>
 *
 * <p>One YAML file per provider in {@code pravaha.ai.dir}; keys only from {@code ${ENV}} (a literal key
 * in a file is refused when it is read); the master switch {@code pravaha.ai.enabled} is off by default
 * and with it off every call is refused with {@code PRV-1080}. Every provider type speaks plain HTTP on
 * the JDK client: no vendor SDK and no Spring. The module depends on {@code pravaha-api} and
 * {@code pravaha-common} only; nothing in the engine depends on it and only the server wires it.
 */
@org.jspecify.annotations.NullMarked
package com.ash.messaging.pravaha.ai;
