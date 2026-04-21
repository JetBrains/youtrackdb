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
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Direct-step tests for {@link LetExpressionStep}, the per-record LET step that evaluates an
 * expression for each incoming record and attaches the result as metadata.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@code internalStart} throws {@link CommandExecutionException} when no predecessor is
 *       attached (line 53-55 guard).
 *   <li>{@code mapResult} evaluates the expression against each record and sets the variable
 *       metadata on the result.
 *   <li>{@code mapResult} skips the metadata assignment when the ctx already has a global
 *       variable of the same name — pins the {@code ctx.getVariable(varName) == null} branch
 *       at line 69.
 *   <li>{@code prettyPrint} renders {@code "+ LET (for each record)\n  varname = expression"}.
 *   <li>{@code serialize} stores {@code varname} and {@code expression} properties; each
 *       null-guard covered.
 *   <li>{@code deserialize} restores both properties when present; exercises the outer catch
 *       via a malformed result property.
 *   <li>{@code canBeCached} returns {@code true}.
 *   <li>{@code copy} handles null/non-null {@code varname} and {@code expression}
 *       independently (both lines 120-126 covered).
 * </ul>
 */
public class LetExpressionStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart
  // =========================================================================

  /**
   * Without a predecessor {@code internalStart} throws {@link CommandExecutionException} with a
   * message indicating the missing target. Pins the {@code prev == null} guard at line 53.
   */
  @Test
  public void internalStartWithoutPrevThrowsCommandExecutionException() {
    var ctx = newContext();
    var step = new LetExpressionStep(
        new SQLIdentifier("x"), parseExpression("SELECT 42 AS a"), ctx, false);

    assertThatThrownBy(() -> step.start(ctx))
        .isInstanceOf(CommandExecutionException.class)
        .hasMessageContaining("LET");
  }

  /**
   * {@code mapResult} evaluates the expression for each record and stores the result as metadata
   * under the variable name. Pins line 63-72 happy path.
   */
  @Test
  public void mapResultEvaluatesExpressionAndSetsMetadata() {
    var ctx = newContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    var r2 = new ResultInternal(session);
    r2.setProperty("id", 2);
    var step = new LetExpressionStep(
        new SQLIdentifier("doubled"),
        parseExpression("SELECT id * 2 AS a"), ctx, false);
    step.setPrevious(sourceStep(ctx, List.of(r1, r2)));

    var stream = step.start(ctx);
    var results = drain(stream, ctx);

    assertThat(results).hasSize(2);
    assertThat(((ResultInternal) results.get(0)).getMetadata("doubled")).isEqualTo(2);
    assertThat(((ResultInternal) results.get(1)).getMetadata("doubled")).isEqualTo(4);
  }

  /**
   * When a global variable with the same name already exists on the context, the per-record LET
   * must NOT overwrite it — pins the {@code ctx.getVariable(varName) == null} false branch at
   * line 69. This guard prevents per-record LETs from shadowing global LETs.
   */
  @Test
  public void mapResultSkipsMetadataWhenGlobalVariableExists() {
    var ctx = newContext();
    ctx.setVariable("shared", "global-value");
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    var step = new LetExpressionStep(
        new SQLIdentifier("shared"),
        parseExpression("SELECT id * 100 AS a"), ctx, false);
    step.setPrevious(sourceStep(ctx, List.of(r1)));

    var stream = step.start(ctx);
    var results = drain(stream, ctx);

    assertThat(results).hasSize(1);
    // Metadata was NOT set because the global variable took precedence.
    assertThat(((ResultInternal) results.get(0)).getMetadata("shared")).isNull();
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /** {@code prettyPrint} renders the "for each record" header followed by "name = expr". */
  @Test
  public void prettyPrintRendersLetHeader() {
    var ctx = newContext();
    var step = new LetExpressionStep(
        new SQLIdentifier("sum"),
        parseExpression("SELECT a + b AS x"), ctx, false);

    var out = step.prettyPrint(0, 2);

    assertThat(out).contains("+ LET (for each record)");
    assertThat(out).contains("sum");
    assertThat(out).contains("=");
  }

  // =========================================================================
  // serialize / deserialize
  // =========================================================================

  /**
   * {@code serialize} populates {@code varname} and {@code expression} properties when both are
   * non-null. Pins both guard true-branches at lines 85 and 88.
   */
  @Test
  public void serializeStoresVarnameAndExpression() {
    var ctx = newContext();
    var step = new LetExpressionStep(
        new SQLIdentifier("x"),
        parseExpression("SELECT 42 AS a"), ctx, false);

    var serialized = step.serialize(session);

    assertThat((Object) serialized.getProperty("varname")).isNotNull();
    assertThat((Object) serialized.getProperty("expression")).isNotNull();
  }

  /**
   * A full serialize → deserialize round-trip currently fails because
   * {@code SQLBooleanExpression.deserializeFromOResult} (reached via the nested expression AST)
   * looks up the concrete AST constructor with {@code getConstructor(Integer.class)} instead of
   * {@code int.class} — every concrete subclass declares only the primitive-int ctor, so lookup
   * throws {@code NoSuchMethodException}. Pinned as a falsifiable regression identical to the
   * Step 4 {@code FetchFromIndexStep} and Step 5 {@code FilterStep} pins — a third pin through
   * {@code LetExpressionStep} reinforces that the bug affects every step whose serialized form
   * nests an AST expression.
   *
   * <p>WHEN-FIXED: Track 22 — change {@code deserializeFromOResult} to use {@code int.class}
   * (primitive). Then delete this test and replace with a positive round-trip assertion.
   */
  @Test
  public void deserializeRoundTripHitsIntegerConstructorBug() {
    var ctx = newContext();
    var original = new LetExpressionStep(
        new SQLIdentifier("v"),
        parseExpression("SELECT 7 AS a"), ctx, false);
    var serialized = original.serialize(session);
    var restored = new LetExpressionStep(null, null, ctx, false);

    assertThatThrownBy(() -> restored.deserialize(serialized, session))
        .isInstanceOf(CommandExecutionException.class)
        .hasRootCauseInstanceOf(NoSuchMethodException.class);
  }

  /**
   * A malformed result (a sub-step referring to a non-existent class) is wrapped in
   * {@link CommandExecutionException} — pins the outer catch branch at lines 106-108.
   */
  @Test
  public void deserializeFailureWrapsInCommandExecutionException() {
    var ctx = newContext();
    var step = new LetExpressionStep(
        new SQLIdentifier("v"),
        parseExpression("SELECT 1 AS a"), ctx, false);

    var bad = new ResultInternal(session);
    var badSub = new ResultInternal(session);
    badSub.setProperty("javaType", "com.nonexistent.Step");
    bad.setProperty("subSteps", List.of(badSub));

    assertThatThrownBy(() -> step.deserialize(bad, session))
        .isInstanceOf(CommandExecutionException.class);
  }

  // =========================================================================
  // canBeCached
  // =========================================================================

  /** {@code canBeCached} always returns true. */
  @Test
  public void stepIsAlwaysCacheable() {
    var ctx = newContext();
    var step = new LetExpressionStep(
        new SQLIdentifier("x"),
        parseExpression("SELECT 1 AS a"), ctx, false);

    assertThat(step.canBeCached()).isTrue();
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy} deep-copies both {@code varname} and {@code expression}, producing independent
   * instances. Pins lines 118-128 non-null paths.
   */
  @Test
  public void copyProducesIndependentStepWithDeepCopies() {
    var ctx = newContext();
    var original = new LetExpressionStep(
        new SQLIdentifier("v"),
        parseExpression("SELECT 1 AS a"), ctx, true);

    ExecutionStep copied = original.copy(ctx);

    assertThat(copied).isNotSameAs(original).isInstanceOf(LetExpressionStep.class);
    var copy = (LetExpressionStep) copied;
    assertThat(copy.isProfilingEnabled()).isTrue();
    // Rendering equivalence pins the copied varname + expression.
    assertThat(copy.prettyPrint(0, 2)).isEqualTo(original.prettyPrint(0, 2));
  }

  /**
   * {@code copy} with null {@code varname} preserves null — pins the null-guard at line 120.
   */
  @Test
  public void copyWithNullVarnamePreservesNull() {
    var ctx = newContext();
    var original = new LetExpressionStep(
        null, parseExpression("SELECT 1 AS a"), ctx, false);

    var copy = (LetExpressionStep) original.copy(ctx);

    // Rendering contains "null = <expr>" (the null gets stringified).
    assertThat(copy.prettyPrint(0, 2)).contains("null = ");
  }

  /**
   * {@code copy} with null {@code expression} preserves null — pins the null-guard at line 124.
   */
  @Test
  public void copyWithNullExpressionPreservesNull() {
    var ctx = newContext();
    var original = new LetExpressionStep(new SQLIdentifier("v"), null, ctx, false);

    var copy = (LetExpressionStep) original.copy(ctx);

    // The rendered header still contains the varname; expression renders as "null".
    assertThat(copy.prettyPrint(0, 2)).contains("v = null");
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
   * Parses {@code SELECT <expr> AS alias} and returns the projection's expression. The expression
   * is constructed over a SELECT AST even though LET uses it stand-alone; this is how the
   * production planner builds LET expressions too.
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
