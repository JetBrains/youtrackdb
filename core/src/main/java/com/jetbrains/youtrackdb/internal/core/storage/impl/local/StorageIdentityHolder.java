package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageLineageIdentity;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Holds the current storage identity pair for one storage instance. */
public final class StorageIdentityHolder {

  private final AtomicReference<IdentityPair> current;

  public StorageIdentityHolder() {
    current = new AtomicReference<>();
  }

  public StorageIdentityHolder(
      StorageIdentity storageIdentity, StorageLineageIdentity lineageIdentity) {
    current = new AtomicReference<>(new IdentityPair(storageIdentity, lineageIdentity));
  }

  public StorageIdentity storageIdentity() {
    return currentIdentity().storageIdentity();
  }

  public StorageLineageIdentity lineageIdentity() {
    return currentIdentity().lineageIdentity();
  }

  public void update(StorageIdentity storageIdentity, StorageLineageIdentity lineageIdentity) {
    current.set(new IdentityPair(storageIdentity, lineageIdentity));
  }

  private IdentityPair currentIdentity() {
    var identity = current.get();
    if (identity == null) {
      throw new IllegalStateException("Storage identity is not initialized");
    }
    return identity;
  }

  private record IdentityPair(
      StorageIdentity storageIdentity, StorageLineageIdentity lineageIdentity) {

    private IdentityPair {
      Objects.requireNonNull(storageIdentity, "storageIdentity");
      Objects.requireNonNull(lineageIdentity, "lineageIdentity");
    }
  }
}
