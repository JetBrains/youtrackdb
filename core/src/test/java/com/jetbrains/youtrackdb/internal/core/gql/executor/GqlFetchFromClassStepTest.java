package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests for GqlFetchFromClassStep: prev must be null, class not found throws,
 * prettyPrint with null alias, copy with previous step.
 */
public class GqlFetchFromClassStepTest extends GraphBaseTest {

  @Test(expected = CommandExecutionException.class)
  @SuppressWarnings("resource")
  public void start_whenPrevIsNotNull_throws() {
    var prev = new FakeStep(GqlExecutionStream.fromIterator(List.of(1).iterator()));
    var step = new GqlFetchFromClassStep("a", "OUser", false);
    step.setPrevious(prev);

    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var ctx = new GqlExecutionContext(graphInternal, session);
      step.start(ctx);
    } finally {
      tx.commit();
    }
  }

  @Test
  @SuppressWarnings("resource")
  public void start_whenClassNotFound_throws() {
    var step = new GqlFetchFromClassStep("a", "NonExistentClass_" + System.nanoTime(), false);
    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var ctx = new GqlExecutionContext(graphInternal, session);
      try {
        step.start(ctx);
        Assert.fail("expected CommandExecutionException");
      } catch (CommandExecutionException e) {
        Assert.assertTrue(e.getMessage().contains("not found"));
      }
    } finally {
      tx.commit();
    }
  }

  @Test
  public void prettyPrint_withNullAlias_usesUnderscore() {
    var step = new GqlFetchFromClassStep(null, "OUser", false);
    var out = step.prettyPrint(0, 1);
    Assert.assertTrue(out.contains("_:OUser"));
  }

  @Test
  public void copy_withPrevious_returnsCopyWithPreviousCopied() {
    var prev = new FakeStep(GqlExecutionStream.fromIterator(List.of(1).iterator()));
    var step = new GqlFetchFromClassStep("a", "OUser", false);
    step.setPrevious(prev);

    var copy = step.copy();
    Assert.assertNotSame(step, copy);
    Assert.assertTrue(copy instanceof GqlFetchFromClassStep);
    Assert.assertNotNull(copy.getPrevious());
  }

  private static final class FakeStep implements GqlExecutionStep {

    private final GqlExecutionStream stream;
    private GqlExecutionStep prev;

    FakeStep(GqlExecutionStream stream) {
      this.stream = stream;
    }

    @Override
    public GqlExecutionStream start(GqlExecutionContext ctx) {
      return stream;
    }

    @Override
    public void setPrevious(GqlExecutionStep step) {
      this.prev = step;
    }

    @Override
    public GqlExecutionStep getPrevious() {
      return prev;
    }

    @Override
    public void close() {
      stream.close();
    }

    @Override
    public void reset() {
    }

    @Override
    public GqlExecutionStep copy() {
      return new FakeStep(GqlExecutionStream.fromIterator(List.of(1).iterator()));
    }
  }
}
