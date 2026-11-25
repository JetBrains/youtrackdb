package com.jetbrains.youtrackdb.internal.core.storage.cache;

import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import java.nio.ByteBuffer;

/**
 * An immutable view of a cached page obtained via an optimistic read. Contains the page data
 * buffer, the PageFrame reference, and the optimistic stamp for later validation.
 *
 * <p>The buffer contents are speculative — they may change if the page is evicted or modified
 * between stamp acquisition and validation. Callers must validate both cached-frame identity
 * and the stamp through {@link OptimisticReadScope#validateOrThrow()} before trusting the data.
 * {@link #validateStamp()} checks only concurrent modification, not cache membership.
 *
 * @param buffer    the page data buffer (direct memory, read-only view)
 * @param pageFrame the PageFrame holding the native memory and StampedLock
 * @param stamp     the optimistic stamp from {@link PageFrame#tryOptimisticRead()}
 * @param cacheEntry the non-null unpinned cached-frame identity token
 */
public record PageView(ByteBuffer buffer, PageFrame pageFrame, long stamp, CacheEntry cacheEntry) {

  public PageView {
    java.util.Objects.requireNonNull(cacheEntry, "Cache entry must not be null");
    assert buffer != null : "Buffer must not be null";
    assert pageFrame != null : "PageFrame must not be null";
    assert stamp != 0 : "Stamp must not be zero (exclusive lock was held)";
  }

  /**
   * Validates that the stamp is still valid (no exclusive lock has been acquired on the
   * PageFrame since the stamp was obtained).
   *
   * @return true if no writer or frame-pool reuse invalidated this stamp
   */
  public boolean validateStamp() {
    return pageFrame.validate(stamp);
  }
}
