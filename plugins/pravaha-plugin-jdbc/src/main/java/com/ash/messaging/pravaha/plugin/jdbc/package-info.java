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
 * Incremental-poll source over any JDBC database.
 *
 * <p>The universal fallback for a database with no usable change feed: a high-water-mark column, a
 * bounded ordered query, and an offset that counts rows at the boundary value so the boundary is
 * neither duplicated nor lost. What polling cannot do -- see a delete, carry a before-image -- is
 * declared in the capabilities rather than implied away (design section 19.8).
 */
package com.ash.messaging.pravaha.plugin.jdbc;
