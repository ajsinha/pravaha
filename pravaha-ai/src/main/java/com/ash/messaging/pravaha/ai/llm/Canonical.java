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
package com.ash.messaging.pravaha.ai.llm;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.ai.CallContext;

/**
 * The canonical model format: OpenAI Chat Completions and Embeddings, typed. Same names and (camelCase)
 * fields as {@code pravaha/ai/llm/canonical.py} in the Python SDK; Drishti's extension field
 * {@code drishti} is {@code pravaha} here. A request stripped of {@code pravaha} is a valid OpenAI request.
 * The larger types have their own files; the small ones are nested here.
 */
public final class Canonical {

    private Canonical() {}

    /**
     * A function call.
     *
     * @param name the function
     * @param arguments a JSON string, as in OpenAI
     */
    public record FunctionCall(String name, String arguments) {
        public FunctionCall(@Nullable String name, @Nullable String arguments) {
            this.name = name == null ? "" : name;
            this.arguments = arguments == null ? "{}" : arguments;
        }
    }

    /**
     * A tool call in an assistant message.
     *
     * @param id the call's id
     * @param type always {@code function}
     * @param function what to call
     */
    public record ToolCall(String id, String type, FunctionCall function) {
        public ToolCall(String id, @Nullable String type, FunctionCall function) {
            this.id = id;
            this.type = type == null ? "function" : type;
            this.function = function;
        }

        /** A function call. */
        public static ToolCall of(String id, String name, String arguments) {
            return new ToolCall(id, "function", new FunctionCall(name, arguments));
        }
    }

    /**
     * One message.
     *
     * @param role system, developer, user, assistant or tool
     * @param content its text, or null
     * @param name the speaker's name, or null
     * @param toolCalls the calls an assistant message asks for, or null
     * @param toolCallId the call a tool message answers, or null
     * @param refusal a refusal's words, or null
     */
    public record ChatMessage(
            @Nullable String role,
            @Nullable String content,
            @Nullable String name,
            @Nullable List<ToolCall> toolCalls,
            @Nullable String toolCallId,
            @Nullable String refusal) {
        public ChatMessage {
            toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
        }

        /** A system message. */
        public static ChatMessage system(String text) {
            return new ChatMessage("system", text, null, null, null, null);
        }

        /** A developer message. */
        public static ChatMessage developer(String text) {
            return new ChatMessage("developer", text, null, null, null, null);
        }

        /** A user message. */
        public static ChatMessage user(String text) {
            return new ChatMessage("user", text, null, null, null, null);
        }

        /** An assistant message. */
        public static ChatMessage assistant(String text, @Nullable List<ToolCall> toolCalls) {
            return new ChatMessage("assistant", text, null, toolCalls, null, null);
        }

        /** A tool's answer. */
        public static ChatMessage tool(String toolCallId, String content) {
            return new ChatMessage("tool", content, null, null, toolCallId, null);
        }

        /** The message's text, "" when it has none. */
        public String text() {
            return content == null ? "" : content;
        }
    }

    /**
     * A function the model may call.
     *
     * @param name its name
     * @param description what it does
     * @param parameters its JSON Schema, as text
     * @param strict whether the schema is enforced
     */
    public record FunctionDefinition(
            String name,
            @Nullable String description,
            @Nullable String parameters,
            @Nullable Boolean strict) {}

    /**
     * A tool definition.
     *
     * @param type always {@code function}
     * @param function the function
     */
    public record ToolDefinition(String type, @Nullable FunctionDefinition function) {
        public ToolDefinition(@Nullable String type, @Nullable FunctionDefinition function) {
            this.type = type == null ? "function" : type;
            this.function = function;
        }

        /** A function tool. */
        public static ToolDefinition of(String name, String description, String parameters) {
            return new ToolDefinition("function", new FunctionDefinition(name, description, parameters, null));
        }

        /** The function's name. */
        public String name() {
            FunctionDefinition f = function;
            return f == null ? "" : f.name();
        }
    }

