package com.jetbrains.youtrack.db.internal.core.storage.index.nkbtree.normalizers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ByteKeyNormalizer implements KeyNormalizers {

  @Override
  public byte[] execute(Object key, int decomposition) throws IOException {
    final var bb = ByteBuffer.allocate(2);
    bb.order(ByteOrder.BIG_ENDIAN);
    bb.put((byte) 0);
    bb.put((byte) ((byte) key + Byte.MAX_VALUE + 1));
    return bb.array();
  }
}
