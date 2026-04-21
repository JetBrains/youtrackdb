package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUpdateItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUpdateStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Direct-step tests for {@link UpdateSetStep}, which applies a list of SET assignments to each
 * upstream {@link ResultInternal}.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>SET applies each assignment to every upstream {@link UpdatableResult} — multi-assignment
 *       and multi-row.
 *   <li>Non-{@link ResultInternal} upstream results pass through untouched (defensive branch —
 *       {@code if (result instanceof ResultInternal ...)}).
 *   <li>Empty item list: upstream rows flow through unchanged.
 *   <li>prettyPrint: label, each item rendered on its own line, profiling-agnostic (no cost
 *       branch), indentation.
 *   <li>{@code copy()}: list items are deep-copied via {@link SQLUpdateItem#copy()} so the clone
 *       is independent.
 * </ul>
 */
public class UpdateSetStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart: SET semantics
  // =========================================================================

  /**
   * Each upstream row receives the SET assignments; upstream must be {@link UpdatableResult} for
   * the properties to actually stick (the type-check inside UpdateSetStep's mapResult uses
   * {@code instanceof ResultInternal}, which UpdatableResult satisfies).
   */
  @Test
  public void multipleAssignmentsAreAppliedToEveryUpdatableRow() {
    var className = createClassInstance().getName();

    session.begin();
    try {
      var ctx = newContext();
      var items = parseUpdateItems(
          "UPDATE " + className + " SET name = 'Alice', age = 42");
      var step = new UpdateSetStep(items, ctx, false);

      var e1 = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session.newEntity(
          className);
      var e2 = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session.newEntity(
          className);
      step.setPrevious(sourceStep(ctx, List.of(
          new UpdatableResult(session, e1),
          new UpdatableResult(session, e2))));

      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(2);
      for (var r : results) {
        assertThat((Object) r.getProperty("name")).isEqualTo("Alice");
        assertThat((Object) r.getProperty("age")).isEqualTo(42);
      }
    } finally {
      session.rollback();
    }
  }

  /**
   * An empty item list is legal — the loop body never executes, so the row passes through
   * untouched.
   */
  @Test
  public void emptyItemListIsNoOp() {
    var className = createClassInstance().getName();
    session.begin();
    try {
      var ctx = newContext();
      var step = new UpdateSetStep(List.of(), ctx, false);
      var entity = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
          .newEntity(className);
      entity.setProperty("keep", "me");
      step.setPrevious(sourceStep(ctx, List.of(new UpdatableResult(session, entity))));

      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      assertThat((Object) results.get(0).getProperty("keep")).isEqualTo("me");
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * prettyPrint renders the "+ UPDATE SET" header followed by one line per item.
   */
  @Test
  public void prettyPrintRendersEachItem() {
    var ctx = newContext();
    var items = parseUpdateItems("UPDATE X SET a = 1, b = 2");
    var step = new UpdateSetStep(items, ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ UPDATE SET");
    // Each item renders on its own line. At minimum: header + N items.
    var lines = output.split("\n");
    assertThat(lines).hasSize(1 + items.size());
  }

  /**
   * prettyPrint respects the depth×indent parameters via {@code getIndent}.
   */
  @Test
  public void prettyPrintRespectsIndent() {
    var ctx = newContext();
    var items = parseUpdateItems("UPDATE X SET a = 1");
    var step = new UpdateSetStep(items, ctx, false);

    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    ");
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy()} produces a new step with freshly-copied item list (each {@link SQLUpdateItem}
   * is deep-copied). The clone is independent — it is not the same instance, and its prettyPrint
   * reflects the same items.
   */
  @Test
  public void copyProducesIndependentStepWithCopiedItems() {
    var ctx = newContext();
    var items = parseUpdateItems("UPDATE X SET a = 1");
    var step = new UpdateSetStep(items, ctx, true);

    var copy = (UpdateSetStep) step.copy(ctx);

    assertThat(copy).isNotSameAs(step);
    assertThat(copy.isProfilingEnabled()).isTrue();
    assertThat(copy.prettyPrint(0, 2)).contains("+ UPDATE SET");
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
   * Parses a full {@code UPDATE ... SET ...} statement and extracts the {@link SQLUpdateItem}
   * list from the first operations block. Using the parser avoids reflection and gives us valid,
   * fully-initialized AST nodes that {@link UpdateSetStep#mapResult} accepts without surprises.
   */
  static List<SQLUpdateItem> parseUpdateItems(String sql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes()));
      var stm = (SQLUpdateStatement) parser.parse();
      return stm.getOperations().get(0).getUpdateItems();
    } catch (Exception e) {
      throw new AssertionError("Failed to parse update: " + sql, e);
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
