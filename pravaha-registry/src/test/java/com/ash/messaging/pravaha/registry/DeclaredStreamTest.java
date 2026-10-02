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
package com.ash.messaging.pravaha.registry;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DECLSTREAM-1: a stream declared after the registry was built is planned over by the next
 * registration, with an identity of its own -- or, for a new version of a stream, the identity of
 * the one it replaces.
 */
class DeclaredStreamTest {

    private static StreamSchema stream(String name, String... fields) {
        StreamSchema.Builder builder = StreamSchema.builder(name);
        for (String field : fields) {
            builder.field(field, Types.int64());
        }
        return builder.build();
    }

    @Test
    void aStreamDeclaredAfterStartCanBeRegisteredOver() {
        try (QueryRegistry registry = new QueryRegistry(new ViewCatalog(), stream("txn", "id"))) {
            assertThatThrownBy(() -> registry.register("s2v", "SELECT k, v FROM s2", List.of(0), Principal.ANONYMOUS))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-2002");

            registry.declare(stream("s2", "k", "v"));

            assertThat(registry.register("s2v", "SELECT k, v FROM s2", List.of(0), Principal.ANONYMOUS)
                            .state()
                            .name())
                    .isEqualTo("RUNNING");
            assertThat(Arrays.stream(registry.streams()).map(StreamSchema::name))
                    .containsExactly("txn", "s2");
        }
    }

    @Test
    void aNewNameTakesTheNextIdentityAndANewVersionKeepsItsOwn() {
        StreamSchema[] known = StreamIdentities.identify(new StreamSchema[] {stream("a", "x"), stream("b", "x")});

        StreamSchema[] grown = StreamIdentities.declare(known, stream("c", "x"));
        assertThat(Arrays.stream(grown).map(StreamSchema::streamId)).containsExactly(1, 2, 3);

        StreamSchema[] replaced = StreamIdentities.declare(grown, stream("b", "x", "y"));
        assertThat(replaced).hasSize(3);
        assertThat(replaced[1].streamId()).isEqualTo(2);
        assertThat(replaced[1].fields()).hasSize(2);
        assertThat(known).hasSize(2).as("the array a running plan holds is never changed in place");
    }
}
