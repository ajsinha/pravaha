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
 * A function the model may ask to call.
 *
 * @param name the tool's name
 * @param description what it does, for the model
 * @param parametersJsonSchema the JSON Schema of its arguments, as text; null is {@code {"type":"object"}}
 */
public record ToolSpec(
        String name, @Nullable String description, @Nullable String parametersJsonSchema) {}
