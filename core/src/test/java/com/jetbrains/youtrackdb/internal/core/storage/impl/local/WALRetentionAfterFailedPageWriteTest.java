package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.junit.Test;

public class WALRetentionAfterFailedPageWriteTest {

  /** An already released table returns on its first read, even with no time left to wait. */
  @Test
  public void alreadyPersistedOperationsReturnWithoutPolling() throws InterruptedException {
    var table = mock(AtomicOperationsTable.class);
    when(table.getSegmentEarliestNotPersistedOperation()).thenReturn(-1L);

    WALRetentionAfterFailedPageWriteIT.awaitOperationsPersisted(table, 0, () -> 0L);

    verify(table, times(1)).getSegmentEarliestNotPersistedOperation();
  }

  /** A pending segment followed by a released table completes after one polling sleep. */
  @Test
  public void operationsPersistDuringPolling() throws InterruptedException {
    var table = mock(AtomicOperationsTable.class);
    // Ordered reads control the transition without a racing background thread.
    when(table.getSegmentEarliestNotPersistedOperation()).thenReturn(42L, -1L);

    // A fixed clock leaves time for polling regardless of real elapsed time.
    WALRetentionAfterFailedPageWriteIT.awaitOperationsPersisted(table, 1_000, () -> 0L);

    verify(table, times(2)).getSegmentEarliestNotPersistedOperation();
  }

  /** Expiry after a second pending read reports the time limit and the latest segment, not the first. */
  @Test
  public void timeoutFailsWithBudgetAndLastObservedSegment() {
    var table = mock(AtomicOperationsTable.class);
    when(table.getSegmentEarliestNotPersistedOperation()).thenReturn(42L, 43L);
    var nanoClock = mock(LongSupplier.class);
    // The deadline is reached only after the polling sleep and the second table read.
    when(nanoClock.getAsLong()).thenReturn(0L, 0L, TimeUnit.MILLISECONDS.toNanos(1_000));

    var failure = assertThrows(AssertionError.class,
        () -> WALRetentionAfterFailedPageWriteIT.awaitOperationsPersisted(table, 1_000, nanoClock));

    assertThat(failure).hasMessageContaining("Timed out after 1000 ms")
        .hasMessageContaining("getSegmentEarliestNotPersistedOperation() = 43")
        .hasMessageNotContaining("getSegmentEarliestNotPersistedOperation() = 42");
    verify(table, times(2)).getSegmentEarliestNotPersistedOperation();
  }

  /** An interrupted caller exits at the first sleep instead of polling until timeout. */
  @Test
  public void interruptionPropagatesWithoutAnotherPoll() {
    var table = mock(AtomicOperationsTable.class);
    when(table.getSegmentEarliestNotPersistedOperation()).thenReturn(42L);

    Thread.currentThread().interrupt();
    try {
      assertThrows(InterruptedException.class,
          () -> WALRetentionAfterFailedPageWriteIT.awaitOperationsPersisted(table, 1_000,
              () -> 0L));
      verify(table, times(1)).getSegmentEarliestNotPersistedOperation();
    } finally {
      // Do not leak interrupt state into another test, including when an assertion fails.
      Thread.interrupted();
    }
  }
}
