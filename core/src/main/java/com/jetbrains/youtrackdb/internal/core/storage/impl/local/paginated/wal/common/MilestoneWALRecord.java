package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecord;

public final class MilestoneWALRecord implements WALRecord {

  private int distance = -1;
  private int diskSize = -1;

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
    this.distance = distance;
  }

  @Override
  public void setDiskSize(int diskSize) {
    this.diskSize = diskSize;
  }

  @Override
  public int getDistance() {
    if (distance < 0) {
      throw new IllegalStateException("Distance is not set");
    }

    return distance;
  }

  @Override
  public int getDiskSize() {
    if (diskSize < 0) {
      throw new IllegalStateException("Disk size is not set");
    }

    return diskSize;
  }

  @Override
  public String toString() {
    return "MilestoneWALRecord{ operation_id_lsn = " + logSequenceNumber + '}';
  }
}
