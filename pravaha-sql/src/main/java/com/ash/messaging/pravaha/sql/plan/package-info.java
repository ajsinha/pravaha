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
 * Pravaha's own plan representation.
 *
 * <p>A sealed operator hierarchy, so the code generator and the interpreted fallback both switch
 * over the full set and the compiler flags any operator either forgets. Adding an operator without
 * handling it everywhere is a build failure rather than a runtime surprise in a customer's query.
 */
package com.ash.messaging.pravaha.sql.plan;
