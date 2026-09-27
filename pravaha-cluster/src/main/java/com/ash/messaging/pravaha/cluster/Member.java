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
package com.ash.messaging.pravaha.cluster;

import com.ash.messaging.pravaha.common.net.Endpoint;

public record Member(String id, String host, int port) {

    public Member {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a member needs a stable id");
        }
    }

    /**
     * Where another node reaches this one, written so it can be pasted into a connection string.
     *
     * <p>CFG-2(c). This was {@code host + ":" + port}, so a node bound to an IPv6 address
     * advertised {@code ::1:19090} -- which no client library can parse, because the colon before
     * the port is indistinguishable from the address's own. Bracketing is RFC 3986's answer and
     * {@link Endpoint} is where this project keeps it, so the startup log and the membership record
     * cannot disagree about how an address is spelled.
     */
    public String address() {
        return Endpoint.address(host, port);
    }

    @Override
    public String toString() {
        return id + "@" + address();
    }
}
