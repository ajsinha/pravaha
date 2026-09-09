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
 * Interpreted execution.
 *
 * <p>Every operator has a correct, slow implementation here, and correctness never depends on code
 * generation succeeding (design 12.4). Wave 3 adds the generated path; this one stays -- as the
 * fallback when generation fails or a stage exceeds the JIT's method-size limit, and as the
 * independent implementation the differential tests compare generated code against.
 *
 * <p>Push-based rather than pull-based. A stage is handed a row and pushes what it produces; a
 * filter that rejects a row simply returns, and the next stage is never involved.
 */
package com.ash.messaging.pravaha.runtime.exec;
