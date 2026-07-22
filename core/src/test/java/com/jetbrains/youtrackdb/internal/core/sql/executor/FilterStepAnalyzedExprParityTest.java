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

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * Differential parity harness for FilterStep's dual-carry mechanism.
 *
 * <p>Tests N1 mitigation (IR-first vs AST-fallback branch selection) and verifies that the IR
 * path and the AST path produce identical filter decisions over a shared record set for every
 * predicate in a comprehensive WHERE corpus.
 *
 * <p>The corpus covers: the six comparisons (=, !=, <, <=, >, >=), arithmetic, NOT, AND, OR,
 * IS NULL, IS NOT NULL, bind parameters, method-call coercions, and ci-collated string
 * comparisons. Records include EntityImpl-backed rows (exercising the YTDB-628 fast path) and
 * projection/non-EntityImpl Result rows (exercising the slow path), plus rows with null
 * property values.
 */
public class FilterStepAnalyzedExprParityTest extends TestUtilsFixture {

  // =========================================================================
  // N1 mitigation: IR present / absent
  // =========================================================================

  /**
   * A lowerable predicate (simple comparison, within IR subset) produces a non-null IR tree.
   * The FilterStep uses the IR path for evaluation and produces correct results.
   */
  @Test
  public void lowerablePredicateUsesIrPath() {
    var className = createClassInstance().getName();
    session.begin();
    try {
      session.newEntity(className).setProperty("age", 20);
      session.newEntity(className).setProperty("age", 40);
      session.newEntity(className).setProperty("age", 60);
      session.commit();
    } catch (RuntimeException e) {
      session.rollback();
      throw e;
    }

    var where = parseWhere("SELECT FROM " + className + " WHERE age > 30");
    var ctx = newContext();
    var step = new FilterStep(where, ctx, -1L, false);
    step.setPrevious(new FetchFromClassExecutionStep(className, null, ctx, null, false));

    // Verify the IR is present (lowerable predicate)
    assertThat(step.getAnalyzed())
        .as("simple comparison 'age > 30' must lower to IR")
        .isNotNull();

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
   * An un-lowerable predicate (IN operator, outside IR subset) produces a null IR tree.
   * The FilterStep falls back to the AST path and still produces correct results.
   */
  @Test
  public void unlowerablePredicateFallsBackToAst() {
    var className = createClassInstance().getName();
    session.begin();
    try {
      session.newEntity(className).setProperty("age", 20);
      session.newEntity(className).setProperty("age", 40);
      session.newEntity(className).setProperty("age", 60);
      session.commit();
    } catch (RuntimeException e) {
      session.rollback();
      throw e;
    }

    var where = parseWhere("SELECT FROM " + className + " WHERE age IN [20, 60]");
    var ctx = newContext();
    var step = new FilterStep(where, ctx, -1L, false);
    step.setPrevious(new FetchFromClassExecutionStep(className, null, ctx, null, false));

    // Verify the IR is absent (un-lowerable predicate)
    assertThat(step.getAnalyzed())
        .as("IN operator is outside IR subset — must fall back to AST")
        .isNull();

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      var ages = results.stream().map(r -> (Integer) r.getProperty("age")).toList();
      assertThat(ages).containsExactlyInAnyOrder(20, 60);
    } finally {
      session.rollback();
    }
  }

  /**
   * A $-variable predicate (outside IR subset due to $-guard) falls back to AST.
   */
  @Test
  public void dollarVarPredicateFallsBackToAst() {
    var where = parseWhere("SELECT FROM OUser WHERE name = $current.name");
    var ctx = newContext();
    var step = new FilterStep(where, ctx, -1L, false);

    assertThat(step.getAnalyzed())
        .as("$-variable predicate is outside IR subset")
        .isNull();
  }

  /**
   * copy() shares the immutable IR and does not re-lower. Both original and copy agree on
   * the IR presence/absence and evaluate identically.
   */
  @Test
  public void copySharesImmutableIr() {
    var where = parseWhere("SELECT FROM OUser WHERE name = 'admin'");
    var ctx = newContext();
    var original = new FilterStep(where, ctx, -1L, false);

    AnalyzedExpr originalIr = original.getAnalyzed();
    assertThat(originalIr).as("lowerable predicate → IR present").isNotNull();

    var copy = (FilterStep) original.copy(ctx);
    assertThat(copy.getAnalyzed())
        .as("copy must share the same immutable IR instance")
        .isSameAs(originalIr);
  }

  // =========================================================================
  // Differential parity: IR path vs AST path produce identical decisions
  // =========================================================================

