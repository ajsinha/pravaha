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
 * Configuration loading for the engine core.
 *
 * <p>Exists because modes A and the CLI carry no Spring (design section 22.1) and so have no
 * configuration mechanism of their own. In modes B, C and D, Spring binds the same keys into the
 * same {@link com.ash.messaging.pravaha.common.config.Configuration} type; there is one
 * configuration model and two ways of filling it, never two sources of truth (design section 22.5).
 */
package com.ash.messaging.pravaha.common.config;
