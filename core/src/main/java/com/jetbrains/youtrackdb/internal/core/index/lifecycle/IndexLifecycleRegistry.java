package com.jetbrains.youtrackdb.internal.core.index.lifecycle;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Storage-scoped lifecycle cells keyed by durable index descriptor identity. */
public final class IndexLifecycleRegistry {

  private final ConcurrentHashMap<RID, IndexLifecycleCell> cells = new ConcurrentHashMap<>();

  public IndexLifecycleCell getOrCreate(@Nonnull RID descriptorIdentity) {
    return cells.computeIfAbsent(
        descriptorIdentity, ignored -> new IndexLifecycleCell(IndexLifecycle.USABLE));
  }

  @Nullable public IndexLifecycleCell get(@Nonnull RID descriptorIdentity) {
    return cells.get(descriptorIdentity);
  }

  public void remove(@Nonnull RID descriptorIdentity) {
    cells.remove(descriptorIdentity);
  }
}
