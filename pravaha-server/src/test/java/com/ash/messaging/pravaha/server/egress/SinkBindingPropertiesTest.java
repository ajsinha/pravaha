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
package com.ash.messaging.pravaha.server.egress;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.server.egress.SinkBindingProperties.Spec;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code pravaha.sinks.*} binds the way {@code pravaha.sources.*} does -- this pins the shape rather
 * than re-deriving it, since {@code SourceBindingProperties}'s javadoc already documents (and a
 * failed run once found) why the field must be named {@code sinks} under a bare {@code pravaha}
 * prefix rather than the other way round.
 */
class SinkBindingPropertiesTest {

    @Test
    void eachConfiguredSinkBecomesABinding() {
        SinkBindingProperties properties = new SinkBindingProperties();
        Spec audit = new Spec();
        audit.setPlugin("filesystem");
        audit.setOptions(Map.of("path", "/var/lib/pravaha/outgoing/audit.csv", "schema", "id:INT64"));
        properties.getSinks().put("audit_trail", audit);

        List<SinkBinding> bindings = properties.toBindings();

        assertThat(bindings).hasSize(1);
        SinkBinding binding = bindings.get(0);
        assertThat(binding.sinkName()).isEqualTo("audit_trail");
        assertThat(binding.plugin()).isEqualTo("filesystem");
        assertThat(binding.options()).containsEntry("path", "/var/lib/pravaha/outgoing/audit.csv");
    }

    @Test
    void noConfiguredSinksMeansNoBindings() {
        assertThat(new SinkBindingProperties().toBindings()).isEmpty();
    }

    @Test
    void unsetOptionsDefaultToEmptyRatherThanNull() {
        Spec bare = new Spec();
        bare.setPlugin("filesystem");
        // setOptions is never called: the field must still be usable, not null.
        assertThat(bare.getOptions()).isNotNull().isEmpty();
    }
}
