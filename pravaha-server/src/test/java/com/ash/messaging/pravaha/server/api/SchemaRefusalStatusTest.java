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

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A schema string the caller got wrong is the caller's mistake, and answers 4xx.
 *
 * <p>Finding TY-8. {@code POST /api/v1/streams} with {@code "schema":"id:INT64,amt:DECIMAL"}
 * answered {@code 500 Internal Server Error}. Nothing was broken: the type name was misspelled, in
 * a field of the caller's own request, and the caller could fix it in a second. The 500 came from
 * the code rather than from the handler -- the schema parser lives in the filesystem plugin and
 * used that plugin's {@code PRV-5040}, and {@link ApiExceptionHandler} derives its status from a
 * code's <em>category</em>, which for the 5xxx series is "the server's problem".
 *
 * <p>Deriving the status from the category is right and is not what changed; the code was in the
 * wrong series. This test pins both halves, because fixing the category without checking the
 * handler would leave the same 500 in place if either moved again.
 */
class SchemaRefusalStatusTest {

    private static final Principal ADMIN = new Principal("root", "acme", Set.of("admin"), Map.of());

    @Test
    void ty8_aMisspelledTypeInTheRequestBodyIsFourHundredAndNotFiveHundred() {
        StreamController streams = new StreamController(
                new StreamCatalog(),
                new DtoMapper(),
                new HttpAuthorizer(SecurityPolicy.PERMISSIVE, AuditSink.NONE),
                new SourceBindingProperties());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, ADMIN);

        assertThatThrownBy(() -> streams.register(
                        new StreamController.RegisterStreamRequest("d", "id:INT64,amt:DECIMAL", null, null), request))
                .isInstanceOf(PravahaException.class)
                .satisfies(thrown -> {
                    ErrorCode code = ((PravahaException) thrown).errorCode();
                    assertThat(ApiExceptionHandler.statusFor(code))
                            .as("the caller mistyped a type name; nothing here is the server's fault")
                            .isEqualTo(HttpStatus.BAD_REQUEST);
                })
                // TY-9's half of the same refusal, seen from the surface an HTTP caller uses.
                .hasMessageContaining("stream 'd'")
                .hasMessageContaining("column 'amt'");
    }

    @Test
    void aRowThatCannotBeDecodedIsStillTheServersProblem() {
        // The control, and the reason this was not fixed by mapping the PLUGIN series to 400: a
        // file whose line will not decode is a genuine 5xx, and PRV-5040 still means that.
        assertThat(ApiExceptionHandler.statusFor(new ErrorCode(5040, "FILESYSTEM_DECODE_FAILED")))
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
