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
package com.ash.messaging.pravaha.testkit;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.MpscLongRing;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;

/**
 * A chain of {@link RowOperator}s connected by real rings and arenas.
 *
 * <p>Every stage boundary is an {@link MpscLongRing} carrying arena handles, exactly as the engine's
 * lane exchanges do. That matters: a harness built on {@code ArrayList} would test the operators and
 * nothing else, and the ordering bugs worth catching live in the handoff.
 *
 * <p>Each stage is a schedulable {@link DeterministicScheduler.Step}, so the scheduler decides how
 * far ahead any stage runs. Interleaving is therefore varied by seed rather than fixed by the loop
 * that happens to be written here.
 */
public final class Pipeline implements AutoCloseable {

    private final List<Stage> stages = new ArrayList<>();
    private final MpscLongRing input;
    private final RowArena inputArena;
    private final RowLayout inputLayout;
    private final BinaryRowWriter inputWriter;
    private final List<byte[]> output = new ArrayList<>();

    private Pipeline(Builder b) {
        this.inputLayout = RowLayout.of(b.inputSchema);
        this.inputArena = new RowArena(MemoryAccess.best(), b.arenaSlabBytes, b.arenaMaxSlabs);
        this.inputWriter = new BinaryRowWriter(inputLayout);
        this.input = new MpscLongRing(b.ringCapacity);

        MpscLongRing upstream = input;
        RowArena upstreamArena = inputArena;
        RowLayout upstreamLayout = inputLayout;

        for (int i = 0; i < b.operators.size(); i++) {
            StageSpec spec = b.operators.get(i);
            boolean last = i == b.operators.size() - 1;
            Stage stage = new Stage(
                    spec.name,
                    spec.operator,
                    java.util.Objects.requireNonNull(upstream, "every stage but the last has a downstream ring"),
                    upstreamArena,
                    upstreamLayout,
                    RowLayout.of(spec.outputSchema),
                    new RowArena(MemoryAccess.best(), b.arenaSlabBytes, b.arenaMaxSlabs),
                    last ? null : new MpscLongRing(b.ringCapacity),
                    last ? output : null);
            stages.add(stage);
            upstream = stage.downstream; // null only after the last stage, where the loop ends
            upstreamArena = stage.outputArena;
            upstreamLayout = stage.outputLayout;
        }
    }

    public static Builder builder(StreamSchema inputSchema) {
        return new Builder(inputSchema);
    }

    /**
     * Writes a row into the pipeline's input.
     *
     * @return {@code false} if the input ring or arena is full -- the same backpressure signal a
     *     real source would see
     */
    public boolean feed(java.util.function.Consumer<RowWriter> populate) {
        long handle = inputArena.allocate(inputLayout.rowSize(256));
        if (handle == ArenaHandle.NULL) {
            return false;
        }
        inputWriter.begin(inputArena.regionOf(handle), inputArena.offsetOf(handle));
        populate.accept(inputWriter);
        inputWriter.commit();
        inputArena.trimTo(handle, inputWriter.sizeSoFar());
        return input.offer(handle);
    }

    /** Registers every stage with a scheduler, so it decides the interleaving. */
    public void registerWith(DeterministicScheduler scheduler) {
        for (Stage stage : stages) {
            scheduler.register(stage.name, stage::runOnce);
        }
    }

    /** The emitted rows, as raw bytes -- the form the determinism test compares. */
    public List<byte[]> output() {
        return List.copyOf(output);
    }

    public int outputCount() {
        return output.size();
    }

    @Override
    public void close() {
        inputArena.close();
        stages.forEach(s -> s.outputArena.close());
    }

    private static final class StageSpec {
        final String name;
        final RowOperator operator;
        final StreamSchema outputSchema;

        StageSpec(String name, RowOperator operator, StreamSchema outputSchema) {
            this.name = name;
            this.operator = operator;
            this.outputSchema = outputSchema;
        }
    }

    private static final class Stage {
        final String name;
        final RowOperator operator;
        final MpscLongRing upstream;
        final RowArena upstreamArena;
        final BinaryRowView view;
        final RowLayout outputLayout;
        final RowArena outputArena;
        final BinaryRowWriter writer;
        final @Nullable MpscLongRing downstream;
        final @Nullable List<byte[]> sink;

        Stage(
                String name,
                RowOperator operator,
                MpscLongRing upstream,
                RowArena upstreamArena,
                RowLayout upstreamLayout,
                RowLayout outputLayout,
                RowArena outputArena,
                @Nullable MpscLongRing downstream,
                @Nullable List<byte[]> sink) {
            this.name = name;
            this.operator = operator;
            this.upstream = upstream;
            this.upstreamArena = upstreamArena;
            this.view = new BinaryRowView(upstreamLayout);
            this.outputLayout = outputLayout;
            this.outputArena = outputArena;
            this.writer = new BinaryRowWriter(outputLayout);
            this.downstream = downstream;
            this.sink = sink;
        }

