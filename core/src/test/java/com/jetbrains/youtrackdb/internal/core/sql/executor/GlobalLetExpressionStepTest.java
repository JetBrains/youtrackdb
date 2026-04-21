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
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Direct-step tests for {@link GlobalLetExpressionStep}, the one-shot LET step that evaluates a
 * scalar expression once and stores the result as a context variable.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@code internalStart} drains the predecessor before evaluating — pins the side-effect
 *       contract at lines 50-52 AND the null-predecessor branch (prev == null).
 *   <li>{@code internalStart} evaluates the expression and stores the result as a context
 *       variable on first call.
 *   <li>A second {@code internalStart} invocation does NOT re-evaluate the expression — pins
 *       the {@code executed} guard at line 59 via a counting expression proxy.
 *   <li>{@code prettyPrint} renders {@code "+ LET (once)\n  varname = expression"}.
 *   <li>{@code canBeCached} returns {@code true}.
 *   <li>{@code copy} produces an independent step with deep-copied varname + expression.
 * </ul>
 */
public class GlobalLetExpressionStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart — variable assignment + predecessor draining
  // =========================================================================

  /**
   * {@code internalStart} evaluates the expression once and stores the result as a context
   * variable. Returns an empty stream.
   */
  @Test
  public void internalStartEvaluatesAndStoresVariable() {
    var ctx = newContext();
    var step = new GlobalLetExpressionStep(
        new SQLIdentifier("threshold"),
        parseExpression("SELECT 100 AS a"), ctx, false);

    var stream = step.start(ctx);

    assertThat(stream.hasNext(ctx)).isFalse();
    stream.close(ctx);
    assertThat(ctx.getVariable("threshold")).isEqualTo(100);
  }

  /**
   * With a non-null predecessor, {@code internalStart} drains (start + close) the upstream stream
   * for side effects BEFORE calculating — pins lines 50-52.
   */
  @Test
  public void internalStartDrainsPredecessorBeforeCalculating() {
    var ctx = newContext();
    var prevStarted = new AtomicBoolean(false);
    var prevClosed = new AtomicBoolean(false);
    var step = new GlobalLetExpressionStep(
        new SQLIdentifier("v"), parseExpression("SELECT 1 AS a"), ctx, false);
    step.setPrevious(trackingPredecessor(ctx, prevStarted, prevClosed));

    step.start(ctx).close(ctx);

    assertThat(prevStarted).isTrue();
    assertThat(prevClosed).isTrue();
    assertThat(ctx.getVariable("v")).isEqualTo(1);
  }

  /**
   * With no predecessor, {@code internalStart} skips the drain and proceeds directly to
   * calculate. Pins the null-predecessor branch (prev == null) at line 50 false arm.
   */
  @Test
  public void internalStartWithoutPrevSkipsDrain() {
    var ctx = newContext();
    var step = new GlobalLetExpressionStep(
        new SQLIdentifier("v"), parseExpression("SELECT 5 AS a"), ctx, false);

    step.start(ctx).close(ctx);

    assertThat(ctx.getVariable("v")).isEqualTo(5);
  }

  /**
   * A second invocation of {@code internalStart} does NOT re-evaluate the expression — pins the
   * {@code executed} guard at line 59. The variable value must equal the first evaluation.
   * Uses a deliberately side-effecting expression via a ctx variable that will be overridden
   * between calls; the second call must leave the first-call's value intact.
   */
  @Test
  public void secondInternalStartDoesNotReevaluate() {
    var ctx = newContext();
    var step = new GlobalLetExpressionStep(
        new SQLIdentifier("captured"),
        parseExpression("SELECT $counter AS a"), ctx, false);

    ctx.setVariable("counter", 1);
    step.start(ctx).close(ctx);
    var firstValue = ctx.getVariable("captured");

    ctx.setVariable("counter", 2);
    step.start(ctx).close(ctx);
    var secondValue = ctx.getVariable("captured");

    assertThat(firstValue).isEqualTo(1);
    assertThat(secondValue)
        .as("second call must NOT re-evaluate; variable still holds first value")
        .isEqualTo(1);
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /** {@code prettyPrint} renders the "once" header followed by "name = expr". */
  @Test
  public void prettyPrintRendersLetOnceHeader() {
    var ctx = newContext();
    var step = new GlobalLetExpressionStep(
        new SQLIdentifier("x"),
        parseExpression("SELECT 42 AS a"), ctx, false);

    var out = step.prettyPrint(0, 2);

    assertThat(out).contains("+ LET (once)");
    assertThat(out).contains("x");
    assertThat(out).contains("=");
  }

  /** A non-zero depth applies leading indent. */
  @Test
  public void prettyPrintAppliesIndentation() {
    var ctx = newContext();
    var step = new GlobalLetExpressionStep(
        new SQLIdentifier("x"),
        parseExpression("SELECT 1 AS a"), ctx, false);

    var out = step.prettyPrint(1, 4);

    assertThat(out).startsWith("    +").doesNotStartWith("     +");
  }

  // =========================================================================
  // canBeCached
  // =========================================================================

  /** {@code canBeCached} always returns true. */
  @Test
  public void stepIsAlwaysCacheable() {
    var ctx = newContext();
    var step = new GlobalLetExpressionStep(
        new SQLIdentifier("x"),
        parseExpression("SELECT 1 AS a"), ctx, false);

    assertThat(step.canBeCached()).isTrue();
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy} deep-copies {@code varname} and {@code expression}, producing a step that
   * renders the same prettyPrint output but is a distinct instance.
   */
  @Test
  public void copyProducesIndependentStep() {
    var ctx = newContext();
    var original = new GlobalLetExpressionStep(
        new SQLIdentifier("v"),
        parseExpression("SELECT 1 AS a"), ctx, true);

    ExecutionStep copied = original.copy(ctx);

    assertThat(copied).isNotSameAs(original).isInstanceOf(GlobalLetExpressionStep.class);
    var copy = (GlobalLetExpressionStep) copied;
    assertThat(copy.isProfilingEnabled()).isTrue();
    assertThat(copy.prettyPrint(0, 2)).isEqualTo(original.prettyPrint(0, 2));
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  private static SQLExpression parseExpression(String selectSql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(selectSql.getBytes()));
      var stm = (SQLSelectStatement) parser.parse();
      return stm.getProjection().getItems().get(0).getExpression();
    } catch (Exception e) {
      throw new AssertionError("Failed to parse expression from: " + selectSql, e);
    }
  }

  private ExecutionStepInternal trackingPredecessor(
      CommandContext ctx, AtomicBoolean started, AtomicBoolean closed) {
    return new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        started.set(true);
        return new ExecutionStream() {
          @Override
          public boolean hasNext(CommandContext c2) {
            return false;
          }

          @Override
          public Result next(CommandContext c2) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void close(CommandContext c2) {
            closed.set(true);
          }
        };
      }
    };
  }

  @SuppressWarnings("unused")
  private static List<Result> drain(ExecutionStream stream, CommandContext ctx) {
    var out = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      out.add(stream.next(ctx));
    }
    stream.close(ctx);
    return out;
  }
}
