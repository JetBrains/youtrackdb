package com.jetbrains.youtrackdb.internal.core.gql.executor.resultset;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;

@SuppressWarnings({"resource", "ResultOfMethodCallIgnored"})
public class GqlExecutionStreamTest {

  // ── EmptyGqlExecutionStream ──

  @Test
  public void emptyStream_hasNext_returnsFalse() {
    Assert.assertFalse(GqlExecutionStream.empty().hasNext());
  }

  @Test(expected = NoSuchElementException.class)
  public void emptyStream_next_throws() {
    GqlExecutionStream.empty().next();
  }

  @Test
  public void emptyStream_close_doesNotThrow() {
    GqlExecutionStream.empty().close();
  }

  @Test
  public void empty_returnsSingleton() {
    Assert.assertSame(EmptyGqlExecutionStream.INSTANCE, GqlExecutionStream.empty());
  }

  // ── IteratorGqlExecutionStream: basic iteration ──

  @Test
  public void iterator_iteratesElements() {
    var stream = GqlExecutionStream.fromIterator(List.of("a", "b", "c").iterator());
    Assert.assertEquals("a", stream.next());
    Assert.assertEquals("b", stream.next());
    Assert.assertEquals("c", stream.next());
    Assert.assertFalse(stream.hasNext());
  }

  @Test
  public void iterator_withMapper_mapsElements() {
    var stream = GqlExecutionStream.fromIterator(List.of(1, 2, 3).iterator(), n -> n * 10);
    Assert.assertEquals(10, stream.next());
    Assert.assertEquals(20, stream.next());
    Assert.assertEquals(30, stream.next());
  }

  @Test
  public void iterator_noMapper_returnsOriginalReference() {
    var obj = new Object();
    var stream = new IteratorGqlExecutionStream<>(List.of(obj).iterator());
    Assert.assertSame(obj, stream.next());
  }

  @Test
  public void iterator_mapperReturnsNull_allowed() {
    var stream = new IteratorGqlExecutionStream<>(List.of("a").iterator(), x -> null);
    Assert.assertNull(stream.next());
  }

  // ── IteratorGqlExecutionStream: closed guard ──

  @Test
  public void iterator_hasNextAfterClose_returnsFalse() {
    var stream = new IteratorGqlExecutionStream<>(List.of(1, 2).iterator());
    stream.close();
    Assert.assertFalse(stream.hasNext());
  }

  @Test(expected = NoSuchElementException.class)
  public void iterator_nextAfterClose_throws() {
    var stream = new IteratorGqlExecutionStream<>(List.of(1, 2).iterator());
    stream.close();
    stream.next();
  }

  // ── IteratorGqlExecutionStream: close on error ──

  @Test
  public void iterator_hasNextThrows_closesSource() {
    var spy = SpyIterator.failingHasNext();
    var stream = new IteratorGqlExecutionStream<>(spy);
    try {
      stream.hasNext();
      Assert.fail();
    } catch (RuntimeException e) {
      Assert.assertEquals("boom", e.getMessage());
    }
    Assert.assertTrue(spy.isClosed());
  }

  @Test
  public void iterator_nextThrows_closesSource() {
    var spy = SpyIterator.failingNext();
    var stream = new IteratorGqlExecutionStream<>(spy);
    try {
      stream.next();
      Assert.fail();
    } catch (RuntimeException e) {
      Assert.assertEquals("boom", e.getMessage());
    }
    Assert.assertTrue(spy.isClosed());
  }

  // ── IteratorGqlExecutionStream: close behavior ──

  @Test
  public void iterator_close_isIdempotent() {
    var spy = new SpyIterator(List.of());
    var stream = new IteratorGqlExecutionStream<>(spy);
    stream.close();
    stream.close();
    Assert.assertEquals(1, spy.closeCount.get());
  }

  @Test
  public void iterator_exhaustion_closesAutoCloseableSource() {
    var spy = new SpyIterator(List.of(1));
    var stream = new IteratorGqlExecutionStream<>(spy);
    stream.next();
    Assert.assertFalse(stream.hasNext());
    Assert.assertTrue(spy.isClosed());
  }

  @Test
  public void iterator_explicitClose_closesAutoCloseableSource() {
    var spy = new SpyIterator(List.of(1, 2));
    var stream = new IteratorGqlExecutionStream<>(spy);
    stream.close();
    Assert.assertTrue(spy.isClosed());
  }

  @Test
  public void iterator_close_nonAutoCloseable_safe() {
    new IteratorGqlExecutionStream<>(List.of(1).iterator()).close();
  }

