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
package com.ash.messaging.pravaha.server.ai;

import com.ash.messaging.pravaha.ai.DefaultLlmPolicy;
import com.ash.messaging.pravaha.security.AuditEvent;

/** Test access to the package-private event mapping. */
public final class AiConfigurationAccess {

    private AiConfigurationAccess() {}

    /** The audit event of an audit row. */
    public static AuditEvent event(DefaultLlmPolicy.AuditRecord a) {
        return AiConfiguration.event(a);
    }
}
