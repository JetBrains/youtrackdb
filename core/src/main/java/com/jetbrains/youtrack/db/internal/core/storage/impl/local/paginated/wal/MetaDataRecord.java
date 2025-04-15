package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal;

import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import java.nio.ByteBuffer;

public final class MetaDataRecord extends AbstractWALRecord {

  private byte[] metadata;

  public MetaDataRecord() {
  }

  public MetaDataRecord(final byte[] metadata) {
    this.metadata = metadata;
  }

  public byte[] getMetadata() {
    return metadata;
  }

  @Override
  public int toStream(byte[] content, int offset) {
    IntegerSerializer.serializeNative(metadata.length, content, offset);
    offset += IntegerSerializer.INT_SIZE;

    System.arraycopy(metadata, 0, content, offset, metadata.length);

    return offset + content.length;
  }

  @Override
  public void toStream(ByteBuffer buffer) {
    buffer.putInt(metadata.length);
    buffer.put(metadata);
  }

  @Override
  public int fromStream(byte[] content, int offset) {
    final var metadataLen = IntegerSerializer.deserializeNative(content, offset);
    offset += IntegerSerializer.INT_SIZE;

    metadata = new byte[metadataLen];
    System.arraycopy(content, offset, metadata, 0, metadataLen);
    return offset + metadataLen;
  }

  @Override
  public int serializedSize() {
    return IntegerSerializer.INT_SIZE + metadata.length;
  }

  @Override
  public int getId() {
    return WALRecordTypes.TX_METADATA;
  }
}
