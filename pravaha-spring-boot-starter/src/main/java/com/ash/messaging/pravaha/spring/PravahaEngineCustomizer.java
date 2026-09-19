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
package com.ash.messaging.pravaha.spring;

import com.ash.messaging.pravaha.embedded.PravahaEngine;

/**
 * Adjusts the auto-configured engine after {@code pravaha.*} has been applied and before it starts:
 * the place to declare a stream from a {@code StreamSchema} built in code, bind a plugin whose
 * options come from somewhere other than properties, or register a {@code PravahaPlugin}.
 *
 * <p>Queries are not registered here -- the engine has not started, and its registry does not exist
 * yet. Declare them with {@code engine.declareQuery(...)}, under {@code pravaha.queries}, or register
 * them through {@link PravahaTemplate} once the context is up.
 */
@FunctionalInterface
public interface PravahaEngineCustomizer {

    void customize(PravahaEngine engine);
}
