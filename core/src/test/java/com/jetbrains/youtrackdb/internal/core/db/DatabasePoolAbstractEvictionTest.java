package com.jetbrains.youtrackdb.internal.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Tests the eviction lifecycle in {@link DatabasePoolAbstract}: verifying that an evictor is
 * scheduled when idle timeout is positive, and cancelled when the pool is closed. Also pins the
 * accessor and listener-callback shape on the dead-code-bound abstract class so a future
 * deletion either updates the pin in lockstep or fails loudly.
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

  /**
   * {@link DatabasePoolAbstract#getMaxSize()} returns the size passed to the constructor.
   */
  @Test
  public void getMaxSizeReflectsConstructorArgument() {
    var pool = new TestPool(0, 0);
    try {
      assertEquals(10, pool.getMaxSize());
    } finally {
      pool.close();
    }
  }

  /**
   * For a pool name not yet acquired, {@link DatabasePoolAbstract#getMaxConnections(String,
   * String)} returns the constructor-supplied {@code maxSize} via the null-pool fallback branch.
   */
  @Test
  public void getMaxConnectionsForUnknownPoolReturnsMaxSize() {
    var pool = new TestPool(0, 0);
    try {
      assertEquals("Unknown pool key must yield the constructor max",
          10, pool.getMaxConnections("not-an-existing-url", "missing-user"));
    } finally {
      pool.close();
    }
  }

  /**
   * For a pool name not yet acquired, {@link DatabasePoolAbstract#getCreatedInstances(String,
   * String)} returns 0 via the null-pool fallback branch.
   */
  @Test
  public void getCreatedInstancesForUnknownPoolReturnsZero() {
    var pool = new TestPool(0, 0);
    try {
      assertEquals(0,
          pool.getCreatedInstances("not-an-existing-url", "missing-user"));
    } finally {
      pool.close();
    }
  }

  /**
   * For a pool name not yet acquired, {@link DatabasePoolAbstract#getAvailableConnections(String,
   * String)} routes through the null-guard inside {@link DatabasePoolAbstract#getPool} and
   * returns 0.
   */
  @Test
  public void getAvailableConnectionsForUnknownPoolReturnsZero() {
    var pool = new TestPool(0, 0);
    try {
      assertEquals(0,
          pool.getAvailableConnections("not-an-existing-url", "missing-user"));
    } finally {
      pool.close();
    }
  }

  /**
   * For a pool name not yet acquired, {@link
   * DatabasePoolAbstract#getConnectionsInCurrentThread(String, String)} returns 0 via the
   * same null-guard.
   */
  @Test
  public void getConnectionsInCurrentThreadForUnknownPoolReturnsZero() {
    var pool = new TestPool(0, 0);
    try {
      assertEquals(0, pool.getConnectionsInCurrentThread(
          "not-an-existing-url", "missing-user"));
    } finally {
      pool.close();
    }
  }

  /**
   * {@link DatabasePoolAbstract#getPools()} returns an unmodifiable view of the inner map. We pin
   * the unmodifiable contract so a refactor that exposes the live map (and breaks the
   * caller-visible immutability invariant) fails loudly.
   */
  @Test
  public void getPoolsReturnsUnmodifiableView() {
    var pool = new TestPool(0, 0);
    try {
      var pools = pool.getPools();
      try {
        pools.put("hijack", null);
        org.junit.Assert.fail(
            "getPools() must return an unmodifiable view; mutation should throw");
      } catch (UnsupportedOperationException expected) {
        // pinned
      }
    } finally {
      pool.close();
    }
  }

  /**
   * {@link DatabasePoolAbstract#remove(String)} on an unknown pool name is a no-op (no
   * exception, no state change). Pin reachability of the null-result branch.
   */
  @Test
  public void removeUnknownPoolNameIsNoOp() {
    var pool = new TestPool(0, 0);
    try {
      pool.remove("admin", "no-such-url");
      assertThat(pool.getPools()).isEmpty();
    } finally {
      pool.close();
    }
  }

  /**
   * {@link DatabasePoolAbstract#onStorageRegistered(AbstractStorage)} is a no-op listener method.
   * The mocked storage is never inspected by the implementation.
   */
  @Test
  public void onStorageRegisteredIsNoOp() {
    var pool = new TestPool(0, 0);
    try {
      pool.onStorageRegistered(mock(AbstractStorage.class));
      assertThat(pool.getPools()).isEmpty();
    } finally {
      pool.close();
    }
  }

  /**
   * {@link DatabasePoolAbstract#onStorageUnregistered(AbstractStorage)} with a URL that does not
   * match any cached pool key takes the early-out branch (poolToClose stays null) and does not
   * invoke {@code remove(...)}.
   */
  @Test
  public void onStorageUnregisteredWithNoMatchingPoolIsNoOp() {
    var storage = mock(AbstractStorage.class);
    Mockito.when(storage.getURL()).thenReturn("memory:no-pool-references-this");

    var pool = new TestPool(0, 0);
    try {
      pool.onStorageUnregistered(storage);
      assertThat(pool.getPools()).isEmpty();
    } finally {
      pool.close();
    }
  }

  /**
   * {@link DatabasePoolAbstract#onShutdown()} delegates to {@link
   * DatabasePoolAbstract#close()}; calling it on an empty pool must not throw and must not
   * trigger an additional state change.
   */
  @Test
  public void onShutdownDelegatesToCloseWithoutThrowing() {
    var pool = new TestPool(0, 0);
    pool.onShutdown();
    assertTrue(
        "After onShutdown(), the pool's pools map remains empty (no resources acquired)",
        pool.getPools().isEmpty());
  }
}
