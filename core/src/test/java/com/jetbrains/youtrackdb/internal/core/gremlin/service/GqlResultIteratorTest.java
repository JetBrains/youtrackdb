package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.query.RegisteredQuery;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.HashMap;
import java.util.Map;
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

  /** The handle describes the live plan, owns teardown, and retires once on every terminal path. */
  @Test
  public void registrationRetiresAtExhaustionExplicitCloseAndRegistryClose() {
    for (var mode = 0; mode < 3; mode++) {
      var fixture = new RegisteredIterator();
      when(fixture.plan.prettyPrint(0, 2)).thenReturn("live GQL plan");
      var handle = fixture.queries.values().iterator().next();
      Assert.assertEquals("live GQL plan", handle.getDescription());
      Assert.assertTrue(fixture.queries.keySet().iterator().next().startsWith("stream-query-"));
      if (mode == 0) {
        when(fixture.stream.hasNext()).thenReturn(false);
        Assert.assertFalse(fixture.iterator.hasNext());
      } else if (mode == 1) {
        fixture.iterator.close();
      } else {
        handle.close();
      }
      Assert.assertTrue(fixture.queries.isEmpty());
      Mockito.clearInvocations(fixture.session, fixture.graph);
      fixture.iterator.close();
      handle.close();
      Assert.assertFalse(fixture.iterator.hasNext());
      Assert.assertThrows(NoSuchElementException.class, fixture.iterator::next);
      verifyNoInteractions(fixture.session, fixture.graph);
      verify(fixture.stream, times(1)).close();
      verify(fixture.plan, times(1)).close();
      verify(fixture.plan, times(0)).start(any());
    }
  }

  /** Iteration and mapping failures stay primary even if every cleanup operation fails. */
  @Test
  public void iterationAndMappingFailuresKeepIdentityIncludingErrorsAndSelfSuppression() {
    for (var operation = 0; operation < 3; operation++) {
      for (var fatal : new boolean[] {false, true}) {
        for (var shared : new boolean[] {false, true}) {
          var fixture = new RegisteredIterator();
          Throwable original =
              fatal ? new AssertionError("read") : new IllegalStateException("read");
          Throwable streamFailure = shared ? original : new AssertionError("stream close");
          var planFailure = new IllegalStateException("plan close");
          var retirementFailure = new AssertionError("retirement");
          doThrow(streamFailure).when(fixture.stream).close();
          doThrow(planFailure).when(fixture.plan).close();
          doAnswer(call -> {
            fixture.queries.remove(call.getArgument(0));
            throw retirementFailure;
          }).when(fixture.session).queryClosed(anyString());
          if (operation == 0) {
            when(fixture.stream.hasNext()).thenThrow(original);
          } else if (operation == 1) {
            when(fixture.stream.next()).thenThrow(original);
          } else {
            var result = mock(Result.class);
            when(fixture.stream.next()).thenReturn(result);
            when(result.isEntity()).thenThrow(original);
          }
          var thrown = Assert.assertThrows(Throwable.class,
              operation == 0 ? fixture.iterator::hasNext : fixture.iterator::next);
          Assert.assertSame(original, thrown);
          if (shared) {
            Assert.assertArrayEquals(new Throwable[] {planFailure, retirementFailure},
                original.getSuppressed());
          } else {
            Assert.assertArrayEquals(new Throwable[] {streamFailure}, original.getSuppressed());
            Assert.assertArrayEquals(new Throwable[] {planFailure, retirementFailure},
                streamFailure.getSuppressed());
          }
          Assert.assertTrue(fixture.queries.isEmpty());
          fixture.iterator.close();
          verify(fixture.stream, times(1)).close();
          verify(fixture.plan, times(1)).close();
          verify(fixture.session, times(1)).queryClosed(anyString());
        }
      }
    }
  }

  /** Each cleanup stage can fail alone or share one failure without skipping retirement. */
  @Test
  public void closeAttemptsEveryStageAndReportsFirstFailure() {
    for (var stage = 0; stage < 4; stage++) {
      var fixture = new RegisteredIterator();
      Throwable failure = stage % 2 == 0 ? new AssertionError("cleanup")
          : new IllegalStateException("cleanup");
      if (stage == 0 || stage == 3) {
        doThrow(failure).when(fixture.stream).close();
      }
      if (stage == 1 || stage == 3) {
        doThrow(failure).when(fixture.plan).close();
      }
      if (stage == 2 || stage == 3) {
        doAnswer(call -> {
          fixture.queries.remove(call.getArgument(0));
          throw failure;
        }).when(fixture.session).queryClosed(anyString());
      }
      Assert.assertSame(failure, Assert.assertThrows(Throwable.class, fixture.iterator::close));
      Assert.assertEquals(0, failure.getSuppressed().length);
      Assert.assertTrue(fixture.queries.isEmpty());
      fixture.iterator.close();
      verify(fixture.stream, times(1)).close();
      verify(fixture.plan, times(1)).close();
      verify(fixture.session, times(1)).queryClosed(anyString());
    }
  }

  /** Exhaustion still reports a close failure instead of appearing to succeed. */
  @Test
  public void exhaustionReportsCleanupFailureAndRemainsClosed() {
    var fixture = new RegisteredIterator();
    var failure = new AssertionError("close");
    doThrow(failure).when(fixture.stream).close();
    Assert.assertSame(failure,
        Assert.assertThrows(AssertionError.class, fixture.iterator::hasNext));
    Assert.assertFalse(fixture.iterator.hasNext());
    Assert.assertTrue(fixture.queries.isEmpty());
    verify(fixture.stream, times(1)).close();
    verify(fixture.plan).close();
  }

  private static final class RegisteredIterator {

    final GqlExecutionStream stream = mock(GqlExecutionStream.class);
    final GqlExecutionPlan plan = mock(GqlExecutionPlan.class);
    final DatabaseSessionEmbedded session = mock(DatabaseSessionEmbedded.class);
    final YTDBGraphInternal graph = mock(YTDBGraphInternal.class);
    final Map<String, RegisteredQuery> queries = new HashMap<>();
    final GqlResultIterator iterator = new GqlResultIterator(stream, plan, graph,
        mock(ImmutableSchema.class));

    RegisteredIterator() {
      doAnswer(call -> {
        queries.put(call.getArgument(0), call.getArgument(1));
        return null;
      }).when(session).queryStarted(anyString(), any(RegisteredQuery.class));
      doAnswer(call -> {
        queries.remove(call.getArgument(0));
        return null;
      }).when(session).queryClosed(anyString());
      iterator.register(session);
    }
  }

  private static GqlResultIterator createIterator(
      GqlExecutionStream stream, GqlExecutionPlan plan) {
    return new GqlResultIterator(
        stream, plan, mock(YTDBGraphInternal.class), mock(ImmutableSchema.class));
  }
}
