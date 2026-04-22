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
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIfStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLReturnStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Direct-step tests for {@link IfStep}, the execution step that evaluates an IF/ELSE condition and
 * delegates to the positive or negative statement list.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@code producePlan} picks the positive plan when the condition evaluates to {@code true}
 *       and the negative plan otherwise.
 *   <li>{@code producePlan} returns {@code null} when the condition is false and no negative
 *       statements are configured (negativeStatements {@code null} or empty).
 *   <li>{@code internalStart} returns an empty stream when {@code producePlan} returns {@code
 *       null} — pins the else branch at line 33 where {@code plan == null}.
 *   <li>{@code initPositivePlan} chains every positive statement; {@code initNegativePlan}
 *       chains every negative statement when non-empty; returns {@code null} for null list AND
 *       for an empty-but-non-null list.
 *   <li>{@code getCondition} / {@code setCondition} round-trip.
 *   <li>{@code containsReturn} detects a {@link SQLReturnStatement} in the positive list, the
 *       negative list, a nested IF positive branch, and a nested IF negative branch; returns
 *       false for a pure-SELECT body.
 *   <li>{@code copy} deep-copies the condition and both statement lists (null and non-null
 *       branches for each list).
 * </ul>
 */
public class IfStepTest extends TestUtilsFixture {

  // =========================================================================
  // producePlan / internalStart
  // =========================================================================

  /**
   * When the condition evaluates to {@code true}, {@code producePlan} returns a non-null plan
   * built from the positive statement list. The plan is chainable and distinct from any negative
   * plan.
   */
  @Test
  public void producePlanChoosesPositiveWhenConditionTrue() {
    var ctx = newContext();
    var step = new IfStep(ctx, false);
    step.setCondition(trueCondition());
    step.positiveStatements = List.of(parseSelect("SELECT 'pos' AS a"));
    step.negativeStatements = List.of(parseSelect("SELECT 'neg' AS a"));

    var plan = step.producePlan(ctx);

    assertThat(plan).isNotNull();
    assertThat(plan.prettyPrint(0, 2)).contains("pos").doesNotContain("neg");
  }

  /**
   * When the condition evaluates to {@code false}, {@code producePlan} returns the negative
   * plan built from the negative statement list.
   */
  @Test
  public void producePlanChoosesNegativeWhenConditionFalse() {
    var ctx = newContext();
    var step = new IfStep(ctx, false);
    step.setCondition(falseCondition());
    step.positiveStatements = List.of(parseSelect("SELECT 'pos' AS a"));
    step.negativeStatements = List.of(parseSelect("SELECT 'neg' AS a"));

    var plan = step.producePlan(ctx);

    assertThat(plan).isNotNull();
    assertThat(plan.prettyPrint(0, 2)).contains("neg").doesNotContain("pos");
  }

  /**
   * With {@code condition=false} and {@code negativeStatements=null}, {@code producePlan} returns
   * {@code null} — pins the negativeStatements null-guard at line 60.
   */
  @Test
  public void producePlanReturnsNullWhenConditionFalseAndNegativeNull() {
    var ctx = newContext();
    var step = new IfStep(ctx, false);
    step.setCondition(falseCondition());
    step.positiveStatements = List.of(parseSelect("SELECT 1 AS a"));
    step.negativeStatements = null;

    assertThat(step.producePlan(ctx)).isNull();
  }

  /**
   * With {@code condition=false} and {@code negativeStatements} set but empty, {@code
   * producePlan} returns {@code null} — pins the {@code size() > 0} check at line 61.
   */
  @Test
  public void producePlanReturnsNullWhenConditionFalseAndNegativeEmpty() {
    var ctx = newContext();
    var step = new IfStep(ctx, false);
    step.setCondition(falseCondition());
    step.positiveStatements = List.of(parseSelect("SELECT 1 AS a"));
    step.negativeStatements = new ArrayList<>();

    assertThat(step.producePlan(ctx)).isNull();
  }

  /**
   * When {@code producePlan} returns null, {@code internalStart} emits {@link
   * com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream#empty} — pins
   * the else branch at line 33.
   */
  @Test
  public void internalStartReturnsEmptyStreamWhenPlanNull() {
    var ctx = newContext();
    var step = new IfStep(ctx, false);
    step.setCondition(falseCondition());
    step.positiveStatements = List.of(parseSelect("SELECT 1 AS a"));
    step.negativeStatements = null;

    var stream = step.start(ctx);

    assertThat(stream.hasNext(ctx)).isFalse();
    stream.close(ctx);
  }

  // =========================================================================
  // initPositivePlan / initNegativePlan
  // =========================================================================

