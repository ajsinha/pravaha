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
package com.ash.messaging.pravaha.server.config;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.config.ConfigErrors;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;
import com.ash.messaging.pravaha.sql.SqlErrors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A node that will not boot over a configuration file says so in the configuration range.
 *
 * <p>DOCX-19. {@code PRV-2002 SQL_VALIDATION_FAILED} had ten throw sites, and seven of them were
 * a configuration file the node refused to start with: a stream with no schema, a standby with
 * nothing to stand by, two watermark durations outside their bounds, one stream declared with two
 * schemas, a stream schema version already registered, and a stream's event-time settings.
 * {@code TROUBLESHOOTING.md}'s ranges table puts {@code PRV-2xxx} under "SQL -- parsing, planning,
 * what the engine will and will not run", so an operator whose node would not start over a typo in
 * a YAML value was pointed at their query.
 *
 * <p>The cases below pin the codes those refusals carry now. The last one pins what did
 * <em>not</em> move: {@code PRV-2002} is still the code for a SQL statement failing validation,
 * which is the whole of what it is for.
 */
class ConfigurationRefusalCodesTest {

    private static final String SCHEMA = "user_id:STRING,amount:INT64,event_time:TIMESTAMP";

    private static StreamDeclarationProperties declared(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("pravaha", StreamDeclarationProperties.class)
                .get();
    }

    private static PravahaNode.Builder node(StreamDeclarationProperties streams) {
        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal("");
        return PravahaNode.builder()
                .withDeclaredStreams(streams)
                .withSecurity(security)
                .withFlight(false, "127.0.0.1", 0)
                .withPersistence(persistence);
    }

    @Test
    void aStreamDeclaredWithNoSchemaIsAMissingKeyAndNotAFailedQuery_DOCX19() {
        PravahaNode refused = node(declared(Map.of("pravaha.streams.txn.event-time", "event_time")))
                .withNodeId("no-schema")
                .build();

        assertThatThrownBy(refused::start)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-1020")
                .hasMessageContaining("pravaha.streams")
                .hasMessageContaining("with no schema");
    }

    @Test
    void aStandbyWithNothingToStandByIsAMissingKey_DOCX19() {
        PravahaNode refused = node(declared(Map.of("pravaha.streams.txn.schema", SCHEMA)))
                .withNodeId("standby-without-a-directory")
                .asStandby(true)
                .build();

        assertThatThrownBy(refused::start)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-1020")
                .hasMessageContaining("pravaha.standby.enabled")
                .hasMessageContaining("pravaha.checkpoint.directory");
    }

    @Test
    void aSchemaVersionAlreadyHeldIsACatalogConflictAndNotAFailedQuery_DOCX19() {
        StreamCatalog catalog = new StreamCatalog();
        StreamSchema first = StreamSchema.builder("txn")
                .field("id", Types.int64())
                .field("at", Types.timestamp())
                .build();
        catalog.register(first);

        assertThatThrownBy(() -> catalog.register(StreamSchema.builder("txn")
                        .field("id", Types.int64())
                        .field("usr", Types.string())
                        .build()))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-1014")
                .hasMessageContaining("already registered")
                .hasMessageContaining("Schema versions are immutable");
    }

    /**
     * The control, and the reason this is a renumbering rather than an emptying: the one thing
     * {@code PRV-2002} is for keeps it.
     */
    @Test
    void sqlValidationItselfIsStillPRV2002_DOCX19() {
        assertThat(SqlErrors.VALIDATION_FAILED.code()).isEqualTo("PRV-2002");
        assertThat(SqlErrors.VALIDATION_FAILED.category()).isEqualTo(ErrorCode.Category.PLANNING);
    }

    /** Every code this finding allocated is in the configuration range, which is the point. */
    @Test
    void theNewCodesAreConfigurationCodes_DOCX19() {
        assertThat(ConfigErrors.CONTRADICTION.code()).isEqualTo("PRV-1015");
        assertThat(ConfigErrors.STREAM_EVENT_TIME_INVALID.code()).isEqualTo("PRV-1013");
        assertThat(ConfigErrors.STREAM_VERSION_IN_USE.code()).isEqualTo("PRV-1014");
        for (ErrorCode allocated : java.util.List.of(
                ConfigErrors.CONTRADICTION,
                ConfigErrors.STREAM_EVENT_TIME_INVALID,
                ConfigErrors.STREAM_VERSION_IN_USE)) {
            assertThat(allocated.category()).as(allocated.code()).isEqualTo(ErrorCode.Category.CONFIGURATION);
        }
    }
}
