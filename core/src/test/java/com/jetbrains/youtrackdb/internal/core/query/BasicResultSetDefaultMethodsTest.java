package com.jetbrains.youtrackdb.internal.core.query;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link BasicResultSet} default methods using a package-private
 * stub fixture. These exercise the interface-level default behavior of
 * {@code stream()}, {@code toList()}, {@code findFirst*()}, {@code detach()},
 * {@code detachedStream()}, {@code toDetachedList()} and {@code remove()}
 * without spinning up a database — the default methods only depend on the
 * abstract {@code hasNext}/{@code next}/{@code close} contract plus
 * {@link BasicResult#detach()}.
 *
 * <p>Pre-existing ResultSet implementations (e.g. {@code IteratorResultSet},
 * {@code InternalResultSet}) all inherit these defaults; regressions here
 * surface as end-user result-consumption breakage that no integration test
 * can pinpoint cheaply.
 */
public class BasicResultSetDefaultMethodsTest {

  /** Minimal BasicResult whose {@link #detach()} produces an independent copy. */
  private static final class TestResult implements BasicResult {

    private final String id;

    TestResult(String id) {
      this.id = id;
    }

    @Override
    public String toString() {
      return id;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof TestResult tr && tr.id.equals(id);
    }

    @Override
    public int hashCode() {
      return id.hashCode();
    }

    @Nullable @Override
    public <T> T getProperty(@Nonnull String name) {
      return null;
    }

    @Nullable @Override
    public BasicResult getResult(@Nonnull String name) {
      return null;
    }

    @Nullable @Override
    public RID getLink(@Nonnull String name) {
      return null;
    }

    @Nonnull
    @Override
    public List<String> getPropertyNames() {
      return Collections.emptyList();
    }

    @Override
    public boolean isIdentifiable() {
      return false;
    }

    @Nullable @Override
    public RID getIdentity() {
      return null;
    }

    @Override
    public boolean isProjection() {
      return false;
    }

    @Nonnull
    @Override
    public Map<String, Object> toMap() {
      return Collections.emptyMap();
    }

    @Nonnull
    @Override
    public String toJSON() {
      return "{}";
    }

    @Override
    public boolean hasProperty(@Nonnull String varName) {
      return false;
    }

    @Nullable @Override
    public DatabaseSessionEmbedded getBoundedToSession() {
      return null;
    }

    /**
     * Returns a new TestResult with a distinct identity but the same id, so
     * {@link BasicResultSet#detach()} tests can tell "detached copy" apart
     * from "same instance" while keeping equality-based assertions readable.
     */
    @Nonnull
    @Override
    public BasicResult detach() {
      return new TestResult(id);
    }
  }

  /**
   * Package-private stub driving the default-method tests. Backed by a List
   * with a cursor, tracks close() invocations, and mirrors the Iterator ⇔
   * Spliterator bridging used by concrete ResultSet subclasses like
   * {@code IteratorResultSet}.
   */
  static final class TestResultSet implements BasicResultSet<TestResult> {

    private final Iterator<TestResult> source;
    private final AtomicInteger closeCount = new AtomicInteger();
    private boolean closed;

    TestResultSet(List<TestResult> items) {
      this.source = items.iterator();
    }

    int closeCount() {
      return closeCount.get();
    }

    @Override
    public boolean hasNext() {
      if (closed) {
        return false;
      }
      return source.hasNext();
    }

    @Override
    public TestResult next() {
      if (closed || !source.hasNext()) {
        throw new NoSuchElementException();
      }
      return source.next();
    }

    /**
     * Idempotent close: increments {@code closeCount} only on the first
     * invocation. Subsequent calls are no-ops. This lets tests assert exact
     * close counts (e.g. {@code assertEquals(1, ...)}) and distinguishes
     * mutations that double-wire {@code onClose} from correct single-close
     * behavior.
     */
    @Override
    public void close() {
      if (!closed) {
        closed = true;
        closeCount.incrementAndGet();
      }
    }

    @Override
    public boolean isClosed() {
      return closed;
    }

    @Nullable @Override
    public DatabaseSessionEmbedded getBoundToSession() {
      return null;
    }

    @Override
    public boolean tryAdvance(Consumer<? super TestResult> action) {
      if (hasNext()) {
        action.accept(next());
        return true;
      }
      return false;
    }

    @Nullable @Override
    public BasicResultSet<TestResult> trySplit() {
      return null;
    }

    @Override
    public long estimateSize() {
      return Long.MAX_VALUE;
    }

    @Override
    public int characteristics() {
      return ORDERED;
    }

    @Override
    public void forEachRemaining(@Nonnull Consumer<? super TestResult> action) {
      while (hasNext()) {
        action.accept(next());
      }
    }
  }

  private static TestResultSet empty() {
    return new TestResultSet(Collections.emptyList());
  }

  private static TestResultSet of(String... ids) {
    return new TestResultSet(Arrays.stream(ids).map(TestResult::new).toList());
  }

  // ===== stream() / toList() =====

  @Test
  public void testStreamEmpty() {
    try (var rs = empty()) {
      Assert.assertEquals(0L, rs.stream().count());
    }
  }

  @Test
  public void testStreamSingleElement() {
    var rs = of("a");
    var list = rs.stream().map(TestResult::toString).toList();
    Assert.assertEquals(List.of("a"), list);
  }

  @Test
  public void testStreamMultiElementOrderPreserved() {
    var rs = of("a", "b", "c");
    var list = rs.stream().map(TestResult::toString).toList();
    Assert.assertEquals(List.of("a", "b", "c"), list);
  }

  @Test
  public void testToListMultiElement() {
    var rs = of("x", "y", "z");
    var list = rs.toList();
    Assert.assertEquals(3, list.size());
    Assert.assertEquals("x", list.get(0).toString());
    Assert.assertEquals("y", list.get(1).toString());
    Assert.assertEquals("z", list.get(2).toString());
  }

  @Test
  public void testToListEmpty() {
    var rs = empty();
    Assert.assertTrue(rs.toList().isEmpty());
  }

  /**
   * Documents the close-behaviour contract: {@code toList}, {@code detach},
   * and {@code toDetachedList} all internally call {@code stream()} but
   * never close the stream (no try-with-resources). Because
   * {@code StreamSupport.stream(...)} streams only invoke the {@code onClose}
   * handler when the stream is explicitly closed, the underlying ResultSet
   * is NOT closed by these terminal-convenience methods. Callers that need
   * close-on-exhaust semantics must either use {@code findFirst*} or wrap
   * {@code stream()} in try-with-resources.
   * WHEN-FIXED: if {@code toList} / {@code detach} / {@code toDetachedList}
   * are ever changed to close the underlying stream (e.g. by wrapping the
   * internal stream in try-with-resources), flip these assertions to
   * {@code assertEquals(1, rs.closeCount())} and delete this marker.
   */
  @Test
  public void testToListDoesNotAutoCloseUnderlyingStream() {
    var rs = of("a");
    rs.toList();
    Assert.assertEquals(
        "toList() does NOT close the underlying result set — callers must"
            + " close explicitly.",
        0,
        rs.closeCount());
  }

  // ===== findFirst() (no-arg) =====

  @Test
  public void testFindFirstReturnsFirstElement() {
    var rs = of("first", "second");
    Assert.assertEquals("first", rs.findFirst().toString());
    // findFirst always closes in the finally block.
    Assert.assertEquals(1, rs.closeCount());
  }

  @Test(expected = NoSuchElementException.class)
  public void testFindFirstEmptyThrowsNoSuchElement() {
    var rs = empty();
    try {
      rs.findFirst();
    } finally {
      // Close must run even on the empty path — documents the finally guarantee.
      Assert.assertEquals(1, rs.closeCount());
    }
  }

  // ===== findFirst(Function) =====

  @Test
  public void testFindFirstWithFunctionAppliesOnFirstElement() {
    var rs = of("hello");
    String upper = rs.findFirst(tr -> tr.toString().toUpperCase());
    Assert.assertEquals("HELLO", upper);
    Assert.assertEquals(1, rs.closeCount());
  }

  @Test(expected = NoSuchElementException.class)
  public void testFindFirstWithFunctionEmptyThrowsNoSuchElement() {
    var rs = empty();
    try {
      rs.findFirst(TestResult::toString);
    } finally {
      Assert.assertEquals(1, rs.closeCount());
    }
  }

  // ===== findFirstOrNull() (no-arg) =====

  @Test
  public void testFindFirstOrNullReturnsFirstElement() {
    var rs = of("a");
    Assert.assertEquals("a", rs.findFirstOrNull().toString());
    Assert.assertEquals(1, rs.closeCount());
  }

  @Test
  public void testFindFirstOrNullEmptyReturnsNull() {
    var rs = empty();
    Assert.assertNull(rs.findFirstOrNull());
    Assert.assertEquals(1, rs.closeCount());
  }

  // ===== findFirstOrNull(Function) =====

  @Test
  public void testFindFirstOrNullWithFunctionReturnsApplied() {
    var rs = of("abc");
    Integer length = rs.findFirstOrNull(tr -> tr.toString().length());
    Assert.assertEquals(Integer.valueOf(3), length);
    Assert.assertEquals(1, rs.closeCount());
  }

  @Test
  public void testFindFirstOrNullWithFunctionEmptyReturnsNull() {
    var rs = empty();
    Integer res = rs.findFirstOrNull(tr -> tr.toString().length());
    Assert.assertNull(res);
    Assert.assertEquals(1, rs.closeCount());
  }

  // ===== detach() / detachedStream() / toDetachedList() =====

  @Test
  public void testDetachReturnsNewInstances() {
    var r1 = new TestResult("a");
    var rs = new TestResultSet(List.of(r1));
    var detached = rs.detach();
    Assert.assertEquals(1, detached.size());
    // Detach must return a distinct instance, not the original:
    // BasicResult.detach() on TestResult always produces a fresh TestResult.
    Assert.assertNotSame(r1, detached.get(0));
    Assert.assertEquals(r1, detached.get(0));
  }

  @Test
  public void testDetachEmpty() {
    var rs = empty();
    Assert.assertTrue(rs.detach().isEmpty());
  }

  @Test
  public void testDetachedStreamOrderAndDetachment() {
    var inputs = List.of(new TestResult("x"), new TestResult("y"));
    var rs = new TestResultSet(inputs);
    var detached = rs.detachedStream().toList();
    Assert.assertEquals(2, detached.size());
    Assert.assertEquals("x", detached.get(0).toString());
    Assert.assertEquals("y", detached.get(1).toString());
    // Detached copies are not the originals.
    Assert.assertNotSame(inputs.get(0), detached.get(0));
    Assert.assertNotSame(inputs.get(1), detached.get(1));
  }

  @Test
  public void testToDetachedListMultiElement() {
    var rs = of("p", "q", "r");
    var list = rs.toDetachedList();
    Assert.assertEquals(3, list.size());
    Assert.assertEquals("p", list.get(0).toString());
    Assert.assertEquals("q", list.get(1).toString());
    Assert.assertEquals("r", list.get(2).toString());
  }

  // ===== remove() =====

  @Test(expected = UnsupportedOperationException.class)
  public void testRemoveThrowsUnsupportedOperation() {
    // remove() is a default method on BasicResultSet that always throws
    // UnsupportedOperationException — ResultSets are forward-only iterators.
    empty().remove();
  }

  // ===== stream onClose wiring =====

  @Test
  public void testStreamOnCloseInvokesResultSetClose() {
    var rs = of("only");
    // Explicit close via try-with-resources on the stream; this verifies
    // that BasicResultSet.stream()'s onClose hook is wired correctly, not
    // that terminal ops happen to close.
    try (var stream = rs.stream()) {
      Assert.assertEquals(1L, stream.count());
    }
    Assert.assertEquals(
        "Stream close must delegate to ResultSet.close exactly once",
        1, rs.closeCount());
  }

  @Test
  public void testDetachedStreamOnCloseInvokesResultSetClose() {
    var rs = of("only");
    try (var stream = rs.detachedStream()) {
      Assert.assertEquals(1L, stream.count());
    }
    Assert.assertEquals(1, rs.closeCount());
  }

  /**
   * Pins the null-element branch in BasicResultSet.detachedStream's
   * internal Spliterator: {@code while (hasNext()) { var nextElem = next();
   * if (nextElem != null) { action.accept(...); return true; } }}. The
   * branch is defensive — if {@code next()} yields null, detachedStream
   * must skip it and continue. A regression that dropped the null guard
   * would NPE inside {@code action.accept(nextElem.detach())}.
   */
  @Test
  public void testDetachedStreamSkipsNullElements() {
    List<TestResult> source = new java.util.ArrayList<>();
    source.add(null);
    source.add(new TestResult("a"));
    source.add(null);
    source.add(new TestResult("b"));
    var rs = new TestResultSet(source);
    var detached = rs.detachedStream().toList();
    Assert.assertEquals(2, detached.size());
    Assert.assertEquals("a", detached.get(0).toString());
    Assert.assertEquals("b", detached.get(1).toString());
  }

  @Test
  public void testDetachedStreamAllNullsYieldsEmpty() {
    List<TestResult> source = new java.util.ArrayList<>();
    source.add(null);
    source.add(null);
    var rs = new TestResultSet(source);
    Assert.assertTrue(rs.detachedStream().toList().isEmpty());
  }
}
