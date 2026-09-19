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
 * ERRC: every error code the engine declares -- reachable, documented, meaning one thing, message
 * actionable. {@code docs/qa/cases/ERRC.md} authors 118 cases against the 110 {@code ErrorCode}
 * declarations across 16 modules, plus eight cross-cutting properties of the code set as a whole.
 *
 * <p>Every case shares one method (the case file's "Standing setup and method"): S1 reach the code on
 * a real product surface (the CLI, the embedded engine's own configuration/SQL entry points, a real
 * server process over HTTP or Flight -- never the throwing Java method called directly), S2 capture it
 * exactly as the surface renders it, then four assertions -- E1 the rendered number, E2 the rendered
 * name, E3 an actionable message, E4 {@code docs/TROUBLESHOOTING.md} documents it correctly. The 8xxx
 * family adds E5 ({@code category()} has no entry for it, so a controller that lets it escape trips
 * {@code ApiExceptionHandler} itself) and the 9xxx family adds E6 (the ranges table omits {@code
 * PRV-9xxx} entirely).
 *
 * <p><strong>The case file's own "facts" preamble is partly stale.</strong> It was authored against
 * the code as it stood before commit {@code e0b6395} ("Defects 3-14"), which landed the day before
 * this round and independently fixed several of the defects the case file's facts 3-7 describe as
 * still open: {@code ErrorCode.Category} now covers all nine ranges (8xxx and 9xxx included), so
 * {@code category()} no longer throws for a registry or cluster code, and {@code
 * BearerTokenFilter.refuse}'s body now has exactly {@code ApiDtos.ApiError}'s five fields with no
 * {@code PRV-0400}. Where a case's expected finding turned out to already be fixed, the test asserts
 * the current, correct behaviour and the log says so explicitly rather than the case silently passing
 * for a reason nobody wrote down -- the same convention {@code docs/qa/FINDINGS.md}'s L-2 entry uses
 * for LIFE.
 *
 * <p>{@code ErrcTestSupport} is the shared harness: a CLI runner ({@code PravahaCli} driven the way
 * {@code PravahaCliTest} drives it -- captured stdout/stderr and an exit code, never the throwing
 * method called directly) and the embedded engine's own {@code Configuration}/{@code
 * ConfigurationBuilder}/{@code SqlPlanner} entry points, which is what "the embedded engine" means as
 * a product surface throughout this package.
 */
package com.ash.messaging.pravaha.it.qa.errc;
