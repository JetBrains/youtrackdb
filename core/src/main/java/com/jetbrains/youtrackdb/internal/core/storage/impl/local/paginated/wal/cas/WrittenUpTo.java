package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;

public final class WrittenUpTo {

  private final LogSequenceNumber lsn;
  private final long position;

  public WrittenUpTo(final LogSequenceNumber lsn, final long position) {
    this.lsn = lsn;
    this.position = position;
  }

  public LogSequenceNumber getLsn() {
    return lsn;
  }

  public long getPosition() {
    return position;
  }
}
