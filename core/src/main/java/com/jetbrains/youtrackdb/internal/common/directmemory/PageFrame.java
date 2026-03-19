package com.jetbrains.youtrackdb.internal.common.directmemory;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.StampedLock;

/**
 * A pooled page-sized direct memory frame with an associated {@link StampedLock}.
 *
 * <p>Frames are recycled via {@link PageFramePool}, not deallocated during normal operation.
 * This guarantees that any Java reference to a PageFrame always points to valid mapped memory
 * (protective memory allocation). Speculative reads may hit stale data, but never unmapped
 * memory.
 *
 * <p>The StampedLock enables three access modes:
 * <ul>
 *   <li><b>Optimistic read</b> — {@link #tryOptimisticRead()} + {@link #validate(long)}.
 *       No CAS, only volatile reads. Used by the pinless read path.</li>
 *   <li><b>Shared lock</b> — {@link #acquireSharedLock()} / {@link #releaseSharedLock(long)}.
 *       Used by the CAS-pinned fallback read path and WOWCache copy-then-verify.</li>
 *   <li><b>Exclusive lock</b> — {@link #acquireExclusiveLock()} /
 *       {@link #releaseExclusiveLock(long)}. Used by write operations, eviction (to invalidate
 *       stamps), and pool acquire/release (stamp invalidation barrier).</li>
 * </ul>
 *
 * <p>Page coordinates ({@code fileId}, {@code pageIndex}) are set under exclusive lock when
 * the frame is assigned to a page. Optimistic readers verify coordinates match the expected
 * page to detect frame reuse.
 */
public final class PageFrame {

  private final Pointer pointer;
  private final StampedLock stampedLock;

  // Page identity — set under exclusive lock when frame is assigned to a page.
  // Non-volatile: must only be read under optimistic stamp, shared lock, or
  // exclusive lock. The StampedLock's memory fence ensures visibility.
  private long fileId;
  private int pageIndex;

  public PageFrame(Pointer pointer) {
    assert pointer != null : "Pointer must not be null";
    this.pointer = pointer;
    this.stampedLock = new StampedLock();
    this.fileId = -1;
    this.pageIndex = -1;
  }

  // --- Optimistic Read API (no CAS) ---

  /**
   * Returns a stamp for later validation, or zero if exclusively locked.
   * This is a volatile read — no CAS operation.
   */
  public long tryOptimisticRead() {
    return stampedLock.tryOptimisticRead();
  }

  /**
   * Returns true if the lock has not been exclusively acquired since the given stamp was
   * obtained.
   */
  public boolean validate(long stamp) {
    return stampedLock.validate(stamp);
  }

  // --- Exclusive Lock API (for writes and eviction) ---

  /**
   * Acquires the exclusive lock, blocking until available. Returns a stamp for use in
   * {@link #releaseExclusiveLock(long)}.
   */
  public long acquireExclusiveLock() {
    return stampedLock.writeLock();
  }

  /**
   * Tries to acquire the exclusive lock without blocking. Returns a non-zero stamp on
   * success (for use in {@link #releaseExclusiveLock(long)}), or zero if the lock could
   * not be acquired immediately (e.g., a shared or exclusive lock is held).
   */
  public long tryAcquireExclusiveLock() {
    return stampedLock.tryWriteLock();
  }

  /**
   * Releases the exclusive lock using the given stamp.
   */
  public void releaseExclusiveLock(long stamp) {
    stampedLock.unlockWrite(stamp);
  }

  // --- Shared Lock API (for CAS-pinned reads that need blocking guarantees) ---

  /**
   * Acquires a shared (read) lock, blocking until available. Returns a stamp for use in
   * {@link #releaseSharedLock(long)}.
   */
  public long acquireSharedLock() {
    return stampedLock.readLock();
  }

  /**
   * Tries to acquire a shared (read) lock without blocking. Returns a non-zero stamp on
   * success (for use in {@link #releaseSharedLock(long)}), or zero if the lock could not
   * be acquired immediately (e.g., an exclusive lock is held).
   */
  public long tryAcquireSharedLock() {
    return stampedLock.tryReadLock();
  }

  /**
   * Releases the shared lock using the given stamp.
   */
  public void releaseSharedLock(long stamp) {
    stampedLock.unlockRead(stamp);
  }

  // --- Page identity ---

  /**
   * Sets the page coordinates for this frame. Must be called under exclusive lock.
   */
  public void setPageCoordinates(long fileId, int pageIndex) {
    assert stampedLock.isWriteLocked() : "Must hold exclusive lock";
    this.fileId = fileId;
    this.pageIndex = pageIndex;
  }

  /**
   * Returns the file ID of the page currently assigned to this frame. Must be read within
   * a stamp-validated region (between {@link #tryOptimisticRead()} and
   * {@link #validate(long)}), or under a shared or exclusive lock.
   */
  public long getFileId() {
    return fileId;
  }

  /**
   * Returns the page index of the page currently assigned to this frame. Must be read within
   * a stamp-validated region (between {@link #tryOptimisticRead()} and
   * {@link #validate(long)}), or under a shared or exclusive lock.
   */
  public int getPageIndex() {
    return pageIndex;
  }

  // --- Memory access ---

  /**
   * Returns the direct ByteBuffer backed by this frame's native memory.
   */
  public ByteBuffer getBuffer() {
    return pointer.getNativeByteBuffer();
  }

  /**
   * Returns the underlying Pointer (native memory handle).
   */
  public Pointer getPointer() {
    return pointer;
  }

  /**
   * Fills the frame's native memory with zeros.
   */
  public void clear() {
    pointer.clear();
  }
}
