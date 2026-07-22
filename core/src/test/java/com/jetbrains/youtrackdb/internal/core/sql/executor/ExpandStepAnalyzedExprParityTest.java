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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

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
import org.mockito.Mockito;

/**
 * Differential parity harness for ExpandStep's dual-carry <em>push-down</em> filter.
 *
 * <p>ExpandStep applies its generic push-down filter <em>after</em> expansion — each expanded
 * record flows through {@code filterMap}, which branches on the pre-lowered IR first (when the
 * predicate is within the lowering subset) and falls back to the AST's {@code matchesFilters}
 * otherwise. This harness mirrors {@link FilterStepAnalyzedExprParityTest} but exercises the
 * filter through ExpandStep so the post-expansion filtering path is covered.
 *
 * <p>Tests N1 mitigation (IR-first vs AST-fallback branch selection, both directions) and verifies
 * that the IR path and the AST path produce identical filter decisions over a shared set of
 * expanded rows for every predicate in a comprehensive WHERE corpus.
 *
 * <p>The corpus covers: the six comparisons (=, !=, <, <=, >, >=), arithmetic, NOT, AND, OR,
 * IS NULL, IS NOT NULL, bind parameters, method-call coercions, and ci-collated string
 * comparisons. Expanded rows include EntityImpl-backed rows (each upstream entity is loaded into a
 * fresh record — exercising the YTDB-628 fast path) and projection/non-EntityImpl passthrough rows
 * (exercising the slow path), plus rows with null property values.
 */
public class ExpandStepAnalyzedExprParityTest extends TestUtilsFixture {

  // =========================================================================
  // N1 mitigation: IR present / absent
  // =========================================================================

