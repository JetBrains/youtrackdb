package com.jetbrains.youtrackdb.internal.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.engine.Engine;
import com.jetbrains.youtrackdb.internal.core.shutdown.ShutdownHandler;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests the YouTrackDB engine shutdown handler mechanism.
 */
@Category(SequentialTest.class)
public class YouTrackShutDownTest {

  private int test = 0;

  @Before
  public void before() {
    YouTrackDBEnginesManager.instance().startup();
  }

  @After
  public void after() {
    YouTrackDBEnginesManager.instance().startup();
  }

  @Test
  public void testShutdownHandler() {

    YouTrackDBEnginesManager.instance()
        .addShutdownHandler(
            new ShutdownHandler() {
              @Override
              public int getPriority() {
                return 0;
              }

              @Override
              public void shutdown() throws Exception {
                test += 1;
              }
            });

    YouTrackDBEnginesManager.instance().shutdown();
    assertEquals(1, test);
    YouTrackDBEnginesManager.instance().startup();
    YouTrackDBEnginesManager.instance().shutdown();
    assertEquals(1, test);
  }

  /**
   * Verifies that shutdownEngines() catches and logs exceptions thrown by individual
   * engines without propagating them, so that a single failing engine does not prevent
   * the rest of the shutdown sequence from completing. Registers both a healthy and a
   * failing engine, then asserts that shutdown() was invoked on both regardless of
   * ConcurrentHashMap iteration order.
   *
   * <p>The test engines remain in the singleton's engines map after this test, but
   * they are harmless: after shutdown() they report isRunning()==false, so
   * shutdownEngines() skips them in subsequent calls via the isRunning() guard.
   */
  @Test
  public void testShutdownEnginesCatchesExceptions() {
    var manager = YouTrackDBEnginesManager.instance();
    var failingEngineName = "test-failing-engine";
    var healthyEngineName = "test-healthy-engine";

    // Track whether shutdown() was actually invoked on each engine.
    var failingShutdownCalled = new AtomicBoolean(false);
    var healthyShutdownCalled = new AtomicBoolean(false);

    // Register a healthy engine that shuts down normally.
    manager.registerEngine(
        createTestEngine(healthyEngineName, false, healthyShutdownCalled));
    // Register a failing engine whose shutdown() always throws.
    manager.registerEngine(
        createTestEngine(failingEngineName, true, failingShutdownCalled));

    // If shutdownEngines() fails to catch the exception, manager.shutdown()
    // will throw and the assertions below will never execute, failing this test.
    manager.shutdown();

    // Both engines' shutdown() must have been called, regardless of iteration order.
    assertTrue("Failing engine shutdown() should have been called",
        failingShutdownCalled.get());
    assertTrue("Healthy engine shutdown() should have been called",
        healthyShutdownCalled.get());

    // Both engines should be in non-running state.
    assertFalse("Failing engine should not be running after shutdown",
        manager.getEngine(failingEngineName).isRunning());
    assertFalse("Healthy engine should not be running after shutdown",
        manager.getEngine(healthyEngineName).isRunning());
  }

  /**
   * Creates a test engine stub. If {@code failOnShutdown} is true, the engine's
   * shutdown() throws a RuntimeException after transitioning to the non-running state.
   * The {@code shutdownCalled} flag is set to true when shutdown() is invoked,
   * allowing the caller to verify invocation independently of isRunning() state.
   */
  private static Engine createTestEngine(String name, boolean failOnShutdown,
      AtomicBoolean shutdownCalled) {
    return new Engine() {
      private boolean running = true;

      @Override
      public String getName() {
        return name;
      }

      @Override
      public Storage createStorage(String iURL, long maxWalSegSize,
          long doubleWriteLogMaxSegSize, int storageId,
          YouTrackDBInternalEmbedded context) {
        return null;
      }

      @Override
      public void shutdown() {
        shutdownCalled.set(true);
        running = false;
        if (failOnShutdown) {
          throw new RuntimeException("Simulated engine shutdown failure");
        }
      }

      @Override
      public void startup() {
        running = true;
      }

      @Override
      public String getNameFromPath(String dbPath) {
        return dbPath;
      }

      @Override
      public boolean isRunning() {
        return running;
      }
    };
  }
}
