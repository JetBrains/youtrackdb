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
import static org.assertj.core.api.Assertions.assertThatCode;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSkip;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Direct-step tests for {@link SkipExecutionStep}, the intermediate step that discards the first
 * N records from the upstream stream.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@code internalStart} eagerly drains the first N records from upstream before returning.
 *   <li>Both exit conditions of the while loop (line 44): reaching the skip count
 *       ({@code skipped < skipValue} becomes false) and exhausting upstream
 *       ({@code rs.hasNext(ctx)} becomes false first).
 *   <li>{@code SKIP 0} passes all records through (loop never enters).
 *   <li>{@code SKIP :offset} resolves the value via input parameters.
 *   <li>{@code sendTimeout} is a no-op.
 *   <li>{@code close} propagates to a non-null predecessor and is a no-op otherwise (line 66).
 *   <li>{@code prettyPrint} renders {@code "+ SKIP (...)"} with indentation.
 *   <li>{@code canBeCached} returns {@code true}.
 *   <li>{@code copy} returns an independent step carrying the same profiling flag.
 * </ul>
 */
public class SkipExecutionStepTest extends TestUtilsFixture {

  // =========================================================================
  // Skip = 0 / passthrough
  // =========================================================================

  /**
   * {@code SKIP 0} yields the full upstream stream — the while loop never enters because {@code
   * skipped < 0} is false on entry. Pins the "skip zero" passthrough.
   */
  @Test
  public void skipZeroPassesAllRecords() {
    var skip = parseSkip("SELECT FROM OUser SKIP 0");
    var ctx = newContext();
    var step = new SkipExecutionStep(skip, ctx, false);
    step.setPrevious(sourceStep(ctx, results(4)));

    var out = drain(step.start(ctx), ctx);

    assertThat(out).hasSize(4);
    assertThat(out.stream().map(r -> (Integer) r.getProperty("i")).toList())
        .containsExactly(0, 1, 2, 3);
  }

  // =========================================================================
  // Skip < upstream
  // =========================================================================

  /**
   * {@code SKIP N} with {@code N < upstream.size()} discards the first N records and passes the
   * rest. The while-loop exits via {@code skipped < skipValue} becoming false (first condition).
   */
  @Test
  public void skipLessThanUpstreamDiscardsFirstN() {
    var skip = parseSkip("SELECT FROM OUser SKIP 2");
    var ctx = newContext();
    var step = new SkipExecutionStep(skip, ctx, false);
    step.setPrevious(sourceStep(ctx, results(5)));

    var out = drain(step.start(ctx), ctx);

    assertThat(out.stream().map(r -> (Integer) r.getProperty("i")).toList())
        .containsExactly(2, 3, 4);
  }

  // =========================================================================
  // Skip > upstream (loop exits via hasNext=false)
  // =========================================================================

  /**
   * {@code SKIP N} with {@code N > upstream.size()} drains upstream entirely — the while-loop
   * exits because {@code rs.hasNext(ctx)} becomes false first (second short-circuit branch).
   * Pins the upstream-exhausted exit path.
   */
  @Test
  public void skipLargerThanUpstreamDrainsEverything() {
    var skip = parseSkip("SELECT FROM OUser SKIP 10");
    var ctx = newContext();
    var step = new SkipExecutionStep(skip, ctx, false);
    step.setPrevious(sourceStep(ctx, results(3)));

    assertThat(drain(step.start(ctx), ctx)).isEmpty();
  }

  /**
   * {@code SKIP N} with an empty upstream returns empty immediately — {@code hasNext} is false on
   * entry so the loop body never executes.
   */
  @Test
  public void skipOnEmptyUpstreamReturnsEmpty() {
    var skip = parseSkip("SELECT FROM OUser SKIP 5");
    var ctx = newContext();
    var step = new SkipExecutionStep(skip, ctx, false);
    step.setPrevious(sourceStep(ctx, results(0)));

    assertThat(drain(step.start(ctx), ctx)).isEmpty();
  }

  // =========================================================================
  // Parameterized skip
  // =========================================================================

  /** {@code SKIP :offset} resolves the value from the context input parameters. */
  @Test
  public void skipViaInputParameterResolvesFromContext() {
    var skip = parseSkip("SELECT FROM OUser SKIP :offset");
    var ctx = newContext();
    ctx.setInputParameters(java.util.Map.of("offset", 2));
    var step = new SkipExecutionStep(skip, ctx, false);
    step.setPrevious(sourceStep(ctx, results(5)));

    var out = drain(step.start(ctx), ctx);

    assertThat(out).hasSize(3);
    assertThat(out.stream().map(r -> (Integer) r.getProperty("i")).toList())
        .containsExactly(2, 3, 4);
  }

