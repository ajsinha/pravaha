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
package com.ash.messaging.pravaha.server.security;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.common.config.ConfigErrors;

/**
 * {@code pravaha.http.*}: what one HTTP request may cost this node before it is refused
 * (HTTPBODY-1). See {@link RequestLimitFilter}.
 */
@Component
@ConfigurationProperties(prefix = "pravaha.http", ignoreUnknownFields = false)
public class HttpLimitsProperties {

    /** A body on a path open without a credential: sign-in, password reset, the documentation. */
    private DataSize maxAnonymousBody = DataSize.ofKilobytes(16);

    /** A body on every other path, which is read only after the caller has authenticated. */
    private DataSize maxRequestBody = DataSize.ofMegabytes(4);

    /** Sign-ins (each a deliberately slow password hash) running at once; the rest are 429. */
    private int maxConcurrentSignIns = 8;

    @PostConstruct
    public void validate() {
        if (maxAnonymousBody.toBytes() < 1024) {
            throw invalid("pravaha.http.max-anonymous-body must be at least 1KB, or no sign-in fits; it is "
                    + maxAnonymousBody);
        }
        if (maxRequestBody.toBytes() < maxAnonymousBody.toBytes()) {
            throw invalid("pravaha.http.max-request-body (" + maxRequestBody + ") must be at least "
                    + "pravaha.http.max-anonymous-body (" + maxAnonymousBody + ")");
        }
        if (maxConcurrentSignIns < 1) {
            throw invalid("pravaha.http.max-concurrent-sign-ins must be at least 1; it is " + maxConcurrentSignIns);
        }
    }

    private static ConfigurationException invalid(String message) {
        return new ConfigurationException(ConfigErrors.OUT_OF_RANGE, message);
    }

    /** The filter these settings describe, given the paths open without a credential. */
    public RequestLimitFilter filter(java.util.Set<String> openPaths) {
        validate();
        return new RequestLimitFilter(
                maxAnonymousBody.toBytes(), maxRequestBody.toBytes(), maxConcurrentSignIns, openPaths);
    }

    public DataSize getMaxAnonymousBody() {
        return maxAnonymousBody;
    }

    public void setMaxAnonymousBody(DataSize maxAnonymousBody) {
        this.maxAnonymousBody = maxAnonymousBody;
    }

    public DataSize getMaxRequestBody() {
        return maxRequestBody;
    }

    public void setMaxRequestBody(DataSize maxRequestBody) {
        this.maxRequestBody = maxRequestBody;
    }

    public int getMaxConcurrentSignIns() {
        return maxConcurrentSignIns;
    }

    public void setMaxConcurrentSignIns(int maxConcurrentSignIns) {
        this.maxConcurrentSignIns = maxConcurrentSignIns;
    }
}
