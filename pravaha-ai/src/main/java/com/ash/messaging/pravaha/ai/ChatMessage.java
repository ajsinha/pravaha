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

import org.jspecify.annotations.Nullable;

/**
 * One turn of a conversation.
 *
 * @param role who said it
 * @param content what was said, never null
 */
public record ChatMessage(Role role, String content) {

    /** Who said a turn. */
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    public ChatMessage(Role role, @Nullable String content) {
        this.role = role;
        this.content = content == null ? "" : content;
    }

    /** A user turn. */
    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text);
    }

    /** An assistant turn. */
    public static ChatMessage assistant(String text) {
        return new ChatMessage(Role.ASSISTANT, text);
    }
}
