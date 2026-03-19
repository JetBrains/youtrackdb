/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.storage.cache;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFramePool;
import com.jetbrains.youtrackdb.internal.common.directmemory.Pointer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import javax.annotation.Nullable;

/**
 * Reference-counted pointer to a cached page in the disk cache.
 *
 * @since 05.08.13
 */
public final class CachePointer {

  private static final AtomicIntegerFieldUpdater<CachePointer> REFERRERS_COUNT_UPDATER;
  private static final AtomicLongFieldUpdater<CachePointer> READERS_WRITERS_REFERRER_UPDATER;

  static {
    REFERRERS_COUNT_UPDATER =
        AtomicIntegerFieldUpdater.newUpdater(CachePointer.class, "referrersCount");
    READERS_WRITERS_REFERRER_UPDATER =
        AtomicLongFieldUpdater.newUpdater(CachePointer.class, "readersWritersReferrer");
  }

  private static final int WRITERS_OFFSET = 32;
  private static final long READERS_MASK = 0xFFFFFFFFL;

  private volatile int referrersCount;

  @SuppressWarnings("UnusedVariable")
  private volatile long readersWritersReferrer;

  private volatile WritersListener writersListener;

  private final Pointer pointer;
  private final ByteBufferPool bufferPool;

  // PageFrame-based fields (null when using legacy Pointer+ByteBufferPool constructor)
  @Nullable private final PageFrame pageFrame;
  @Nullable private final PageFramePool framePool;

  private long version;

  private final long fileId;
  private final int pageIndex;

  private volatile LogSequenceNumber endLSN;

  private int hash;

  public CachePointer(
      final Pointer pointer,
      final ByteBufferPool bufferPool,
      final long fileId,
      final int pageIndex) {
    this.pointer = pointer;
    this.bufferPool = bufferPool;
    this.pageFrame = null;
    this.framePool = null;

    if (fileId < 0) {
      throw new IllegalStateException("File id has invalid value " + fileId);
    }

    if (pageIndex < 0) {
      throw new IllegalStateException("Page index has invalid value " + pageIndex);
    }

    this.fileId = fileId;
    this.pageIndex = pageIndex;
  }

  /**
   * Creates a CachePointer backed by a {@link PageFrame}. The pointer is derived from the
   * PageFrame's underlying Pointer. When {@code referrersCount} reaches 0, the frame is
   * released back to the {@link PageFramePool} instead of the ByteBufferPool.
   *
   * <p>The {@code pageFrame} and {@code framePool} parameters may both be {@code null} for
   * sentinel CachePointers (e.g., in AtomicOperationBinaryTracking for metadata-only entries).
   *
   * @param pageFrame the page frame wrapping native memory (nullable for sentinel)
   * @param framePool the pool to return the frame to on release (nullable for sentinel)
   * @param fileId    file identifier (must be >= 0)
   * @param pageIndex page index within file (must be >= 0)
   */
  public CachePointer(
      @Nullable final PageFrame pageFrame,
      @Nullable final PageFramePool framePool,
      final long fileId,
      final int pageIndex) {
    if ((pageFrame == null) != (framePool == null)) {
      throw new IllegalArgumentException(
          "pageFrame and framePool must both be null or both non-null");
    }

    this.pointer = pageFrame != null ? pageFrame.getPointer() : null;
    this.bufferPool = null;
    this.pageFrame = pageFrame;
    this.framePool = framePool;

    if (fileId < 0) {
      throw new IllegalStateException("File id has invalid value " + fileId);
    }

    if (pageIndex < 0) {
      throw new IllegalStateException("Page index has invalid value " + pageIndex);
    }

    this.fileId = fileId;
    this.pageIndex = pageIndex;
  }

  public void setWritersListener(WritersListener writersListener) {
    this.writersListener = writersListener;
  }

  public long getFileId() {
    return fileId;
  }

  public int getPageIndex() {
    return pageIndex;
  }

