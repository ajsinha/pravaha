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

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.ai.llm.Canonical.ChoiceDelta;
import com.ash.messaging.pravaha.ai.llm.Canonical.ChunkChoice;
import com.ash.messaging.pravaha.ai.llm.Canonical.CompletionUsage;
import com.ash.messaging.pravaha.ai.llm.Canonical.ResponsePravaha;

/**
 * One streamed piece of a chat completion.
 *
 * @param id the completion's id
 * @param object always {@code chat.completion.chunk}
 * @param created seconds since the epoch
 * @param model the model
 * @param choices the pieces
 * @param usage tokens, on the last chunk
 * @param pravaha the extension, on the last chunk
 */
public record ChatCompletionChunk(
        String id,
        String object,
        long created,
        String model,
        List<ChunkChoice> choices,
        @Nullable CompletionUsage usage,
        @Nullable ResponsePravaha pravaha) {

    public ChatCompletionChunk(
            String id,
            @Nullable String object,
            long created,
            String model,
            @Nullable List<ChunkChoice> choices,
            @Nullable CompletionUsage usage,
            @Nullable ResponsePravaha pravaha) {
        this.id = id;
        this.object = object == null ? "chat.completion.chunk" : object;
        this.created = created;
        this.model = model;
        this.choices = choices == null ? List.of() : List.copyOf(choices);
        this.usage = usage;
        this.pravaha = pravaha;
    }

    /** The text this chunk adds, "" when none. */
    public String delta() {
        if (choices.isEmpty()) {
            return "";
        }
        ChoiceDelta d = choices.get(0).delta();
        String c = d == null ? null : d.content();
        return c == null ? "" : c;
    }

    /** A completion as chunks: the role, the text, the finish (and the usage), the way a stream arrives. */
    public static List<ChatCompletionChunk> fromCompletion(ChatCompletion c) {
        ChoiceDelta role = new ChoiceDelta("assistant", "", null, null);
        ChoiceDelta text = new ChoiceDelta(null, c.text(), null, null);
        return List.of(
                new ChatCompletionChunk(
                        c.id(), null, c.created(), c.model(), List.of(new ChunkChoice(0, role, null)), null, null),
                new ChatCompletionChunk(
                        c.id(), null, c.created(), c.model(), List.of(new ChunkChoice(0, text, null)), null, null),
                new ChatCompletionChunk(
                        c.id(),
                        null,
                        c.created(),
                        c.model(),
                        List.of(new ChunkChoice(0, new ChoiceDelta(null, null, null, null), c.finishReason())),
                        c.usage(),
                        c.pravaha()));
    }
}
