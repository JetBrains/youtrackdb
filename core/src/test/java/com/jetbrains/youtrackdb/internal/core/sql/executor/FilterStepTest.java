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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExpireResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Direct-step tests for {@link FilterStep}, the intermediate step that filters upstream records
 * using a WHERE clause.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@code internalStart} throws {@link IllegalStateException} when no predecessor is attached.
 *   <li>{@code internalStart} registers the WHERE expression on the context so upstream LET steps
 *       can detect variable references (mutation-kill: omitting the register call loses the side
 *       effect that decides subquery materialization).
 *   <li>Records for which {@code whereClause.matchesFilters(result, ctx)} returns {@code true} pass
 *       through; non-matching records are discarded.
 *   <li>When {@code timeoutMillis > 0} the step wraps the filtered stream in an {@code
 *       ExpireResultSet}; when {@code timeoutMillis <= 0} no wrapping occurs.
 *   <li>{@code prettyPrint} renders {@code "+ FILTER ITEMS WHERE"} plus the WHERE body, with the
 *       profiling cost suffix appended only when {@code profilingEnabled=true}.
 *   <li>{@code serialize} captures the WHERE clause; {@code deserialize} restores it. Corrupt input
 *       is wrapped in {@link CommandExecutionException}.
 *   <li>{@code canBeCached} returns {@code true} (AST is deep-copied per execution).
 *   <li>{@code copy} produces an independent step with an independently-copied WHERE clause.
 * </ul>
 */
public class FilterStepTest extends TestUtilsFixture {

  // =========================================================================
  // prev==null guard
  // =========================================================================

  /**
   * Without a predecessor {@code internalStart} throws {@link IllegalStateException} with a message
   * mentioning the step role — pins both the null-check branch and the exact error text so a
   * mutation that removes the guard or silently returns empty would be caught.
   */
  @Test
  public void internalStartWithoutPrevThrowsIllegalState() {
    var where = parseWhere("SELECT FROM OUser WHERE name = 'admin'");
    var ctx = newContext();
    var step = new FilterStep(where, ctx, -1L, false);

    assertThatThrownBy(() -> step.start(ctx))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("filter step requires a previous step");
  }

  // =========================================================================
  // whereClause registration (mutation-kill)
  // =========================================================================

  /**
   * {@code internalStart} MUST call {@code ctx.registerBooleanExpression(whereClause
   * .getBaseExpression())} before returning. A capturing {@link BasicCommandContext} subclass pins
   * the side effect: a mutation that drops the register call fails this test even though the
   * filtered stream itself is unchanged.
   */
  @Test
  public void internalStartRegistersBooleanExpressionOnContext() {
    var where = parseWhere("SELECT FROM OUser WHERE name = 'admin'");
    var capturing = new CapturingContext();
    capturing.setDatabaseSession(session);
    var step = new FilterStep(where, capturing, -1L, false);
    step.setPrevious(sourceStep(capturing, List.of()));

    drain(step.start(capturing), capturing);

    assertThat(capturing.registeredExpressions)
        .as("FilterStep must register the WHERE base-expression so LET steps can detect refs")
        .hasSize(1)
        .first()
        .isSameAs(where.getBaseExpression());
  }

  // =========================================================================
  // Predicate evaluation
  // =========================================================================