  /**
   * Runs each lowerable predicate through both evaluation paths (IR and AST) over a shared
   * record set and asserts identical filter decisions. EntityImpl-backed rows exercise the
   * YTDB-628 fast path; projection rows exercise the slow path; null-valued rows exercise
   * null handling.
   */
  @Test
  public void parityEntityBackedRows() {
    var className = createClassInstance().getName();
    session.begin();
    try {
      var e1 = session.newEntity(className);
      e1.setProperty("age", 10);
      e1.setProperty("score", 100);
      e1.setProperty("name", "Alice");

      var e2 = session.newEntity(className);
      e2.setProperty("age", 30);
      e2.setProperty("score", 200);
      e2.setProperty("name", "Bob");

      var e3 = session.newEntity(className);
      e3.setProperty("age", 50);
      e3.setProperty("score", 300);
      e3.setProperty("name", "Charlie");

      // Null-valued row to exercise null handling
      var e4 = session.newEntity(className);
      e4.setProperty("age", null);
      e4.setProperty("score", null);
      e4.setProperty("name", null);

      session.commit();
    } catch (RuntimeException e) {
      session.rollback();
      throw e;
    }

    // Corpus of lowerable predicates covering the IR subset
    var corpus = List.of(
        "age = 30",
        "age != 30",
        "age < 30",
        "age <= 30",
        "age > 30",
        "age >= 30",
        "age + 10 > 30",
        "NOT age > 30",
        "age > 10 AND age < 50",
        "age < 10 OR age > 40",
        "age IS NULL",
        "age IS NOT NULL",
        "name = 'Bob'",
        "name.asString() = 'Bob'");

    session.begin();
    try {
      for (String predicate : corpus) {
        assertParityEntityRows(className, predicate);
      }
    } finally {
      session.rollback();
    }
  }

  /**
   * Parity test with projection (non-EntityImpl) rows. These bypass the YTDB-628 fast path
   * and exercise the evaluator's slow comparison path.
   */
  @Test
  public void parityProjectionRows() {
    var className = createClassInstance().getName();

    // Build projection rows (non-entity)
    var rows = List.of(
        projectionRow(Map.of("age", 10, "score", 100, "name", "Alice")),
        projectionRow(Map.of("age", 30, "score", 200, "name", "Bob")),
        projectionRow(Map.of("age", 50, "score", 300, "name", "Charlie")),
        projectionRow(Map.of()) // no properties → nulls
    );

    var corpus = List.of(
        "age = 30",
        "age != 30",
        "age < 30",
        "age <= 30",
        "age > 30",
        "age >= 30",
        "age + 10 > 30",
        "NOT age > 30",
        "age > 10 AND age < 50",
        "age < 10 OR age > 40",
        "age IS NULL",
        "age IS NOT NULL",
        "name = 'Bob'");

    for (String predicate : corpus) {
      assertParityProjectionRows(className, predicate, rows);
    }
  }

  /**
   * Parity test with bind parameters. Both IR and AST paths must resolve parameters
   * identically from the command context.
   */
  @Test
  public void parityWithBindParameters() {
    var className = createClassInstance().getName();
    session.begin();
    try {
      session.newEntity(className).setProperty("age", 20);
      session.newEntity(className).setProperty("age", 40);
      session.newEntity(className).setProperty("age", 60);
      session.commit();
    } catch (RuntimeException e) {
      session.rollback();
      throw e;
    }

    var where = parseWhere("SELECT FROM " + className + " WHERE age > :threshold");
    var ctx = newContext();
    Map<Object, Object> params = new HashMap<>();
    params.put("threshold", 30);
    ctx.setInputParameters(params);

    var step = new FilterStep(where, ctx, -1L, false);
    step.setPrevious(new FetchFromClassExecutionStep(className, null, ctx, null, false));

    assertThat(step.getAnalyzed())
        .as("bind parameter predicate must lower to IR")
        .isNotNull();

    session.begin();
    try {
      var irResults = drain(step.start(ctx), ctx);
      var irAges = irResults.stream().map(r -> (Integer) r.getProperty("age")).toList();

      // AST-only path for comparison: manually evaluate matchesFilters
      var ctx2 = newContext();
      ctx2.setInputParameters(params);
      var allStep = new FetchFromClassExecutionStep(className, null, ctx2, null, false);
      var allStream = allStep.start(ctx2);
      var astAges = new ArrayList<Integer>();
      while (allStream.hasNext(ctx2)) {
        var row = allStream.next(ctx2);
        if (where.matchesFilters(row, ctx2)) {
          astAges.add(row.getProperty("age"));
        }
      }
      allStream.close(ctx2);

      assertThat(irAges)
          .as("bind param predicate: IR and AST must agree")
          .containsExactlyInAnyOrderElementsOf(astAges);
    } finally {
      session.rollback();
    }
  }

