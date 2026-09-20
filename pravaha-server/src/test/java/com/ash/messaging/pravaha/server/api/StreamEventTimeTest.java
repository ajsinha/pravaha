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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
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
 * Event time and lateness on the stream catalogue, in both directions.
 *
 * <p>A stream declared over HTTP could not have an event time at all, so no window over it could ever
 * close; and no client could see which column a declared stream's event time was, which is the first
 * thing a windowed query has to name.
 */
class StreamEventTimeTest {

    private static final Principal ADMIN = new Principal("root", "acme", Set.of("admin"), Map.of());

    private static MockHttpServletRequest asAdmin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, ADMIN);
        return request;
    }

    private static StreamController controller(StreamCatalog catalog, SourceBindingProperties sources) {
        return new StreamController(
                catalog, new DtoMapper(), new HttpAuthorizer(SecurityPolicy.PERMISSIVE, AuditSink.NONE), sources);
    }

    @Test
    void aStreamDeclaredOverHttpCanHaveAnEventTimeAndItsOwnLateness() {
        StreamCatalog catalog = new StreamCatalog();
        StreamController streams = controller(catalog, new SourceBindingProperties());

        ApiDtos.StreamSummary created = streams.register(
                        new StreamController.RegisterStreamRequest("clicks", "user:STRING,at:TIMESTAMP", "at", "PT30S"),
                        asAdmin())
                .getBody();

        assertThat(created.eventTime()).isEqualTo("at");
        assertThat(created.outOfOrderness()).isEqualTo("PT30S");
        assertThat(catalog.require("clicks").eventTimeOrdinal()).hasValue(1);
        assertThat(streams.get("clicks", asAdmin()).eventTime()).isEqualTo("at");
    }

    @Test
    void aStreamWithoutAnEventTimeSaysSoRatherThanShowingTheDefaultLateness() {
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(
                StreamSchema.builder("plain").field("id", Types.int64()).build());

        ApiDtos.StreamSummary plain =
                controller(catalog, new SourceBindingProperties()).get("plain", asAdmin());

        assertThat(plain.eventTime()).isNull();
        assertThat(plain.outOfOrderness())
                .as("a lateness is about an event time")
                .isNull();
        assertThat(plain.source()).isNull();
    }

    @Test
    void latenessWithoutAnEventTimeIsRefusedAndSoIsAColumnTheStreamDoesNotHave() {
        StreamController streams = controller(new StreamCatalog(), new SourceBindingProperties());

        // TIME-9: a coded refusal naming the stream and both spellings of the key, where this was a
        // bare IllegalArgumentException. The status is unchanged -- PRV-2002 is in the SQL category
        // and maps to 400, as PRV-0400 did.
        assertThatThrownBy(() -> streams.register(
                        new StreamController.RegisterStreamRequest("a", "id:INT64", null, "PT5S"), asAdmin()))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2002")
                .hasMessageContaining("needs an event time")
                .hasMessageContaining("pravaha.streams.a.out-of-orderness");
        assertThatThrownBy(() -> streams.register(
                        new StreamController.RegisterStreamRequest("b", "id:INT64", "at", null), asAdmin()))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("no such column");
        assertThatThrownBy(() -> streams.register(
                        new StreamController.RegisterStreamRequest("c", "at:TIMESTAMP", "at", "thirty seconds"),
                        asAdmin()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO-8601");
    }

    @Test
    void aBoundStreamNamesItsPluginAndNeverItsOptions() throws Exception {
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(StreamSchema.builder("txn").field("id", Types.int64()).build());
        SourceBindingProperties sources = new SourceBindingProperties();
        SourceBindingProperties.Spec spec = new SourceBindingProperties.Spec();
        spec.setPlugin("filesystem");
        spec.setOptions(Map.of("path", "/data/txn.csv", "password", "do-not-print-me"));
        sources.getSources().put("txn", spec);

        ApiDtos.StreamSummary txn = controller(catalog, sources).get("txn", asAdmin());

        assertThat(txn.source()).isEqualTo("filesystem");
        String serialised = new ObjectMapper()
                .writeValueAsString(controller(catalog, sources).list(asAdmin()));
        assertThat(serialised).doesNotContain("do-not-print-me").doesNotContain("/data/txn.csv");
    }

    @Test
    void anUnknownStreamIsRefusedWithoutListingTheOnesThatExist() {
        // SX-5: GET /api/v1/streams/{name} reaches this for any name the policy allows, and it used to
        // append every declared stream -- including ones the listing hides from this caller.
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(
                StreamSchema.builder("payroll").field("salary", Types.int64()).build());

        assertThatThrownBy(
                        () -> controller(catalog, new SourceBindingProperties()).get("invented", asAdmin()))
                .isInstanceOf(PravahaException.class)
                .hasMessageNotContaining("payroll");
    }
}
