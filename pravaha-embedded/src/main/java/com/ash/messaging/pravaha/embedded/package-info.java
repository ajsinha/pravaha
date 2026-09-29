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
 * Embedded mode: an engine in the host application's process.
 *
 * <p>Mode A of design 22.2, and the foundation the other three modes are built on. Plain Java with
 * no Spring, no cluster and no gateway, so a host application on any Spring version can embed it --
 * which is the property the competitive position in design 2.2 rests on. An enforcer rule fails the
 * build if Spring ever appears on this module's path.
 *
 * <p><strong>Event time over pushed rows</strong> is the host's to declare, with {@link
 * com.ash.messaging.pravaha.embedded.PravahaEngine#advanceEventTime}; a bound source's advances on
 * its own. A host that only pushes rows can opt a stream in to {@link
 * com.ash.messaging.pravaha.embedded.PravahaEngine#trackEventTime}, off by default, and its event time
 * then follows the greatest event time pushed, less an allowed lateness.
 */
package com.ash.messaging.pravaha.embedded;