  // =========================================================================
  // sendTimeout / close
  // =========================================================================

  /** {@code sendTimeout} is a no-op — pinned via {@code doesNotThrowAnyException}. */
  @Test
  public void sendTimeoutIsNoOp() {
    var skip = parseSkip("SELECT FROM OUser SKIP 1");
    var ctx = newContext();
    var step = new SkipExecutionStep(skip, ctx, false);

    assertThatCode(step::sendTimeout).doesNotThrowAnyException();
  }

  /** {@code close} propagates to the predecessor when non-null (line 66 true branch). */
  @Test
  public void closeWithPrevPropagates() {
    var skip = parseSkip("SELECT FROM OUser SKIP 1");
    var ctx = newContext();
    var step = new SkipExecutionStep(skip, ctx, false);
    var prevClosed = new AtomicBoolean(false);
    step.setPrevious(new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        return ExecutionStream.empty();
      }

      @Override
      public void close() {
        prevClosed.set(true);
      }
    });

    step.close();

    assertThat(prevClosed).isTrue();
  }

  /** {@code close} without a predecessor must not NPE — pins the {@code prev == null} branch. */
  @Test
  public void closeWithoutPrevIsNoOp() {
    var skip = parseSkip("SELECT FROM OUser SKIP 1");
    var ctx = newContext();
    var step = new SkipExecutionStep(skip, ctx, false);

    assertThatCode(step::close).doesNotThrowAnyException();
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /** {@code prettyPrint} renders {@code "+ SKIP (<body>)"}. */
  @Test
  public void prettyPrintRendersSkipBody() {
    var skip = parseSkip("SELECT FROM OUser SKIP 12");
    var ctx = newContext();
    var step = new SkipExecutionStep(skip, ctx, false);

    var out = step.prettyPrint(0, 2);

    assertThat(out).startsWith("+ SKIP (").contains("12").endsWith(")");
  }

  /** Non-zero depth prepends exactly {@code depth * indent} leading spaces. */
  @Test
  public void prettyPrintAppliesIndentation() {
    var skip = parseSkip("SELECT FROM OUser SKIP 3");
    var ctx = newContext();
    var step = new SkipExecutionStep(skip, ctx, false);

    var out = step.prettyPrint(1, 4);

    assertThat(out).startsWith("    +").doesNotStartWith("     +");
    assertThat(out).contains("+ SKIP (");
  }

  // =========================================================================
  // canBeCached / copy
  // =========================================================================

  /** SKIP is always cacheable — the skip value is resolved at execution time. */
  @Test
  public void stepIsAlwaysCacheable() {
    var skip = parseSkip("SELECT FROM OUser SKIP 1");
    var ctx = newContext();
    var step = new SkipExecutionStep(skip, ctx, false);

    assertThat(step.canBeCached()).isTrue();
  }

  /**
   * {@code copy} produces a distinct step whose internal {@code skip} AST is an independently-
   * copied instance. Uses reflection to reach the private {@code skip} field and assert {@code
   * isNotSameAs(original.skip)} — without this a mutation that aliased the same {@code skip}
   * reference (returning {@code new SkipExecutionStep(this.skip, ctx, profilingEnabled)}) would
   * pass a prettyPrint-only check because the rendered body would be identical.
   */
  @Test
  public void copyProducesIndependentStepWithDeepCopiedSkip() throws Exception {
    var skip = parseSkip("SELECT FROM OUser SKIP 9");
    var ctx = newContext();
    var original = new SkipExecutionStep(skip, ctx, true);

    var copied = original.copy(ctx);

    assertThat(copied).isNotSameAs(original).isInstanceOf(SkipExecutionStep.class);
    var copy = (SkipExecutionStep) copied;
    assertThat(copy.isProfilingEnabled()).isTrue();
    assertThat(copy.prettyPrint(0, 2)).contains("9");

    var skipField = SkipExecutionStep.class.getDeclaredField("skip");
    skipField.setAccessible(true);
    assertThat(skipField.get(copy))
        .as("copy's internal skip AST must be an independently-copied instance")
        .isNotSameAs(skipField.get(original));
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  private static SQLSkip parseSkip(String selectSql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(selectSql.getBytes()));
      var stm = (SQLSelectStatement) parser.parse();
      return stm.getSkip();
    } catch (Exception e) {
      throw new AssertionError("Failed to parse SKIP from: " + selectSql, e);
    }
  }

  private List<Result> results(int count) {
    var out = new ArrayList<Result>(count);
    for (var i = 0; i < count; i++) {
      var r = new ResultInternal(session);
      r.setProperty("i", i);
      out.add(r);
    }
    return out;
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
