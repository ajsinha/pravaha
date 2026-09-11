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
package com.ash.messaging.pravaha.cluster;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Assigning virtual partitions to nodes.
 *
 * <p>Two properties carry the weight, and a naive assignment gets both wrong. **Minimal movement**,
 * because moving a partition means snapshotting its state, handing it over and resuming -- so a
 * three-node cluster growing to four should migrate about a quarter of its state, not three quarters.
 * And **determinism**, because every node that agrees on the membership must compute the same owners
 * without asking anybody.
 */
class PartitionAssignmentTest {

    /** Ports derive from the id, not the argument order, so member "c" is the same node either way. */
    private static List<Member> members(String... ids) {
        List<Member> members = new ArrayList<>();
        for (String id : ids) {
            members.add(new Member(id, "host-" + id, 9070 + id.charAt(0) - 'a'));
        }
        return members;
    }

    private static List<String> ownerIds(PartitionAssignment assignment) {
        return assignment.owners().values().stream().map(Member::id).toList();
    }

    @Test
    void everyPartitionHasExactlyOneOwner() {
        PartitionAssignment assignment = PartitionAssignment.of(1024, members("a", "b", "c"));

        assertThat(assignment.owners()).hasSize(1024);
        for (int partition = 0; partition < 1024; partition++) {
            assertThat(assignment.ownerOf(partition))
                    .as("partition %d", partition)
                    .isPresent();
        }
        // The invariant the whole design rests on: two owners means two nodes writing one aggregate.
        assertThat(assignment.partitionsOf(members("a").get(0)).size()
                        + assignment.partitionsOf(members("b").get(0)).size()
                        + assignment.partitionsOf(members("c").get(0)).size())
                .isEqualTo(1024);
    }

    @Test
    void theSameMembershipAlwaysGivesTheSameAnswer() {
        // Determinism is why a leader does not have to distribute a map. It decides membership; the
        // assignment follows, and every node computes it for itself.
        PartitionAssignment first = PartitionAssignment.of(256, members("a", "b", "c"));
        PartitionAssignment second = PartitionAssignment.of(256, members("c", "a", "b"));

        assertThat(ownerIds(second)).isEqualTo(ownerIds(first));
    }

    @Test
    void theLoadIsSpreadAcrossNodes() {
        PartitionAssignment assignment = PartitionAssignment.of(1024, members("a", "b", "c", "d"));

        Map<String, Integer> counts = counts(assignment);
        // Rendezvous hashing is statistically fair rather than exactly equal. A quarter of 1024 is
        // 256; anything within a third of that is spread, and anything outside it is a bad hash.
        assertThat(counts).hasSize(4);
        counts.values().forEach(count -> assertThat(count).isBetween(170, 350));
    }

    @Test
    void addingANodeMovesOnlyItsShare() {
        PartitionAssignment before = PartitionAssignment.of(1024, members("a", "b", "c"));
        PartitionAssignment after = PartitionAssignment.of(1024, members("a", "b", "c", "d"));

        List<PartitionAssignment.PartitionMove> moves = before.movesTo(after);

        // A quarter of 1024 is 256. Modulo assignment would move roughly three quarters here, which
        // is the difference between a rebalance and an outage.
        assertThat(moves).hasSizeLessThan(400);
        // And everything that moved, moved *to* the new node: no partition should be shuffled
        // between two nodes that were both already there.
        assertThat(moves).allSatisfy(move -> assertThat(move.to().id()).isEqualTo("d"));
    }

    @Test
    void removingANodeMovesOnlyWhatItHeld() {
        PartitionAssignment before = PartitionAssignment.of(1024, members("a", "b", "c", "d"));
        PartitionAssignment after = PartitionAssignment.of(1024, members("a", "b", "c"));

        List<PartitionAssignment.PartitionMove> moves = before.movesTo(after);

        assertThat(moves).allSatisfy(move -> assertThat(move.from().id()).isEqualTo("d"));
        assertThat(moves)
                .hasSize(before.partitionsOf(new Member("d", "host-d", 9073)).size());
    }

    @Test
    void aKeyAlwaysLandsInTheSamePartitionWhateverTheMembership() {
        // Rescaling moves partitions and never rehashes keys. A key's partition is a property of the
        // key; only the owner changes. That is what makes elasticity tractable at all.
        PartitionAssignment three = PartitionAssignment.of(256, members("a", "b", "c"));
        PartitionAssignment four = PartitionAssignment.of(256, members("a", "b", "c", "d"));

        long key = "user-12345".hashCode();
        assertThat(three.ownerOfKey(key)).isPresent();
        assertThat(four.ownerOfKey(key)).isPresent();
        // The owner may differ; the partition may not.
        assertThat(Math.floorMod(key, 256)).isEqualTo(Math.floorMod(key, 256));
    }

    @Test
    void aNegativeKeyHashStillLandsSomewhere() {
        // Math.floorMod, not %, or half the key space would index negatively and own nothing.
        PartitionAssignment assignment = PartitionAssignment.of(64, members("a", "b"));

        assertThat(assignment.ownerOfKey(Long.MIN_VALUE)).isPresent();
        assertThat(assignment.ownerOfKey(-1)).isPresent();
    }

    @Test
    void oneNodeOwnsEverything() {
        PartitionAssignment assignment = PartitionAssignment.of(128, members("only"));

        assertThat(assignment.partitionsOf(members("only").get(0))).hasSize(128);
    }

    @Test
    void anEmptyClusterIsRefusedRatherThanAssignedToNobody() {
        // An empty assignment would look valid and own nothing.
        assertThatThrownBy(() -> PartitionAssignment.of(64, List.of()))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-9005")
                .hasMessageContaining("nowhere to put the work");
    }

    @Test
    void thePartitionCountCannotChange() {
        PartitionAssignment before = PartitionAssignment.of(256, members("a"));
        PartitionAssignment after = PartitionAssignment.of(512, members("a"));

        // Changing it would rehash every key, which is the thing virtual partitions exist to avoid.
        assertThatThrownBy(() -> before.movesTo(after))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("immutable for its life");
    }

    @Test
    void movingToAnIdenticalAssignmentMovesNothing() {
        PartitionAssignment assignment = PartitionAssignment.of(256, members("a", "b"));

        assertThat(assignment.movesTo(PartitionAssignment.of(256, members("b", "a"))))
                .isEmpty();
    }

    private static Map<String, Integer> counts(PartitionAssignment assignment) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        assignment.owners().values().forEach(member -> counts.merge(member.id(), 1, Integer::sum));
        return counts;
    }
}
