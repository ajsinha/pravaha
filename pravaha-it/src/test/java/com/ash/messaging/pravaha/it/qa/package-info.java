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
 * The regression suite: authored QA cases, executable.
 *
 * <p>{@code docs/qa/cases/} holds 2,385 test cases as prose. Prose has to be read and run by a
 * person, once; these are the same cases as code, and they run in every build. That difference is
 * the whole point. Three claims of the form "this is checked by a test" in this repository turned
 * out to be false -- {@code ExamplesTest} never opened the quickstart it was said to cover, the SQL
 * support matrix compared no values, and the differential test never invoked the generator -- and
 * each one rotted quietly because nothing executed what the document claimed.
 *
 * <p>Which is also why this is a package inside {@code src/test/java} rather than a folder beside
 * the source tree. A directory Maven does not compile is a directory that stops running the day
 * after it is written.
 *
 * <p><strong>Layout.</strong> One sub-package per area in {@code docs/qa/cases/}: {@code sql},
 * {@code window}, {@code time}, {@code incremental}, {@code streaming}. A test's name carries the
 * case it came from -- {@code time032_oneMillisecondOfLatenessCostsAWholeWindow} is TIME-032 -- so a
 * failure leads back to the case that described it and the reasoning behind it.
 *
 * <p><strong>Running it.</strong> Select by package:
 *
 * <pre>
 * ./mvnw test -pl pravaha-it -Dtest='com.ash.messaging.pravaha.it.qa.**'        the suite alone
 * ./mvnw test -pl pravaha-it -Dtest='com.ash.messaging.pravaha.it.qa.window.**' one area
 * ./mvnw test                                                                  all of it, as a drill runs
 * </pre>
 *
 * <p>The classes also carry {@code @Tag("qa")}, which an IDE will honour, but Surefire's
 * {@code -Dgroups} does not select them in this build -- verified, not assumed: {@code -Dgroups=qa}
 * matched nothing even with the class named explicitly. The package selector above is the supported
 * route, and it needs no build configuration to keep working.
 *
 * <p><strong>A disabled test here is a live defect, not dead weight.</strong> A conversion whose
 * case the product fails is kept and marked {@code @Disabled} with its defect, so the suite stays
 * green while the defect stays visible. Deleting one hides a known failure.
 */
package com.ash.messaging.pravaha.it.qa;
