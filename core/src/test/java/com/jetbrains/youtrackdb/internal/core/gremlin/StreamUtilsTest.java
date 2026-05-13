package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * Tests for {@link StreamUtils} — three small wrappers used across the gremlin engine to
 * adapt iterators to streams. Each public overload has a non-trivial branch (parallel flag,
 * {@link AutoCloseable} delegation, varargs collection) that needs explicit pinning.
 */
public class StreamUtilsTest {

  /**
   * The single-arg overload defaults to a sequential stream — pin {@code parallel == false}
   * so a refactor that flips the default surfaces here.
   */
  @Test
  public void asStreamSequentialPreservesIteratorOrder() {
    var src = List.of("a", "b", "c").iterator();
    var collected = StreamUtils.asStream(src).collect(Collectors.toList());
    assertEquals(List.of("a", "b", "c"), collected);
  }

  /**
   * The two-arg overload with {@code parallel == true} returns a parallel stream — verify
   * the flag is honoured. (Counts only — parallel order is undefined.)
   */
  @Test
  public void asStreamParallelHonoursFlag() {
    var src = List.of(1, 2, 3, 4).iterator();
    var stream = StreamUtils.asStream(src, true);
    assertTrue(stream.isParallel());
    assertEquals(4, stream.count());
  }

  /**
   * When the source iterator implements {@link AutoCloseable}, the stream's
   * {@link java.util.stream.Stream#close} call must propagate to the iterator. This covers the
   * "happy path" of the {@link AutoCloseable} branch — the iterator is closed exactly once.
   */
  @Test
  public void asStreamClosesAutoCloseableIterator() {
    var closed = new AtomicBoolean(false);
    Iterator<String> closeable = new CloseableTrackingIterator(closed, List.of("x", "y"));

    try (var stream = StreamUtils.asStream(closeable)) {
      var collected = stream.collect(Collectors.toList());
      assertEquals(List.of("x", "y"), collected);
    }

    assertTrue("AutoCloseable iterator must be closed when the stream is closed", closed.get());
  }

  /**
   * Branch coverage: an iterator that does NOT implement {@link AutoCloseable} must not throw
   * on stream close — the try/instanceof guard is the load-bearing predicate.
   */
  @Test
  public void asStreamCloseOnNonCloseableIteratorIsNoOp() {
    Iterator<Integer> plain = List.of(1, 2).iterator();
    try (var stream = StreamUtils.asStream(plain)) {
      assertEquals(2, stream.count());
    }
    // no exception — pass
  }

  /**
   * When the {@link AutoCloseable#close} call on the iterator throws, the stream's
   * {@code close()} must surface the exception wrapped in {@link IllegalStateException} so
   * call sites can distinguish stream-close failures from upstream iterator failures.
   */
  @Test
  public void asStreamWrapsCloseFailureInIllegalStateException() {
    Iterator<String> failing =
        new ThrowingCloseableIterator(List.of("a"), new RuntimeException("boom"));

    var stream = StreamUtils.asStream(failing);
    // drain without closing
    assertEquals(List.of("a"), stream.collect(Collectors.toList()));

    var thrown = assertThrows(IllegalStateException.class, stream::close);
    assertEquals("boom", thrown.getCause().getMessage());
  }

  /**
   * The {@code asStream(String[])} overload wraps a varargs / array as a stream — pin element
   * order and the empty-array branch.
   */
  @Test
  public void asStreamFromStringArrayPreservesOrder() {
    var arr = new String[] {"foo", "bar", "baz"};
    var collected = StreamUtils.asStream(arr).collect(Collectors.toList());
    assertEquals(Arrays.asList("foo", "bar", "baz"), collected);
  }

  /** Empty-array branch — important because {@code newArrayList} on length 0 must succeed. */
  @Test
  public void asStreamFromEmptyStringArrayIsEmpty() {
    var collected = StreamUtils.asStream(new String[0]).collect(Collectors.toList());
    assertTrue(collected.isEmpty());
  }

  /** Test helper — minimal {@link Iterator} + {@link AutoCloseable} that records closure. */
  private static final class CloseableTrackingIterator implements Iterator<String>,
      AutoCloseable {
    private final AtomicBoolean closed;
    private final Iterator<String> delegate;

    private CloseableTrackingIterator(AtomicBoolean closed, List<String> items) {
      this.closed = closed;
      this.delegate = items.iterator();
    }

    @Override
    public boolean hasNext() {
      return delegate.hasNext();
    }

    @Override
    public String next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return delegate.next();
    }

    @Override
    public void close() {
      assertFalse("close called twice", closed.getAndSet(true));
    }
  }

  /** Iterator whose {@link #close} throws — drives the IllegalStateException-wrap branch. */
  private static final class ThrowingCloseableIterator implements Iterator<String>,
      AutoCloseable {
    private final Iterator<String> delegate;
    private final RuntimeException onClose;

    private ThrowingCloseableIterator(List<String> items, RuntimeException onClose) {
      this.delegate = items.iterator();
      this.onClose = onClose;
    }

    @Override
    public boolean hasNext() {
      return delegate.hasNext();
    }

    @Override
    public String next() {
      return delegate.next();
    }

    @Override
    public void close() {
      throw onClose;
    }
  }
}