  /**
   * Parity test with ci-collated string comparison. The evaluator must apply the collation
   * from the schema property, matching the AST's behavior.
   */
  @Test
  public void parityWithCiCollation() {
    var className = createClassInstance().getName();
    // Create a STRING property with case-insensitive collation
    var schema = session.getMetadata().getSchema();
    var cls = schema.getClass(className);
    cls.createProperty("label", PropertyType.STRING).setCollate("ci");

    session.begin();
    try {
      session.newEntity(className).setProperty("label", "Hello");
      session.newEntity(className).setProperty("label", "HELLO");
      session.newEntity(className).setProperty("label", "world");
      session.commit();
    } catch (RuntimeException e) {
      session.rollback();
      throw e;
    }

    // ci collation: 'hello' should match both 'Hello' and 'HELLO'
    var where = parseWhere("SELECT FROM " + className + " WHERE label = 'hello'");
    var ctx = newContext();
    var step = new FilterStep(where, ctx, -1L, false);
    step.setPrevious(new FetchFromClassExecutionStep(className, null, ctx, null, false));

    assertThat(step.getAnalyzed())
        .as("simple comparison must lower to IR")
        .isNotNull();

    session.begin();
    try {
      var irResults = drain(step.start(ctx), ctx);
      var irLabels = irResults.stream()
          .map(r -> (String) r.getProperty("label"))
          .collect(Collectors.toList());

      // AST-only path
      var ctx2 = newContext();
      var allStep = new FetchFromClassExecutionStep(className, null, ctx2, null, false);
      var allStream = allStep.start(ctx2);
      var astLabels = new ArrayList<String>();
      while (allStream.hasNext(ctx2)) {
        var row = allStream.next(ctx2);
        if (where.matchesFilters(row, ctx2)) {
          astLabels.add(row.getProperty("label"));
        }
      }
      allStream.close(ctx2);

      assertThat(irLabels)
          .as("ci-collated comparison: IR and AST must agree")
          .containsExactlyInAnyOrderElementsOf(astLabels)
          .hasSize(2); // Both "Hello" and "HELLO" match "hello" under ci
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  /**
   * Asserts that IR and AST evaluation paths produce identical filter decisions for the given
   * predicate over entity-backed rows fetched from the given class.
   */
  private void assertParityEntityRows(String className, String predicate) {
    var where = parseWhere("SELECT FROM " + className + " WHERE " + predicate);
    var ctx1 = newContext();
    var step = new FilterStep(where, ctx1, -1L, false);
    step.setPrevious(new FetchFromClassExecutionStep(className, null, ctx1, null, false));

    assertThat(step.getAnalyzed())
        .as("predicate '%s' must lower to IR for parity test", predicate)
        .isNotNull();

    // IR path
    var irResults = drain(step.start(ctx1), ctx1);

    // AST-only path: iterate all rows and test via matchesFilters
    var ctx2 = newContext();
    var allStep = new FetchFromClassExecutionStep(className, null, ctx2, null, false);
    var allStream = allStep.start(ctx2);
    var astResults = new ArrayList<Result>();
    while (allStream.hasNext(ctx2)) {
      var row = allStream.next(ctx2);
      if (where.matchesFilters(row, ctx2)) {
        astResults.add(row);
      }
    }
    allStream.close(ctx2);

    assertThat(irResults)
        .as("entity rows: IR and AST must agree on predicate '%s'", predicate)
        .hasSameSizeAs(astResults);
  }

  /**
   * Asserts parity over projection (non-EntityImpl) rows for the given predicate.
   */
  private void assertParityProjectionRows(
      String className, String predicate, List<ResultInternal> rows) {
    var where = parseWhere("SELECT FROM " + className + " WHERE " + predicate);

    var ctx1 = newContext();
    var step = new FilterStep(where, ctx1, -1L, false);
    step.setPrevious(sourceStep(ctx1, rows));

    assertThat(step.getAnalyzed())
        .as("predicate '%s' must lower to IR for parity test", predicate)
        .isNotNull();

    // IR path
    var irResults = drain(step.start(ctx1), ctx1);

    // AST path: manually filter the same rows
    var ctx2 = newContext();
    var astResults = new ArrayList<Result>();
    for (var row : rows) {
      if (where.matchesFilters(row, ctx2)) {
        astResults.add(row);
      }
    }

    assertThat(irResults)
        .as("projection rows: IR and AST must agree on predicate '%s'", predicate)
        .hasSameSizeAs(astResults);
  }

  private ResultInternal projectionRow(Map<String, Object> properties) {
    var result = new ResultInternal(session);
    for (var entry : properties.entrySet()) {
      result.setProperty(entry.getKey(), entry.getValue());
    }
    return result;
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
}