        boolean runOnce() {
            long handle = upstream.poll();
            if (handle == MpscLongRing.EMPTY) {
                return false;
            }
            view.wrap(upstreamArena.regionOf(handle), upstreamArena.offsetOf(handle));
            operator.process(view, new StageEmitter(this));
            return true;
        }
    }

    private static final class StageEmitter implements RowEmitter {
        private final Stage stage;
        private long pending = ArenaHandle.NULL;
        private int emitted;

        StageEmitter(Stage stage) {
            this.stage = stage;
        }

        @Override
        public RowWriter begin() {
            pending = stage.outputArena.allocate(stage.outputLayout.rowSize(256));
            if (pending == ArenaHandle.NULL) {
                throw new IllegalStateException("output arena exhausted in stage " + stage.name);
            }
            stage.writer.begin(stage.outputArena.regionOf(pending), stage.outputArena.offsetOf(pending));
            return new CommittingWriter(this);
        }

        @Override
        public int count() {
            return emitted;
        }

        void commit() {
            stage.writer.commit();
            stage.outputArena.trimTo(pending, stage.writer.sizeSoFar());
            if (stage.downstream != null) {
                if (!stage.downstream.offer(pending)) {
                    throw new IllegalStateException("downstream ring full in stage " + stage.name);
                }
            } else {
                // The terminal stage materialises bytes, which is what the determinism test diffs.
                BinaryRowView out = new BinaryRowView(stage.outputLayout)
                        .wrap(stage.outputArena.regionOf(pending), stage.outputArena.offsetOf(pending));
                java.util.Objects.requireNonNull(stage.sink, "a last stage has a sink")
                        .add(out.toByteArray());
            }
            emitted++;
            pending = ArenaHandle.NULL;
        }

        void abort() {
            stage.writer.abort();
            pending = ArenaHandle.NULL;
        }

        BinaryRowWriter writer() {
            return stage.writer;
        }
    }

    /** Delegates to the stage's writer but routes commit/abort back through the emitter. */
    private record CommittingWriter(StageEmitter emitter) implements RowWriter {

        @Override
        public StreamSchema schema() {
            return emitter.writer().schema();
        }

        @Override
        public RowWriter setNull(int o) {
            emitter.writer().setNull(o);
            return this;
        }

        @Override
        public RowWriter setBoolean(int o, boolean v) {
            emitter.writer().setBoolean(o, v);
            return this;
        }

        @Override
        public RowWriter setByte(int o, byte v) {
            emitter.writer().setByte(o, v);
            return this;
        }

        @Override
        public RowWriter setShort(int o, short v) {
            emitter.writer().setShort(o, v);
            return this;
        }

        @Override
        public RowWriter setInt(int o, int v) {
            emitter.writer().setInt(o, v);
            return this;
        }

        @Override
        public RowWriter setLong(int o, long v) {
            emitter.writer().setLong(o, v);
            return this;
        }

        @Override
        public RowWriter setFloat(int o, float v) {
            emitter.writer().setFloat(o, v);
            return this;
        }

        @Override
        public RowWriter setDouble(int o, double v) {
            emitter.writer().setDouble(o, v);
            return this;
        }

        @Override
        public RowWriter setDecimal(int o, long hi, long lo) {
            emitter.writer().setDecimal(o, hi, lo);
            return this;
        }

        @Override
        public RowWriter setBytes(int o, byte[] v) {
            emitter.writer().setBytes(o, v);
            return this;
        }

        @Override
        public RowWriter setString(int o, String v) {
            emitter.writer().setString(o, v);
            return this;
        }

        @Override
        public RowWriter weight(long w) {
            emitter.writer().weight(w);
            return this;
        }

        @Override
        public RowWriter eventTimestampNanos(long n) {
            emitter.writer().eventTimestampNanos(n);
            return this;
        }

        @Override
        public RowWriter sequence(long s) {
            emitter.writer().sequence(s);
            return this;
        }

        @Override
        public int commit() {
            emitter.commit();
            return 0;
        }

        @Override
        public void abort() {
            emitter.abort();
        }
    }

    /** Assembles a pipeline. */
    public static final class Builder {
        private final StreamSchema inputSchema;
        private final List<StageSpec> operators = new ArrayList<>();
        private int ringCapacity = 1024;
        private int arenaSlabBytes = 1 << 20;
        private int arenaMaxSlabs = 16;

        private Builder(StreamSchema inputSchema) {
            this.inputSchema = inputSchema;
        }

        public Builder stage(String name, StreamSchema outputSchema, RowOperator operator) {
            operators.add(new StageSpec(name, operator, outputSchema));
            return this;
        }

        public Builder ringCapacity(int value) {
            this.ringCapacity = value;
            return this;
        }

        public Builder arena(int slabBytes, int maxSlabs) {
            this.arenaSlabBytes = slabBytes;
            this.arenaMaxSlabs = maxSlabs;
            return this;
        }

        public Pipeline build() {
            if (operators.isEmpty()) {
                throw new IllegalStateException("a pipeline needs at least one stage");
            }
            return new Pipeline(this);
        }
    }
}
