package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjectionItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Regression tests for hoisting {@link com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjection#isExpand()}
 * to {@link ProjectionCalculationStep} (R6a): the per-row path uses a boolean computed once at step
 * construction / copy instead of calling {@code isExpand()} for every result row.
 */
public class ProjectionCalculationStepTest extends DbTestBase {

  /**
   * The three-argument {@link SQLProjection#calculateSingle(CommandContext, Result, boolean)} with
   * {@code projection.isExpand()} must agree with the two-argument overload (which delegates to {@code
   * isExpand()} internally).
   */
  @Test
  public void calculateSingleWithHoistedExpandFlagMatchesTwoArgOverload() {
    var ctx = newContext();
    var item = fakeNonAggregateItem("x");
    var projection = new SQLProjection(List.of(item), false);
    var row = new ResultInternal(session);
    row.setProperty("x", 42);

    var ref = projection.calculateSingle(ctx, row);
    var hoisted = projection.calculateSingle(ctx, row, projection.isExpand());
    assertThat((Object) hoisted.getProperty("x")).isEqualTo(ref.getProperty("x"));
  }

  /**
   * expand() projections cannot be evaluated as a single-row projection; both overloads must reject
   * them when the expand flag is true.
   */
  @Test
  public void calculateSingleRejectsExpandProjection() throws ParseException {
    var ctx = newContext();
    var projection = parseExpandProjection("select expand(foo) from V");
    var row = new ResultInternal(session);

    assertThatThrownBy(() -> projection.calculateSingle(ctx, row))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> projection.calculateSingle(ctx, row, true))
        .isInstanceOf(IllegalStateException.class);
  }

  /**
   * {@link ProjectionCalculationStep#copy} must preserve behavior: same upstream yields the same
   * projected rows without relying on a second {@link SQLProjection#isExpand()} on the copied AST.
   */
  @Test
  public void copiedStepProducesSameRowsAsOriginal() {
    var ctx = newContext();
    var item = fakeNonAggregateItem("x");
    var projection = new SQLProjection(List.of(item), false);
    var original = new ProjectionCalculationStep(projection, ctx, false);
    original.setPrevious(upstreamTwoRows(ctx));
    var copy = (ProjectionCalculationStep) original.copy(ctx);
    copy.setPrevious(upstreamTwoRows(ctx));

    var outOriginal = drain(original.start(ctx), ctx);
    var outCopy = drain(copy.start(ctx), ctx);
    assertThat(outCopy).hasSize(outOriginal.size());
    for (var i = 0; i < outOriginal.size(); i++) {
      assertThat((Object) outCopy.get(i).getProperty("x"))
          .isEqualTo(outOriginal.get(i).getProperty("x"));
    }
  }

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  private SQLProjection parseExpandProjection(String sql) throws ParseException {
    var is = new ByteArrayInputStream(sql.getBytes());
    var osql = new YouTrackDBSql(is);
    var stm = (SQLSelectStatement) osql.parse();
    return stm.getProjection();
  }

  private SQLProjectionItem fakeNonAggregateItem(String aliasName) {
    var aliasId = new SQLIdentifier(aliasName);
    return new SQLProjectionItem(-1) {
      {
        this.aggregate = false;
        this.alias = aliasId;
      }

      @Override
      public boolean isAggregate(
          com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded s) {
        return false;
      }

      @Override
      public Object execute(Result record, CommandContext ctx) {
        return record.getProperty(aliasName);
      }

      @Override
      public SQLIdentifier getProjectionAlias() {
        return aliasId;
      }

      @Override
      public SQLProjectionItem copy() {
        return fakeNonAggregateItem(aliasName);
      }
    };
  }

  private AbstractExecutionStep upstreamTwoRows(CommandContext ctx) {
    return new AbstractExecutionStep(ctx, false) {
      boolean done = false;

      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        List<Result> results = new ArrayList<>();
        if (!done) {
          var r1 = new ResultInternal(c.getDatabaseSession());
          r1.setProperty("x", 1);
          results.add(r1);
          var r2 = new ResultInternal(c.getDatabaseSession());
          r2.setProperty("x", 2);
          results.add(r2);
          done = true;
        }
        return ExecutionStream.resultIterator(results.iterator());
      }
    };
  }

  private List<Result> drain(ExecutionStream stream, CommandContext ctx) {
    var results = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      results.add(stream.next(ctx));
    }
    stream.close(ctx);
    return results;
  }
}