  /**
   * A lowerable push-down predicate (simple comparison, within IR subset) produces a non-null IR
   * tree. The ExpandStep uses the IR path for post-expansion filtering, the AST's
   * {@code matchesFilters} is NEVER called, and the filter produces correct results.
   *
   * <p>N1 regression guard: a Mockito spy on the SQLWhereClause verifies that
   * {@code matchesFilters} is never invoked. If {@code filterMap} were reverted to always use the
   * AST fallback, this test would fail because the spy would detect the invocation.
   */
  @Test
  public void lowerablePushDownFilterUsesIrPathNotAstFallback() {
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

    var where = spy(parseWhere("SELECT FROM " + className + " WHERE age > 30"));
    var ctx = newContext();
    // Expand each fetched entity (null alias → Identifiable branch → load → EntityImpl row),
    // then apply the push-down filter.
    var step = new ExpandStep(ctx, false, null, where, null);
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

      // N1 mitigation: matchesFilters must NEVER be called when the IR path is active.
      verify(where, never()).matchesFilters(any(Result.class), any(CommandContext.class));
    } finally {
      session.rollback();
    }
  }

  /**
   * An un-lowerable push-down predicate (IN operator, outside IR subset) produces a null IR tree.
   * The ExpandStep falls back to the AST's {@code matchesFilters} path and produces correct
   * results.
   *
   * <p>N1 mirror: a Mockito spy on the SQLWhereClause verifies that {@code matchesFilters} IS
   * invoked at least once, proving the AST fallback branch is actually taken. Combined with
   * {@link #lowerablePushDownFilterUsesIrPathNotAstFallback()}, this covers both branches.
   */
  @Test
  public void unlowerablePushDownFilterUsesAstFallbackPath() {
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

    var where = spy(parseWhere("SELECT FROM " + className + " WHERE age IN [20, 60]"));
    var ctx = newContext();
    var step = new ExpandStep(ctx, false, null, where, null);
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

      // N1 mirror: matchesFilters MUST be called when the IR is absent (AST fallback).
      verify(where, Mockito.atLeastOnce())
          .matchesFilters(any(Result.class), any(CommandContext.class));
    } finally {
      session.rollback();
    }
  }

  /**
   * A $-variable predicate (outside IR subset due to $-guard) falls back to AST. Behaviorally
   * exercises the fallback filtering path (not just lowering) through ExpandStep to confirm the AST
   * path works correctly for $-variable push-down predicates.
   */
  @Test
  public void dollarVarPushDownFilterFallsBackToAst() {
    var className = createClassInstance().getName();
    session.begin();
    try {
      session.newEntity(className).setProperty("name", "Alice");
      session.newEntity(className).setProperty("name", "Bob");
      session.commit();
    } catch (RuntimeException e) {
      session.rollback();
      throw e;
    }

    // $current.name is a $-prefixed context variable — outside IR subset
    var where = parseWhere("SELECT FROM " + className + " WHERE name = $current.name");
    var ctx = newContext();
    var step = new ExpandStep(ctx, false, null, where, null);
    step.setPrevious(new FetchFromClassExecutionStep(className, null, ctx, null, false));

    assertThat(step.getAnalyzed())
        .as("$-variable predicate is outside IR subset")
        .isNull();

    // $current resolves to the row under evaluation, so `name = $current.name` is a tautology
    // matching every expanded record. Verifies the AST fallback path runs without errors.
    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results)
          .as("$-var fallback must evaluate without errors (tautology matches all)")
          .hasSize(2);
    } finally {
      session.rollback();
    }
  }

  /**
   * copy() shares the immutable IR and does not re-lower. The copy agrees with the original on the
   * IR instance (same reference), confirming the private copy-constructor shares rather than
   * re-derives the lowered tree.
   */
  @Test
  public void copySharesImmutableIr() {
    var className = createClassInstance().getName();
    var where = parseWhere("SELECT FROM " + className + " WHERE name = 'admin'");
    var ctx = newContext();
    var original = new ExpandStep(ctx, false, null, where, null);

    AnalyzedExpr originalIr = original.getAnalyzed();
    assertThat(originalIr).as("lowerable predicate → IR present").isNotNull();

    var copy = (ExpandStep) original.copy(ctx);
    assertThat(copy.getAnalyzed())
        .as("copy must share the same immutable IR instance")
        .isSameAs(originalIr);
  }

  // =========================================================================
  // Differential parity: IR path vs AST path produce identical decisions
  // =========================================================================

  /**
   * Runs each lowerable predicate through both evaluation paths over a shared set of expanded
   * entity rows. EntityImpl-backed expanded rows exercise the YTDB-628 fast path; a null-valued row
   * exercises null handling. The IR path is the ExpandStep push-down filter; the AST oracle expands
   * the same rows without a filter and applies {@code matchesFilters} directly.
   */
  @Test
  public void parityEntityBackedExpandedRows() {
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

    session.begin();
    try {
      for (String predicate : lowerableCorpus()) {
        assertParityEntityExpandedRows(className, predicate);
      }
    } finally {
      session.rollback();
    }
  }

  /**
   * Parity test with projection (non-EntityImpl) expanded rows. Each upstream row's single field is
   * a passthrough inner {@link Result}, so ExpandStep emits the projection rows unchanged — these
   * bypass the YTDB-628 fast path and exercise the evaluator's slow comparison path.
   */
  @Test
  public void parityProjectionExpandedRows() {
    var className = createClassInstance().getName();
    for (String predicate : lowerableCorpus()) {
      assertParityProjectionExpandedRows(className, predicate);
    }
  }

  /**
   * Parity test with bind parameters through ExpandStep. Both IR and AST paths must resolve
   * parameters identically from the command context.
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
    Map<Object, Object> params = new HashMap<>();
    params.put("threshold", 30);

    var ctx1 = newContext();
    ctx1.setInputParameters(params);
    var step = new ExpandStep(ctx1, false, null, where, null);
    step.setPrevious(new FetchFromClassExecutionStep(className, null, ctx1, null, false));

    assertThat(step.getAnalyzed())
        .as("bind parameter predicate must lower to IR")
        .isNotNull();

    session.begin();
    try {
      var irAges = drain(step.start(ctx1), ctx1).stream()
          .map(r -> (Integer) r.getProperty("age"))
          .toList();

      // AST oracle: expand the same entities without filter, then matchesFilters.
      var ctx2 = newContext();
      ctx2.setInputParameters(params);
      var expandOnly = new ExpandStep(ctx2, false, null);
      expandOnly.setPrevious(new FetchFromClassExecutionStep(className, null, ctx2, null, false));
      var astAges = new ArrayList<Integer>();
      for (var row : drain(expandOnly.start(ctx2), ctx2)) {
        if (where.matchesFilters(row, ctx2)) {
          astAges.add(row.getProperty("age"));
        }
      }

      assertThat(irAges)
          .as("bind param predicate: IR and AST must agree")
          .containsExactlyInAnyOrderElementsOf(astAges);
    } finally {
      session.rollback();
    }
  }

  /**
   * Parity test with ci-collated string comparison through ExpandStep. The evaluator must apply the
   * collation from the schema property, matching the AST's behavior over the expanded rows.
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
    var ctx1 = newContext();
    var step = new ExpandStep(ctx1, false, null, where, null);
    step.setPrevious(new FetchFromClassExecutionStep(className, null, ctx1, null, false));

    assertThat(step.getAnalyzed())
        .as("simple comparison must lower to IR")
        .isNotNull();

    session.begin();
    try {
      var irLabels = drain(step.start(ctx1), ctx1).stream()
          .map(r -> (String) r.getProperty("label"))
          .collect(Collectors.toList());

      // AST oracle
      var ctx2 = newContext();
      var expandOnly = new ExpandStep(ctx2, false, null);
      expandOnly.setPrevious(new FetchFromClassExecutionStep(className, null, ctx2, null, false));
      var astLabels = new ArrayList<String>();
      for (var row : drain(expandOnly.start(ctx2), ctx2)) {
        if (where.matchesFilters(row, ctx2)) {
          astLabels.add(row.getProperty("label"));
        }
      }

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

  /** Corpus of lowerable predicates covering the IR subset. */
  private static List<String> lowerableCorpus() {
    return List.of(
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
  }

  /**
   * Asserts that IR and AST evaluation paths produce identical filter decisions for the given
   * predicate over EntityImpl-backed rows expanded from the given class. The IR path is the
   * ExpandStep push-down filter; the AST oracle expands the same entities without a filter and
   * applies {@code matchesFilters} directly.
   */
  private void assertParityEntityExpandedRows(String className, String predicate) {
    var where = parseWhere("SELECT FROM " + className + " WHERE " + predicate);

    // IR path: expand entities, then push-down filter.
    var ctx1 = newContext();
    var step = new ExpandStep(ctx1, false, null, where, null);
    step.setPrevious(new FetchFromClassExecutionStep(className, null, ctx1, null, false));

    assertThat(step.getAnalyzed())
        .as("predicate '%s' must lower to IR for parity test", predicate)
        .isNotNull();

    var irResults = drain(step.start(ctx1), ctx1);

    // AST oracle: expand the same entities WITHOUT a filter, then matchesFilters manually.
    var ctx2 = newContext();
    var expandOnly = new ExpandStep(ctx2, false, null);
    expandOnly.setPrevious(new FetchFromClassExecutionStep(className, null, ctx2, null, false));
    var astResults = new ArrayList<Result>();
    for (var row : drain(expandOnly.start(ctx2), ctx2)) {
      if (where.matchesFilters(row, ctx2)) {
        astResults.add(row);
      }
    }

    // Value-level comparison: extract user property maps (excluding metadata like @rid)
    // so a same-cardinality wrong-row selection cannot pass.
    var irProps = irResults.stream().map(this::userProperties).collect(Collectors.toList());
    var astProps = astResults.stream().map(this::userProperties).collect(Collectors.toList());
    assertThat(irProps)
        .as("entity expanded rows: IR and AST must agree on predicate '%s'", predicate)
        .containsExactlyInAnyOrderElementsOf(astProps);
  }

  /**
   * Asserts parity over projection (non-EntityImpl) expanded rows for the given predicate. Each
   * upstream row wraps a projection {@link Result} in its single field, so ExpandStep passes the
   * projection rows through unchanged — reaching the evaluator's slow path.
   */
  private void assertParityProjectionExpandedRows(String className, String predicate) {
    var where = parseWhere("SELECT FROM " + className + " WHERE " + predicate);
    // Shared expanded-row inputs (inner projection Results) reused by both runs — evaluation and
    // matchesFilters are read-only, so sharing is safe.
    var wrappedRows = wrappedProjectionRows();

    // IR path
    var ctx1 = newContext();
    var step = new ExpandStep(ctx1, false, null, where, null);
    step.setPrevious(sourceStep(ctx1, wrappedRows));

    assertThat(step.getAnalyzed())
        .as("predicate '%s' must lower to IR for parity test", predicate)
        .isNotNull();

    var irResults = drain(step.start(ctx1), ctx1);

    // AST oracle: expand the same wrapped rows without a filter, then matchesFilters manually.
    var ctx2 = newContext();
    var expandOnly = new ExpandStep(ctx2, false, null);
    expandOnly.setPrevious(sourceStep(ctx2, wrappedRows));
    var astResults = new ArrayList<Result>();
    for (var row : drain(expandOnly.start(ctx2), ctx2)) {
      if (where.matchesFilters(row, ctx2)) {
        astResults.add(row);
      }
    }

    var irProps = irResults.stream().map(this::userProperties).collect(Collectors.toList());
    var astProps = astResults.stream().map(this::userProperties).collect(Collectors.toList());
    assertThat(irProps)
        .as("projection expanded rows: IR and AST must agree on predicate '%s'", predicate)
        .containsExactlyInAnyOrderElementsOf(astProps);
  }

  /**
   * Builds upstream rows each wrapping a projection {@link Result} in a single "field" property.
   * ExpandStep's Result branch passes the inner projection through unchanged (non-EntityImpl → slow
   * path). The last row wraps a property-less inner Result to exercise null handling.
   */
  private List<ResultInternal> wrappedProjectionRows() {
    return List.of(
        wrap(projectionRow(Map.of("age", 10, "score", 100, "name", "Alice"))),
        wrap(projectionRow(Map.of("age", 30, "score", 200, "name", "Bob"))),
        wrap(projectionRow(Map.of("age", 50, "score", 300, "name", "Charlie"))),
        wrap(projectionRow(Map.of()))); // no properties → nulls
  }

  /** Wraps an inner projection Result as the single "field" of an outer upstream row. */
  private ResultInternal wrap(ResultInternal inner) {
    var outer = new ResultInternal(session);
    outer.setProperty("field", inner);
    return outer;
  }

  /**
   * Extracts user-defined properties from a Result into a Map for value-level comparison. Excludes
   * metadata (@rid, @class, @version) so entity-backed and projection results can be compared
   * uniformly.
   */
  private Map<String, Object> userProperties(Result r) {
    var map = new HashMap<String, Object>();
    for (String name : r.getPropertyNames()) {
      map.put(name, r.getProperty(name));
    }
    return map;
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
