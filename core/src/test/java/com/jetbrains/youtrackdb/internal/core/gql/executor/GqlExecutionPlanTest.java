package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests for GqlExecutionPlan: empty plan, chain, start, close, reset, copy, canBeCached.
 */
public class GqlExecutionPlanTest extends GraphBaseTest {

  @Test
  public void emptyPlan_start_returnsEmptyStream() {
    var plan = new GqlExecutionPlan();
    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    var session = tx.getDatabaseSession();
    var ctx = new GqlExecutionContext(graphInternal, session);
    var stream = plan.start(ctx);
    Assert.assertNotNull(stream);
    Assert.assertFalse(stream.hasNext());
    tx.commit();
  }

  @Test
  public void emptyPlan_close_reset_copy_doNotThrow() {
    var plan = new GqlExecutionPlan();
    plan.close();
    plan.reset();
    var copy = plan.copy();
    Assert.assertNotNull(copy);
    Assert.assertNotSame(plan, copy);
  }

  @Test
  public void planWithOneStep_start_returnsStepStream() {
    var plan = new GqlExecutionPlan();
    var step = new FakeStep(GqlExecutionStream.fromIterator(List.of(1, 2, 3).iterator()));
    plan.chain(step);

    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    var session = tx.getDatabaseSession();
    var ctx = new GqlExecutionContext(graphInternal, session);

    var stream = plan.start(ctx);
    Assert.assertNotNull(stream);
    Assert.assertEquals(1, stream.next());
    Assert.assertEquals(2, stream.next());
    Assert.assertEquals(3, stream.next());
    Assert.assertFalse(stream.hasNext());
    tx.commit();
  }

  @Test
  public void plan_close_closesSteps() {
    var plan = new GqlExecutionPlan();
    var step = new FakeStep(GqlExecutionStream.fromIterator(List.of(1).iterator()));
    plan.chain(step);
    plan.close();
    Assert.assertTrue(step.closed);
  }

  @Test
  public void plan_reset_resetsSteps() {
    var plan = new GqlExecutionPlan();
    var step = new FakeStep(GqlExecutionStream.fromIterator(List.of(1).iterator()));
    plan.chain(step);
    plan.reset();
    Assert.assertEquals(1, step.resetCount);
  }

  @Test
  public void plan_copy_returnsNewPlanWithCopiedStep() {
    var plan = new GqlExecutionPlan();
    var step = new FakeStep(GqlExecutionStream.fromIterator(List.of(1).iterator()));
    plan.chain(step);

    var copy = plan.copy();
    Assert.assertNotSame(plan, copy);
    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    var session = tx.getDatabaseSession();
    var ctx = new GqlExecutionContext(graphInternal, session);
    var stream = copy.start(ctx);
    Assert.assertNotNull(stream);
    Assert.assertEquals(1, stream.next());
    tx.commit();
  }

  @Test
  public void canBeCached_returnsTrue() {
    Assert.assertTrue(GqlExecutionPlan.canBeCached());
  }

  private static final class FakeStep implements GqlExecutionStep {

    private final GqlExecutionStream stream;
    private GqlExecutionStep prev;
    boolean closed;
    int resetCount;

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
      closed = true;
      stream.close();
    }

    @Override
    public void reset() {
      resetCount++;
    }

    @Override
    public GqlExecutionStep copy() {
      return new FakeStep(GqlExecutionStream.fromIterator(List.of(1).iterator()));
    }
  }
}