  /**
   * {@code initPositivePlan} chains every positive statement and returns a non-null plan even
   * for a one-element list. Pins the per-statement content via {@code prettyPrint} — a mutation
   * that silently dropped {@code positiveStatements[1]} would only reduce step count but keep
   * the plan non-empty, so a size-only assertion is too weak. Asserting both constants appear
   * in the chained plan detects both "second statement dropped" and "statements swapped" bugs.
   */
  @Test
  public void initPositivePlanChainsAllStatements() {
    var ctx = newContext();
    var step = new IfStep(ctx, false);
    step.positiveStatements =
        List.of(parseSelect("SELECT 'a' AS x"), parseSelect("SELECT 'b' AS x"));

    var plan = step.initPositivePlan(ctx);

    assertThat(plan).isNotNull();
    assertThat(plan.getSteps()).hasSizeGreaterThanOrEqualTo(2);
    var rendered = plan.prettyPrint(0, 0);
    assertThat(rendered)
        .as("both statements' projected constants must appear in the chained plan")
        .contains("\"a\" AS x")
        .contains("\"b\" AS x");
  }

  /**
   * {@code initNegativePlan} returns {@code null} when {@code negativeStatements == null} (line
   * 60 false branch).
   */
  @Test
  public void initNegativePlanReturnsNullForNullList() {
    var ctx = newContext();
    var step = new IfStep(ctx, false);
    step.negativeStatements = null;

    assertThat(step.initNegativePlan(ctx)).isNull();
  }

  /**
   * {@code initNegativePlan} returns {@code null} for an empty-but-non-null list (line 61 false
   * branch).
   */
  @Test
  public void initNegativePlanReturnsNullForEmptyList() {
    var ctx = newContext();
    var step = new IfStep(ctx, false);
    step.negativeStatements = new ArrayList<>();

    assertThat(step.initNegativePlan(ctx)).isNull();
  }

  /**
   * {@code initNegativePlan} chains every negative statement for a non-empty list. Pins the
   * per-statement content via {@code prettyPrint} so a mutation returning any non-empty fallback
   * plan (unrelated to the input statements) would still fail the test.
   */
  @Test
  public void initNegativePlanChainsAllStatementsForNonEmptyList() {
    var ctx = newContext();
    var step = new IfStep(ctx, false);
    step.negativeStatements = List.of(parseSelect("SELECT 1 AS x"));

    var plan = step.initNegativePlan(ctx);

    assertThat(plan).isNotNull();
    assertThat(plan.getSteps()).isNotEmpty();
    assertThat(plan.prettyPrint(0, 0))
        .as("the statement's projected constant must appear in the chained plan")
        .contains("1");
  }

  // =========================================================================
  // getCondition / setCondition
  // =========================================================================

  /** {@code setCondition} followed by {@code getCondition} returns the same instance. */
  @Test
  public void getConditionReturnsWhatSetConditionStored() {
    var ctx = newContext();
    var step = new IfStep(ctx, false);
    var condition = trueCondition();

    step.setCondition(condition);

    assertThat(step.getCondition()).isSameAs(condition);
  }

  // =========================================================================
  // containsReturn
  // =========================================================================

  /**
   * A positive-only body with no RETURN returns {@code false}. Pins the "no return found" path
   * through both loops.
   */
  @Test
  public void containsReturnFalseWhenNoReturnInEitherList() {
    var ctx = newContext();
    var step = new IfStep(ctx, false);
    step.setCondition(trueCondition());
    step.positiveStatements = List.of(parseSelect("SELECT 1 AS x"));
    step.negativeStatements = List.of(parseSelect("SELECT 2 AS x"));

    assertThat(step.containsReturn()).isFalse();
  }

  /** A RETURN in the positive list triggers {@code containsReturn == true}. */
  @Test
  public void containsReturnTrueWhenReturnInPositive() {
    var ctx = newContext();
    var step = new IfStep(ctx, false);
    step.setCondition(trueCondition());
    step.positiveStatements = List.of(new SQLReturnStatement(-1));
    step.negativeStatements = List.of(parseSelect("SELECT 1 AS x"));

    assertThat(step.containsReturn()).isTrue();
  }

  /** A RETURN in the negative list triggers {@code containsReturn == true}. */
  @Test
  public void containsReturnTrueWhenReturnInNegative() {
    var ctx = newContext();
    var step = new IfStep(ctx, false);
    step.setCondition(trueCondition());
    step.positiveStatements = List.of(parseSelect("SELECT 1 AS x"));
    step.negativeStatements = List.of(new SQLReturnStatement(-1));

    assertThat(step.containsReturn()).isTrue();
  }

