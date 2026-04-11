package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link HistogramStatsPage#writeEmpty(byte)}. Captures the serializer ID.
 * Unconditional — always registered when writeEmpty is called during a transaction.
 */
public final class HistogramStatsPageWriteEmptyOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.HISTOGRAM_STATS_PAGE_WRITE_EMPTY_OP;

  private byte serializerId;

  public HistogramStatsPageWriteEmptyOp() {
  }

  public HistogramStatsPageWriteEmptyOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, byte serializerId) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.serializerId = serializerId;
  }

  @Override
  public void redo(DurablePage page) {
    var statsPage = new HistogramStatsPage(page.getCacheEntry());
    statsPage.writeEmpty(serializerId);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public byte getSerializerId() {
    return serializerId;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Byte.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.put(serializerId);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    serializerId = buffer.get();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof HistogramStatsPageWriteEmptyOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return serializerId == that.serializerId;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + serializerId;
    return result;
  }

  @Override
  public String toString() {
    return toString("serializerId=" + serializerId);
  }
}
