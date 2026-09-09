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
 * Delimited-file source and sink.
 *
 * <p>The reference plugin. It has no external dependency, so it exercises the SPI without a store
 * getting in the way, and every integration test uses it as a known-good input and output. Later
 * plugins are written against the shape it establishes.
 */
package com.ash.messaging.pravaha.plugin.filesystem;
