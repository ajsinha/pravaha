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
 * Plugin discovery, loading and lifecycle.
 *
 * <p>Holds the classloader isolation that lets plugins bundle conflicting dependency versions, and
 * the registry that owns their lifecycle. Storage clients never appear on the engine's own
 * classpath (design NFR-4), and an ArchUnit rule fails the build if one does.
 */
package com.ash.messaging.pravaha.connect;
