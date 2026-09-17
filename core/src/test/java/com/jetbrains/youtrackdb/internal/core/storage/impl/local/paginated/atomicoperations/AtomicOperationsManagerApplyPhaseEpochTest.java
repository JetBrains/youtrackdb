package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import org.junit.Test;

/** Tests stable apply-epoch identity for logical component lock domains. */
public class AtomicOperationsManagerApplyPhaseEpochTest {

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
