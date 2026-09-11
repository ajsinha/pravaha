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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.Configuration;

/**
 * Makes the socket coordinator selectable as {@code mechanism: socket}.
 *
 * <pre>
 * pravaha:
 *   cluster:
 *     mode: REPLICATED          # PARTITIONED is refused for this mechanism
 *     mechanism: socket
 *     socket:
 *       peers: "a=host1:9070,b=host2:9070,c=host3:9070"
 *       heartbeat: 1s
 *       timeout: 5s
 * </pre>
 */
public final class SocketProvider implements CoordinatorProvider {

    @Override
    public String mechanism() {
        return "socket";
    }

    @Override
    public Guarantees guarantees() {
        return new Guarantees("socket", false, false, false);
    }

    @Override
    public ClusterCoordinator create(Configuration configuration) {
        String spec = configuration
                .getString("pravaha.cluster.socket.peers")
                .orElseThrow(() -> new PravahaException(
                        ClusterErrors.BAD_MEMBERSHIP,
                        "the socket coordinator needs pravaha.cluster.socket.peers, as "
                                + "'id=host:port,id=host:port'. There is no discovery: discovery without "
                                + "consensus is one more thing for two halves of a cluster to disagree about"));

        List<Member> peers = new ArrayList<>();
        for (String entry : spec.split(",")) {
            String trimmed = entry.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            int colon = trimmed.lastIndexOf(':');
            if (equals < 0 || colon < equals) {
                throw new PravahaException(
                        ClusterErrors.BAD_MEMBERSHIP, "'" + trimmed + "' is not a peer; each is 'id=host:port'");
            }
            peers.add(new Member(
                    trimmed.substring(0, equals).strip(),
                    trimmed.substring(equals + 1, colon).strip(),
                    Integer.parseInt(trimmed.substring(colon + 1).strip())));
        }
        return new SocketCoordinator(
                peers,
                Duration.ofMillis(configuration.getLong("pravaha.cluster.socket.heartbeat.millis", 1_000)),
                Duration.ofMillis(configuration.getLong("pravaha.cluster.socket.timeout.millis", 5_000)));
    }
}
