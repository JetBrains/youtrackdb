package com.jetbrains.youtrackdb.internal.core.db.record.record;

import javax.annotation.Nonnull;

public interface Identifiable extends Comparable<Identifiable> {

  /**
   * Returns the record identity.
   *
   * @return RID instance
   */
  @Nonnull
  RID getIdentity();
}
