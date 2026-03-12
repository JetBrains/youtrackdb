package com.jetbrains.youtrackdb.junit;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * JUnit 5 extension that manages the shared {@link YouTrackDBImpl} instance lifecycle.
 *
 * <p>The instance is created once (before the first test class) and closed after all
 * tests complete, using {@link ExtensionContext.Store.CloseableResource} registered
 * on the root extension context.
 *
 * <p>Also tracks test failures. If any test fails, the memory leak check is skipped
 * on shutdown.
 */
public class SuiteLifecycleExtension implements BeforeAllCallback, TestWatcher {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(SuiteLifecycleExtension.class);
  private static final String STORE_KEY = "youtrackdb-instance";

  /** Tracks whether any test has failed during the suite run. */
  private static volatile boolean anyTestFailed = false;

  @Override
  public void beforeAll(ExtensionContext context) {
    var root = context.getRoot();
    var store = root.getStore(NAMESPACE);
    store.getOrComputeIfAbsent(
        STORE_KEY, k -> new YouTrackDBResource(), YouTrackDBResource.class);
  }

  @Override
  public void testFailed(ExtensionContext context, Throwable cause) {
    anyTestFailed = true;
  }

  /**
   * Returns the shared {@link YouTrackDBImpl} instance. Must be called after the
   * extension has initialized (i.e., after {@code @BeforeAll} has fired).
   */
  static YouTrackDBImpl getYouTrackDB() {
    var inst = YouTrackDBResource.instance;
    if (inst == null || !inst.isOpen()) {
      throw new IllegalStateException(
          "YouTrackDB instance not yet initialized. "
              + "Ensure SuiteLifecycleExtension is active.");
    }
    return inst;
  }

  static class YouTrackDBResource implements ExtensionContext.Store.CloseableResource {

    // Accessible via the static field for simple access from test base classes.
    // Written once in the constructor, read by getYouTrackDB(). volatile ensures
    // visibility. close() is only called after all tests complete.
    static volatile YouTrackDBImpl instance;

    YouTrackDBResource() {
      var buildDirectory = System.getProperty("buildDirectory", ".");
      var config = new BaseConfiguration();
      instance = (YouTrackDBImpl) YourTracks.instance(
          buildDirectory + "/test-db-junit5", config);
    }

    YouTrackDBImpl getInstance() {
      return instance;
    }

    @Override
    public void close() {
      // Close the database instance first
      if (instance != null && instance.isOpen()) {
        instance.close();
        instance = null;
      }

      // Then check for memory leaks (only if no tests failed)
      if (!anyTestFailed) {
        MemoryLeakDetectionExtension.checkForLeaks();
      }
    }
  }
}
