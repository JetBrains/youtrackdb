package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Logical WAL record for {@link HistogramStatsPage#writeSnapshot}. Captures all pre-serialized
 * field values so that redo can replay the page writes without needing BinarySerializer or
 * BinarySerializerFactory.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code serializerId} — key type serializer ID</li>
 *   <li>{@code totalCount}, {@code distinctCount}, {@code nullCount} — statistics counters</li>
 *   <li>{@code mutationsSinceRebalance}, {@code totalCountAtLastBuild} — tracking counters</li>
 *   <li>{@code persistedHllCount} — HLL register count (may include {@link
 *       HistogramStatsPage#HLL_PAGE1_FLAG} high bit)</li>
 *   <li>{@code histData} — pre-serialized histogram data blob (empty if no histogram)</li>
 *   <li>{@code inlineHllData} — HLL register bytes when inline on page 0 (empty if HLL is on
 *       page 1 or absent)</li>
 * </ul>
 */
public final class HistogramStatsPageWriteSnapshotOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.HISTOGRAM_STATS_PAGE_WRITE_SNAPSHOT_OP;

  private byte serializerId;
  private long totalCount;
  private long distinctCount;
  private long nullCount;
  private long mutationsSinceRebalance;
  private long totalCountAtLastBuild;
  private int persistedHllCount;
  private byte[] histData;
  private byte[] inlineHllData;

  public HistogramStatsPageWriteSnapshotOp() {
  }

  public HistogramStatsPageWriteSnapshotOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn,
      byte serializerId, long totalCount, long distinctCount,
      long nullCount, long mutationsSinceRebalance, long totalCountAtLastBuild,
      int persistedHllCount, byte[] histData, byte[] inlineHllData) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.serializerId = serializerId;
    this.totalCount = totalCount;
    this.distinctCount = distinctCount;
    this.nullCount = nullCount;
    this.mutationsSinceRebalance = mutationsSinceRebalance;
    this.totalCountAtLastBuild = totalCountAtLastBuild;
    this.persistedHllCount = persistedHllCount;
    this.histData = histData;
    this.inlineHllData = inlineHllData;
  }

  @Override
  public void redo(DurablePage page) {
    var statsPage = new HistogramStatsPage(page.getCacheEntry());
    statsPage.writeSnapshotRaw(
        serializerId, totalCount, distinctCount, nullCount,
        mutationsSinceRebalance, totalCountAtLastBuild,
        persistedHllCount, histData, inlineHllData);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public byte getSerializerId() {
    return serializerId;
  }

  public long getTotalCount() {
    return totalCount;
  }

  public long getDistinctCount() {
    return distinctCount;
  }

  public long getNullCount() {
    return nullCount;
  }

  public long getMutationsSinceRebalance() {
    return mutationsSinceRebalance;
  }

  public long getTotalCountAtLastBuild() {
    return totalCountAtLastBuild;
  }

  public int getPersistedHllCount() {
    return persistedHllCount;
  }

  public byte[] getHistData() {
    return histData;
  }

  public byte[] getInlineHllData() {
    return inlineHllData;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize()
        + Byte.BYTES // serializerId
        + Long.BYTES * 5 // totalCount, distinctCount, nullCount,
                         // mutationsSinceRebalance, totalCountAtLastBuild
        + Integer.BYTES // persistedHllCount
        + Integer.BYTES + histData.length // histData length + data
        + Integer.BYTES + inlineHllData.length; // inlineHllData length + data
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.put(serializerId);
    buffer.putLong(totalCount);
    buffer.putLong(distinctCount);
    buffer.putLong(nullCount);
    buffer.putLong(mutationsSinceRebalance);
    buffer.putLong(totalCountAtLastBuild);
    buffer.putInt(persistedHllCount);
    buffer.putInt(histData.length);
    buffer.put(histData);
    buffer.putInt(inlineHllData.length);
    buffer.put(inlineHllData);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    serializerId = buffer.get();
    totalCount = buffer.getLong();
    distinctCount = buffer.getLong();
    nullCount = buffer.getLong();
    mutationsSinceRebalance = buffer.getLong();
    totalCountAtLastBuild = buffer.getLong();
    persistedHllCount = buffer.getInt();
    int histLen = buffer.getInt();
    histData = new byte[histLen];
    buffer.get(histData);
    int hllLen = buffer.getInt();
    inlineHllData = new byte[hllLen];
    buffer.get(inlineHllData);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof HistogramStatsPageWriteSnapshotOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return serializerId == that.serializerId
        && totalCount == that.totalCount
        && distinctCount == that.distinctCount
        && nullCount == that.nullCount
        && mutationsSinceRebalance == that.mutationsSinceRebalance
        && totalCountAtLastBuild == that.totalCountAtLastBuild
        && persistedHllCount == that.persistedHllCount
        && Arrays.equals(histData, that.histData)
        && Arrays.equals(inlineHllData, that.inlineHllData);
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + serializerId;
    result = 31 * result + Long.hashCode(totalCount);
    result = 31 * result + Long.hashCode(distinctCount);
    result = 31 * result + Long.hashCode(nullCount);
    result = 31 * result + Long.hashCode(mutationsSinceRebalance);
    result = 31 * result + Long.hashCode(totalCountAtLastBuild);
    result = 31 * result + persistedHllCount;
    result = 31 * result + Arrays.hashCode(histData);
    result = 31 * result + Arrays.hashCode(inlineHllData);
    return result;
  }

  @Override
  public String toString() {
    return toString("serializerId=" + serializerId
        + ", totalCount=" + totalCount
        + ", histDataLen=" + (histData != null ? histData.length : "null")
        + ", inlineHllLen=" + (inlineHllData != null ? inlineHllData.length : "null"));
  }
}
