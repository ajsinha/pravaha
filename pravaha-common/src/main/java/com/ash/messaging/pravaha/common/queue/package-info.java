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
 * Lock-free queues for the lane edges.
 *
 * <p>Primitive-carrying and bounded. Every queue in the engine is bounded by design invariant
 * (design NFR-9): an unbounded queue does not remove backpressure, it just hides it until the heap
 * runs out.
 */
package com.ash.messaging.pravaha.common.queue;