  @Test
  public void iterator_close_sourceThrows_wrappedInRuntime() {
    var spy = SpyIterator.failingClose();
    var stream = new IteratorGqlExecutionStream<>(spy);
    try {
      stream.close();
      Assert.fail();
    } catch (RuntimeException e) {
      Assert.assertTrue(e.getMessage().contains("Failed to close"));
    }
  }

  // ── FlatMapGqlExecutionStream: basic flatMap ──

  @Test
  public void flatMap_mapsEachElement() {
    var upstream = GqlExecutionStream.fromIterator(List.of(1, 2).iterator());
    var stream = upstream.flatMap(n -> GqlExecutionStream.fromIterator(
        List.of((int) n * 10, (int) n * 10 + 1).iterator()));
    Assert.assertEquals(List.of(10, 11, 20, 21), drain(stream));
  }

  @Test
  public void flatMap_emptyUpstream_isEmpty() {
    var stream = GqlExecutionStream.empty()
        .flatMap(x -> GqlExecutionStream.fromIterator(List.of(x).iterator()));
    Assert.assertFalse(stream.hasNext());
  }

  @Test(expected = NoSuchElementException.class)
  public void flatMap_nextAfterExhaustion_throws() {
    var stream = GqlExecutionStream.fromIterator(List.of(1).iterator())
        .flatMap(n -> GqlExecutionStream.fromIterator(List.of(n).iterator()));
    stream.next();
    stream.next();
  }

  @Test
  public void flatMap_skipsEmptyChildren() {
    var upstream = GqlExecutionStream.fromIterator(List.of(0, 1, 0, 2, 0).iterator());
    var stream = upstream.flatMap(n ->
        (int) n == 0
            ? GqlExecutionStream.empty()
            : GqlExecutionStream.fromIterator(List.of((int) n * 10).iterator()));
    Assert.assertEquals(List.of(10, 20), drain(stream));
  }

  @Test
  public void flatMap_childReturnsNull_passedThrough() {
    var nullChild = new GqlExecutionStream() {
      boolean consumed;

      @Override public boolean hasNext() { return !consumed; }

      @Override public Object next() { consumed = true; return null; }

      @Override public void close() { }
    };
    var stream = GqlExecutionStream.fromIterator(List.of(1).iterator())
        .flatMap(n -> nullChild);
    Assert.assertTrue(stream.hasNext());
    Assert.assertNull(stream.next());
  }

  // ── FlatMapGqlExecutionStream: resource cleanup on exhaustion ──

  @Test
  public void flatMap_exhaustion_closesUpstream() {
    var upstream = new SpyStream(1);
    var stream = upstream.flatMap(n -> GqlExecutionStream.fromIterator(List.of(n).iterator()));
    drain(stream);
    Assert.assertTrue(upstream.closed);
  }

  @Test
  public void flatMap_exhaustion_closesActiveChild() {
    var child = new SpyStream(1);
    var upstream = GqlExecutionStream.fromIterator(List.of(1).iterator());
    var stream = upstream.flatMap(n -> child);
    drain(stream);
    Assert.assertTrue(child.closed);
  }

  @Test
  public void flatMap_closesOldChildBeforeAdvancing() {
    var closeOrder = new ArrayList<Integer>();
    var upstream = GqlExecutionStream.fromIterator(List.of(1, 2, 3).iterator());
    var stream = upstream.flatMap(n -> new TrackableChild((int) n, closeOrder));
    Assert.assertEquals(List.of(1, 2, 3), drain(stream));
    Assert.assertEquals(List.of(1, 2, 3), closeOrder);
  }

  // ── FlatMapGqlExecutionStream: close() behavior ──

  @Test
  public void flatMap_close_withActiveChild_closesBoth() {
    var upstream = new SpyStream(1);
    var child = new SpyStream(1, 2);
    var stream = upstream.flatMap(n -> child);
    stream.hasNext();
    stream.close();
    Assert.assertTrue(child.closed);
    Assert.assertTrue(upstream.closed);
  }

  @Test
  public void flatMap_close_noActiveChild_closesUpstream() {
    var upstream = new SpyStream(1);
    var stream = upstream.flatMap(n -> GqlExecutionStream.empty());
    stream.close();
    Assert.assertTrue(upstream.closed);
  }

  // ── FlatMapGqlExecutionStream: close() exception handling ──

  @Test
  public void flatMap_close_childThrows_upstreamStillClosed() {
    var upstream = new SpyStream(1);
    var stream = upstream.flatMap(n -> SpyStream.failOnClose());
    stream.hasNext();
    try {
      stream.close();
      Assert.fail();
    } catch (RuntimeException e) {
      Assert.assertEquals("Failed to close stream", e.getMessage());
      Assert.assertEquals("child error", e.getCause().getMessage());
    }
    Assert.assertTrue(upstream.closed);
  }