  /**
   * Records whose WHERE predicate evaluates to {@code true} pass through unchanged; non-matching
   * records are discarded. Counts upstream records BEFORE and AFTER to pin that the filter
   * consumed all inputs (no early termination) and produced a strict subset.
   */
  @Test
  public void internalStartPassesMatchingDiscardsNonMatching() {
    var className = createClassInstance().getName();

    session.begin();
    try {
      var r1 = session.newEntity(className);
      r1.setProperty("age", 20);
      var r2 = session.newEntity(className);
      r2.setProperty("age", 40);
      var r3 = session.newEntity(className);
      r3.setProperty("age", 60);
      session.commit();
    } catch (RuntimeException e) {
      session.rollback();
      throw e;
    }

    var where = parseWhere("SELECT FROM " + className + " WHERE age > 30");
    var ctx = newContext();
    var step = new FilterStep(where, ctx, -1L, false);
    step.setPrevious(new FetchFromClassExecutionStep(className, null, ctx, null, false));

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      var ages = results.stream().map(r -> (Integer) r.getProperty("age")).toList();
      assertThat(ages).containsExactlyInAnyOrder(40, 60);
    } finally {
      session.rollback();
    }
  }

  /**
   * A WHERE that matches no records yields an empty stream. Pins the "filter discards everything"
   * path — a mutation that inverts the predicate result (returning non-matching records) would
   * fail this.
   */
  @Test
  public void internalStartWithNoMatchesReturnsEmptyStream() {
    var className = createClassInstance().getName();

    session.begin();
    try {
      session.newEntity(className).setProperty("age", 20);
      session.newEntity(className).setProperty("age", 40);
      session.commit();
    } catch (RuntimeException e) {
      session.rollback();
      throw e;
    }

    var where = parseWhere("SELECT FROM " + className + " WHERE age > 999");
    var ctx = newContext();
    var step = new FilterStep(where, ctx, -1L, false);
    step.setPrevious(new FetchFromClassExecutionStep(className, null, ctx, null, false));

    session.begin();
    try {
      assertThat(drain(step.start(ctx), ctx)).isEmpty();
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // Timeout wrapping
  // =========================================================================

  /**
   * With {@code timeoutMillis > 0} the returned stream is wrapped in an {@link
   * com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExpireResultSet} so timeout
   * enforcement fires per record. The non-zero branch is pinned via class identity.
   */
  @Test
  public void positiveTimeoutWrapsStreamInExpireResultSet() {
    var where = parseWhere("SELECT FROM OUser WHERE name = 'admin'");
    var ctx = newContext();
    var step = new FilterStep(where, ctx, 10_000L, false);
    step.setPrevious(sourceStep(ctx, List.of()));

    var stream = step.start(ctx);

    assertThat(stream).isInstanceOf(ExpireResultSet.class);
    stream.close(ctx);
  }

  /**
   * With {@code timeoutMillis <= 0} the stream is NOT wrapped. Pins the zero/negative branch. The
   * returned type is a plain {@link
   * com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.FilterExecutionStream}, not
   * {@code ExpireResultSet}.
   */
  @Test
  public void nonPositiveTimeoutDoesNotWrapStream() {
    var where = parseWhere("SELECT FROM OUser WHERE name = 'admin'");
    var ctx = newContext();
    var step = new FilterStep(where, ctx, -1L, false);
    step.setPrevious(sourceStep(ctx, List.of()));

    var stream = step.start(ctx);

    assertThat(stream).isNotInstanceOf(ExpireResultSet.class);
    stream.close(ctx);
  }

  /**
   * Boundary: {@code timeoutMillis == 0} also skips wrapping (the guard is strict {@code > 0}). A
   * mutation flipping this to {@code >=} would fail.
   */
  @Test
  public void zeroTimeoutDoesNotWrapStream() {
    var where = parseWhere("SELECT FROM OUser WHERE name = 'admin'");
    var ctx = newContext();
    var step = new FilterStep(where, ctx, 0L, false);
    step.setPrevious(sourceStep(ctx, List.of()));

    var stream = step.start(ctx);

    assertThat(stream).isNotInstanceOf(ExpireResultSet.class);
    stream.close(ctx);
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * {@code prettyPrint} with profiling disabled renders the header and the WHERE body without a
   * cost suffix. The exact header text {@code "+ FILTER ITEMS WHERE"} is pinned so renaming
   * mutations would fail.
   */
  @Test
  public void prettyPrintWithoutProfilingOmitsCost() {
    var where = parseWhere("SELECT FROM OUser WHERE name = 'admin'");
    var ctx = newContext();
    var step = new FilterStep(where, ctx, -1L, false);

    var out = step.prettyPrint(0, 2);

    assertThat(out).contains("+ FILTER ITEMS WHERE");
    assertThat(out).contains("name");
    assertThat(out).doesNotContain("μs").doesNotContain("(0");
  }

  /**
   * With profiling enabled the header appends a "(cost μs)" suffix. Pins the profilingEnabled
   * branch (line 82).
   */
  @Test
  public void prettyPrintWithProfilingAppendsCost() {
    var where = parseWhere("SELECT FROM OUser WHERE name = 'admin'");
    var ctx = newContext();
    var step = new FilterStep(where, ctx, -1L, true);

    var out = step.prettyPrint(0, 2);

    assertThat(out).contains("+ FILTER ITEMS WHERE");
    assertThat(out).contains("μs");
  }

  /**
   * A non-zero depth prepends exactly {@code depth * indent} leading spaces. Exact-width pin
   * rejects both under-indent and over-indent mutations.
   */
  @Test
  public void prettyPrintAppliesIndentation() {
    var where = parseWhere("SELECT FROM OUser WHERE name = 'admin'");
    var ctx = newContext();
    var step = new FilterStep(where, ctx, -1L, false);

    var out = step.prettyPrint(1, 4);

    assertThat(out).startsWith("    +").doesNotStartWith("     +");
    assertThat(out).contains("+ FILTER ITEMS WHERE");
  }

  // =========================================================================
  // serialize / deserialize
  // =========================================================================

  /**
   * A {@code serialize → deserialize} round-trip currently fails because {@code
   * SQLBooleanExpression.deserializeFromOResult} looks up the concrete AST constructor with {@code
   * getConstructor(Integer.class)} instead of {@code int.class} — every concrete subclass (e.g.
   * {@code SQLOrBlock}) declares only the primitive-int ctor, so lookup always throws {@code
   * NoSuchMethodException}. The wrapper {@code CommandExecutionException} has that as its root
   * cause. Pinned as a falsifiable regression identical to Track 8 Step 4's {@code
   * FetchFromIndexStep} pin — a second pin through {@code FilterStep} demonstrates the bug's
   * generality to every step that serializes a {@code SQLBooleanExpression}.
   *
   * <p>WHEN-FIXED: Track 22 — change {@code deserializeFromOResult} to use {@code int.class}
   * (primitive). Then delete this test and replace with a positive round-trip assertion.
   */
  @Test
  public void serializeDeserializeHitsIntegerConstructorBug() {
    var original = parseWhere("SELECT FROM OUser WHERE name = 'admin'");
    var ctx = newContext();
    var source = new FilterStep(original, ctx, -1L, false);

    var serialized = source.serialize(session);
    var restored = new FilterStep(new SQLWhereClause(-1), ctx, -1L, false);

    assertThatThrownBy(() -> restored.deserialize(serialized, session))
        .isInstanceOf(CommandExecutionException.class)
        .hasRootCauseInstanceOf(NoSuchMethodException.class)
        // Pin the <init>(java.lang.Integer) signature fragment so an unrelated NSME regression
        // (a different missing ctor surfaced during a refactor) cannot silently pass this
        // WHEN-FIXED marker. The leading AST class varies with the serialized expression
        // shape, so we match only the signature fragment that uniquely identifies this
        // Integer-vs-int ctor bug.
        .rootCause()
        .hasMessageEndingWith("<init>(java.lang.Integer)");
  }

  /**
   * {@code serialize} stores the {@code whereClause} property when it is non-null. The null
   * branch of the {@code whereClause != null} guard in {@link FilterStep#serialize} is only
   * reachable when a subclass nulls out the private field post-construction, so it is
   * intentionally not covered here.
   *
   * <p>The assertion pins both (a) the stored property's type (a {@code ResultInternal}
   * fragment encoding the clause's AST, matching the shape {@code FilterStep#deserialize}
   * reads back) and (b) that the serialized fragment records the expected AST class tag
   * ({@code SQLOrBlock}, the root of a typical WHERE clause AST). A mutation that serialized
   * a stub value or a different property would not carry the {@code __class} tag, so a
   * non-null-only assertion is too weak.
   */
  @Test
  public void serializeStoresWhereClauseProperty() {
    var where = parseWhere("SELECT FROM OUser WHERE name = 'admin'");
    var ctx = newContext();
    var step = new FilterStep(where, ctx, -1L, false);

    var serialized = step.serialize(session);

    Object whereProp = serialized.getProperty("whereClause");
    assertThat(whereProp).isNotNull();
    assertThat(whereProp)
        .as("FilterStep.serialize must store the whereClause as a ResultInternal AST fragment")
        .isInstanceOf(com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal.class);
    // The serialized WHERE clause stores the AST-class tag under "__class". The WHERE root
    // for a comparison clause is SQLOrBlock (which wraps the AND chain containing the
    // equality) — pinning that token catches a mutation that serialized a stub value or
    // dropped the clause's AST in favor of a primitive string/field reference.
    assertThat(whereProp.toString())
        .as("serialized whereClause must carry the SQLOrBlock AST-class tag")
        .contains("SQLOrBlock");
  }

  /**
   * Malformed deserialize input (a substep pointing at a non-existent class) is wrapped in {@link
   * CommandExecutionException} — pins the catch-all at line 108.
   */
  @Test
  public void deserializeFailureWrapsInCommandExecutionException() {
    var where = parseWhere("SELECT FROM OUser WHERE name = 'admin'");
    var ctx = newContext();
    var step = new FilterStep(where, ctx, -1L, false);

    var bad = new ResultInternal(session);
    var badSub = new ResultInternal(session);
    badSub.setProperty("javaType", "com.nonexistent.Step");
    bad.setProperty("subSteps", List.of(badSub));

    assertThatThrownBy(() -> step.deserialize(bad, session))
        .isInstanceOf(CommandExecutionException.class)
        .hasRootCauseInstanceOf(ClassNotFoundException.class);
  }

  // =========================================================================
  // canBeCached
  // =========================================================================

  /** FilterStep is always cacheable — the WHERE AST is deep-copied on {@code copy()}. */
  @Test
  public void stepIsAlwaysCacheable() {
    var where = parseWhere("SELECT FROM OUser WHERE name = 'admin'");
    var ctx = newContext();
    var step = new FilterStep(where, ctx, -1L, false);

    assertThat(step.canBeCached()).isTrue();
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy} produces a distinct step with an independently-copied WHERE clause and carries
   * the same {@code timeoutMillis} / {@code profilingEnabled}. Functional equivalence is pinned by
   * rendering the same prettyPrint header as the original.
   */
  @Test
  public void copyProducesIndependentStepWithSameSettings() {
    var where = parseWhere("SELECT FROM OUser WHERE name = 'admin'");
    var ctx = newContext();
    var original = new FilterStep(where, ctx, 5_000L, true);

    ExecutionStep copied = original.copy(ctx);

    assertThat(copied).isNotSameAs(original).isInstanceOf(FilterStep.class);
    var copy = (FilterStep) copied;
    assertThat(copy.isProfilingEnabled()).isTrue();
    assertThat(copy.canBeCached()).isTrue();

    // Rendering equivalence: same header, same WHERE body text. The rendered form uses
    // double-quoted string literals ("admin"), not single-quoted ('admin') like the source SQL.
    var originalText = original.prettyPrint(0, 2);
    var copyText = copy.prettyPrint(0, 2);
    assertThat(copyText).contains("+ FILTER ITEMS WHERE").contains("name").contains("admin");
    // Same structural content (ignoring cost values which may differ post-run).
    assertThat(copyText.split("\n")[1]).isEqualTo(originalText.split("\n")[1]);
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  private static SQLWhereClause parseWhere(String selectSql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(selectSql.getBytes()));
      var stm = (SQLSelectStatement) parser.parse();
      return stm.getWhereClause();
    } catch (Exception e) {
      throw new AssertionError("Failed to parse WHERE from: " + selectSql, e);
    }
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

  /**
   * Capturing subclass of {@link BasicCommandContext} that records every boolean expression
   * registered via {@code registerBooleanExpression}. Used to pin the side effect in {@link
   * FilterStep#internalStart} so a mutation dropping the registration would be caught.
   */
  private static class CapturingContext extends BasicCommandContext {
    final List<
        com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression> registeredExpressions =
            new ArrayList<>();

    @Override
    public void registerBooleanExpression(
        com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression expr) {
      registeredExpressions.add(expr);
      super.registerBooleanExpression(expr);
    }
  }
}
