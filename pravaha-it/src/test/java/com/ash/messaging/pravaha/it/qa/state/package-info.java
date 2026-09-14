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
/**
 * STATE: checkpoints, the registry journal, recovery, cluster modes.
 *
 * <p>{@code docs/qa/cases/STATE.md} authors 110 cases against {@code FileCheckpointStore},
 * {@code PeriodicCheckpointer}, {@code QueryExecution.checkpoint/restore}, {@code
 * InterpretedPipeline}'s snapshot format, {@code QueryRegistry}'s per-query checkpoint directories
 * and journal, {@code RegistryJournal} itself, and {@code CoordinatorFactory}'s cluster-mode matrix.
 *
 * <p>The case file's own "Three facts" section was written against an earlier state of {@code
 * develop}. As executed, {@code QueryRegistry.start} now wires {@code
 * QueryExecution.checkpointingViewWith} for every registration (a served view's committed contents
 * travel in the checkpoint under the key {@code "served-view"}, regardless of {@code isStateful()})
 * and calls {@code restoreFrom} before a query is fed anything -- so {@code CheckpointStore.latest()}
 * and {@code QueryExecution.restore()} *are* reachable from shipped code, through {@code register()}.
 * Where a case's authored "Expected" section rests on the older facts, this is recorded case by case
 * in {@code docs/qa/logs/STATE.md} rather than silently reconciled here.
 *
 * <p>Cases whose harness is described as "a direct checkpointer" over an embedded execution are
 * built the way {@code PeriodicCheckpointerTest} and {@code CheckpointRecoveryTest} already do: a
 * {@code QueryExecution} constructed directly from a physical plan, bypassing {@code QueryRegistry}
 * entirely, so that {@code checkpointingViewWith} is never wired and {@code operatorState()} reflects
 * only {@code isStateful()} pipelines, as the case file's harness section (H-CS, H-PRJ, H-WIN,
 * H-JOIN) describes. Cases that name H-REG, H-JRN or H-SRV go through the real {@code QueryRegistry}
 * or a real node, and are executed and reported as such, served-view wiring included.
 */
package com.ash.messaging.pravaha.it.qa.state;
