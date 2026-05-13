package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;

/** Shared helpers for the Link*ResultImpl tests. Package-private. */
final class LinkTestFixtures {

  private LinkTestFixtures() {
  }

  /** Build a canonical RID (Identifiable) for use in tests. */
  static Identifiable rid(int cluster, long position) {
    return new RecordId(cluster, position);
  }
}
