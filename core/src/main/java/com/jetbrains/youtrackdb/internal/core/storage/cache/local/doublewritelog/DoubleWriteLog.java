package com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.Pointer;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Interface for the log which keeps data of pages before they will be finally fsync-ed to the data
 * files. This log is used to implement double write pattern.
 *
 * <p>At the first step we perform fsync of the data to the log using single sequential write
 * calling {@link #write(ArrayList, IntArrayList, IntArrayList)} method.
 *
 * <p>As the second step we write pages to the data files.
 *
 * <p>At third step we fsync data files in background process and then truncate log calling {@link
 * #truncate(Set)} method. Write to the file and truncation of data should be done in single lock to
 * prevent situation when data are written to the log but not to the pages and then truncated. That
 * is typically not a problem because write cache uses single thread model.
 *
 * <p>If during the write of pages we reach log threshold {@link #write(ArrayList, IntArrayList,
 * IntArrayList)} method requests synchronization after an uncovered rotation.
 * A successful flush-executor synchronization acknowledges the returned token before cleanup.
 */
public interface DoubleWriteLog {

  /** The segment is null for a no-operation log. The token covers rotations before this append. */
  record AppendResult(@Nullable Long segment, boolean syncRequired, long syncToken) {
  }

  AppendResult write(ArrayList<ByteBuffer> buffers, IntArrayList fileId, IntArrayList pageIndex)
      throws IOException;

  default void truncate() throws IOException {
    truncate(Set.of());
  }

  /** Deletes only non-active segments not retained by the caller. Suppressed in restore mode. */
  void truncate(Set<Long> retainedSegments) throws IOException;

  /**
   * Stages coverage after successful synchronization on the serial flush executor. Coverage is
   * published by cleanup outside restore mode, so a skipped cleanup leaves its request pending.
   */
  void acknowledgeSync(long syncToken);

  void open(String storageName, String baseName, Path storagePath, int pageSize) throws IOException;

  Pointer loadPage(final int fileId, final int pageIndex, ByteBufferPool bufferPool)
      throws IOException;

  void restoreModeOn() throws IOException;

  void restoreModeOff();

  void close() throws IOException;

  /** Releases failed-open resources without removing segments needed by the next recovery. */
  default void closeAfterFailedOpen() throws IOException {
    close();
  }

  void startCheckpoint() throws IOException;

  void endCheckpoint();
}
