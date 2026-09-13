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
 * LIFE: the lifecycle of a continuous query, as code.
 *
 * <p>{@code docs/qa/cases/LIFE.md} authors 130 cases against register, pause, resume, drop,
 * re-register, share, read and fail. Three of the file's own six standing facts decide how most of
 * this package is written: lifecycle is Flight-only so there is no REST surface to drive here, the
 * read path is always {@code CONSISTENT} so the four-mode matrix in {@code LifeReadConsistencyTest}
 * runs in-process against {@code ServedView.get} directly, and a paused or failed query keeps
 * answering rather than going blank -- which is asserted, not assumed, in {@code LifePauseTest} and
 * {@code LifeFailureTest}.
 *
 * <p>Everything here drives {@code QueryRegistry} in-process, one of the three shipped ways in
 * (bin/pravaha over Flight and the Java SDK are the other two). That is not a shortcut: LIFE.md
 * itself reaches for the same route whenever a case needs to hold a reference across a state
 * transition a name-based lookup would hide -- a dropped query's {@code ServedView}, a paused
 * query's frontier, a failed query's {@code RegisteredQuery} -- because {@code QueryRegistry} and
 * {@code RegisteredQuery} are the production classes every transport is a thin wrapper over.
 *
 * <p>A restart is simulated by closing a registry and replaying its {@link
 * com.ash.messaging.pravaha.registry.RegistryJournal} into a fresh one, which is exactly what
 * {@code QueryRegistry.recover(...)} does for a real process restart -- the journal has no way to
 * know the difference.
 */
package com.ash.messaging.pravaha.it.qa.lifecycle;
