package com.jetbrains.youtrackdb.internal.core.storage.index.nkbtree.normalizers;

import java.io.IOException;

public interface KeyNormalizers {

  byte[] execute(Object key, int decomposition) throws IOException;
}