  public void incrementReadersReferrer() {
    var readersWriters = READERS_WRITERS_REFERRER_UPDATER.get(this);
    var readers = getReaders(readersWriters);
    var writers = getWriters(readersWriters);
    readers++;

    while (!READERS_WRITERS_REFERRER_UPDATER.compareAndSet(
        this, readersWriters, composeReadersWriters(readers, writers))) {
      readersWriters = READERS_WRITERS_REFERRER_UPDATER.get(this);
      readers = getReaders(readersWriters);
      writers = getWriters(readersWriters);
      readers++;
    }

    final var wl = writersListener;
    if (wl != null) {
      if (writers > 0 && readers == 1) {
        wl.removeOnlyWriters(fileId, pageIndex);
      }
    }

    incrementReferrer();
  }

  public void decrementReadersReferrer() {
    var readersWriters = READERS_WRITERS_REFERRER_UPDATER.get(this);
    var readers = getReaders(readersWriters);
    var writers = getWriters(readersWriters);
    readers--;

    assert readers >= 0;

    while (!READERS_WRITERS_REFERRER_UPDATER.compareAndSet(
        this, readersWriters, composeReadersWriters(readers, writers))) {
      readersWriters = READERS_WRITERS_REFERRER_UPDATER.get(this);
      readers = getReaders(readersWriters);
      writers = getWriters(readersWriters);

      if (readers == 0) {
        throw new IllegalStateException(
            "Invalid direct memory state, number of readers cannot be zero " + readers);
      }

      readers--;
      assert readers >= 0;
    }

    final var wl = writersListener;
    if (wl != null) {
      if (writers > 0 && readers == 0) {
        wl.addOnlyWriters(fileId, pageIndex);
      }
    }

    decrementReferrer();
  }

  public void incrementWritersReferrer() {
    var readersWriters = READERS_WRITERS_REFERRER_UPDATER.get(this);
    var readers = getReaders(readersWriters);
    var writers = getWriters(readersWriters);
    writers++;

    while (!READERS_WRITERS_REFERRER_UPDATER.compareAndSet(
        this, readersWriters, composeReadersWriters(readers, writers))) {
      readersWriters = READERS_WRITERS_REFERRER_UPDATER.get(this);
      readers = getReaders(readersWriters);
      writers = getWriters(readersWriters);
      writers++;
    }

    incrementReferrer();
  }

  public void decrementWritersReferrer() {
    var readersWriters = READERS_WRITERS_REFERRER_UPDATER.get(this);
    var readers = getReaders(readersWriters);
    var writers = getWriters(readersWriters);
    writers--;

    assert writers >= 0;

    while (!READERS_WRITERS_REFERRER_UPDATER.compareAndSet(
        this, readersWriters, composeReadersWriters(readers, writers))) {
      readersWriters = READERS_WRITERS_REFERRER_UPDATER.get(this);
      readers = getReaders(readersWriters);
      writers = getWriters(readersWriters);

      if (writers == 0) {
        throw new IllegalStateException(
            "Invalid direct memory state, number of writers cannot be zero " + writers);
      }

      writers--;

      assert writers >= 0;
    }

    final var wl = writersListener;
    if (wl != null) {
      if (readers == 0 && writers == 0) {
        wl.removeOnlyWriters(fileId, pageIndex);
      }
    }

    decrementReferrer();
  }

  public void incrementReferrer() {
    REFERRERS_COUNT_UPDATER.incrementAndGet(this);
  }

  public void decrementReferrer() {
    final var rf = REFERRERS_COUNT_UPDATER.decrementAndGet(this);
    if (rf == 0 && pointer != null) {
      if (pageFrame != null && framePool != null) {
        framePool.release(pageFrame);
      } else if (bufferPool != null) {
        bufferPool.release(pointer);
      }
    }

    if (rf < 0) {
      throw new IllegalStateException(
          "Invalid direct memory state, number of referrers cannot be negative " + rf);
    }
  }

