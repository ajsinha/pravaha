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
 * The data model: types, schemas, and zero-copy row access.
 *
 * <p>Nothing here allocates on a per-record basis. Rows are flyweights over an off-heap arena and
 * fields are addressed by ordinal, which is the difference between roughly 500 and roughly 5000
 * CPU cycles per record (design section 29.1).
 */
package com.ash.messaging.pravaha.api.data;
