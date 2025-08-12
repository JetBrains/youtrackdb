package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal;

import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import java.nio.ByteBuffer;

public class FileTruncatedWALRecord extends OperationUnitBodyRecord {

  private long fileId;

  public FileTruncatedWALRecord() {
  }

  public FileTruncatedWALRecord(long operationUnitId, long fileId) {
    super(operationUnitId);
    this.fileId = fileId;
  }

  public long getFileId() {
    return fileId;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    buffer.putLong(fileId);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    fileId = buffer.getLong();
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + LongSerializer.LONG_SIZE;
  }

  @Override
  public int getId() {
    return WALRecordTypes.FILE_TRUNCATED_WAL_RECORD;
  }
}
