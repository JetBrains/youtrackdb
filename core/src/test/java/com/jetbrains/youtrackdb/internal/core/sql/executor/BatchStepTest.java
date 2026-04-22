/*
 * Copyright 2018 YouTrackDB
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBatch;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Direct-step tests for {@link BatchStep}, the step that commits intermediate transactions after
 * every {@code batchSize} records.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>The public constructor reads {@code batchSize} from the {@link SQLBatch} AST via
 *       {@code batch.evaluate(ctx)}.
 *   <li>{@code mapResult} commits + begins a new transaction when the entry count crosses a
 *       batch boundary (entryCount % batchSize == 0) AND a transaction is active.
 *   <li>{@code mapResult} does NOT commit when there's no active transaction — pins the
 *       {@code isActive()} false branch.
 *   <li>{@code mapResult} does NOT commit when the entry count is not yet at a boundary.
 *   <li>{@code prettyPrint} renders {@code "+ BATCH COMMIT EVERY N"}.
 *   <li>{@code copy} produces an independent step carrying the same batch size.
 * </ul>
 *
 * <p>Note: {@code BatchStep} has no previously-existing test class. The step is only reachable
 * via DELETE/UPDATE/INSERT BATCH clauses, which are exercised indirectly in
 * {@code DeleteStepTest} and others; this file provides the first direct-step coverage.
 */
public class BatchStepTest extends TestUtilsFixture {

  // =========================================================================
  // Constructor
  // =========================================================================

  /**
   * The public constructor reads the batch size from the {@link SQLBatch} AST at construction
   * time. Pins line 22 via a prettyPrint assertion (the size is only observable through
   * rendering).
   */
  @Test
  public void publicConstructorResolvesBatchSizeFromSQLBatch() {
    var ctx = newContext();
    var batch = batchOf(5);

    var step = new BatchStep(batch, ctx, false);

    assertThat(step.prettyPrint(0, 2)).contains("+ BATCH COMMIT EVERY 5");
  }

  // =========================================================================
  // internalStart / mapResult
  // =========================================================================

  /**
   * When the upstream produces records during an active transaction, {@code mapResult} commits
   * at each batch boundary (every {@code batchSize} records) AND opens a new transaction. Pins
   * the happy path at lines 35-39 (isActive true, modulo==0 true, commit + begin).
   *
   * <p>Seeds {@code batchSize=2} and pushes 4 records; expects 2 commit + begin pairs. Since
   * commit + begin reopens the transaction, the test verifies the records survive across the
   * boundary by asserting the final count.
   */
  @Test
  public void mapResultCommitsAndBeginsAtEachBoundary() {
    var ctx = newContext();
    var className = createClassInstance().getName();
    session.begin();
    try {
      var r1 = new ResultInternal(session);
      r1.setProperty("id", 1);
      var r2 = new ResultInternal(session);
      r2.setProperty("id", 2);
      var r3 = new ResultInternal(session);
      r3.setProperty("id", 3);
      var r4 = new ResultInternal(session);
      r4.setProperty("id", 4);

      // Simulate entity mutations so the transaction's entry count grows.
      session.newEntity(className);
      session.newEntity(className);
      session.newEntity(className);
      session.newEntity(className);

      var step = new BatchStep(batchOf(2), ctx, false);
      step.setPrevious(sourceStep(ctx, List.of(r1, r2, r3, r4)));

      var results = drain(step.start(ctx), ctx);

      assertThat(results).hasSize(4);
      // The transaction was committed and reopened; the records survive across batches.
      assertThat(session.countClass(className))
          .as("all 4 entities committed across 2 batch boundaries")
          .isEqualTo(4);
    } finally {
      if (session.isTxActive()) {
        session.rollback();
      }
    }
  }

  /**
   * With no active transaction, {@code mapResult} does NOT commit (pins the
   * {@code isActive()} false branch at line 35). The records pass through unchanged.
   */
  @Test
  public void mapResultDoesNothingWhenNoTransactionActive() {
    var ctx = newContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    var r2 = new ResultInternal(session);
    r2.setProperty("id", 2);
    var step = new BatchStep(batchOf(1), ctx, false);
    step.setPrevious(sourceStep(ctx, List.of(r1, r2)));

    var results = drain(step.start(ctx), ctx);

    assertThat(results).hasSize(2);
    assertThat(session.isTxActive()).isFalse();
  }

