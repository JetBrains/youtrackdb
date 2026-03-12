package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import java.util.NoSuchElementException;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Unit tests for GqlResultIterator verifying resource cleanup contracts:
 * - close() delegates to both stream.close() and plan.close()
 * - hasNext() auto-closes on exhaustion and on exception
 * - next() closes on exception
 * - close() is idempotent
 * - plan.close() is called even when stream.close() throws
 */
public class GqlResultIteratorTest {

  @Test
  public void close_delegatesToStreamAndPlan() {
    var stream = mock(GqlExecutionStream.class);
    var plan = mock(GqlExecutionPlan.class);
    var iter = createIterator(stream, plan);

    iter.close();

    verify(stream).close();
    verify(plan).close();
  }

  @Test
  public void close_isIdempotent_onlyClosesOnce() {
    var stream = mock(GqlExecutionStream.class);
    var plan = mock(GqlExecutionPlan.class);
    var iter = createIterator(stream, plan);

    iter.close();
    iter.close();
    iter.close();

    verify(stream, times(1)).close();
    verify(plan, times(1)).close();
  }

  @Test
  public void close_planClosedEvenWhenStreamCloseThrows() {
    var stream = mock(GqlExecutionStream.class);
    Mockito.doThrow(new RuntimeException("stream close failed")).when(stream).close();
    var plan = mock(GqlExecutionPlan.class);
    var iter = createIterator(stream, plan);

    try {
      iter.close();
      Assert.fail("Expected RuntimeException from stream.close()");
    } catch (RuntimeException e) {
      Assert.assertEquals("stream close failed", e.getMessage());
    }

    verify(stream).close();
    verify(plan).close();
  }

  @Test
  public void hasNext_whenExhausted_closesResources() {
    var stream = mock(GqlExecutionStream.class);
    when(stream.hasNext()).thenReturn(false);
    var plan = mock(GqlExecutionPlan.class);
    var iter = createIterator(stream, plan);

    Assert.assertFalse(iter.hasNext());

    verify(stream).close();
    verify(plan).close();
  }

  @Test
  public void hasNext_whenStreamThrows_closesResources() {
    var stream = mock(GqlExecutionStream.class);
    when(stream.hasNext()).thenThrow(new RuntimeException("stream error"));
    var plan = mock(GqlExecutionPlan.class);
    var iter = createIterator(stream, plan);

    try {
      iter.hasNext();
      Assert.fail("Expected RuntimeException");
    } catch (RuntimeException e) {
      Assert.assertEquals("stream error", e.getMessage());
    }

    verify(stream).close();
    verify(plan).close();
  }

  @Test
  public void hasNext_afterClose_returnsFalseWithoutTouchingStream() {
    var stream = mock(GqlExecutionStream.class);
    var plan = mock(GqlExecutionPlan.class);
    var iter = createIterator(stream, plan);

    iter.close();
    Mockito.clearInvocations(stream);

    Assert.assertFalse(iter.hasNext());
    verify(stream, times(0)).hasNext();
  }

  @Test
  public void hasNext_trueWhenStreamHasMore() {
    var stream = mock(GqlExecutionStream.class);
    when(stream.hasNext()).thenReturn(true);
    var plan = mock(GqlExecutionPlan.class);
    var iter = createIterator(stream, plan);

    Assert.assertTrue(iter.hasNext());

    verifyNoInteractions(plan);
  }

  @Test
  public void next_whenStreamThrows_closesResources() {
    var stream = mock(GqlExecutionStream.class);
    when(stream.next()).thenThrow(new RuntimeException("next error"));
    var plan = mock(GqlExecutionPlan.class);
    var iter = createIterator(stream, plan);

    try {
      iter.next();
      Assert.fail("Expected RuntimeException");
    } catch (RuntimeException e) {
      Assert.assertEquals("next error", e.getMessage());
    }

    verify(stream).close();
    verify(plan).close();
  }

  @Test(expected = NoSuchElementException.class)
  public void next_afterClose_throwsNoSuchElement() {
    var stream = mock(GqlExecutionStream.class);
    var plan = mock(GqlExecutionPlan.class);
    var iter = createIterator(stream, plan);

    iter.close();
    iter.next();
  }

  @Test
  public void next_returnsRawValueWhenNotResult() {
    var stream = mock(GqlExecutionStream.class);
    when(stream.next()).thenReturn("plainValue");
    var plan = mock(GqlExecutionPlan.class);
    var iter = createIterator(stream, plan);

    Assert.assertEquals("plainValue", iter.next());
  }

  private static GqlResultIterator createIterator(
      GqlExecutionStream stream, GqlExecutionPlan plan) {
    return new GqlResultIterator(
        stream, plan, mock(YTDBGraphInternal.class), mock(ImmutableSchema.class));
  }
}
