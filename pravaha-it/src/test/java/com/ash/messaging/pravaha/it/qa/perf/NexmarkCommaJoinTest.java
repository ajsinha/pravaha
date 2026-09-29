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
package com.ash.messaging.pravaha.it.qa.perf;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL-13, proven on the query that found it: Nexmark q9's interval join, written the way Nexmark
 * writes it -- {@code FROM auction A, bid B WHERE A.id = B.auction AND B.date_time BETWEEN ...} --
 * registers, as the identical {@code INNER JOIN ... ON} already did. Only q9's {@code ROW_NUMBER}
 * filter is dropped, because {@code OVER} is a separate refusal ({@code PRV-2021}) and not this one.
 */
class NexmarkCommaJoinTest {

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    private static final String Q9_JOIN_AS_NEXMARK_WRITES_IT =
            "SELECT A.id, A.item_name, A.seller, A.category, B.auction, B.bidder, B.price FROM auction A, bid B "
                    + "WHERE A.id = B.auction AND B.date_time BETWEEN A.date_time AND A.expires";

    private static final String Q9_JOIN_AS_ON =
            "SELECT A.id, A.item_name, A.seller, A.category, B.auction, B.bidder, B.price FROM auction A "
                    + "INNER JOIN bid B ON A.id = B.auction AND B.date_time BETWEEN A.date_time AND A.expires";

    @Test
    void q9sCommaJoinRegistersAsItsInnerJoinFormDoes() {
        try (QueryRegistry registry = new QueryRegistry(
                new ViewCatalog(), NexmarkStreams.person(), NexmarkStreams.auction(), NexmarkStreams.bid())) {
            assertThat(registry.outputSchemaOf(Q9_JOIN_AS_NEXMARK_WRITES_IT))
                    .as("the comma form and the ON form are one query, so they answer in one shape")
                    .isEqualTo(registry.outputSchemaOf(Q9_JOIN_AS_ON));
            registry.register("nexmark_q9_comma", Q9_JOIN_AS_NEXMARK_WRITES_IT, List.of(0), DANA);
            registry.drop("nexmark_q9_comma");
        }
    }
}
