package com.jetbrains.youtrack.db.internal.core.db;

public interface DatabaseTask<X> {
  X call(DatabaseSessionEmbedded session);
}
