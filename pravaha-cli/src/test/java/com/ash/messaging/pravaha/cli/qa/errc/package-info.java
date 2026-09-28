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
 * ERRC cases that need a real, running server: PRV-3xxx through PRV-9xxx and the cross-cutting
 * cases, continuing {@code pravaha-it}'s {@code qa.errc} package (ERRC-001..029, PRV-1xxx/2xxx,
 * which need no server).
 *
 * <p>Lives in {@code pravaha-cli} rather than {@code pravaha-it}, for the same reason {@link
 * com.ash.messaging.pravaha.cli.JoinReachabilityAgainstServerTest} does: {@code pravaha-it}'s own
 * dependency graph once mixed netty 4.1.135 (pulled in transitively through {@code pravaha-server},
 * test scope) with the 4.2.9 line Arrow Flight needs, and constructing a real {@code FlightClient}
 * there threw {@code AbstractMethodError} (recorded as FINDINGS.md E-9). {@code pravaha-cli}'s test
 * scope carries {@code pravaha-flight}, {@code pravaha-registry}, {@code pravaha-serving} and the
 * Java SDK: a real {@code PravahaFlightServer} over a real {@code QueryRegistry}, on {@code
 * localhost}, port 0 (OS-assigned), driven through the SDK ({@code SdkVerbs}, in the call shape the
 * Java CLI's remote commands had before they moved to the Python CLI) or a raw {@code
 * PravahaFlightClient}/{@code FlightClient} against the same port.
 *
 * <p>{@code ErrcServerSupport} is the shared fixture. Each test class configures its own {@code
 * PravahaFlightServer} (security, TLS, admission control) because the case bodies in this range need
 * different combinations and a shared {@code @BeforeEach} would force the least common denominator.
 */
package com.ash.messaging.pravaha.cli.qa.errc;
