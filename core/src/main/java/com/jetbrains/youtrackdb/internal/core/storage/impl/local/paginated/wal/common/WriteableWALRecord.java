package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecord;
import java.nio.ByteBuffer;

public interface WriteableWALRecord extends WALRecord {

  void setBinaryContent(ByteBuffer buffer);

  ByteBuffer getBinaryContent();

  void freeBinaryContent();

  int getBinaryContentLen();

  int toStream(byte[] content, int offset);

  void toStream(ByteBuffer buffer);

  int fromStream(byte[] content, int offset);

  int serializedSize();

  void written();

  boolean isWritten();

  int getId();
}