  @Test
  public void flatMap_close_upstreamThrows_wrapped() {
    var stream = new FlatMapGqlExecutionStream(
        SpyStream.failOnCloseUpstream(),
        n -> GqlExecutionStream.empty());
    try {
      stream.close();
      Assert.fail();
    } catch (RuntimeException e) {
      Assert.assertEquals("Failed to close stream", e.getMessage());
      Assert.assertEquals("upstream error", e.getCause().getMessage());
    }
  }

  @Test
  public void flatMap_close_bothThrow_childPrimary_upstreamSuppressed() {
    var stream = new FlatMapGqlExecutionStream(
        SpyStream.failOnCloseUpstream(),
        n -> SpyStream.failOnClose());
    stream.hasNext();
    try {
      stream.close();
      Assert.fail();
    } catch (RuntimeException e) {
      Assert.assertEquals("Failed to close stream", e.getMessage());
      Assert.assertEquals("child error", e.getCause().getMessage());
      Assert.assertEquals("upstream error", e.getCause().getSuppressed()[0].getMessage());
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Test helpers
  // ════════════════════════════════════════════════════════════════════════

  private static List<Object> drain(GqlExecutionStream stream) {
    var results = new ArrayList<>();
    while (stream.hasNext()) {
      results.add(stream.next());
    }
    return results;
  }

  /**
   * AutoCloseable iterator that tracks close() calls.
   * Can be configured to throw on hasNext(), next(), or close().
   */
  private static final class SpyIterator implements Iterator<Integer>, AutoCloseable {
    private final Iterator<Integer> delegate;
    private final RuntimeException hasNextError;
    private final RuntimeException nextError;
    private final Exception closeError;
    final AtomicInteger closeCount = new AtomicInteger();

    SpyIterator(List<Integer> elements) {
      this(elements, null, null, null);
    }

    private SpyIterator(List<Integer> elements,
        RuntimeException hasNextError, RuntimeException nextError, Exception closeError) {
      this.delegate = elements.iterator();
      this.hasNextError = hasNextError;
      this.nextError = nextError;
      this.closeError = closeError;
    }

    static SpyIterator failingHasNext() {
      return new SpyIterator(List.of(), new RuntimeException("boom"), null, null);
    }

    static SpyIterator failingNext() {
      return new SpyIterator(List.of(1), null, new RuntimeException("boom"), null);
    }

    static SpyIterator failingClose() {
      return new SpyIterator(List.of(), null, null, new Exception("close error"));
    }

    @Override
    public boolean hasNext() {
      if (hasNextError != null) {
        throw hasNextError;
      }
      return delegate.hasNext();
    }

    @Override
    public Integer next() {
      if (nextError != null) {
        throw nextError;
      }
      return delegate.next();
    }

    @Override
    public void close() throws Exception {
      closeCount.incrementAndGet();
      if (closeError != null) {
        throw closeError;
      }
    }

    boolean isClosed() {
      return closeCount.get() > 0;
    }
  }

  /**
   * GqlExecutionStream that tracks close() calls.
   * Also usable as a CloseableIterator (for FlatMap upstream).
   */
  private static class SpyStream implements GqlExecutionStream {
    private final Iterator<?> delegate;
    boolean closed;

    SpyStream(Object... elements) {
      this.delegate = List.of(elements).iterator();
    }

    @Override
    public boolean hasNext() {
      return delegate.hasNext();
    }

    @Override
    public Object next() {
      return delegate.next();
    }

    @Override
    public void close() {
      closed = true;
    }

    static GqlExecutionStream failOnClose() {
      return new GqlExecutionStream() {
        @Override public boolean hasNext() { return true; }

        @Override public Object next() { return 1; }

        @Override public void close() { throw new RuntimeException("child error"); }
      };
    }

    static org.apache.tinkerpop.gremlin.structure.util.CloseableIterator<Object>
    failOnCloseUpstream() {
      return new org.apache.tinkerpop.gremlin.structure.util.CloseableIterator<>() {
        private final Iterator<Object> it = List.of((Object) 1).iterator();

        @Override public boolean hasNext() { return it.hasNext(); }

        @Override public Object next() { return it.next(); }

        @Override public void close() { throw new RuntimeException("upstream error"); }
      };
    }
  }

  /** Single-element child stream that records close order in a shared list. */
  private static final class TrackableChild implements GqlExecutionStream {
    private final int id;
    private final List<Integer> closeOrder;
    private boolean consumed;

    TrackableChild(int id, List<Integer> closeOrder) {
      this.id = id;
      this.closeOrder = closeOrder;
    }

    @Override public boolean hasNext() { return !consumed; }

    @Override public Object next() { consumed = true; return id; }

    @Override public void close() { closeOrder.add(id); }
  }
}
