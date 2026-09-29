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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.bindings.egress.PluginSinks;
import com.ash.messaging.pravaha.bindings.egress.SinkBinding;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VALIDATEREG-1: {@code /validate} given a whole {@code CREATE CONTINUOUS QUERY} statement answers every
 * refusal registering it would give -- key and index columns, the sink, the name, the options --
 * without registering it. It planned only a {@code SELECT}, so a draft passed it and was refused on
 * register.
 */
class ValidateRegistrationTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("region", Types.string())
            .field("amount", Types.int64())
            .field("ratio", Types.float64())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private static final String SELECT = " AS SELECT user_id, region, amount, ratio FROM txn";

    private QueryRegistry registry;
    private PluginSinks sinks;
    private QueryController queries;

    @BeforeEach
    void start(@TempDir Path dir) {
        sinks = new PluginSinks()
                .bind(new SinkBinding(
                        "txn_out",
                        "filesystem",
                        Map.of(
                                "path",
                                dir.resolve("out.csv").toString(),
                                "schema",
                                "user_id:STRING,region:STRING,amount:INT64,ratio:FLOAT64")));
        registry =
                new QueryRegistry(new ViewCatalog(), SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN).writingTo(sinks);
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(TXN);
        queries = new QueryController(
                catalog,
                new DtoMapper(),
                new HttpAuthorizer(SecurityPolicy.PERMISSIVE, AuditSink.NONE),
                new RegistryAccess(registry, sinks, AuditSink.NONE));
    }

    @AfterEach
    void stop() throws Exception {
        registry.close();
        sinks.close();
    }

    @Test
    void aStatementRegistrationWouldAcceptIsValidAndNothingIsRegistered() {
        ApiDtos.ValidationResult result =
                validate("CREATE CONTINUOUS QUERY latest KEYED BY (user_id) INDEX (region)" + SELECT);

        assertThat(result.valid()).as(String.valueOf(result.diagnostics())).isTrue();
        assertThat(result.outputFields())
                .extracting(ApiDtos.FieldInfo::name)
                .containsExactly("user_id", "region", "amount", "ratio");
        assertThat(registry.names()).as("validated, not registered").isEmpty();
    }

    @Test
    void aKeyTheViewWouldNotHaveIsRefusedAsRegistrationRefusesIt() {
        assertThat(codes(validate("CREATE CONTINUOUS QUERY latest KEYED BY (nobody)" + SELECT)))
                .containsExactly("PRV-2071");
    }

    @Test
    void anIndexThisEngineCannotKeepIsRefused() {
        assertThat(codes(validate("CREATE CONTINUOUS QUERY latest KEYED BY (user_id) INDEX (ratio)" + SELECT)))
                .containsExactly("PRV-2074");
    }

    @Test
    void anUnknownOptionIsRefused() {
        assertThat(codes(validate("CREATE CONTINUOUS QUERY latest KEYED BY (user_id) WITH (volume = '11')" + SELECT)))
                .containsExactly("PRV-8017");
    }

    @Test
    void aTakenNameAndAnUnboundSinkAreBothSaid() {
        registry.register("latest", "SELECT user_id, region, amount, ratio FROM txn", List.of(0), DANA);

        ApiDtos.ValidationResult result =
                validate("CREATE CONTINUOUS QUERY latest KEYED BY (user_id) WRITING TO nowhere" + SELECT);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics()).hasSize(2);
        assertThat(result.diagnostics().get(0).message()).contains("latest");
        assertThat(result.diagnostics().get(1).message()).contains("nowhere");
    }

    @Test
    void aSinkTheQueryDoesNotMatchIsRefusedBeforeRegistration() {
        ApiDtos.ValidationResult result =
                validate("CREATE CONTINUOUS QUERY latest KEYED BY (user_id) WRITING TO txn_out "
                        + "AS SELECT region, user_id, amount, ratio FROM txn");

        assertThat(codes(result)).containsExactly("PRV-8010");
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void aPlainSelectIsValidatedAsBefore() {
        ApiDtos.ValidationResult result = validate("SELECT user_id FROM txn WHERE amount > 5");

        assertThat(result.valid()).isTrue();
        assertThat(result.outputFields()).extracting(ApiDtos.FieldInfo::name).containsExactly("user_id");
    }

    private ApiDtos.ValidationResult validate(String sql) {
        return queries.validate(new QueryController.ValidateRequest(sql), as(DANA));
    }

    private static List<String> codes(ApiDtos.ValidationResult result) {
        return result.diagnostics().stream().map(ApiDtos.Diagnostic::code).toList();
    }

    private static MockHttpServletRequest as(Principal who) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, who);
        return request;
    }
}
