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
package com.ash.messaging.pravaha.sample.orders;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A small application of the kind {@code @PravahaTest} is for: one component with a
 * {@code @PravahaListener} ({@link OrderAlerts}), and one without ({@link Invoicing}), which the slice
 * leaves out. Its configuration is {@code application-sample.yaml}.
 *
 * <p>In a package of its own, outside {@code com.ash.messaging.pravaha.spring}, so the other tests'
 * applications do not component-scan it.
 */
@SpringBootApplication
public class SampleOrdersApplication {}
