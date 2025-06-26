package com.jetbrains.youtrack.db.internal.core.storage.cache.local.doublewritelog;

import com.jetbrains.youtrack.db.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrack.db.internal.common.directmemory.Pointer;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.annotation.Nullable;

/**
 * Stub for double write log
 */
public class DoubleWriteLogNoOP implements DoubleWriteLog {

  @Override
  public boolean write(ArrayList<ByteBuffer> buffers, IntArrayList fileId, IntArrayList pageIndex) {
    return false;
  }

  @Override
  public void truncate() {
  }

  @Override
  public void open(String storageName, String baseName, Path storagePath, int pageSize) {
  }

  @Nullable
  @Override
  public Pointer loadPage(int fileId, int pageIndex, ByteBufferPool bufferPool) {
    return null;
  }

  @Override
  public void restoreModeOn() throws IOException {
  }

  @Override
  public void restoreModeOff() {
  }

  @Override
  public void close() throws IOException {
  }

  @Override
  public void startCheckpoint() {
  }

  @Override
  public void endCheckpoint() {
  }
}
