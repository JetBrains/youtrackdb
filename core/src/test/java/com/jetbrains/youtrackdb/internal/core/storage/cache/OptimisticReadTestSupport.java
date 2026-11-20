package com.jetbrains.youtrackdb.internal.core.storage.cache;

import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;

/** Identity tokens for tests that allocate frames without a read cache. */
public final class OptimisticReadTestSupport {

  private OptimisticReadTestSupport() {
  }

  public static CacheEntry cacheEntry(PageFrame frame) {
    if (frame == null) {
      return null;
    }
    // A mock pointer avoids republishing coordinates while a stamp-zero test holds the lock.
    var pointer = mock(CachePointer.class, invocation -> {
      if (invocation.getMethod().getName().equals("getPageFrame")) {
        return frame;
      }
      return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    return new CacheEntryImpl(0, 0, pointer, true, null);
  }
}
