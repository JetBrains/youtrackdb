package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link DistinctExecutionStep} for deduplicating query results.
 */
public class DistinctExecutionStepTest extends DbTestBase {

  /** Ordinary projected DISTINCT keeps one row for each content value. */
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

  /** Identity mode keeps original first rows without loading RIDs, across copies and restarts. */
  @Test
  public void identityModePreservesRowsAndOwnsSeenStatePerStart() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    var first = identityRow(1);
    var second = identityRow(2);
    var duplicate = identityRow(1);
    duplicate.setProperty("other", "different upstream binding");
    var original = new DistinctExecutionStep(ctx, false, "a");
    var copy = (DistinctExecutionStep) original.copy(ctx);
    assertThat(copy.prettyPrint(0, 2)).contains("BY IDENTITY (a)");
    for (var step : List.of(original, copy, original)) {
      step.setPrevious(source(ctx, List.of(first, second, duplicate)));
      var stream = step.start(ctx);
      var independent = step.start(ctx);
      try {
        assertThat(stream.next(ctx)).isSameAs(first);
        assertThat(independent.next(ctx)).isSameAs(first);
        assertThat(stream.next(ctx)).isSameAs(second);
        assertThat(stream.hasNext(ctx)).isFalse();
      } finally {
        stream.close(ctx);
        independent.close(ctx);
      }
    }
    original.setPrevious(source(ctx, List.of()));
    var empty = original.start(ctx);
    assertThat(empty.hasNext(ctx)).isFalse();
    empty.close(ctx);
  }

  /** Both projected and alias keys enforce the same cap, counting unique keys rather than rows. */
  @Test
  public void identityAndProjectedModesKeepHeapLimitContract() {
    var config = session.getConfiguration();
    var setting = GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP;
    var saved = config.getValueAsLong(setting);
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    try {
      for (var cap : List.of(2, 0)) {
        config.setValue(setting, cap);
        for (var alias : new String[] {"a", null}) {
          var step = new DistinctExecutionStep(ctx, false, alias);
          step.setPrevious(source(ctx,
              List.of(identityRow(1), identityRow(1), identityRow(2), identityRow(3))));
          var stream = step.start(ctx);
          try {
            stream.next(ctx);
            stream.next(ctx);
            if (cap > 0) {
              assertThatThrownBy(() -> stream.hasNext(ctx))
                  .isInstanceOf(CommandExecutionException.class)
                  .hasMessageContaining("in-heap DISTINCT")
                  .hasMessageContaining("(2)");
            } else {
              stream.next(ctx);
              assertThat(stream.hasNext(ctx)).isFalse();
            }
          } finally {
            stream.close(ctx);
          }
        }
      }
    } finally {
      config.setValue(setting, saved);
    }
  }

  private ResultInternal identityRow(long position) {
    var row = new ResultInternal(session);
    row.setProperty("a", new RecordId(100, position));
    return row;
  }

  private static AbstractExecutionStep source(CommandContext ctx, List<Result> rows) {
    return new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext context) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext context) {
        return ExecutionStream.resultIterator(rows.iterator());
      }
    };
  }
}
