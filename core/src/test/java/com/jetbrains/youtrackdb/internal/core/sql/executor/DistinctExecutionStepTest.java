package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class DistinctExecutionStepTest extends DbTestBase {

  @Test
  public void test() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    var step = new DistinctExecutionStep(ctx, false);

    var prev =
        new AbstractExecutionStep(ctx, false) {
          @Override
          public ExecutionStep copy(CommandContext ctx) {
            throw new UnsupportedOperationException("Not supported yet.");
          }

          boolean done = false;

          @Override
          public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
            List<Result> result = new ArrayList<>();
            if (!done) {
              for (var i = 0; i < 10; i++) {
                var item = new ResultInternal(ctx.getDatabaseSession());
                item.setProperty("name", i % 2 == 0 ? "foo" : "bar");
                result.add(item);
              }
              done = true;
            }
            return ExecutionStream.resultIterator(result.iterator());
          }
        };

    step.setPrevious(prev);
    var res = step.start(ctx);
    Assert.assertTrue(res.hasNext(ctx));
    res.next(ctx);
    Assert.assertTrue(res.hasNext(ctx));
    res.next(ctx);
    Assert.assertFalse(res.hasNext(ctx));
  }
}
