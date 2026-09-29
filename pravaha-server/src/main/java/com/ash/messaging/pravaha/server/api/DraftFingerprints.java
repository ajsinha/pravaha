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

import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.registry.DraftFingerprint;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.serving.Retention;

/**
 * {@code /explain}'s fingerprint: what a registration of the SQL would get, for the caller
 * (EXPLAINFP-1). See {@link DraftFingerprint} for how it is computed; this reads the request.
 */
final class DraftFingerprints {

    /** The fingerprint's short form, or why a registration would be refused; both null when not asked. */
    record Answer(String value, ApiDtos.Diagnostic refusal) {

        static final Answer NONE = new Answer(null, null);
    }

    private DraftFingerprints() {}

    static Answer answer(
            RegistryAccess access,
            HttpAuthorizer authorizer,
            QueryController.ValidateRequest request,
            HttpServletRequest http) {
        List<Integer> keys = request.keys();
        if (keys == null || keys.isEmpty()) {
            // A fingerprint includes the key columns, so without them there is none to give.
            return Answer.NONE;
        }
        for (Integer key : keys) {
            if (key == null || key < 0) {
                throw new PravahaException(
                        ApiErrors.INVALID_PARAMETER,
                        "'keys' are the view's key columns as zero-based ordinals of the SELECT list, in order; " + keys
                                + " is not");
            }
        }
        Retention retention = retentionOf(request.retention());
        Optional<QueryRegistry> registry = access.registry();
        if (registry.isEmpty()) {
            return Answer.NONE;
        }
        String name = request.name() == null || request.name().isBlank()
                ? "explain"
                : request.name().strip();
        String sink = request.sink() == null || request.sink().isBlank()
                ? null
                : request.sink().strip();
        try {
            return new Answer(
                    DraftFingerprint.of(
                                    registry.get(),
                                    name,
                                    request.sql(),
                                    List.copyOf(keys),
                                    authorizer.principalOf(http),
                                    retention,
                                    sink)
                            .shortForm(),
                    null);
        } catch (PravahaException e) {
            // The statement's plan was explained; what a registration would refuse is said beside
            // it, in the words register would use, rather than failing the explanation.
            return new Answer(null, new ApiDtos.Diagnostic(e.errorCode().code(), e.getMessage(), e.helpUrl(), "error"));
        }
    }

    /**
     * A retention as a registration takes it: an ISO-8601 duration of event time or {@code forever};
     * absent for the registration's default. Anything else is refused rather than defaulted, as
     * registration refuses it.
     */
    static Retention retentionOf(String text) {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty()) {
            return null;
        }
        if (value.equalsIgnoreCase("forever")) {
            return Retention.forever();
        }
        try {
            return Retention.ofAge(java.time.Duration.parse(value.toUpperCase(java.util.Locale.ROOT)));
        } catch (java.time.format.DateTimeParseException | IllegalArgumentException e) {
            throw new PravahaException(
                    ApiErrors.INVALID_PARAMETER,
                    "'" + value + "' is not a retention. Give an ISO-8601 duration of event time, such as PT24H "
                            + "or P7D, or 'forever'; the age must be positive.");
        }
    }
}
