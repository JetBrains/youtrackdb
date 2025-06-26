package com.jetbrains.youtrack.db.internal.core.storage.index.nkbtree.normalizers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Date;

public class DateTimeKeyNormalizer implements KeyNormalizers {

  @Override
  public byte[] execute(Object key, int decomposition) throws IOException {
    final var bb = ByteBuffer.allocate(9);
    bb.order(ByteOrder.BIG_ENDIAN);
    bb.put((byte) 0);
    bb.putLong(((Date) key).getTime());
    return bb.array();
  }
}
