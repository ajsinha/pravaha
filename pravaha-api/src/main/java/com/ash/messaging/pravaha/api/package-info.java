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
 * The public Pravaha SPI.
 *
 * <p>This module has zero third-party dependencies (Java 25 class files from 2.0, like every module;
 * it targeted 17 in 1.x). That is deliberate and load-bearing: it is what plugin authors compile against, the only package visible
 * from a plugin's parent classloader, and the module under semantic-versioning enforcement. Every
 * richer type -- buffers, Netty, Calcite -- stays behind it.
 */
package com.ash.messaging.pravaha.api;
