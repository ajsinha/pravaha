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
package com.ash.messaging.pravaha.serving;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowKind;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a query writes when its answer is served rather than shipped.
 *
 * <p>The sink is thin on purpose, and its whole job is not to lose the changelog's meaning on the
 * way in. A weight of {@code -1} is a removal; treating it as another insert leaves the view serving
 * a row the query has already withdrawn.
 *
 * <p>That failure hides in the common case. A correction arrives as a retraction and an insert for
 * the same key in one batch, and the insert overwrites either way -- so a sink that ignores weights
 * looks correct until a retraction is the last word for its key, which is exactly what a delete is.
 * That is the case here.
 */
class ViewSinkTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    private static ViewSink sink(ServedView view) {
        return new ViewSink(view, SCHEMA);
    }

    private static ServedView view() {
        return new ServedView("user_volume", SCHEMA, List.of(0), 100);
    }

    @Test
    void aCommitRefusedAsTooLargeStillDrainsTheChangeLog() {
        // STRM-5. ServedView.commit applies and evicts and *then* refuses with VIEW_TOO_LARGE, so
        // the rows are in the view by the time it throws -- and the throw used to leave
        // ViewSink.commit before pending was ever touched, with StagedRow.commit still appending on
        // every subsequent row. Measured at 100 001 entries and climbing.
        //
        // A subscriber has to be attached: the no-listener path cleared unconditionally, so the
        // leak needed the case the old comment claimed was safe.
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 2);
        ViewSink sink = sink(view);
        List<ViewChange> received = new ArrayList<>();
        sink.onCommit((batch, frontier) -> received.addAll(batch));

        sink.begin().setString(0, "u1").setLong(1, 1).weight(1).sequence(1).commit();
        sink.begin().setString(0, "u2").setLong(1, 2).weight(1).sequence(2).commit();
        sink.begin().setString(0, "u3").setLong(1, 3).weight(1).sequence(3).commit();

        assertThatThrownBy(() -> sink.commit(sink.appliedFrontier()))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4022");

        assertThat(sink.pendingChanges())
                .as("the refusal is about the view's size, not a reason to keep the change log for ever")
                .isZero();
        assertThat(received)
                .as("and the changes that were applied before it refused still reached the subscriber")
                .isNotEmpty();

        // And it stays drained: the next rows do not accumulate on top of the first refusal.
        sink.begin().setString(0, "u4").setLong(1, 4).weight(1).sequence(4).commit();
        assertThatThrownBy(() -> sink.commit(sink.appliedFrontier())).isInstanceOf(PravahaException.class);
        assertThat(sink.pendingChanges()).isZero();
    }

    @Test
    void aRowWrittenAndCommittedIsServed() {
        ServedView view = view();
        ViewSink sink = sink(view);

        sink.begin().setString(0, "u1").setLong(1, 42).weight(1).sequence(10).commit();
        sink.commit(sink.appliedFrontier());

        assertThat(view.get("u1").values().orElseThrow()[1]).isEqualTo(42L);
        assertThat(sink.rowsApplied()).isEqualTo(1);
    }

    @Test
    void aRetractionThatIsTheLastWordRemovesTheKey() {
        // The case a correction hides: with a retraction and an insert in one batch the insert
        // overwrites whatever the retraction did, so a sink that ignores weights passes. A delete
        // is a retraction with nothing after it, and there is nowhere left to hide.
        ServedView view = view();
        ViewSink sink = sink(view);
        sink.begin().setString(0, "u1").setLong(1, 42).weight(1).sequence(10).commit();
        sink.commit(10);
        assertThat(view.get("u1").found()).isTrue();

        sink.begin().setString(0, "u1").setLong(1, 42).weight(-1).sequence(20).commit();
        sink.commit(20);

        assertThat(view.get("u1").found())
                .as("the query withdrew this row and the view still serves it")
                .isFalse();
        assertThat(view.size()).isZero();
    }

    @Test
    void aDeleteRowKindIsARetraction() {
        // Sources and operators express the same thing two ways. Both have to arrive as a removal,
        // or the meaning depends on which one the writer happened to use.
        ServedView view = view();
        ViewSink sink = sink(view);
        sink.begin().setString(0, "u1").setLong(1, 42).weight(1).sequence(10).commit();
        sink.commit(10);

        sink.begin()
                .setString(0, "u1")
                .setLong(1, 42)
                .rowKind(RowKind.DELETE)
                .sequence(20)
                .commit();
        sink.commit(20);

        assertThat(view.get("u1").found()).isFalse();
    }

    @Test
    void applyingIsNotCommitting() {
        // Committing per row would make every intermediate state of a batch readable, and a
        // consistent read would then be consistent with nothing.
        ServedView view = view();
        ViewSink sink = sink(view);

        sink.begin().setString(0, "u1").setLong(1, 1).weight(1).sequence(10).commit();
        sink.begin().setString(0, "u2").setLong(1, 2).weight(1).sequence(11).commit();

        assertThat(view.size()).isZero();
        assertThat(view.pendingChanges()).isEqualTo(2);

        sink.commit(sink.appliedFrontier());

        assertThat(view.size()).isEqualTo(2);
    }

    @Test
    void theAppliedFrontierIsTheFurthestRowSeen() {
        ServedView view = view();
        ViewSink sink = sink(view);

        sink.begin().setString(0, "u1").setLong(1, 1).weight(1).sequence(50).commit();
        sink.begin().setString(0, "u2").setLong(1, 2).weight(1).sequence(20).commit();

        assertThat(sink.appliedFrontier())
                .as("an out-of-order row must not drag the frontier backwards")
                .isEqualTo(50);
    }

    @Test
    void anAbortedRowLeavesNothingBehind() {
        ServedView view = view();
        ViewSink sink = sink(view);

        var writer = sink.begin();
        writer.setString(0, "u1").setLong(1, 99);
        writer.abort();
        sink.commit(10);

        assertThat(view.size()).isZero();
        assertThat(sink.rowsApplied()).isZero();
    }

    @Test
    void aSubscriberAttachingMidCommitGetsTheNextCommitWholeRatherThanThisOnesTail() {
        // STRM-11. The staging decision was `!listeners.isEmpty()` evaluated per row, so a
        // subscriber that attached between two rows of one commit received the rows after it
        // attached and not the ones before -- a fragment, delivered as a completed batch.
        // USER_GUIDE.md promises "a batch is a commit. Never a partial window", and on a windowed
        // query this is a partly-closed window presented as a closed one.
        ServedView view = view();
        ViewSink sink = sink(view);

        List<List<ViewChange>> batches = new java.util.ArrayList<>();

        // First row of the commit goes in with nobody listening...
        sink.begin().setString(0, "u1").setLong(1, 1).weight(1).sequence(1).commit();
        // ...and the subscriber arrives here, mid-commit.
        sink.onCommit((changes, frontier) -> batches.add(List.copyOf(changes)));
        sink.begin().setString(0, "u2").setLong(1, 2).weight(1).sequence(2).commit();
        sink.begin().setString(0, "u3").setLong(1, 3).weight(1).sequence(3).commit();
        sink.commit(100L);

        assertThat(batches)
                .as("this commit began with no audience, so the subscriber hears nothing of it. It "
                        + "used to receive [u2, u3] -- two thirds of a commit, indistinguishable from "
                        + "a whole one")
                .isEmpty();

        // The next commit is delivered entire.
        sink.begin().setString(0, "u4").setLong(1, 4).weight(1).sequence(4).commit();
        sink.begin().setString(0, "u5").setLong(1, 5).weight(1).sequence(5).commit();
        sink.commit(200L);

        assertThat(batches).hasSize(1);
        assertThat(batches.get(0))
                .as("a subscription starts at a commit boundary, never inside one")
                .hasSize(2);
    }

    @Test
    void aCommitThatBeganWithSubscribersIsDeliveredWhole() {
        // The property the fix must not cost: an already-attached subscriber still gets every row.
        ServedView view = view();
        ViewSink sink = sink(view);

        List<List<ViewChange>> batches = new java.util.ArrayList<>();
        sink.onCommit((changes, frontier) -> batches.add(List.copyOf(changes)));

        sink.begin().setString(0, "u1").setLong(1, 1).weight(1).sequence(1).commit();
        sink.begin().setString(0, "u2").setLong(1, 2).weight(1).sequence(2).commit();
        sink.begin().setString(0, "u3").setLong(1, 3).weight(1).sequence(3).commit();
        sink.commit(100L);

        assertThat(batches).hasSize(1);
        assertThat(batches.get(0)).as("all three rows, one batch, one commit").hasSize(3);
    }

    @Test
    void aSinkWithNoSubscribersStagesNothing() {
        // The other property: a sink nobody is listening to must not accumulate a change log.
        ServedView view = view();
        ViewSink sink = sink(view);

        sink.begin().setString(0, "u1").setLong(1, 1).weight(1).sequence(1).commit();
        sink.begin().setString(0, "u2").setLong(1, 2).weight(1).sequence(2).commit();

        assertThat(sink.pendingChanges()).isZero();
        sink.commit(100L);
        assertThat(sink.pendingChanges()).isZero();
    }
}
