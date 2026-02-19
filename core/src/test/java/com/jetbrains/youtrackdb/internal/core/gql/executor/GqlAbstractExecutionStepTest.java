package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests for GqlAbstractExecutionStep: setPrevious, getPrevious, close cascade, reset when closed
 * throws, reset cascade, start delegates to internalStart, prettyPrint.
 */
public class GqlAbstractExecutionStepTest extends GraphBaseTest {

  @Test
  public void step_setPrevious_getPrevious() {
    var step = new ConcreteStep();
    var prev = new ConcreteStep();
    step.setPrevious(prev);
    Assert.assertSame(prev, step.getPrevious());
  }

  @Test
  public void step_close_cascadesToPrevious() {
    var step = new ConcreteStep();
    var prev = new ConcreteStep();
    step.setPrevious(prev);
    step.close();
    Assert.assertTrue(prev.closed);
  }

  @Test
  public void step_close_idempotent() {
    var step = new ConcreteStep();
    step.close();
    step.close();
  }

  @Test(expected = IllegalStateException.class)
  public void step_resetAfterClose_throws() {
    var step = new ConcreteStep();
    step.close();
    step.reset();
  }

  @Test
  public void step_reset_cascadesToPrevious() {
    var step = new ConcreteStep();
    var prev = new ConcreteStep();
    step.setPrevious(prev);
    step.reset();
    Assert.assertEquals(1, prev.resetCount);
  }

  @Test
  public void step_start_returnsInternalStartResult() {
    var step = new ConcreteStep();
    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    var session = tx.getDatabaseSession();
    var ctx = new GqlExecutionContext(graphInternal, session);
    var stream = step.start(ctx);
    Assert.assertNotNull(stream);
    Assert.assertEquals(42, stream.next());
    tx.commit();
  }

  @Test
  public void step_prettyPrint_returnsClassName() {
    var step = new ConcreteStep();
    Assert.assertTrue(step.prettyPrint(0, 1).contains("ConcreteStep"));
  }

  private static final class ConcreteStep extends GqlAbstractExecutionStep {

    boolean closed;
    int resetCount;

    @Override
    protected GqlExecutionStream internalStart(GqlExecutionContext ctx) {
      return GqlExecutionStream.fromIterator(List.of(42).iterator());
    }

    @Override
    public void close() {
      super.close();
      closed = true;
    }

    @Override
    public void reset() {
      super.reset();
      resetCount++;
    }

    @Override
    public GqlExecutionStep copy() {
      return new ConcreteStep();
    }
  }
}
