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

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The last shape on the way out, so there is only ever one.
 *
 * <p>CFG-20. {@code ApiExceptionHandler} makes an {@link ApiDtos.ApiError} out of every failure a
 * handler method raises, and three of the six non-2xx responses a client can provoke never reach a
 * handler method: {@code DELETE /api/v1/streams} (405), a {@code text/plain} body on a JSON
 * endpoint (415) and {@code GET /nosuchpath} (404). Those were dispatched to Spring Boot's
 * {@code BasicErrorController} and came back as {@code {"timestamp":…,"status":405,"error":"Method
 * Not Allowed","path":…}} -- a third error shape, with no {@code code}, no {@code message} and no
 * {@code helpUrl}, on a surface whose contract says there is exactly one.
 *
 * <p>Replacing the controller rather than adding handlers per exception type: a handler list is a
 * list somebody has to keep complete, and the failure mode of an incomplete one is invisible --
 * the response still looks like an error, just not this API's. Everything that reaches
 * {@code /error} leaves as an {@code ApiError}, including whatever is added next.
 *
 * <p>Hidden from the OpenAPI document. {@code /error} is where the servlet container forwards, not
 * a path a client calls, and publishing it would put a phantom endpoint in the contract the console
 * and every integration are generated from.
 */
@RestController
@Hidden
public class ApiErrorController implements ErrorController {

    @RequestMapping(value = "${server.error.path:/error}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiDtos.ApiError> error(HttpServletRequest request) {
        HttpStatus status = statusOf(request);
        return ResponseEntity.status(status)
                .body(new ApiDtos.ApiError(
                        ApiErrors.UNHANDLED_REQUEST.code(),
                        message(status, request),
                        ApiErrors.UNHANDLED_REQUEST.helpUrl(),
                        Instant.now(),
                        path(request)));
    }

    /**
     * What went wrong, in the terms the caller can act on.
     *
     * <p>Spring's own message is put in only when the container supplied one, and the status is
     * always named: "Method Not Allowed" on its own does not say which method or which path, and
     * the path is the field a client logs.
     */
    private static String message(HttpStatus status, HttpServletRequest request) {
        Object supplied = request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
        String detail = supplied == null || supplied.toString().isBlank() ? "" : " -- " + supplied;
        return status.value() + " " + status.getReasonPhrase() + " for " + method(request) + " " + path(request)
                + detail + ". Every error from this API is an ApiError; this one did not reach an endpoint, "
                + "so there is no more specific code than " + ApiErrors.UNHANDLED_REQUEST.code() + ".";
    }

    private static HttpStatus statusOf(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        HttpStatus resolved = HttpStatus.resolve(Integer.parseInt(code.toString()));
        return resolved == null ? HttpStatus.INTERNAL_SERVER_ERROR : resolved;
    }

    private static String path(HttpServletRequest request) {
        Object original = request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
        if (original == null) {
            original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        }
        return original == null ? request.getRequestURI() : original.toString();
    }

    private static String method(HttpServletRequest request) {
        Object original = request.getAttribute("jakarta.servlet.error.method");
        return original == null ? request.getMethod() : original.toString();
    }
}
