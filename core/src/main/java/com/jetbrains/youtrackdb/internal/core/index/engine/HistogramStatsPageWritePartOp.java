package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Replays exactly one compact statistics fragment on its addressed page. */
public final class HistogramStatsPageWritePartOp extends PageOperation {

  private byte[] part;

  public HistogramStatsPageWritePartOp() {
  }

  HistogramStatsPageWritePartOp(long pageIndex, long fileId, long unitId, LogSequenceNumber lsn,
      byte[] part) {
    super(pageIndex, fileId, unitId, lsn);
    var buffer = ByteBuffer.wrap(part).order(ByteOrder.nativeOrder());
    readPart(buffer);
  }

  @Override
  public int getId() {
    return WALRecordTypes.HISTOGRAM_STATS_PAGE_WRITE_PART_OP;
  }

  @Override
  public void redo(DurablePage page) {
    new HistogramStatsPage(page.getCacheEntry()).writePart(part);
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + part.length;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.put(part);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    readPart(buffer);
  }

  private void readPart(ByteBuffer buffer) {
    // The fragment is the entire remaining WAL body. Check its extent before allocating.
    int size = HistogramStatsPageContinuationCodec.partSize(buffer, getPageIndex());
    if (buffer.remaining() != size) {
      throw new IllegalArgumentException("Invalid statistics WAL fragment extent");
    }
    if (buffer.getInt(buffer.position() + Integer.BYTES) < HistogramStatsPage.IMAGE_PREFIX_SIZE) {
      throw new IllegalArgumentException(
          "Statistics WAL image is shorter than its snapshot prefix");
    }
    part = new byte[size];
    buffer.get(part);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof HistogramStatsPageWritePartOp op
        && super.equals(op) && Arrays.equals(part, op.part);
  }

  @Override
  public int hashCode() {
    return 31 * super.hashCode() + Arrays.hashCode(part);
  }
}
