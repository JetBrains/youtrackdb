package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AbstractWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

public final class EmptyWALRecord extends AbstractWALRecord {

  public EmptyWALRecord() {
  }

  @Override
  public int toStream(final byte[] content, final int offset) {
    return offset;
  }

  @Override
  public void toStream(final ByteBuffer buffer) {
  }

  @Override
  public int fromStream(final byte[] content, final int offset) {
    return offset;
  }

  @Override
  public int serializedSize() {
    return 0;
  }

  @Override
  public int getId() {
    return WALRecordTypes.EMPTY_WAL_RECORD;
  }
}
