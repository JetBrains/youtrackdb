package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical write-ahead log record for the durable link-bag identifier counter.
 */
public final class RidbagEntryPointSetRidBagIdCounterOp extends PageOperation {

  public static final int RECORD_ID =
      WALRecordTypes.RIDBAG_ENTRY_POINT_SET_RID_BAG_ID_COUNTER_OP;

  private long counter;

  public RidbagEntryPointSetRidBagIdCounterOp() {
  }

  public RidbagEntryPointSetRidBagIdCounterOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, long counter) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.counter = counter;
  }

  @Override
  public void redo(DurablePage page) {
    new EntryPoint(page.getCacheEntry()).setRidBagIdCounter(counter);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public long getCounter() {
    return counter;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Long.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putLong(counter);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    counter = buffer.getLong();
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (!(object instanceof RidbagEntryPointSetRidBagIdCounterOp that)) {
      return false;
    }
    return super.equals(object) && counter == that.counter;
  }

  @Override
  public int hashCode() {
    return 31 * super.hashCode() + Long.hashCode(counter);
  }

  @Override
  public String toString() {
    return toString("counter=" + counter);
  }
}
