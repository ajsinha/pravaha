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

import org.springframework.stereotype.Service;

/** A component with nothing to do with Pravaha, which a {@code @PravahaTest} context leaves out. */
@Service
public class Invoicing {

    public String invoice(String orderId) {
        return "invoice for " + orderId;
    }
}
