package com.jetbrains.youtrack.db.internal.core.storage.index.nkbtree.normalizers;

import java.io.IOException;
import java.nio.ByteBuffer;

public class BinaryKeyNormalizer implements KeyNormalizers {

  @Override
  public byte[] execute(Object key, int decomposition) throws IOException {
    final var matKey = (byte[]) key;
    final var bb = ByteBuffer.allocate(1 + matKey.length);
    bb.put((byte) 0);
    bb.put(matKey);
    return bb.array();
  }
}
