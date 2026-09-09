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
 * The data model: types, schemas, and zero-copy row access.
 *
 * <p>Nothing here allocates on a per-record basis. Rows are flyweights over an off-heap arena and
 * fields are addressed by ordinal, which is the difference between roughly 500 and roughly 5000
 * CPU cycles per record (design section 29.1).
 */
package com.ash.messaging.pravaha.api.data;
