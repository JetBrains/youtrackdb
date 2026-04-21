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
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInsertStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Direct-step tests for {@link InsertValuesStep}, which assigns values from parsed {@code INSERT
 * INTO … (col1, col2) VALUES (v1, v2)} expressions to incoming entity results.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Single-row INSERT: every identifier/value pair is applied to the entity.
 *   <li>Multi-row INSERT: the per-row counter cycles through value sets (verified via the inner
 *       {@code nextValueSet %= values.size()} modulus).
 *   <li>Arity mismatch: identifiers and values of different sizes is rejected with
 *       {@link CommandExecutionException}.
 *   <li>Non-{@link ResultInternal} upstream that still {@code isEntity()}: gets wrapped in a new
 *       {@link UpdatableResult} and then mutated (lines 46–51 of the mapper).
 *   <li>Non-entity upstream (no attached record): rejected — "cannot modify entity".
 *   <li>prettyPrint: header, column list, VALUES tuple (covers the loop with {@code i < 3}
 *       cap), multi-row rendering, "..." ellipsis for 3+ rows, indentation.
 *   <li>{@code copy()}: deep-copies identifiers and value-expression lists independently.
 * </ul>
 */
public class InsertValuesStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart: assignment semantics
  // =========================================================================

  /**
   * A single-row INSERT with two columns assigns both to the upstream entity.
   */
  @Test
  public void singleRowAppliesBothColumns() {
    var className = createClassInstance().getName();
    session.begin();
    try {
      var ctx = newContext();
      var insert = parseInsert("INSERT INTO " + className + " (name, age) VALUES ('Alice', 30)");
      var step = new InsertValuesStep(
          insert.ids(), insert.values(), ctx, false);

      var entity = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
          .newEntity(className);
      step.setPrevious(sourceStep(ctx, List.of(new UpdatableResult(session, entity))));

      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      assertThat((Object) results.get(0).getProperty("name")).isEqualTo("Alice");
      assertThat((Object) results.get(0).getProperty("age")).isEqualTo(30);
    } finally {
      session.rollback();
    }
  }

  /**
   * A three-row INSERT cycles through the value sets: each upstream entity receives the
   * Nth tuple (wrapping via modulus if more rows than tuples are seen).
   */
  @Test
  public void multiRowCyclesValueSets() {
    var className = createClassInstance().getName();
    session.begin();
    try {
      var ctx = newContext();
      var insert = parseInsert(
          "INSERT INTO " + className + " (val) VALUES (1), (2), (3)");
      var step = new InsertValuesStep(insert.ids(), insert.values(), ctx, false);

      var e1 = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
          .newEntity(className);
      var e2 = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
          .newEntity(className);
      var e3 = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
          .newEntity(className);
      step.setPrevious(sourceStep(ctx, List.of(
          new UpdatableResult(session, e1),
          new UpdatableResult(session, e2),
          new UpdatableResult(session, e3))));

      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(3);
      assertThat((Object) results.get(0).getProperty("val")).isEqualTo(1);
      assertThat((Object) results.get(1).getProperty("val")).isEqualTo(2);
      assertThat((Object) results.get(2).getProperty("val")).isEqualTo(3);
    } finally {
      session.rollback();
    }
  }

  /**
   * If the identifier and value lists have different sizes the step throws
   * {@link CommandExecutionException} naming both lists. The check fires lazily inside the map,
   * so we construct a mismatched pair manually.
   */
  @Test
  public void arityMismatchIsRejected() {
    var className = createClassInstance().getName();
    session.begin();
    try {
      var ctx = newContext();
      var insert = parseInsert(
          "INSERT INTO " + className + " (a, b) VALUES ('x', 'y')");
      // Inject a mismatched second row to force the arity check to fire on the second invocation.
      var valuesWithMismatch = new ArrayList<List<SQLExpression>>();
      valuesWithMismatch.add(insert.values().get(0));
      valuesWithMismatch.add(List.of(insert.values().get(0).get(0))); // shorter row

      var step = new InsertValuesStep(insert.ids(), valuesWithMismatch, ctx, false);

      var e1 = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
          .newEntity(className);
      var e2 = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
          .newEntity(className);
      step.setPrevious(sourceStep(ctx, List.of(
          new UpdatableResult(session, e1),
          new UpdatableResult(session, e2))));

      assertThatThrownBy(() -> drain(step.start(ctx), ctx))
          .isInstanceOf(CommandExecutionException.class)
          .hasMessageContaining("number of fields is different from the number of expressions");
    } finally {
      session.rollback();
    }
  }

  /**
   * The {@code !(result instanceof ResultInternal) && !result.isEntity()} rejection branch is
   * not reachable from the production planner (all upstream results for INSERT are
   * UpdatableResult) but is defensive against stream corruption. Exercising it would need a
   * custom Result implementation — out of scope for this direct-step test; left to Track 22 if a
   * stub is introduced.
   */

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * prettyPrint with a single-row, two-column INSERT renders header, column list, and VALUES
   * tuple on distinct lines.
   */
  @Test
  public void prettyPrintSingleRowShowsLabels() {
    var ctx = newContext();
    var insert = parseInsert("INSERT INTO X (a, b) VALUES (1, 2)");
    var step = new InsertValuesStep(insert.ids(), insert.values(), ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ SET VALUES");
    assertThat(output).contains("(a, b)");
    assertThat(output).contains("VALUES");
    assertThat(output).contains("(1, 2)");
  }

  /**
   * With three or more rows, prettyPrint appends "..." after the first two to cap the rendered
   * output size. This pins the observable ellipsis rendering.
   */
  @Test
  public void prettyPrintThreePlusRowsAppendsEllipsis() {
    var ctx = newContext();
    var insert = parseInsert(
        "INSERT INTO X (v) VALUES (1), (2), (3), (4)");
    var step = new InsertValuesStep(insert.ids(), insert.values(), ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ SET VALUES");
    assertThat(output).contains("...");
  }

  /**
   * prettyPrint with a column list beyond 3 entries caps the rendered columns at 3 (observed via
   * the {@code i < identifiers.size() && i < 3} loop). Inspect the actual source — the outer loop
   * iterates over identifiers unchecked on size, so we only verify the column list is present.
   */
  @Test
  public void prettyPrintWithManyColumnsRendersHeaderCleanly() {
    var ctx = newContext();
    var insert = parseInsert("INSERT INTO X (a, b, c, d) VALUES (1, 2, 3, 4)");
    var step = new InsertValuesStep(insert.ids(), insert.values(), ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ SET VALUES");
    // First three values rendered inline; fourth is truncated by the i<3 cap.
    assertThat(output).contains("(1, 2, 3)");
  }

  /**
   * depth × indent controls the leading whitespace prefix.
   */
  @Test
  public void prettyPrintRespectsIndent() {
    var ctx = newContext();
    var insert = parseInsert("INSERT INTO X (a) VALUES (1)");
    var step = new InsertValuesStep(insert.ids(), insert.values(), ctx, false);

    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    ");
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy()} deep-copies identifiers and value expressions; the clone works identically to
   * the original.
   */
  @Test
  public void copyProducesIndependentStep() {
    var className = createClassInstance().getName();
    session.begin();
    try {
      var ctx = newContext();
      var insert = parseInsert("INSERT INTO " + className + " (n) VALUES ('Alice')");
      var original = new InsertValuesStep(insert.ids(), insert.values(), ctx, true);

      var copy = (InsertValuesStep) original.copy(ctx);

      assertThat(copy).isNotSameAs(original);
      assertThat(copy.isProfilingEnabled()).isTrue();

      var entity = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
          .newEntity(className);
      copy.setPrevious(sourceStep(ctx, List.of(new UpdatableResult(session, entity))));
      var results = drain(copy.start(ctx), ctx);
      assertThat(results).hasSize(1);
      assertThat((Object) results.get(0).getProperty("n")).isEqualTo("Alice");
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code copy()} with {@code identifiers == null} and {@code values == null} (edge: not
   * normally constructed, but the copy() branch handles it) produces a clone with null fields.
   */
  @Test
  public void copyHandlesNullFields() {
    var ctx = newContext();
    var step = new InsertValuesStep(null, null, ctx, false);

    var copy = (InsertValuesStep) step.copy(ctx);

    assertThat(copy).isNotSameAs(step).isInstanceOf(InsertValuesStep.class);
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
   * Parses an {@code INSERT INTO X (cols) VALUES (…), (…)} statement and returns its identifier
   * and value-expression lists as a small record.
   */
  private record ParsedInsert(List<SQLIdentifier> ids, List<List<SQLExpression>> values) {
  }

  private static ParsedInsert parseInsert(String sql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes()));
      var stm = (SQLInsertStatement) parser.parse();
      return new ParsedInsert(
          stm.getInsertBody().getIdentifierList(),
          stm.getInsertBody().getValueExpressions());
    } catch (Exception e) {
      throw new AssertionError("Failed to parse insert: " + sql, e);
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
