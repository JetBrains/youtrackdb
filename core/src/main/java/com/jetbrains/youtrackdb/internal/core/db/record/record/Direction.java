package com.jetbrains.youtrackdb.internal.core.db.record.record;

import javax.annotation.Nonnull;

public enum Direction {
  OUT,
  IN,
  BOTH;

  @Nonnull
  public Direction opposite() {
    if (this.equals(OUT)) {
      return IN;
    } else if (this.equals(IN)) {
      return OUT;
    } else {
      return BOTH;
    }
  }
}
