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
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLForEachBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIfStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLReturnStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Direct-step tests for {@link ForEachStep}, the execution step that iterates over a source
 * collection, binding each element to a loop variable and executing a body plan per iteration.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@code internalStart} drains (start+close) the predecessor before iterating — pins the
 *       side-effect contract at lines 41-42.
 *   <li>{@code internalStart} with a non-empty iterator binds the loop variable on the context
 *       and delegates to the body plan's stream.
 *   <li>{@code internalStart} with an empty iterator returns an empty stream via EmptyStep (line
 *       53).
 *   <li>{@code containsReturn} returns {@code true} for each branch: direct {@link
 *       SQLReturnStatement}, nested {@link SQLForEachBlock} with return, nested {@link
 *       SQLIfStatement} with return; returns {@code false} for a pure-SELECT body.
 *   <li>{@code copy} handles null body (line 90 false branch) and non-null body (true branch).
 * </ul>
 */
public class ForEachStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart — predecessor drain + iteration
  // =========================================================================

  /**
   * {@code internalStart} starts AND closes the predecessor stream for side effects before
   * iterating the source collection. Both {@code prevStarted} and {@code prevClosed} must be true
   * after the call (pins the drain contract at lines 41-42).
   */
  @Test
  public void internalStartDrainsPredecessorForSideEffects() {
    var ctx = newContext();
    var loopVar = new SQLIdentifier("item");
    var source = parseExpression("SELECT [1] AS a");
    var body = List.of(parseStatement("SELECT 1 AS x"));

    var step = new ForEachStep(loopVar, source, body, ctx, false);

    var prevStarted = new AtomicBoolean(false);
    var prevClosed = new AtomicBoolean(false);
    step.setPrevious(new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        prevStarted.set(true);
        return new ExecutionStream() {
          @Override
          public boolean hasNext(CommandContext c2) {
            return false;
          }

          @Override
          public com.jetbrains.youtrackdb.internal.core.query.Result next(CommandContext c2) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void close(CommandContext c2) {
            prevClosed.set(true);
          }
        };
      }
    });

    var stream = step.start(ctx);
    // Drain so the step completes.
    while (stream.hasNext(ctx)) {
      stream.next(ctx);
    }
    stream.close(ctx);

    assertThat(prevStarted).as("predecessor must be started").isTrue();
    assertThat(prevClosed).as("predecessor stream must be closed").isTrue();
  }

  /**
   * With an empty source collection the iterator has no elements; the while-loop never enters
   * and {@code internalStart} returns the {@link EmptyStep}'s stream (line 53). The returned
   * stream has {@code hasNext=false}.
   */
  @Test
  public void internalStartWithEmptySourceReturnsEmptyStream() {
    var ctx = newContext();
    var step = new ForEachStep(
        new SQLIdentifier("item"),
        parseExpression("SELECT [] AS a"),
        List.of(parseStatement("SELECT 1 AS x")),
        ctx,
        false);
    step.setPrevious(stubEmptyPredecessor(ctx));

    var stream = step.start(ctx);

    assertThat(stream.hasNext(ctx)).isFalse();
    stream.close(ctx);
  }

  /**
   * With a non-empty source and a body whose plan's {@code executeFull()} returns {@code null}
   * (pure SELECT body — no RETURN), the while-loop runs to completion and the loop variable ends
   * bound to the LAST element. Pins line 45's {@code ctx.setVariable(...)} side effect: a
   * mutation that drops the {@code setVariable} call would leave the variable unset. A mutation
   * that broke iteration (e.g. {@code iterator.next()} moved outside the loop) would bind it to
   * the first element instead of the last.
   */
  @Test
  public void internalStartBindsLoopVariableToLastElementWhenBodyPlanReturnsNull() {
    var ctx = newContext();
    var step = new ForEachStep(
        new SQLIdentifier("item"),
        parseExpression("SELECT [7, 8, 9] AS a"),
        List.of(parseStatement("SELECT 1 AS x")),
        ctx,
        false);
    step.setPrevious(stubEmptyPredecessor(ctx));

    var stream = step.start(ctx);
    while (stream.hasNext(ctx)) {
      stream.next(ctx);
    }
    stream.close(ctx);

    // Body has no RETURN, so executeFull() returns null for every iteration; loop runs to
    // completion, variable holds the LAST element.
    assertThat(ctx.getVariable("item")).isEqualTo(9);
  }

  // =========================================================================
  // containsReturn
  // =========================================================================

  /** A body of pure SELECT statements with no RETURN produces {@code false}. */
  @Test
  public void containsReturnFalseForSelectOnlyBody() {
    var ctx = newContext();
    var step = new ForEachStep(
        new SQLIdentifier("i"),
        parseExpression("SELECT [1] AS a"),
        List.of(parseStatement("SELECT 1 AS x"), parseStatement("SELECT 2 AS y")),
        ctx,
        false);

    assertThat(step.containsReturn()).isFalse();
  }

  /** A direct RETURN in the body propagates via the {@link SQLReturnStatement} instanceof check. */
  @Test
  public void containsReturnTrueForDirectReturn() {
    var ctx = newContext();
    var step = new ForEachStep(
        new SQLIdentifier("i"),
        parseExpression("SELECT [1] AS a"),
        List.of((SQLStatement) new SQLReturnStatement(-1)),
        ctx,
        false);

    assertThat(step.containsReturn()).isTrue();
  }

  /**
   * A nested IF statement whose body contains RETURN propagates via the {@link SQLIfStatement}
   * instanceof check (line 80).
   */
  @Test
  public void containsReturnTrueForNestedIfWithReturn() {
    var ctx = newContext();
    var nestedIf = new SQLIfStatement(-1);
    nestedIf.addStatement(new SQLReturnStatement(-1));
    var step = new ForEachStep(
        new SQLIdentifier("i"),
        parseExpression("SELECT [1] AS a"),
        List.of((SQLStatement) nestedIf),
        ctx,
        false);

    assertThat(step.containsReturn()).isTrue();
  }

  /**
   * A nested FOREACH whose body contains RETURN propagates via the {@link SQLForEachBlock}
   * instanceof check (line 77).
   */
  @Test
  public void containsReturnTrueForNestedForEachWithReturn() {
    var ctx = newContext();
    var nestedFor = new SQLForEachBlock(-1);
    nestedFor.addStatement(new SQLReturnStatement(-1));
    var step = new ForEachStep(
        new SQLIdentifier("i"),
        parseExpression("SELECT [1] AS a"),
        List.of((SQLStatement) nestedFor),
        ctx,
        false);

    assertThat(step.containsReturn()).isTrue();
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy} with a non-null body stream-maps statement copies. The returned step is a
   * distinct instance carrying the same loopVariable, source, profiling flag, and an independent
   * body list.
   */
  @Test
  public void copyWithNonNullBodyProducesIndependentStep() {
    var ctx = newContext();
    var bodyStatement = parseStatement("SELECT 1 AS x");
    var original = new ForEachStep(
        new SQLIdentifier("i"),
        parseExpression("SELECT [1, 2] AS a"),
        new ArrayList<>(List.of(bodyStatement)),
        ctx,
        true);

    var copied = original.copy(ctx);

    assertThat(copied).isNotSameAs(original).isInstanceOf(ForEachStep.class);
    var copy = (ForEachStep) copied;
    assertThat(copy.isProfilingEnabled()).isTrue();
    assertThat(copy.body).isNotSameAs(original.body).hasSize(1);
  }

  /**
   * {@code copy} with a null body preserves {@code null} — pins the null-guard at line 90. A
   * mutation that produced an empty list instead of null would fail.
   */
  @Test
  public void copyWithNullBodyPreservesNull() {
    var ctx = newContext();
    var original = new ForEachStep(
        new SQLIdentifier("i"), parseExpression("SELECT [1] AS a"), null, ctx, false);

    var copy = (ForEachStep) original.copy(ctx);

    assertThat(copy.body).isNull();
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
   * Parses a {@code SELECT <expr> ...} and returns the expression of the first projection item.
   */
  private static SQLExpression parseExpression(String selectSql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(selectSql.getBytes()));
      var stm = (SQLSelectStatement) parser.parse();
      return stm.getProjection().getItems().get(0).getExpression();
    } catch (Exception e) {
      throw new AssertionError("Failed to parse expression from: " + selectSql, e);
    }
  }

  private static SQLStatement parseStatement(String sql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes()));
      return parser.parse();
    } catch (Exception e) {
      throw new AssertionError("Failed to parse statement: " + sql, e);
    }
  }

  private ExecutionStepInternal stubEmptyPredecessor(CommandContext ctx) {
    return new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        return ExecutionStream.empty();
      }
    };
  }
}
