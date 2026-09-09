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
 * Bump-pointer arena allocation for rows.
 *
 * <p>Batch-scoped memory with O(1) reclaim. Rows that must outlive their batch -- buffered window
 * inputs, a join build side, a subscriber tap -- are copied into the owning structure's own arena.
 * That copy is the only one in the pipeline and it is deliberate and explicit (design section 8.5).
 */
package com.ash.messaging.pravaha.common.arena;
