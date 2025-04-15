package com.jetbrains.youtrack.db.internal.core.storage.index.nkbtree.normalizers;

import java.io.IOException;
import java.nio.ByteBuffer;

public class BooleanKeyNormalizer implements KeyNormalizers {

  @Override
  public byte[] execute(Object key, int decomposition) throws IOException {
    final var bb = ByteBuffer.allocate(1);
    bb.put((Boolean) key ? (byte) 1 : (byte) 0);
    return bb.array();
  }
}
