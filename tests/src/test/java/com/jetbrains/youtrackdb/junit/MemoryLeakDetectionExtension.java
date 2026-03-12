package com.jetbrains.youtrackdb.junit;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;

/**
 * Utility that checks for direct memory leaks after all tests complete,
 * replacing the TestNG {@code TestNGTestListener} (ISuiteListener).
 *
 * <p>Called by {@link SuiteLifecycleExtension.YouTrackDBResource#close()} after
 * the database instance is closed, to ensure correct shutdown ordering.
 */
final class MemoryLeakDetectionExtension {

  private MemoryLeakDetectionExtension() {
  }

  /**
   * Shuts down the engine and checks for direct memory leaks.
   * Mirrors the behavior of {@code TestNGTestListener.onFinish()}.
   */
  static void checkForLeaks() {
    System.out.println(
        "Shutting down engine and checking for direct memory leaks...");
    var youTrack = YouTrackDBEnginesManager.instance();
    if (youTrack != null) {
      // state is verified during shutdown
      youTrack.shutdown();
    } else {
      ByteBufferPool.instance(null).checkMemoryLeaks();
    }
  }
}
