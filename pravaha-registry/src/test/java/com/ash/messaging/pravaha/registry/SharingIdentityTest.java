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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two registrations share a computation only when they are actually the same question.
 *
 * <p>I-3 and TY-21, which are the same mistake seen from two sides: something that changes a view's
 * answer was not part of deciding whether two views are the same, and something that changes a
 * view's answer was applied without being asked for.
 *
 * <p>I-3: {@code QueryFingerprint.of} saw the plan and the row filters and not the key columns, so
 * {@code --keys 1} and {@code --keys 0,1} over identical SQL shared one view — <strong>keyed as the
 * first registrant asked, with no error</strong>. A view keyed on a different column conflates
 * different rows, so the second caller's answers were wrong, not merely unexpected.
 *
 * <p>TY-21: every registration got a 24-hour event-time retention nobody chose and nothing on the
 * server ever set, which silently evicts most of a view built over data spanning years.
 */
class SharingIdentityTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("region", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final String SQL = "SELECT user_id, region, amount FROM txn";
    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private QueryRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new QueryRegistry(new ViewCatalog(), TXN);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void twoKeyingsOfTheSameSqlAreTwoComputations() {
        RegisteredQuery byUser = registry.register("by_user", SQL, List.of(0), DANA);
        RegisteredQuery byUserAndRegion = registry.register("by_user_region", SQL, List.of(0, 1), DANA);

        assertThat(byUserAndRegion.fingerprint())
                .as("different keys are a different view. Sharing here handed the second registrant a "
                        + "view keyed the way the first asked, silently, and it answers differently")
                .isNotEqualTo(byUser.fingerprint());
        assertThat(byUserAndRegion.view().keyOrdinals())
                .as("and the second registrant got the keying it actually asked for")
                .containsExactly(0, 1);
    }

    @Test
    void keyOrderIsPartOfTheIdentity() {
        // The ordinals are a tuple in the order given and a subscriber conflates on that order, so
        // sorting them before hashing would reintroduce I-3 for a narrower case.
        RegisteredQuery forward = registry.register("forward", SQL, List.of(0, 1), DANA);
        RegisteredQuery reversed = registry.register("reversed", SQL, List.of(1, 0), DANA);

        assertThat(reversed.fingerprint()).isNotEqualTo(forward.fingerprint());
        assertThat(reversed.view().keyOrdinals()).containsExactly(1, 0);
    }

    @Test
    void twoRetentionsOfTheSameSqlAreTwoComputations() {
        RegisteredQuery kept = registry.register("kept", SQL, List.of(0), DANA, Retention.forever());
        RegisteredQuery aged = registry.register("aged", SQL, List.of(0), DANA, Retention.ofAge(Duration.ofHours(1)));

        assertThat(aged.fingerprint())
                .as("the sharing path returns before start(), so the second registrant's retention was "
                        + "simply discarded — the view kept whatever the first asked for")
                .isNotEqualTo(kept.fingerprint());
        assertThat(aged.view().retention().maxAge()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void identicalQuestionsStillShareOneComputation() {
        // Sharing is the feature. A fingerprint that distinguished everything would be correct and
        // useless, so the property that matters is that this still holds.
        RegisteredQuery first = registry.register("a", SQL, List.of(0), DANA);
        RegisteredQuery second = registry.register("b", SQL, List.of(0), DANA);

        assertThat(second.fingerprint()).isEqualTo(first.fingerprint());
        assertThat(second).isSameAs(first);
    }

    @Test
    void aRegistrationThatChoosesNoRetentionKeepsEverything() {
        // TY-21. This was 24 hours of event time, applied without being asked for and without being
        // reported, against data whose timestamps routinely span years.
        RegisteredQuery query = registry.register("unspecified", SQL, List.of(0), DANA);

        assertThat(query.view().retention().isForever())
                .as("a view that silently drops rows answers a question nobody asked, and a short "
                        + "answer is indistinguishable from a complete one")
                .isTrue();
        assertThat(registry.defaultRetention().isForever()).isTrue();
    }
}
