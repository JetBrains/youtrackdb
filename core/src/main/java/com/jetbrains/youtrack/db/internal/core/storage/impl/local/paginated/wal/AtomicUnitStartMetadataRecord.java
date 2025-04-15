package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal;

import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import java.nio.ByteBuffer;

public class AtomicUnitStartMetadataRecord extends AtomicUnitStartRecord {

  private byte[] metadata;

  public AtomicUnitStartMetadataRecord() {
  }

  public AtomicUnitStartMetadataRecord(
      final boolean isRollbackSupported, final long unitId, byte[] metadata) {
    super(isRollbackSupported, unitId);
    this.metadata = metadata;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(metadata.length);
    buffer.put(metadata);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    var len = buffer.getInt();
    this.metadata = new byte[len];
    buffer.get(this.metadata);
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + IntegerSerializer.INT_SIZE + metadata.length;
  }

  public byte[] getMetadata() {
    return metadata;
  }

  @Override
  public int getId() {
    return WALRecordTypes.ATOMIC_UNIT_START_METADATA_RECORD;
  }
}
