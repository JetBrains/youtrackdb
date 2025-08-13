package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecord;

public final class StartWALRecord implements WALRecord {

  private volatile LogSequenceNumber logSequenceNumber;

  @Override
  public LogSequenceNumber getLsn() {
    return logSequenceNumber;
  }

  @Override
  public void setLsn(LogSequenceNumber lsn) {
    this.logSequenceNumber = lsn;
  }

  @Override
  public void setDistance(int distance) {
  }

  @Override
  public void setDiskSize(int diskSize) {
  }

  @Override
  public int getDistance() {
    return 0;
  }

  @Override
  public int getDiskSize() {
    return CASWALPage.RECORDS_OFFSET;
  }
}