  /**
   * When the entry count is not yet at a boundary ({@code entryCount % batchSize != 0}),
   * {@code mapResult} skips the commit. Pins the modulo-nonzero branch at line 36.
   *
   * <p>Uses {@code batchSize=100} and only 2 records, so the boundary is never reached. The
   * transaction remains active throughout.
   */
  @Test
  public void mapResultSkipsCommitBeforeReachingBoundary() {
    var ctx = newContext();
    var className = createClassInstance().getName();
    session.begin();
    try {
      session.newEntity(className);
      session.newEntity(className);

      var r1 = new ResultInternal(session);
      r1.setProperty("id", 1);
      var r2 = new ResultInternal(session);
      r2.setProperty("id", 2);
      var step = new BatchStep(batchOf(100), ctx, false);
      step.setPrevious(sourceStep(ctx, List.of(r1, r2)));

      var results = drain(step.start(ctx), ctx);

      assertThat(results).hasSize(2);
      assertThat(session.isTxActive())
          .as("transaction must still be active — boundary not reached")
          .isTrue();
    } finally {
      if (session.isTxActive()) {
        session.rollback();
      }
    }
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /** {@code prettyPrint} renders "+ BATCH COMMIT EVERY N". */
  @Test
  public void prettyPrintRendersBatchHeader() {
    var ctx = newContext();
    var step = new BatchStep(batchOf(7), ctx, false);

    var out = step.prettyPrint(0, 2);

    assertThat(out).contains("+ BATCH COMMIT EVERY 7");
  }

  /** A non-zero depth applies leading indent; exact-width pin. */
  @Test
  public void prettyPrintAppliesIndentation() {
    var ctx = newContext();
    var step = new BatchStep(batchOf(1), ctx, false);

    var out = step.prettyPrint(1, 4);

    assertThat(out).startsWith("    +").doesNotStartWith("     +");
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy} produces an independent step carrying the same batch size. Uses the private
   * constructor via the public {@code copy} method.
   */
  @Test
  public void copyProducesIndependentStepWithSameBatchSize() {
    var ctx = newContext();
    var original = new BatchStep(batchOf(3), ctx, true);

    ExecutionStep copied = original.copy(ctx);

    assertThat(copied).isNotSameAs(original).isInstanceOf(BatchStep.class);
    var copy = (BatchStep) copied;
    assertThat(copy.isProfilingEnabled()).isTrue();
    assertThat(copy.prettyPrint(0, 2)).isEqualTo(original.prettyPrint(0, 2));
  }

  /**
   * {@code batchSize == 0} is accepted by {@link SQLBatch} (and therefore by the public
   * constructor), but {@code mapResult} unconditionally executes {@code entryCount % batchSize}
   * on the first record — which throws {@link ArithmeticException} at iteration time. Pinning
   * the current "silent accept, runtime crash" behavior as a falsifiable regression so that a
   * future hardening (reject 0 in the constructor, or treat 0 as "no commit") forces updating
   * this test.
   *
   * <p>WHEN-FIXED: Track 22 — either reject {@code batchSize == 0} at constructor time with a
   * descriptive {@link IllegalArgumentException}, or re-interpret 0 as "never commit" (matching
   * the dead-code "no batch" semantics in {@code SQLBatch.evaluate}).
   */
  @Test
  public void batchSizeZeroThrowsArithmeticExceptionOnFirstRecord() {
    var ctx = newContext();
    var r1 = new ResultInternal(session);
    var step = new BatchStep(batchOf(0), ctx, false);
    step.setPrevious(sourceStep(ctx, List.of(r1)));

    // mapResult's entryCount % batchSize is only reached when a transaction is active
    // (the outer `if (db.getTransactionInternal().isActive())` guard). Start a tx so the
    // bug surfaces — which is the production shape any real "BATCH 0" usage would have,
    // since the step is only scheduled under an active tx in practice.
    session.begin();
    try {
      var stream = step.start(ctx);
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> drain(stream, ctx))
          .as("batchSize=0 must crash on the first record via entryCount %% batchSize")
          .isInstanceOf(ArithmeticException.class);
    } finally {
      if (session.isTxActive()) {
        session.rollback();
      }
    }
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  /**
   * Builds a {@link SQLBatch} with the given integer value via an anonymous subclass that sets
   * the protected {@code num} field.
   */
  private static SQLBatch batchOf(int value) {
    var numArg = new SQLInteger(-1);
    numArg.setValue(value);
    return new SQLBatch(-1) {
      {
        this.num = numArg;
      }
    };
  }

  private ExecutionStepInternal sourceStep(CommandContext ctx, List<? extends Result> rows) {
    return new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        return ExecutionStream.resultIterator(new ArrayList<Result>(rows).iterator());
      }
    };
  }

  private static List<Result> drain(ExecutionStream stream, CommandContext ctx) {
    var out = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      out.add(stream.next(ctx));
    }
    stream.close(ctx);
    return out;
  }
}
