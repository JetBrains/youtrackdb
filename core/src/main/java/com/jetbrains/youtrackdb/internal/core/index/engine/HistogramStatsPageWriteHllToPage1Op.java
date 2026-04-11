package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Logical WAL record for {@link HistogramStatsPage#writeHllToPage1(
 * com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry, HyperLogLogSketch)}.
 * Captures the serialized HLL register bytes (1024 bytes). Registered on the page 1
 * cache entry, not page 0.
 */
public final class HistogramStatsPageWriteHllToPage1Op extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.HISTOGRAM_STATS_PAGE_WRITE_HLL_TO_PAGE1_OP;

  private byte[] hllData;

  public HistogramStatsPageWriteHllToPage1Op() {
  }

  public HistogramStatsPageWriteHllToPage1Op(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, byte[] hllData) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.hllData = hllData;
  }

  @Override
  public void redo(DurablePage page) {
    HistogramStatsPage.writeHllRaw(page.getCacheEntry(), hllData);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public byte[] getHllData() {
    return hllData;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Integer.BYTES + hllData.length;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(hllData.length);
    buffer.put(hllData);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    int len = buffer.getInt();
    hllData = new byte[len];
    buffer.get(hllData);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof HistogramStatsPageWriteHllToPage1Op that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return Arrays.equals(hllData, that.hllData);
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + Arrays.hashCode(hllData);
    return result;
  }

  @Override
  public String toString() {
    return toString("hllDataLen=" + (hllData != null ? hllData.length : "null"));
  }
}
