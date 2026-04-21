package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUpdateRemoveItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUpdateStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Direct-step tests for {@link UpdateRemoveStep}, which applies a list of REMOVE items to each
 * upstream {@link ResultInternal}. REMOVE without an "= value" on the right-hand side deletes the
 * named property outright; REMOVE with a right-hand side removes one or more elements from a
 * collection-typed property.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>REMOVE simpleName: deletes the whole property from each upstream row.
 *   <li>Empty item list: upstream rows pass through untouched.
 *   <li>prettyPrint: header + one line per item, profiling-agnostic (no cost branch),
 *       indentation.
 *   <li>{@code copy()}: independent, still functional.
 * </ul>
 */
public class UpdateRemoveStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart: REMOVE semantics
  // =========================================================================

  /**
   * {@code UPDATE … REMOVE name} clears the property from each upstream row (the "left-only"
   * branch of {@link SQLUpdateRemoveItem#applyUpdate}, which calls
   * {@code left.applyRemove(result, ctx)}).
   */
  @Test
  public void removeDeletesNamedProperty() {
    var className = createClassInstance().getName();

    session.begin();
    try {
      var ctx = newContext();
      var items = parseRemoveItems("UPDATE " + className + " REMOVE name");
      var step = new UpdateRemoveStep(items, ctx, false);

      var e1 = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
          .newEntity(className);
      e1.setProperty("name", "Alice");
      e1.setProperty("age", 42);
      var e2 = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
          .newEntity(className);
      e2.setProperty("name", "Bob");

      step.setPrevious(sourceStep(ctx, List.of(
          new UpdatableResult(session, e1),
          new UpdatableResult(session, e2))));

      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(2);
      for (var r : results) {
        // "name" is gone from every row.
        assertThat(r.getPropertyNames()).doesNotContain("name");
      }
      // Non-removed property is preserved.
      assertThat((Object) results.get(0).getProperty("age")).isEqualTo(42);
    } finally {
      session.rollback();
    }
  }

  /**
   * An empty item list is legal — the loop body never executes and the row passes through
   * untouched. This pins the interaction between the empty-items branch and the pass-through
   * behavior of the step.
   */
  @Test
  public void emptyItemListIsNoOp() {
    var className = createClassInstance().getName();
    session.begin();
    try {
      var ctx = newContext();
      var step = new UpdateRemoveStep(List.of(), ctx, false);

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
   * prettyPrint renders the "+ UPDATE REMOVE" header followed by one line per item.
   */
  @Test
  public void prettyPrintRendersEachItem() {
    var ctx = newContext();
    var items = parseRemoveItems("UPDATE X REMOVE a, b");
    var step = new UpdateRemoveStep(items, ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ UPDATE REMOVE");
    var lines = output.split("\n");
    assertThat(lines).hasSize(1 + items.size());
  }

  /**
   * depth × indent controls the leading whitespace prefix.
   */
  @Test
  public void prettyPrintRespectsIndent() {
    var ctx = newContext();
    var items = parseRemoveItems("UPDATE X REMOVE a");
    var step = new UpdateRemoveStep(items, ctx, false);

    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    ");
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy()} produces a new step with deep-copied items and preserves the profiling flag.
   */
  @Test
  public void copyProducesIndependentStep() {
    var ctx = newContext();
    var items = parseRemoveItems("UPDATE X REMOVE a");
    var step = new UpdateRemoveStep(items, ctx, true);

    var copy = (UpdateRemoveStep) step.copy(ctx);

    assertThat(copy).isNotSameAs(step);
    assertThat(copy.isProfilingEnabled()).isTrue();
    assertThat(copy.prettyPrint(0, 2)).contains("+ UPDATE REMOVE");
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
   * Parses a full {@code UPDATE ... REMOVE ...} statement and returns the extracted remove items.
   */
  private static List<SQLUpdateRemoveItem> parseRemoveItems(String sql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes()));
      var stm = (SQLUpdateStatement) parser.parse();
      return stm.getOperations().get(0).getUpdateRemoveItems();
    } catch (Exception e) {
      throw new AssertionError("Failed to parse remove: " + sql, e);
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
