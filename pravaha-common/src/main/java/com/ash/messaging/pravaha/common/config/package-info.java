/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright 2026 Ashutosh Sinha <ajsinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
