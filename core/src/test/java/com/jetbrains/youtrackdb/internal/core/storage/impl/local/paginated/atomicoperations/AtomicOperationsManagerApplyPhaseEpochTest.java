package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import org.junit.Test;

/** Tests stable apply-epoch identity for logical component lock domains. */
public class AtomicOperationsManagerApplyPhaseEpochTest {

  // A new manager, including reopen of the same storage, starts at zero and cannot
  // contribute to another storage's counters.
  @Test
  public void commitCountersAreIsolatedAndResetWithManagerLifetime() {
    var storage = mock(AbstractStorage.class);
    var first = new AtomicOperationsManager(storage, mock(AtomicOperationsTable.class));
    var other = new AtomicOperationsManager(
        mock(AbstractStorage.class), mock(AtomicOperationsTable.class));
    first.getCommitProtocolCounters().record(CommitProtocolCounters.Outcome.PAGE_SET);
    var reopened = new AtomicOperationsManager(storage, mock(AtomicOperationsTable.class));
    assertNotSame(first.getCommitProtocolCounters(), reopened.getCommitProtocolCounters());
    for (var outcome : CommitProtocolCounters.Outcome.values()) {
      assertEquals(outcome == CommitProtocolCounters.Outcome.PAGE_SET ? 1 : 0,
          first.getCommitProtocolCounters().count(outcome));
      assertEquals(0, other.getCommitProtocolCounters().count(outcome));
      assertEquals(0, reopened.getCommitProtocolCounters().count(outcome));
    }
  }

  @Test
  public void componentsSharingLockNameShareStableEpoch() {
    // A collection and its internal maps are separate Java objects but use one lock name.
    // Repeated lookup must retain exactly one epoch for their shared logical domain.
    var manager = new AtomicOperationsManager(
        mock(AbstractStorage.class), mock(AtomicOperationsTable.class));

    var collectionEpoch = manager.getApplyPhaseEpoch("collection-7");
    var positionMapEpoch = manager.getApplyPhaseEpoch("collection-7");

    assertSame(collectionEpoch, positionMapEpoch);
  }

  @Test
  public void unrelatedLockNamesUseIndependentEpochs() {
    // Independent components must not invalidate each other's optimistic read attempts.
    var manager = new AtomicOperationsManager(
        mock(AbstractStorage.class), mock(AtomicOperationsTable.class));

    var collectionEpoch = manager.getApplyPhaseEpoch("collection-7");
    var indexEpoch = manager.getApplyPhaseEpoch("index-3");

    assertNotSame(collectionEpoch, indexEpoch);
  }
}
