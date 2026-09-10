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
package com.ash.messaging.pravaha.backfill;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Changing a running query's SQL without stopping it.
 *
 * <p>The property under all of these is one sentence: every input record is reflected in exactly one
 * version's output. Not zero -- that is a gap, and it is permanent and invisible. Not two -- that is
 * a double count, and every number involved looks plausible. A wall-clock cutover cannot promise
 * either, which is why the seam is a frontier.
 */
class ShadowDeploymentTest {

    @Test
    void beforeAnyCutoverTheFirstVersionOwnsEverything() {
        ShadowDeployment deployment = new ShadowDeployment("v1");

        assertThat(deployment.currentVersion()).isEqualTo("v1");
        assertThat(deployment.isAuthoritative("v1", 0)).isTrue();
        assertThat(deployment.isAuthoritative("v1", Long.MAX_VALUE)).isTrue();
        assertThat(deployment.isAuthoritative("v2", 0)).isFalse();
    }

    @Test
    void aCandidateIsCaughtUpWhenItReachesTheActiveVersionsFrontier() {
        // Not after a number of rows and not after a duration. Both are proxies that are wrong
        // exactly when the input rate changes, which is when somebody is most likely deploying.
        ShadowDeployment deployment = new ShadowDeployment("v1");
        deployment.startShadow("v2");

        assertThat(deployment.observeFrontiers(1000, 400)).isEqualTo(ShadowDeployment.State.BACKFILLING);
        assertThat(deployment.backfillRemaining()).isEqualTo(600);

        assertThat(deployment.observeFrontiers(1000, 1000)).isEqualTo(ShadowDeployment.State.CAUGHT_UP);
        assertThat(deployment.backfillRemaining()).isZero();
    }

    @Test
    void everyInputBelongsToExactlyOneVersionAcrossTheSeam() {
        // The property, checked directly over a range that straddles the cutover.
        ShadowDeployment deployment = new ShadowDeployment("v1");
        deployment.startShadow("v2");
        deployment.observeFrontiers(100, 100);
        deployment.cutOver(100);

        for (long frontier = 0; frontier < 200; frontier++) {
            long position = frontier;
            long owners = List.of("v1", "v2").stream()
                    .filter(version -> deployment.isAuthoritative(version, position))
                    .count();
            assertThat(owners)
                    .as("input at frontier %d is owned by %d versions, not one", position, owners)
                    .isEqualTo(1);
        }
        assertThat(deployment.isAuthoritative("v1", 99)).isTrue();
        assertThat(deployment.isAuthoritative("v2", 99)).isFalse();
        assertThat(deployment.isAuthoritative("v2", 100)).isTrue();
        assertThat(deployment.isAuthoritative("v1", 100)).isFalse();
    }

    @Test
    void cuttingOverBeforeTheCandidateIsReadyIsRefused() {
        // Not a smaller version of the same operation: the input between the candidate's frontier
        // and the seam ends up in neither version's output, permanently, with nothing downstream
        // able to tell.
        ShadowDeployment deployment = new ShadowDeployment("v1");
        deployment.startShadow("v2");
        deployment.observeFrontiers(1000, 400);

        assertThatThrownBy(() -> deployment.cutOver(1000))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4014")
                .hasMessageContaining("neither version's output");
    }

    @Test
    void rollingBackIsTheSameSwapReversed() {
        ShadowDeployment deployment = new ShadowDeployment("v1");
        deployment.startShadow("v2");
        deployment.observeFrontiers(100, 100);
        deployment.cutOver(100);

        deployment.rollBack(150);

        assertThat(deployment.currentVersion()).isEqualTo("v1");
        assertThat(deployment.state()).isEqualTo(ShadowDeployment.State.ROLLED_BACK);
        // And the record of who served what stays intact: v2 owned 100 to 149 and always will have.
        assertThat(deployment.isAuthoritative("v2", 120)).isTrue();
        assertThat(deployment.isAuthoritative("v1", 120)).isFalse();
        assertThat(deployment.isAuthoritative("v1", 150)).isTrue();
    }

    @Test
    void aSecondDeploymentCanFollowARollback() {
        // The realistic sequence: deploy, find a problem, roll back, fix, deploy again. Each seam
        // is another segment, and every frontier still has exactly one owner.
        ShadowDeployment deployment = new ShadowDeployment("v1");
        deployment.startShadow("v2");
        deployment.observeFrontiers(100, 100);
        deployment.cutOver(100);
        deployment.rollBack(150);

        deployment.startShadow("v3");
        deployment.observeFrontiers(200, 200);
        deployment.cutOver(200);

        assertThat(deployment.currentVersion()).isEqualTo("v3");
        assertThat(deployment.ownerAt(50)).isEqualTo("v1");
        assertThat(deployment.ownerAt(120)).isEqualTo("v2");
        assertThat(deployment.ownerAt(170)).isEqualTo("v1");
        assertThat(deployment.ownerAt(250)).isEqualTo("v3");
        assertThat(deployment.cutovers()).isEqualTo(2);
        assertThat(deployment.rollbacks()).isEqualTo(1);
    }

    @Test
    void aSeamCannotBePlacedAtOrBeforeTheLastOne() {
        // An overlapping seam makes two versions both responsible for the same input, and both emit
        // it. The double count is exactly the failure the frontier model exists to prevent.
        ShadowDeployment deployment = new ShadowDeployment("v1");
        deployment.startShadow("v2");
        deployment.observeFrontiers(100, 100);
        deployment.cutOver(100);

        assertThatThrownBy(() -> deployment.rollBack(100))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4015")
                .hasMessageContaining("both would emit it");
        assertThatThrownBy(() -> deployment.rollBack(50))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("Seams only move forward");
    }

    @Test
    void shadowingTheRunningVersionIsRefused() {
        ShadowDeployment deployment = new ShadowDeployment("v1");

        assertThatThrownBy(() -> deployment.startShadow("v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cut over to itself");
    }

    @Test
    void rollingBackWithNothingToRollBackToIsRefused() {
        assertThatThrownBy(() -> new ShadowDeployment("v1").rollBack(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only one version has ever served");
    }

    @Test
    void theHistoryReadsAsAnAuditTrail() {
        ShadowDeployment deployment = new ShadowDeployment("v1");
        deployment.startShadow("v2");
        deployment.observeFrontiers(10, 10);
        deployment.cutOver(10);

        assertThat(deployment.history()).containsExactly("from the beginning: v1", "from 10: v2");
    }

    @Test
    void anEmptyVersionNameIsRefused() {
        List<String> bad = new ArrayList<>();
        bad.add("");
        bad.add("   ");
        for (String name : bad) {
            assertThatThrownBy(() -> new ShadowDeployment(name)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
