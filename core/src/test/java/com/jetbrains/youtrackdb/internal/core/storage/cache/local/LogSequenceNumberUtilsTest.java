package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import org.junit.Test;

/** Tests optional write-ahead log position ordering for cache protection. */
public class LogSequenceNumberUtilsTest {

  /** Two absent requirements produce no protection requirement. */
  @Test
  public void bothNullReturnsNull() {
    assertNull(LogSequenceNumberUtils.earliest(null, null));
  }

  /** An absent first requirement yields the second requirement. */
  @Test
  public void nullFirstReturnsSecond() {
    final var second = new LogSequenceNumber(2, 20);

    assertSame(second, LogSequenceNumberUtils.earliest(null, second));
  }

  /** An absent second requirement retains the first requirement. */
  @Test
  public void nullSecondReturnsFirst() {
    final var first = new LogSequenceNumber(2, 20);

    assertSame(first, LogSequenceNumberUtils.earliest(first, null));
  }

  /** An earlier first requirement retains its reference. */
  @Test
  public void earlierFirstReturnsFirst() {
    final var first = new LogSequenceNumber(2, 20);
    final var second = new LogSequenceNumber(3, 10);

    assertSame(first, LogSequenceNumberUtils.earliest(first, second));
  }

  /** An earlier second requirement returns its reference. */
  @Test
  public void earlierSecondReturnsSecond() {
    final var first = new LogSequenceNumber(3, 10);
    final var second = new LogSequenceNumber(2, 20);

    assertSame(second, LogSequenceNumberUtils.earliest(first, second));
  }

  /** Equal positions retain the first reference. */
  @Test
  public void equalPositionsReturnFirstReference() {
    final var first = new LogSequenceNumber(2, 20);
    final var second = new LogSequenceNumber(2, 20);

    assertSame(first, LogSequenceNumberUtils.earliest(first, second));
  }
}
