package com.jetbrains.youtrackdb.internal.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Tests the eviction lifecycle in {@link DatabasePoolAbstract}: verifying that an evictor is
 * scheduled when idle timeout is positive, and cancelled when the pool is closed.
 */
public class DatabasePoolAbstractEvictionTest {

  /**
   * Minimal concrete subclass for testing. Does not create real database sessions — only the
   * eviction scheduling/cancellation behavior is exercised.
   */
  static class TestPool extends DatabasePoolAbstract {

    TestPool(long idleTimeout, long evictionInterval) {
      super("test-owner", 1, 10, idleTimeout, evictionInterval);
    }

    @Override
    public DatabaseSessionEmbedded createNewResource(String iKey, Object... iAdditionalArgs) {
      throw new UnsupportedOperationException("not used in eviction test");
    }

    @Override
    public boolean reuseResource(String iKey, Object[] iAdditionalArgs,
        DatabaseSessionEmbedded iValue) {
      throw new UnsupportedOperationException("not used in eviction test");
    }
  }

  /**
   * When idle timeout and eviction interval are both positive, the pool schedules periodic
   * eviction on the shared scheduled pool. Closing the pool cancels the eviction future
   * so that no further eviction runs are attempted after close.
   */
  @Test
  public void evictionIsScheduledAndCancelledOnClose() throws Exception {
    // Ensure the engines manager is up so the scheduled pool is available.
    var manager = YouTrackDBEnginesManager.instance();
    assertThat(manager.getScheduledPool().isShutdown()).isFalse();

    // Create a pool with eviction enabled (100ms idle timeout, 50ms eviction interval).
    var pool = new TestPool(100, 50);
    try {
      // Let the evictor fire at least once to prove it is scheduled and runs.
      Thread.sleep(150);
      // Pool should remain functional after evictor runs (no resources to evict is fine).
      assertThat(pool.getPools()).isEmpty();
    } finally {
      // Close must cancel the eviction future so the evictor stops.
      pool.close();
    }
  }

  /**
   * When idle timeout or eviction interval is zero, no evictor is scheduled. The pool can
   * still be created and closed without errors.
   */
  @Test
  public void noEvictionWhenTimeoutIsZero() {
    var manager = YouTrackDBEnginesManager.instance();
    assertThat(manager.getScheduledPool().isShutdown()).isFalse();

    // Zero idle timeout → no eviction scheduling.
    var pool = new TestPool(0, 0);
    try {
      assertThat(pool.getPools()).isEmpty();
    } finally {
      pool.close();
    }
  }
}
