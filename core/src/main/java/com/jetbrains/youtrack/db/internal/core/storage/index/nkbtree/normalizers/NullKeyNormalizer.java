package com.jetbrains.youtrack.db.internal.core.storage.index.nkbtree.normalizers;

import java.io.IOException;
import java.nio.ByteBuffer;

public class NullKeyNormalizer implements KeyNormalizers {

  @Override
  public byte[] execute(Object key, int decomposition) throws IOException {
    final var bb = ByteBuffer.allocate(1);
    bb.put((byte) 1);
    return bb.array();
  }
}
