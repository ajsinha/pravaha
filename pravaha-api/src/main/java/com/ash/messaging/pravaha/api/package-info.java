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
 * The public Pravaha SPI.
 *
 * <p>This module has zero third-party dependencies and is compiled to Java 17 bytecode. That is
 * deliberate and load-bearing: it is what plugin authors compile against, the only package visible
 * from a plugin's parent classloader, and the module under semantic-versioning enforcement. Every
 * richer type -- buffers, Netty, Calcite -- stays behind it.
 */
package com.ash.messaging.pravaha.api;
