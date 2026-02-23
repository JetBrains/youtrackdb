package com.jetbrains.youtrackdb.internal.core;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.core.shutdown.ShutdownHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the YouTrackDB engine shutdown handler mechanism.
 */
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
}
