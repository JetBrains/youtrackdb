package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineReference;
import com.jetbrains.youtrackdb.internal.core.index.lifecycle.IndexLifecycleCell;
import javax.annotation.Nullable;

/** One immutable and coherently published snapshot of an index handle. */
record IndexHandleState(
    int engineIdentifier,
    @Nullable IndexEngineReference engineReference,
    @Nullable RID descriptorIdentity,
    @Nullable IndexLifecycleCell lifecycleCell) {

  static final IndexHandleState EMPTY = new IndexHandleState(-1, null, null, null);

  IndexHandleState {
    if (engineIdentifier < 0 && engineReference != null) {
      throw new IllegalStateException("An absent index engine cannot carry a reference");
    }
    if (lifecycleCell != null
        && (descriptorIdentity == null || !descriptorIdentity.isPersistent())) {
      throw new IllegalStateException("A lifecycle cell requires a durable descriptor identity");
    }
  }

  boolean hasEngine() {
    return engineIdentifier >= 0;
  }

  boolean isDurablyIdentified() {
    return descriptorIdentity != null && descriptorIdentity.isPersistent();
  }
}
