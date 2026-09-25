package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageLineageIdentity;
import org.junit.Test;

/** Covers identity publication for volatile and durable storage profiles. */
public class StorageIdentityHolderTest {

  /** An empty holder rejects both identity reads before durable identity publication. */
  @Test
  public void identityReadsFailBeforeDurablePublication() {
    var holder = new StorageIdentityHolder();

    var storageFailure = assertThrows(IllegalStateException.class, holder::storageIdentity);
    var lineageFailure = assertThrows(IllegalStateException.class, holder::lineageIdentity);

    assertEquals("Storage identity is not initialized", storageFailure.getMessage());
    assertEquals("Storage identity is not initialized", lineageFailure.getMessage());
  }

  /** One update publishes both durable identities to later readers. */
  @Test
  public void updatePublishesCompleteDurableIdentityPair() {
    var holder = new StorageIdentityHolder();
    var storageIdentity = StorageIdentity.random();
    var lineageIdentity = StorageLineageIdentity.random();

    holder.update(storageIdentity, lineageIdentity);

    assertEquals(storageIdentity, holder.storageIdentity());
    assertEquals(lineageIdentity, holder.lineageIdentity());
  }
}
