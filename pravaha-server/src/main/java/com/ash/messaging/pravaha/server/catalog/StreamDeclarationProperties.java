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
package com.ash.messaging.pravaha.server.catalog;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code pravaha.streams.*}: the streams this node knows about, declared in configuration.
 *
 * <pre>
 * pravaha:
 *   streams:
 *     txn:
 *       schema: "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING"
 * </pre>
 *
 * <p>Until now the only way to put a schema in the catalog was {@code POST /api/v1/streams}, so a
 * node could not be described by a file: every restart needed someone to make an HTTP call before
 * any query would plan. A stream that is bound to a source under {@code pravaha.sources} but never
 * declared produced "Object 'txn' not found. Known streams: []" -- accurate, and baffling next to a
 * configuration file that clearly mentions {@code txn}.
 *
 * <p>The spec form is the plugin's own ({@code name:TYPE,...}), the same spelling used by the CLI,
 * the REST API and a filesystem binding's {@code schema} option. One spelling of a schema across
 * every surface is worth more than a tidier one here that has to be learned separately.
 */
@Component
@ConfigurationProperties(prefix = "pravaha")
public class StreamDeclarationProperties {

    private final Map<String, Declaration> streams = new LinkedHashMap<>();

    public Map<String, Declaration> getStreams() {
        return streams;
    }

    /** One stream's schema, as written in configuration. */
    public static class Declaration {

        private String schema;

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }
    }
}
