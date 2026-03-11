package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

@SuppressWarnings("DataFlowIssue")
public class OrderByStepTest extends DbTestBase {

  private CommandContext ctx() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  private static final String SORT_FIELD = "val";

  private SQLOrderBy orderBy(String direction) {
    var item = new SQLOrderByItem();
    item.setAlias(SORT_FIELD);
    item.setType(direction);
    var orderBy = new SQLOrderBy(-1);
    orderBy.setItems(new ArrayList<>(List.of(item)));
    return orderBy;
  }

  private AbstractExecutionStep upstream(CommandContext ctx, List<Result> rows) {
    return new AbstractExecutionStep(ctx, false) {
      boolean done = false;

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
        if (done) {
          return ExecutionStream.empty();
        }
        done = true;
        return ExecutionStream.resultIterator(new ArrayList<>(rows).iterator());
      }
    };
  }

  private List<Result> makeRows(CommandContext ctx, int... values) {
    var rows = new ArrayList<Result>();
    for (var v : values) {
      var r = new ResultInternal(ctx.getDatabaseSession());
      r.setProperty("val", v);
      rows.add(r);
    }
    return rows;
  }

  private List<Result> collect(ExecutionStream stream, CommandContext ctx) {
    var out = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      out.add(stream.next(ctx));
    }
    return out;
  }

  // ── Unbounded path ──

  @Test
  public void unboundedSortsAllRowsAsc() {
    var ctx = ctx();
    var step = new OrderByStep(orderBy(SQLOrderByItem.ASC), null, ctx, -1, false);
    step.setPrevious(upstream(ctx, makeRows(ctx, 5, 3, 1, 4, 2)));

    var results = collect(step.start(ctx), ctx);
    Assert.assertEquals(5, results.size());
    for (var i = 0; i < 5; i++) {
      Assert.assertEquals(i + 1, (int) results.get(i).getProperty("val"));
    }
  }

  @Test
  public void unboundedSortsAllRowsDesc() {
    var ctx = ctx();
    var step = new OrderByStep(orderBy(SQLOrderByItem.DESC), null, ctx, -1, false);
    step.setPrevious(upstream(ctx, makeRows(ctx, 5, 3, 1, 4, 2)));

    var results = collect(step.start(ctx), ctx);
    Assert.assertEquals(5, results.size());
    for (var i = 0; i < 5; i++) {
      Assert.assertEquals(5 - i, (int) results.get(i).getProperty("val"));
    }
  }

  @Test
  public void unboundedEmptyUpstream() {
    var ctx = ctx();
    var step = new OrderByStep(orderBy(SQLOrderByItem.ASC), null, ctx, -1, false);
    step.setPrevious(upstream(ctx, List.of()));

    var results = collect(step.start(ctx), ctx);
    Assert.assertTrue(results.isEmpty());
  }

  @Test
  public void unboundedSingleRow() {
    var ctx = ctx();
    var step = new OrderByStep(orderBy(SQLOrderByItem.ASC), null, ctx, -1, false);
    step.setPrevious(upstream(ctx, makeRows(ctx, 42)));

    var results = collect(step.start(ctx), ctx);
    Assert.assertEquals(1, results.size());
    Assert.assertEquals(42, (int) results.getFirst().getProperty("val"));
  }

  // ── Bounded heap path ──

  @Test
  public void boundedHeapKeepsTopN() {
    var ctx = ctx();
    var step = new OrderByStep(orderBy(SQLOrderByItem.ASC), 3, ctx, -1, false);
    step.setPrevious(upstream(ctx, makeRows(ctx, 5, 3, 1, 4, 2)));

    var results = collect(step.start(ctx), ctx);
    Assert.assertEquals(3, results.size());
    Assert.assertEquals(1, (int) results.get(0).getProperty("val"));
    Assert.assertEquals(2, (int) results.get(1).getProperty("val"));
    Assert.assertEquals(3, (int) results.get(2).getProperty("val"));
  }

  @Test
  public void boundedHeapKeepsTopNDesc() {
    var ctx = ctx();
    var step = new OrderByStep(orderBy(SQLOrderByItem.DESC), 3, ctx, -1, false);
    step.setPrevious(upstream(ctx, makeRows(ctx, 5, 3, 1, 4, 2)));

    var results = collect(step.start(ctx), ctx);
    Assert.assertEquals(3, results.size());
    Assert.assertEquals(5, (int) results.get(0).getProperty("val"));
    Assert.assertEquals(4, (int) results.get(1).getProperty("val"));
    Assert.assertEquals(3, (int) results.get(2).getProperty("val"));
  }

  @Test
  public void boundedHeapWithMaxResultsEqualToInputSize() {
    var ctx = ctx();
    var step = new OrderByStep(orderBy(SQLOrderByItem.ASC), 5, ctx, -1, false);
    step.setPrevious(upstream(ctx, makeRows(ctx, 5, 3, 1, 4, 2)));

    var results = collect(step.start(ctx), ctx);
    Assert.assertEquals(5, results.size());
    for (var i = 0; i < 5; i++) {
      Assert.assertEquals(i + 1, (int) results.get(i).getProperty("val"));
    }
  }

  @Test
  public void boundedHeapWithMaxResultsLargerThanInput() {
    var ctx = ctx();
    var step = new OrderByStep(orderBy(SQLOrderByItem.ASC), 10, ctx, -1, false);
    step.setPrevious(upstream(ctx, makeRows(ctx, 3, 1, 2)));

    var results = collect(step.start(ctx), ctx);
    Assert.assertEquals(3, results.size());
    Assert.assertEquals(1, (int) results.get(0).getProperty("val"));
    Assert.assertEquals(2, (int) results.get(1).getProperty("val"));
    Assert.assertEquals(3, (int) results.get(2).getProperty("val"));
  }

  @Test
  public void boundedHeapWithMaxResultsOne() {
    var ctx = ctx();
    var step = new OrderByStep(orderBy(SQLOrderByItem.ASC), 1, ctx, -1, false);
    step.setPrevious(upstream(ctx, makeRows(ctx, 5, 3, 1, 4, 2)));

    var results = collect(step.start(ctx), ctx);
    Assert.assertEquals(1, results.size());
    Assert.assertEquals(1, (int) results.getFirst().getProperty("val"));
  }

  @Test
  public void boundedHeapWithMaxResultsZeroReturnsEmpty() {
    var ctx = ctx();
    var step = new OrderByStep(orderBy(SQLOrderByItem.ASC), 0, ctx, -1, false);
    step.setPrevious(upstream(ctx, makeRows(ctx, 5, 3, 1)));

    var results = collect(step.start(ctx), ctx);
    Assert.assertTrue(results.isEmpty());
  }

  @Test
  public void boundedHeapDiscardsWorseElements() {
    var ctx = ctx();
    // ASC order, keep top 2 → should keep 1, 2 and discard 3, 4, 5
    var step = new OrderByStep(orderBy(SQLOrderByItem.ASC), 2, ctx, -1, false);
    step.setPrevious(upstream(ctx, makeRows(ctx, 4, 2, 5, 1, 3)));

    var results = collect(step.start(ctx), ctx);
    Assert.assertEquals(2, results.size());
    Assert.assertEquals(1, (int) results.get(0).getProperty("val"));
    Assert.assertEquals(2, (int) results.get(1).getProperty("val"));
  }

  @Test
  public void boundedHeapPreservesOrderWithDuplicates() {
    var ctx = ctx();
    var step = new OrderByStep(orderBy(SQLOrderByItem.ASC), 4, ctx, -1, false);
    step.setPrevious(upstream(ctx, makeRows(ctx, 3, 1, 3, 1, 2)));

    var results = collect(step.start(ctx), ctx);
    Assert.assertEquals(4, results.size());
    Assert.assertEquals(1, (int) results.get(0).getProperty("val"));
    Assert.assertEquals(1, (int) results.get(1).getProperty("val"));
    Assert.assertEquals(2, (int) results.get(2).getProperty("val"));
    Assert.assertEquals(3, (int) results.get(3).getProperty("val"));
  }

  // ── Constructor: negative maxResults treated as null (unbounded) ──

  @Test
  public void negativeMaxResultsTreatedAsUnbounded() {
    var ctx = ctx();
    var step = new OrderByStep(orderBy(SQLOrderByItem.ASC), -5, ctx, -1, false);
    step.setPrevious(upstream(ctx, makeRows(ctx, 3, 1, 2)));

    var results = collect(step.start(ctx), ctx);
    Assert.assertEquals(3, results.size());
    Assert.assertEquals(1, (int) results.get(0).getProperty("val"));
    Assert.assertEquals(2, (int) results.get(1).getProperty("val"));
    Assert.assertEquals(3, (int) results.get(2).getProperty("val"));
  }

  // ── No upstream (prev == null) ──

  @Test
  public void noPreviousStepReturnsEmpty() {
    var ctx = ctx();
    var step = new OrderByStep(orderBy(SQLOrderByItem.ASC), null, ctx, -1, false);

    var results = collect(step.start(ctx), ctx);
    Assert.assertTrue(results.isEmpty());
  }

  // ── prettyPrint ──

  @Test
  public void prettyPrintShowsBufferSizeWhenBounded() {
    var ctx = ctx();
    var step = new OrderByStep(orderBy(SQLOrderByItem.ASC), 20, ctx, -1, false);
    var text = step.prettyPrint(0, 2);
    Assert.assertTrue(
        "prettyPrint should contain buffer size", text.contains("buffer size: 20"));
  }

  @Test
  public void prettyPrintOmitsBufferSizeWhenUnbounded() {
    var ctx = ctx();
    var step = new OrderByStep(orderBy(SQLOrderByItem.ASC), null, ctx, -1, false);
    var text = step.prettyPrint(0, 2);
    Assert.assertFalse(
        "prettyPrint should not contain buffer size", text.contains("buffer size"));
  }

  // ── copy ──

  @Test
  public void copyPreservesMaxResults() {
    var ctx = ctx();
    var step = new OrderByStep(orderBy(SQLOrderByItem.ASC), 7, ctx, -1, false);
    var copied = (OrderByStep) step.copy(ctx);
    var text = copied.prettyPrint(0, 2);
    Assert.assertTrue(
        "copied step should preserve buffer size", text.contains("buffer size: 7"));
  }

  @Test
  public void copyPreservesUnbounded() {
    var ctx = ctx();
    var step = new OrderByStep(orderBy(SQLOrderByItem.ASC), null, ctx, -1, false);
    var copied = (OrderByStep) step.copy(ctx);
    var text = copied.prettyPrint(0, 2);
    Assert.assertFalse(
        "copied step should preserve unbounded", text.contains("buffer size"));
  }

  // ── canBeCached ──

  @Test
  public void canBeCachedReturnsTrue() {
    var ctx = ctx();
    var step = new OrderByStep(orderBy(SQLOrderByItem.ASC), null, ctx, -1, false);
    Assert.assertTrue(step.canBeCached());
  }

  // ── Boundary: bounded heap correctness with large input ──

  @Test
  public void boundedHeapLargeInput() {
    var ctx = ctx();
    var values = new int[100];
    for (var i = 0; i < 100; i++) {
      values[i] = 100 - i;
    }
    var step = new OrderByStep(orderBy(SQLOrderByItem.ASC), 5, ctx, -1, false);
    step.setPrevious(upstream(ctx, makeRows(ctx, values)));

    var results = collect(step.start(ctx), ctx);
    Assert.assertEquals(5, results.size());
    for (var i = 0; i < 5; i++) {
      Assert.assertEquals(i + 1, (int) results.get(i).getProperty("val"));
    }
  }
}
