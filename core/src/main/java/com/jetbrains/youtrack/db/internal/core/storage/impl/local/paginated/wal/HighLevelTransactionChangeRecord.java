package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal;

import static com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALRecordTypes.HIGH_LEVEL_TRANSACTION_CHANGE_RECORD;

import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import java.nio.ByteBuffer;

public class HighLevelTransactionChangeRecord extends OperationUnitRecord {

  private byte[] data;

  public HighLevelTransactionChangeRecord() {
  }

  public HighLevelTransactionChangeRecord(long operationUnitId, byte[] data) {
    super(operationUnitId);
    this.data = data;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    buffer.putInt(data.length);
    buffer.put(data, 0, data.length);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    var size = buffer.getInt();
    data = new byte[size];
    buffer.get(data, 0, size);
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + IntegerSerializer.INT_SIZE + data.length;
  }

  @Override
  public int getId() {
    return HIGH_LEVEL_TRANSACTION_CHANGE_RECORD;
  }

  public byte[] getData() {
    return data;
  }
}