  /**
   * A nested IF whose positive body contains a RETURN propagates upward through {@code
   * containsReturn}'s recursive {@code SQLIfStatement} branch.
   */
  @Test
  public void containsReturnTrueWhenNestedIfHasReturn() {
    var ctx = newContext();
    var step = new IfStep(ctx, false);
    step.setCondition(trueCondition());

    var nested = new SQLIfStatement(-1);
    nested.addStatement(new SQLReturnStatement(-1));
    step.positiveStatements = List.of((SQLStatement) nested);
    step.negativeStatements = null;

    assertThat(step.containsReturn()).isTrue();
  }

  /** Null statement lists on both sides yield {@code false} (pins both null-guard branches). */
  @Test
  public void containsReturnFalseWhenBothListsNull() {
    var ctx = newContext();
    var step = new IfStep(ctx, false);
    step.positiveStatements = null;
    step.negativeStatements = null;

    assertThat(step.containsReturn()).isFalse();
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy} deep-copies the condition and both statement lists. The copy has independent
   * list instances (mutating the original lists does not affect the copy).
   */
  @Test
  public void copyDeepCopiesConditionAndBothStatementLists() {
    var ctx = newContext();
    var original = new IfStep(ctx, true);
    original.setCondition(trueCondition());
    original.positiveStatements = new ArrayList<>(List.of(parseSelect("SELECT 1 AS x")));
    original.negativeStatements = new ArrayList<>(List.of(parseSelect("SELECT 2 AS x")));

    var copied = original.copy(ctx);

    assertThat(copied).isNotSameAs(original).isInstanceOf(IfStep.class);
    var copy = (IfStep) copied;
    assertThat(copy.isProfilingEnabled()).isTrue();
    assertThat(copy.positiveStatements).isNotSameAs(original.positiveStatements).hasSize(1);
    assertThat(copy.negativeStatements).isNotSameAs(original.negativeStatements).hasSize(1);
    // condition was copied (independent instance)
    assertThat(copy.getCondition()).isNotSameAs(original.getCondition());
    // Element-level independence: each SQLStatement was individually copied via
    // statement.copy() (IfStep.copy line 121). A mutation that just reused the original
    // elements (copy.positiveStatements.add(statement) instead of statement.copy()) would
    // leave the list instances distinct but the element references aliased.
    assertThat(copy.positiveStatements.get(0))
        .isNotSameAs(original.positiveStatements.get(0));
    assertThat(copy.negativeStatements.get(0))
        .isNotSameAs(original.negativeStatements.get(0));

    // Mutating the original lists must not affect the copy.
    original.positiveStatements.clear();
    original.negativeStatements.clear();
    assertThat(copy.positiveStatements).hasSize(1);
    assertThat(copy.negativeStatements).hasSize(1);
  }

  /**
   * {@code copy} handles {@code positiveStatements == null} — pins the null-guard at line 118.
   * The copy's positiveStatements must stay null (not a fresh empty list).
   */
  @Test
  public void copyWithNullPositiveStatementsPreservesNull() {
    var ctx = newContext();
    var original = new IfStep(ctx, false);
    original.setCondition(trueCondition());
    original.positiveStatements = null;
    original.negativeStatements = List.of(parseSelect("SELECT 1 AS x"));

    var copy = (IfStep) original.copy(ctx);

    assertThat(copy.positiveStatements).isNull();
    assertThat(copy.negativeStatements).hasSize(1);
  }

  /**
   * {@code copy} handles {@code negativeStatements == null} — pins the null-guard at line 126.
   */
  @Test
  public void copyWithNullNegativeStatementsPreservesNull() {
    var ctx = newContext();
    var original = new IfStep(ctx, false);
    original.setCondition(trueCondition());
    original.positiveStatements = List.of(parseSelect("SELECT 1 AS x"));
    original.negativeStatements = null;

    var copy = (IfStep) original.copy(ctx);

    assertThat(copy.positiveStatements).hasSize(1);
    assertThat(copy.negativeStatements).isNull();
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  /** Always-true condition via {@code WHERE 1=1}. */
  private static SQLBooleanExpression trueCondition() {
    var where = parseWhere("SELECT FROM OUser WHERE 1=1");
    return where.getBaseExpression();
  }

  /** Always-false condition via {@code WHERE 1=2}. */
  private static SQLBooleanExpression falseCondition() {
    var where = parseWhere("SELECT FROM OUser WHERE 1=2");
    return where.getBaseExpression();
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

  private static SQLStatement parseSelect(String sql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes()));
      return parser.parse();
    } catch (Exception e) {
      throw new AssertionError("Failed to parse statement: " + sql, e);
    }
  }
}
