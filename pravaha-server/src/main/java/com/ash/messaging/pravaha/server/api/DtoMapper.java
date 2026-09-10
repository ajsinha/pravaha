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

import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * Converts engine types into wire types.
 *
 * <p>One place, so the wire format cannot drift per endpoint. Two endpoints that each build a
 * {@code FieldInfo} their own way will eventually disagree about how a nullable decimal is rendered,
 * and the client discovers it rather than the build.
 */
@Component
public class DtoMapper {

    public ApiDtos.StreamSummary toSummary(StreamSchema schema) {
        return new ApiDtos.StreamSummary(schema.name(), schema.version(), schema.fieldCount(), toFields(schema));
    }

    public List<ApiDtos.FieldInfo> toFields(StreamSchema schema) {
        List<ApiDtos.FieldInfo> fields = new ArrayList<>(schema.fieldCount());
        for (int i = 0; i < schema.fieldCount(); i++) {
            var field = schema.field(i);
            fields.add(new ApiDtos.FieldInfo(
                    field.name(),
                    // The SQL rendering, not the internal enum: a client should see DECIMAL(18, 4),
                    // not the name of a Java constant.
                    field.type().sqlName(),
                    field.type().nullable(),
                    field.ordinal()));
        }
        return fields;
    }
}