  @Nullable public ByteBuffer getBuffer() {
    if (pointer == null) {
      return null;
    }

    return pointer.getNativeByteBuffer();
  }

  public Pointer getPointer() {
    return pointer;
  }

  /**
   * Returns the underlying PageFrame, or {@code null} if this CachePointer was created
   * with the legacy Pointer+ByteBufferPool constructor or is a sentinel.
   */
  @Nullable public PageFrame getPageFrame() {
    return pageFrame;
  }

  /**
   * Acquires the exclusive lock on the underlying PageFrame, blocking until available.
   * Returns a stamp for use in {@link #releaseExclusiveLock(long)}.
   *
   * @throws IllegalStateException if this is a sentinel CachePointer (null PageFrame)
   */
  public long acquireExclusiveLock() {
    if (pageFrame == null) {
      throw new IllegalStateException("Lock on sentinel CachePointer");
    }
    long stamp = pageFrame.acquireExclusiveLock();
    version++;
    return stamp;
  }

  public long getVersion() {
    return version;
  }

  /**
   * Releases the exclusive lock using the given stamp.
   *
   * @throws IllegalStateException if this is a sentinel CachePointer (null PageFrame)
   */
  public void releaseExclusiveLock(long stamp) {
    if (pageFrame == null) {
      throw new IllegalStateException("Lock on sentinel CachePointer");
    }
    pageFrame.releaseExclusiveLock(stamp);
  }

  /**
   * Acquires a shared (read) lock on the underlying PageFrame, blocking until available.
   * Returns a stamp for use in {@link #releaseSharedLock(long)}.
   *
   * @throws IllegalStateException if this is a sentinel CachePointer (null PageFrame)
   */
  public long acquireSharedLock() {
    if (pageFrame == null) {
      throw new IllegalStateException("Lock on sentinel CachePointer");
    }
    return pageFrame.acquireSharedLock();
  }

  /**
   * Releases the shared lock using the given stamp.
   *
   * @throws IllegalStateException if this is a sentinel CachePointer (null PageFrame)
   */
  public void releaseSharedLock(long stamp) {
    if (pageFrame == null) {
      throw new IllegalStateException("Lock on sentinel CachePointer");
    }
    pageFrame.releaseSharedLock(stamp);
  }

  /**
   * Tries to acquire a shared (read) lock without blocking. Returns a non-zero stamp on
   * success, or zero if the lock could not be acquired immediately.
   *
   * @throws IllegalStateException if this is a sentinel CachePointer (null PageFrame)
   */
  public long tryAcquireSharedLock() {
    if (pageFrame == null) {
      throw new IllegalStateException("Lock on sentinel CachePointer");
    }
    return pageFrame.tryAcquireSharedLock();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    var that = (CachePointer) o;

    if (fileId != that.fileId) {
      return false;
    }
    return pageIndex == that.pageIndex;
  }

  @Override
  public int hashCode() {
    if (hash != 0) {
      return hash;
    }

    var result = (int) (fileId ^ (fileId >>> 32));
    result = 31 * result + pageIndex;

    hash = result;

    return hash;
  }

  @Override
  public String toString() {
    return "CachePointer{" + "referrersCount=" + referrersCount + '}';
  }

  private static long composeReadersWriters(int readers, int writers) {
    return ((long) writers) << WRITERS_OFFSET | readers;
  }

  private static int getReaders(long readersWriters) {
    return (int) (readersWriters & READERS_MASK);
  }

  private static int getWriters(long readersWriters) {
    return (int) (readersWriters >>> WRITERS_OFFSET);
  }

  public interface WritersListener {

    void addOnlyWriters(long fileId, long pageIndex);

    void removeOnlyWriters(long fileId, long pageIndex);
  }

  public LogSequenceNumber getEndLSN() {
    return endLSN;
  }

  void setEndLSN(LogSequenceNumber endLSN) {
    this.endLSN = endLSN;
  }
}
