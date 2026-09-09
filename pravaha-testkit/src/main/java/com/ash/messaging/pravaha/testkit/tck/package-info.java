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
 * The plugin conformance suite.
 *
 * <p>A plugin declares capabilities the engine then acts on. The TCK exercises each declaration
 * against real behaviour rather than believing it, because the failures these prevent are silent:
 * a source that claims replayable offsets but cannot resume loses data on the first recovery, and
 * nothing before that moment reveals it.
 *
 * <p>With a closed-source engine (design 30.4) this matters more than usual. A third party writing a
 * connector cannot read how the engine calls them, so the conformance suite has to serve as the
 * specification.
 */
package com.ash.messaging.pravaha.testkit.tck;
