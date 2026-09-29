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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.registry.ContinuousQueryStatements;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.sql.ContinuousStatement;
import com.ash.messaging.pravaha.sql.ContinuousStatements;
import com.ash.messaging.pravaha.sql.SourcePosition;

/**
 * {@code /validate} of a whole {@code CREATE CONTINUOUS QUERY} statement: every refusal registering it
 * would give, without registering it (VALIDATEREG-1).
 *
 * <p>{@code /validate} planned only a {@code SELECT}, so a draft whose key or index named a column the
 * view would not have (PRV-2071, PRV-2074), whose sink the caller may not see or whose retention does
 * not parse passed, and was refused only on register. Given the statement, it is read and checked by
 * {@link ContinuousQueryStatements#validate} -- registration's own reading and preparation -- and every
 * refusal comes back as a diagnostic; a statement registration would accept is {@code valid}, with the
 * columns its view would have.
 */
final class RegistrationValidation {

    private RegistrationValidation() {}

    /**
     * The verdict on {@code sql} when it is a {@code CREATE CONTINUOUS QUERY} statement and this node
     * hosts a registry; empty otherwise, for {@code /validate} to plan it as a {@code SELECT}.
     */
    static Optional<ApiDtos.ValidationResult> of(
            String sql,
            RegistryAccess access,
            HttpAuthorizer authorizer,
            HttpServletRequest http,
            DtoMapper mapper,
            long started) {
        Optional<QueryRegistry> hosted = access.registry();
        if (hosted.isEmpty()) {
            return Optional.empty();
        }
        Optional<ContinuousStatement> recognized;
        try {
            recognized = ContinuousStatements.recognize(sql);
        } catch (PravahaException e) {
            return Optional.of(invalid(List.of(e), List.of(), started));
        }
        if (!(recognized.orElse(null) instanceof ContinuousStatement.Create create)) {
            return Optional.empty();
        }
        QueryRegistry registry = hosted.get();
        Principal principal = authorizer.principalOf(http);
        List<ApiDtos.FieldInfo> fields = List.of();
        try {
            fields = mapper.toFields(registry.outputSchemaOf(create.select(), principal));
        } catch (PravahaException e) {
            // Refused by the plan: the statement's own validation says so, with the same code.
        }
        List<PravahaException> refusals =
                new ContinuousQueryStatements(registry, registry.policy(), access.audit()).validate(create, principal);
        return Optional.of(
                refusals.isEmpty()
                        ? ApiDtos.ValidationResult.ok(fields, micros(started))
                        : invalid(refusals, fields, started));
    }

    private static ApiDtos.ValidationResult invalid(
            List<PravahaException> refusals, List<ApiDtos.FieldInfo> fields, long started) {
        List<ApiDtos.Diagnostic> diagnostics = new ArrayList<>();
        for (PravahaException refusal : refusals) {
            ApiDtos.SourceRange range = SourcePosition.of(refusal)
                    .map(at -> new ApiDtos.SourceRange(at.startLine(), at.startColumn(), at.endLine(), at.endColumn()))
                    .orElse(null);
            diagnostics.add(new ApiDtos.Diagnostic(
                    refusal.errorCode().code(), refusal.getMessage(), refusal.helpUrl(), "error", range));
        }
        return new ApiDtos.ValidationResult(false, diagnostics, fields, micros(started));
    }

    private static long micros(long started) {
        return (System.nanoTime() - started) / 1_000L;
    }
}
