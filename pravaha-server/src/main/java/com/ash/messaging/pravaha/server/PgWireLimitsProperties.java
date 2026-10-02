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
package com.ash.messaging.pravaha.server;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import com.ash.messaging.pravaha.pgwire.PgWireLimits;

/**
 * {@code pravaha.pgwire.limits.*}: what the PostgreSQL gateway holds at once (PGPREAUTH-1).
 *
 * <p>Defaults are {@link PgWireLimits#DEFAULTS}'s, written out as literals so that the help pages' check
 * of every stated default reads the same numbers. Validated by {@link PgWireLimits} itself, which
 * refuses an out-of-range value by name (PRV-6220) when the node starts the gateway.
 */
@Component
@ConfigurationProperties(prefix = "pravaha.pgwire.limits", ignoreUnknownFields = false)
public class PgWireLimitsProperties {

    private int maxConnections = 100;
    private int maxConnectionsPerPrincipal = 0;
    private int maxUnauthenticated = 32;
    private Duration authenticationTimeout = Duration.ofSeconds(10);
    private DataSize maxMessageSize = DataSize.ofMegabytes(1);
    private Duration idleTimeout = Duration.ZERO;

    /** The gateway's limits; throws PRV-6220 for a value out of range. */
    public PgWireLimits limits() {
        long bytes = maxMessageSize.toBytes();
        return new PgWireLimits(
                maxConnections,
                maxConnectionsPerPrincipal,
                maxUnauthenticated,
                authenticationTimeout,
                bytes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bytes,
                idleTimeout);
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getMaxConnectionsPerPrincipal() {
        return maxConnectionsPerPrincipal;
    }

    public void setMaxConnectionsPerPrincipal(int maxConnectionsPerPrincipal) {
        this.maxConnectionsPerPrincipal = maxConnectionsPerPrincipal;
    }

    public int getMaxUnauthenticated() {
        return maxUnauthenticated;
    }

    public void setMaxUnauthenticated(int maxUnauthenticated) {
        this.maxUnauthenticated = maxUnauthenticated;
    }

    public Duration getAuthenticationTimeout() {
        return authenticationTimeout;
    }

    public void setAuthenticationTimeout(Duration authenticationTimeout) {
        this.authenticationTimeout = authenticationTimeout;
    }

    public DataSize getMaxMessageSize() {
        return maxMessageSize;
    }

    public void setMaxMessageSize(DataSize maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }
}
