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
 * SQL parsing, validation and planning.
 *
 * <p>The only module that imports Calcite, and deliberately so: Calcite brings around thirty
 * transitive dependencies, and none of them belong anywhere near the engine core.
 * {@code pravaha-runtime} does not depend on this module at all.
 *
 * <p>Calcite is used as a compiler, not a runtime (ADR-002). It parses, validates and optimises;
 * {@link com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder} then translates its output into
 * Pravaha's own operator tree, and nothing below that boundary knows Calcite exists.
 */
package com.ash.messaging.pravaha.sql;
