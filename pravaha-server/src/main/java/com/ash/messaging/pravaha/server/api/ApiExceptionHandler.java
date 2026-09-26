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
package com.ash.messaging.pravaha.server.api;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Turns engine exceptions into API responses.
 *
 * <p>Every non-2xx response is an {@link ApiDtos.ApiError} and nothing else. A client that has to
 * parse two error shapes will handle one of them badly, and it will be the one that occurs rarely --
 * which is to say the one that matters.
 *
 * <p>The status is derived from the error's <em>category</em> rather than mapped case by case. A new
 * {@code PRV-2xxx} planning error therefore returns 400 without anyone remembering to add it, which
 * is the failure mode a hand-maintained mapping has.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(PravahaException.class)
    public ResponseEntity<ApiDtos.ApiError> handle(PravahaException e, HttpServletRequest request) {
        HttpStatus status = statusFor(e.errorCode());
        return ResponseEntity.status(status)
                .body(new ApiDtos.ApiError(
                        e.errorCode().code(), e.getMessage(), e.helpUrl(), Instant.now(), request.getRequestURI()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiDtos.ApiError> handleBadRequest(IllegalArgumentException e, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(new ApiDtos.ApiError("PRV-0400", e.getMessage(), "", Instant.now(), request.getRequestURI()));
    }

    /**
     * Maps an error category to a status.
     *
     * <p>Configuration and planning problems are the caller's ({@code 400}); runtime, state, plugin
     * and cluster problems are the server's ({@code 500}); security is {@code 403}. Deriving this
     * from the category means a newly-added code is classified correctly by construction.
     */
    static HttpStatus statusFor(ErrorCode code) {
        // A named thing that is not there, or that the listing rules hide from this caller -- the two
        // answer identically, by design. 404 rather than the registry category's 400: the request was
        // well-formed, and a client retrying a 400 by fixing its body would be looking for a mistake it
        // did not make.
        if (code.code().equals(com.ash.messaging.pravaha.registry.RegistryErrors.NO_SUCH_QUERY.code())
                || code.code().equals(com.ash.messaging.pravaha.serving.ServingErrors.NO_SUCH_VIEW.code())
                || code.code().equals(com.ash.messaging.pravaha.state.StateErrors.DLQ_NO_SUCH_LETTER.code())) {
            return HttpStatus.NOT_FOUND;
        }
        // A replay the engine will not perform because it could not be correct (B5). Not a 500: the
        // server is fine and the request was well formed. Not a 400 either -- there is nothing in the
        // body to fix, and a client retrying after correcting one would be looking for a mistake it
        // did not make. 409: the state of the query says no.
        if (code.code().equals(com.ash.messaging.pravaha.state.StateErrors.DLQ_REPLAY_REFUSED.code())) {
            return HttpStatus.CONFLICT;
        }
        // ADR-050. A tenant at its quota is 409 for the same reason: the body is fine and the
        // tenant's holdings say no, so the fix is a drop or a raised quota, not a changed request.
        // A replacement from another tenant is a refusal of the caller, so 403.
        if (code.code().equals(com.ash.messaging.pravaha.registry.RegistryErrors.TENANT_QUERY_QUOTA.code())
                || code.code().equals(com.ash.messaging.pravaha.registry.RegistryErrors.TENANT_STATE_QUOTA.code())) {
            return HttpStatus.CONFLICT;
        }
        if (code.code().equals(com.ash.messaging.pravaha.registry.RegistryErrors.TENANT_MISMATCH.code())) {
            return HttpStatus.FORBIDDEN;
        }
        return switch (code.category()) {
            case CONFIGURATION, PLANNING -> HttpStatus.BAD_REQUEST;
            case SECURITY -> HttpStatus.FORBIDDEN;
            // REGISTRY is the caller's: a name already in use, no such query, an illegal transition.
            // These were previously unreachable here -- 8xxx had no category, so category() threw
            // IllegalStateException from inside this very handler while it built an error response.
            case REGISTRY -> HttpStatus.BAD_REQUEST;
            case PLUGIN, RUNTIME, STATE, CLUSTER, FLIGHT -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
