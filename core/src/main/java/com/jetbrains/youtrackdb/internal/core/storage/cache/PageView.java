package com.jetbrains.youtrackdb.internal.core.storage.cache;

import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import java.nio.ByteBuffer;

/**
 * An immutable view of a cached page obtained via an optimistic read. Contains the page data
 * buffer, the PageFrame reference, and the optimistic stamp for later validation.
 *
 * <p>The buffer contents are speculative — they may change if the page is evicted or modified
 * between the stamp acquisition and validation. Callers must validate the stamp (via
 * {@link #validateStamp()} or {@link OptimisticReadScope#validateOrThrow()}) before trusting
 * the data.
 *
 * @param buffer    the page data buffer (direct memory, read-only view)
 * @param pageFrame the PageFrame holding the native memory and StampedLock
 * @param stamp     the optimistic stamp from {@link PageFrame#tryOptimisticRead()}
 */
public record PageView(ByteBuffer buffer, PageFrame pageFrame, long stamp) {

  /**
   * Validates that the stamp is still valid (no exclusive lock has been acquired on the
   * PageFrame since the stamp was obtained).
   *
   * @return true if the data read under this stamp is consistent
   */
  public boolean validateStamp() {
    return pageFrame.validate(stamp);
  }
}