    /**
     * The answer's format.
     *
     * @param type text, json_object or json_schema
     * @param jsonSchema the schema, for json_schema
     */
    public record ResponseFormat(String type, @Nullable String jsonSchema) {
        public ResponseFormat(@Nullable String type, @Nullable String jsonSchema) {
            this.type = type == null ? "text" : type;
            this.jsonSchema = jsonSchema;
        }
    }

    /**
     * What the extension field of a request carries.
     *
     * @param context the caller (identity and feature, for the policy)
     * @param traceId a trace id
     * @param inputPurpose for embeddings, the purpose
     */
    public record PravahaRequest(
            @Nullable CallContext context,
            @Nullable String traceId,
            @Nullable String inputPurpose) {}

    /**
     * Tokens.
     *
     * @param promptTokens prompt
     * @param completionTokens answer
     * @param totalTokens both
     */
    public record CompletionUsage(int promptTokens, int completionTokens, int totalTokens) {
        /** Usage from its parts. */
        public static CompletionUsage of(int prompt, int completion) {
            return new CompletionUsage(prompt, completion, prompt + completion);
        }

        /** The sum of two. */
        public CompletionUsage plus(CompletionUsage o) {
            return of(promptTokens + o.promptTokens, completionTokens + o.completionTokens);
        }
    }

    /**
     * The extension field of a response: who answered, what it cost, how long it took.
     *
     * @param provider the provider
     * @param qualifiedModel {@code provider/model}
     * @param costUsd money spent
     * @param cached whether it came from a cache
     * @param latencyMs how long it took
     * @param traceId the request's trace id
     */
    public record ResponsePravaha(
            String provider, String qualifiedModel, double costUsd, boolean cached, long latencyMs, String traceId) {}

    /**
     * One choice of a completion.
     *
     * @param index its position
     * @param message the message
     * @param finishReason why it stopped
     */
    public record Choice(
            int index, ChatMessage message, @Nullable String finishReason) {}

    /**
     * A streamed function call piece.
     *
     * @param name the function
     * @param arguments a piece of the arguments
     */
    public record DeltaFunction(
            @Nullable String name, @Nullable String arguments) {}

    /**
     * A streamed tool call piece.
     *
     * @param index which call
     * @param id its id
     * @param type always function
     * @param function the piece
     */
    public record DeltaToolCall(
            int index,
            @Nullable String id,
            @Nullable String type,
            @Nullable DeltaFunction function) {}

    /**
     * What one chunk adds.
     *
     * @param role the role, on the first chunk
     * @param content a piece of text
     * @param refusal a piece of a refusal
     * @param toolCalls pieces of tool calls
     */
    public record ChoiceDelta(
            @Nullable String role,
            @Nullable String content,
            @Nullable String refusal,
            @Nullable List<DeltaToolCall> toolCalls) {}

    /**
     * One choice of a chunk.
     *
     * @param index its position
     * @param delta what it adds
     * @param finishReason why it stopped, on the last chunk
     */
    public record ChunkChoice(
            int index,
            @Nullable ChoiceDelta delta,
            @Nullable String finishReason) {}

    /**
     * One embedding.
     *
     * @param object always {@code embedding}
     * @param embedding the vector
     * @param index which input
     */
    public record EmbeddingData(String object, List<Double> embedding, int index) {
        public EmbeddingData(@Nullable String object, List<Double> embedding, int index) {
            this.object = object == null ? "embedding" : object;
            this.embedding = List.copyOf(embedding);
            this.index = index;
        }

        /** The vector as floats. */
        public float[] vector() {
            float[] v = new float[embedding.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = embedding.get(i).floatValue();
            }
            return v;
        }
    }

    /**
     * Embedding tokens.
     *
     * @param promptTokens tokens read
     * @param totalTokens the same
     */
    public record EmbeddingsUsage(int promptTokens, int totalTokens) {}

    /** Free-form options a provider may take that have no standard field: none. */
    public static Map<String, Object> noExtra() {
        return Map.of();
    }
}
