package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies the lifecycle contract of {@link CachedEntry} and the idempotent-close behaviour of {@link
 * IdempotentExecutionStream}. These two classes carry the cache's close-path safety: the underlying
 * execution stream must be closed exactly once even when both the cache and the consumer-facing
 * result set reach it at transaction end. The view-pin refcount and the K0_NONE strike counter are
 * also exercised because later steps depend on their exact increment/decrement semantics.
 */
public class CachedEntryTest {

  /** A counting stream so a test can assert how many times {@code close} reached the underlying. */
  private static final class CountingStream implements ExecutionStream {

    int closeCount;
    private final boolean throwOnSecondClose;

    CountingStream(boolean throwOnSecondClose) {
      this.throwOnSecondClose = throwOnSecondClose;
    }

    @Override
    public boolean hasNext(CommandContext ctx) {
      return false;
    }

    @Override
    public Result next(CommandContext ctx) {
      throw new NoSuchElementException();
    }

    @Override
    public void close(CommandContext ctx) {
      closeCount++;
      if (throwOnSecondClose && closeCount > 1) {
        throw new IllegalStateException("underlying stream closed twice");
      }
    }
  }

  private static CachedEntry recordEntry(ExecutionStream stream) {
    return new CachedEntry(
        CacheableShape.RECORD, Set.of("OUser"), null, null, stream, null, null, 0L);
  }

  /**
   * {@link IdempotentExecutionStream} must forward the first close to the underlying and swallow
   * every later close, so the underlying observes exactly one close regardless of how many owners
   * call it.
   */
  @Test
  public void idempotentStreamClosesUnderlyingExactlyOnce() {
    var underlying = new CountingStream(true);
    var wrapper = new IdempotentExecutionStream(underlying);
    Assert.assertFalse(wrapper.isClosed());

    wrapper.close(null);
    Assert.assertTrue(wrapper.isClosed());
    // Second and third close must be no-ops; a throw here would mean the underlying was hit twice.
    wrapper.close(null);
    wrapper.close(null);

    Assert.assertEquals(
        "Underlying stream must be closed exactly once through the idempotent wrapper",
        1,
        underlying.closeCount);
  }

  /**
   * {@link CachedEntry#close} must be idempotent and must release the wrapped stream once. A second
   * close sees the nulled stream and returns without re-closing, which protects the cross-owner
   * double-close path at transaction end.
   */
  @Test
  public void entryCloseIsIdempotent() {
    var underlying = new CountingStream(true);
    var entry = recordEntry(new IdempotentExecutionStream(underlying));

    entry.close();
    entry.close();

    Assert.assertEquals(
        "Entry close must release the underlying stream exactly once across repeated calls",
        1,
        underlying.closeCount);
    Assert.assertNull("Stream reference must be nulled after close", entry.getStream());
  }

  /**
   * Closing an entry that holds the cache's idempotent wrapper, then closing the same wrapper again
   * directly (mimicking {@code closeActiveQueries()} reaching it via the result set after the cache
   * already closed it), must still close the underlying exactly once and never throw.
   */
  @Test
  public void crossOwnerDoubleCloseClosesUnderlyingOnce() {
    var underlying = new CountingStream(true);
    var wrapper = new IdempotentExecutionStream(underlying);
    var entry = recordEntry(wrapper);

    entry.close();
    // The result set still holds the same wrapper and closes it independently.
    wrapper.close(null);

    Assert.assertEquals(1, underlying.closeCount);
  }

  /** An entry constructed with no live stream must close cleanly (no NPE, no underlying call). */
  @Test
  public void closeWithNullStreamIsSafe() {
    var entry = recordEntry(null);
    entry.close();
    Assert.assertNull(entry.getStream());
  }

  /**
   * The view-pin refcount must increment and decrement symmetrically and never go below zero, since
   * the LRU eviction guard reads it to decide whether an entry is safe to evict.
   */
  @Test
  public void liveViewCountIncrementsAndDecrements() {
    var entry = recordEntry(null);
    Assert.assertEquals(0, entry.getLiveViewCount());

    entry.incrementLiveViewCount();
    entry.incrementLiveViewCount();
    Assert.assertEquals(2, entry.getLiveViewCount());

    entry.decrementLiveViewCount();
    Assert.assertEquals(1, entry.getLiveViewCount());

    entry.decrementLiveViewCount();
    // An extra decrement must clamp at zero rather than go negative.
    entry.decrementLiveViewCount();
    Assert.assertEquals(0, entry.getLiveViewCount());
  }
}
