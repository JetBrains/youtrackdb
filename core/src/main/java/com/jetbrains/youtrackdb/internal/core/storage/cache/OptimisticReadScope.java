package com.jetbrains.youtrackdb.internal.core.storage.cache;

import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import java.util.Arrays;

/**
 * Tracks page frames and their optimistic stamps accumulated during a multi-page read
 * operation (e.g., B-tree traversal). Validation happens both per-page (to catch stale
 * pointers early) and at the end of the operation (to ensure all pages form a consistent
 * snapshot).
 *
 * <p>Stored in {@code AtomicOperation} and reused across optimistic read attempts within
 * the same transaction. {@link #reset()} is called before each attempt.
 *
 * <p>Not thread-safe — each AtomicOperation belongs to a single thread.
 */
public final class OptimisticReadScope {

  private static final int INITIAL_CAPACITY = 8;

  private PageFrame[] frames;
  private long[] stamps;
  private int count;

  public OptimisticReadScope() {
    this.frames = new PageFrame[INITIAL_CAPACITY];
    this.stamps = new long[INITIAL_CAPACITY];
    this.count = 0;
  }

  /**
   * Records a page frame and its optimistic stamp. Called by
   * {@code StorageComponent.loadPageOptimistic()} for each page accessed.
   */
  public void record(PageFrame frame, long stamp) {
    assert frame != null : "PageFrame must not be null";
    assert stamp != 0 : "Stamp must not be zero (exclusive lock was held)";

    if (count == frames.length) {
      grow();
    }
    frames[count] = frame;
    stamps[count] = stamp;
    count++;
  }

  /**
   * Validates all accumulated stamps. Throws {@link OptimisticReadFailedException} if any
   * stamp is invalid (page was evicted or modified).
   */
  public void validateOrThrow() {
    for (int i = 0; i < count; i++) {
      if (!frames[i].validate(stamps[i])) {
        throw OptimisticReadFailedException.INSTANCE;
      }
    }
  }

  /**
   * Validates only the most recently recorded stamp. Used during traversals to catch stale
   * pointers early — before following a child pointer read from a potentially evicted page.
   *
   * @throws OptimisticReadFailedException if the last stamp is invalid
   * @throws IllegalStateException         if no stamps have been recorded
   */
  public void validateLastOrThrow() {
    assert count > 0 : "No stamps recorded — cannot validate last";

    if (!frames[count - 1].validate(stamps[count - 1])) {
      throw OptimisticReadFailedException.INSTANCE;
    }
  }

  /**
   * Resets the scope for reuse. Nulls frame references up to the current count to avoid
   * preventing garbage collection of evicted PageFrames.
   */
  public void reset() {
    // Null out frame references to prevent GC retention
    Arrays.fill(frames, 0, count, null);
    count = 0;
  }

  /**
   * Returns the number of page frames currently tracked.
   */
  public int count() {
    return count;
  }

  /**
   * Returns the page frame at the given index. Used after validation succeeds to
   * record optimistic accesses in the read cache's frequency sketch.
   */
  public PageFrame getFrame(int index) {
    assert index >= 0 && index < count : "Index out of bounds: " + index;
    return frames[index];
  }

  private void grow() {
    int newCapacity = frames.length * 2;
    frames = Arrays.copyOf(frames, newCapacity);
    stamps = Arrays.copyOf(stamps, newCapacity);
  }
}
