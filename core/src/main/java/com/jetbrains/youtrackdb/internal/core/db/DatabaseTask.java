package com.jetbrains.youtrackdb.internal.core.db;

import javax.annotation.Nullable;

public interface DatabaseTask<X> {
  @Nullable
  X call(DatabaseSessionEmbedded session);
}
