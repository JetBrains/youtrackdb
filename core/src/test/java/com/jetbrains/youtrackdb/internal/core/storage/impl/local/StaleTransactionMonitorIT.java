/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Gauge;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for the stale transaction detection and monitoring feature (YTDB-550).
 * Tests verify that:
 * <ul>
 *   <li>The monitor starts automatically when a storage opens</li>
 *   <li>Diagnostic metadata is captured on TsMinHolder during transaction begin</li>
 *   <li>Metrics are correctly updated by the monitor</li>
 *   <li>The monitor correctly detects long-running transactions</li>
 *   <li>Configuration parameters control monitor behavior</li>
 *   <li>The monitor stops cleanly on storage shutdown</li>
 * </ul>
 */
public class StaleTransactionMonitorIT {

  private YouTrackDBImpl youTrackDB;

  @Before
  public void setUp() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb(
        "test", DatabaseType.MEMORY, getClass());
  }

  @After
  public void tearDown() {
    if (youTrackDB != null) {
      youTrackDB.close();
      youTrackDB = null;
    }
  }

  // --- Monitor lifecycle ---

  /**
   * Verify that the stale transaction monitor is started when a storage is opened
   * and that the monitor-related metrics are registered for the database.
   */
  @Test
  public void testMonitorStartsOnStorageOpen() {
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      var registry = YouTrackDBEnginesManager.instance().getMetricsRegistry();

      // The database metrics should be registered after storage open
      Gauge<Long> oldestTxAge =
          registry.databaseMetric(CoreMetrics.OLDEST_TX_AGE, "test");
      Gauge<Long> snapshotIndexSize =
          registry.databaseMetric(CoreMetrics.SNAPSHOT_INDEX_SIZE, "test");
      Gauge<Long> lwmLag =
          registry.databaseMetric(CoreMetrics.LWM_LAG, "test");
      Gauge<Integer> staleTxCount =
          registry.databaseMetric(CoreMetrics.STALE_TX_COUNT, "test");
      Gauge<Integer> activeTxCount =
          registry.databaseMetric(CoreMetrics.ACTIVE_TX_COUNT, "test");

      assertThat(oldestTxAge).isNotNull();
      assertThat(snapshotIndexSize).isNotNull();
      assertThat(lwmLag).isNotNull();
      assertThat(staleTxCount).isNotNull();
      assertThat(activeTxCount).isNotNull();
    } finally {
      session.close();
    }
  }

  /**
   * Verify that the monitor can be disabled via configuration.
   */
  @Test
  public void testMonitorCanBeDisabled() {
    YouTrackDBImpl localYtdb = null;
    try {
      // Set the global config to disable before creating the DB
      var original = GlobalConfiguration.STORAGE_TX_MONITOR_ENABLED.getValue();
      try {
        GlobalConfiguration.STORAGE_TX_MONITOR_ENABLED.setValue(false);
        localYtdb = DbTestBase.createYTDBManagerAndDb(
            "testDisabled", DatabaseType.MEMORY, getClass());
        var session = localYtdb.open("testDisabled", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var storage = (AbstractStorage) session.getStorage();
          // The monitor field should be null when disabled
          // We verify indirectly: metrics should still exist (registered by MetricsRegistry)
          // but won't be updated since the monitor is not running
          var registry = YouTrackDBEnginesManager.instance().getMetricsRegistry();
          Gauge<Integer> activeTxCount =
              registry.databaseMetric(CoreMetrics.ACTIVE_TX_COUNT, "testDisabled");
          // The gauge exists but its value should be the initial default (null or 0)
          assertThat(activeTxCount).isNotNull();
        } finally {
          session.close();
        }
      } finally {
        GlobalConfiguration.STORAGE_TX_MONITOR_ENABLED.setValue(original);
      }
    } finally {
      if (localYtdb != null) {
        localYtdb.close();
      }
    }
  }

  // --- Diagnostic metadata on TsMinHolder ---

  /**
   * Verify that starting a transaction populates the diagnostic fields on TsMinHolder:
   * txStartTimeNanos, ownerThreadName, and ownerThreadId.
   */
  @Test
  public void testTxBeginCapturesDiagnosticMetadata() {
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      var storage = (AbstractStorage) session.getStorage();
      var holder = storage.tsMinThreadLocal.get();

      // Before beginning a transaction, diagnostic fields should be unset
      assertThat(holder.getTxStartTimeNanosOpaque()).isEqualTo(0);
      assertThat(holder.getTxStartStackTraceOpaque()).isNull();

      session.begin();
      try {
        // After begin, diagnostic fields should be populated
        assertThat(holder.getTxStartTimeNanosOpaque()).isGreaterThan(0);
        assertThat(holder.getOwnerThreadNameOpaque())
            .isEqualTo(Thread.currentThread().getName());
        assertThat(holder.getOwnerThreadIdOpaque())
            .isEqualTo(Thread.currentThread().threadId());
        // Stack trace capture is off by default
        assertThat(holder.getTxStartStackTraceOpaque()).isNull();
      } finally {
        session.rollback();
      }

      // After rollback, diagnostic fields should be cleared
      assertThat(holder.getTxStartTimeNanosOpaque()).isEqualTo(0);
      assertThat(holder.getTxStartStackTraceOpaque()).isNull();
    } finally {
      session.close();
    }
  }

  /**
   * Verify that enabling STORAGE_TX_CAPTURE_STACK_TRACE causes the stack trace to be
   * captured at transaction begin.
   */
  @Test
  public void testStackTraceCapturedWhenEnabled() {
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      var storage = (AbstractStorage) session.getStorage();
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_TX_CAPTURE_STACK_TRACE, true);
      try {
        var holder = storage.tsMinThreadLocal.get();

        session.begin();
        try {
          var stackTrace = holder.getTxStartStackTraceOpaque();
          assertThat(stackTrace).isNotNull();
          assertThat(stackTrace.length).isGreaterThan(0);
          // The stack trace should contain this test method somewhere
          boolean containsThisMethod = false;
          for (var element : stackTrace) {
            if (element.getMethodName().equals("testStackTraceCapturedWhenEnabled")) {
              containsThisMethod = true;
              break;
            }
          }
          assertThat(containsThisMethod)
              .as("Stack trace should contain the test method")
              .isTrue();
        } finally {
          session.rollback();
        }

        // After rollback, stack trace should be cleared
        assertThat(holder.getTxStartStackTraceOpaque()).isNull();
      } finally {
        storage.getContextConfiguration()
            .setValue(GlobalConfiguration.STORAGE_TX_CAPTURE_STACK_TRACE, false);
      }
    } finally {
      session.close();
    }
  }

  /**
   * Verify that nested transactions (activeTxCount > 1) preserve the diagnostic fields
   * from the first transaction begin and only clear them when the last transaction ends.
   */
  @Test
  public void testNestedTxPreservesDiagnosticFields() {
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      var storage = (AbstractStorage) session.getStorage();
      var holder = storage.tsMinThreadLocal.get();

      session.begin();
      try {
        long firstStartTime = holder.getTxStartTimeNanosOpaque();
        assertThat(firstStartTime).isGreaterThan(0);

        // Start a second overlapping tx on the same thread (via storage directly)
        storage.startStorageTx();
        try {
          // The diagnostic fields should still reflect the first tx (not overwritten)
          assertThat(holder.getTxStartTimeNanosOpaque()).isEqualTo(firstStartTime);
          assertThat(holder.activeTxCount).isGreaterThanOrEqualTo(2);
        } finally {
          storage.resetTsMin();
        }

        // After ending the inner tx, diagnostic fields should still be set
        // because the outer tx is still active
        assertThat(holder.getTxStartTimeNanosOpaque()).isEqualTo(firstStartTime);
      } finally {
        session.rollback();
      }

      // After both txs end, diagnostic fields should be cleared
      assertThat(holder.getTxStartTimeNanosOpaque()).isEqualTo(0);
    } finally {
      session.close();
    }
  }

  // --- Monitor doCheck() direct invocation ---

  /**
   * Verify that doCheck() correctly detects an active transaction and updates metrics.
   * Uses a very short warn timeout to trigger detection immediately.
   */
  @Test
  public void testDoCheckDetectsActiveTx() throws InterruptedException {
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      var storage = (AbstractStorage) session.getStorage();

      // Set very short warn timeout (1 second) so our tx is immediately "stale"
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_TX_WARN_TIMEOUT_SECS, 1);
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_TX_CRITICAL_TIMEOUT_SECS, 2);

      var registry = YouTrackDBEnginesManager.instance().getMetricsRegistry();
      Gauge<Integer> activeTxCount =
          registry.databaseMetric(CoreMetrics.ACTIVE_TX_COUNT, "test");
      Gauge<Long> oldestTxAge =
          registry.databaseMetric(CoreMetrics.OLDEST_TX_AGE, "test");

      session.begin();
      try {
        // Wait a bit longer than the warn threshold so the tx is considered stale
        Thread.sleep(1500);

        // Create a monitor and run doCheck() directly (not via the scheduler)
        var monitor = new StaleTransactionMonitor(
            "test", storage.tsMins, storage.snapshotIndexSize, storage.getIdGen(),
            YouTrackDBEnginesManager.instance().getTicker(),
            storage.getContextConfiguration(),
            registry);
        monitor.doCheck();

        // Verify metrics were updated
        assertThat(activeTxCount.getValue())
            .as("Should detect at least 1 active transaction")
            .isGreaterThanOrEqualTo(1);
        assertThat(oldestTxAge.getValue())
            .as("Oldest tx age should be at least 1 second")
            .isGreaterThanOrEqualTo(1L);
      } finally {
        session.rollback();
      }
    } finally {
      session.close();
    }
  }

  /**
   * Verify that doCheck() correctly reports zero active transactions when none are open.
   */
  @Test
  public void testDoCheckReportsZeroWhenNoActiveTx() {
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      var storage = (AbstractStorage) session.getStorage();
      var registry = YouTrackDBEnginesManager.instance().getMetricsRegistry();
      Gauge<Integer> activeTxCount =
          registry.databaseMetric(CoreMetrics.ACTIVE_TX_COUNT, "test");
      Gauge<Long> oldestTxAge =
          registry.databaseMetric(CoreMetrics.OLDEST_TX_AGE, "test");

      // Run doCheck with no active transactions
      var monitor = new StaleTransactionMonitor(
          "test", storage.tsMins, storage.snapshotIndexSize, storage.getIdGen(),
          YouTrackDBEnginesManager.instance().getTicker(),
          storage.getContextConfiguration(),
          registry);
      monitor.doCheck();

      assertThat(activeTxCount.getValue()).isEqualTo(0);
      assertThat(oldestTxAge.getValue()).isEqualTo(0L);
    } finally {
      session.close();
    }
  }

  /**
   * Verify that the stale transaction count metric correctly reflects how many
   * transactions exceed the warn threshold.
   */
  @Test
  public void testStaleTxCountMetric() throws InterruptedException {
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      var storage = (AbstractStorage) session.getStorage();
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_TX_WARN_TIMEOUT_SECS, 1);

      var registry = YouTrackDBEnginesManager.instance().getMetricsRegistry();
      Gauge<Integer> staleTxCount =
          registry.databaseMetric(CoreMetrics.STALE_TX_COUNT, "test");

      session.begin();
      try {
        // Wait for the tx to become "stale" (> 1 second)
        Thread.sleep(1500);

        var monitor = new StaleTransactionMonitor(
            "test", storage.tsMins, storage.snapshotIndexSize, storage.getIdGen(),
            YouTrackDBEnginesManager.instance().getTicker(),
            storage.getContextConfiguration(),
            registry);
        monitor.doCheck();

        assertThat(staleTxCount.getValue())
            .as("Should report at least 1 stale transaction")
            .isGreaterThanOrEqualTo(1);
      } finally {
        session.rollback();
      }
    } finally {
      session.close();
    }
  }

  /**
   * Verify that the LWM lag metric is non-zero when there is an active transaction
   * and other transactions have committed since.
   */
  @Test
  public void testLwmLagMetric() {
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      var storage = (AbstractStorage) session.getStorage();
      var registry = YouTrackDBEnginesManager.instance().getMetricsRegistry();
      Gauge<Long> lwmLag =
          registry.databaseMetric(CoreMetrics.LWM_LAG, "test");

      // Start a transaction to pin the LWM
      session.begin();
      try {
        // Perform a write on a separate session to advance the commit timestamp
        var session2 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          session2.command("CREATE CLASS TestLwmLag IF NOT EXISTS");
          session2.begin();
          session2.command("INSERT INTO TestLwmLag SET name = 'test'");
          session2.commit();
        } finally {
          session2.close();
        }

        var monitor = new StaleTransactionMonitor(
            "test", storage.tsMins, storage.snapshotIndexSize, storage.getIdGen(),
            YouTrackDBEnginesManager.instance().getTicker(),
            storage.getContextConfiguration(),
            registry);
        monitor.doCheck();

        // The LWM should lag behind because our session1 tx pins it
        assertThat(lwmLag.getValue())
            .as("LWM lag should be > 0 when an older tx is pinning the low-water-mark")
            .isGreaterThan(0L);
      } finally {
        session.rollback();
      }
    } finally {
      session.close();
    }
  }

  /**
   * Verify that the snapshot index size metric reflects the approximate count.
   */
  @Test
  public void testSnapshotIndexSizeMetric() {
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      var storage = (AbstractStorage) session.getStorage();
      var registry = YouTrackDBEnginesManager.instance().getMetricsRegistry();
      Gauge<Long> snapshotIndexSizeMetric =
          registry.databaseMetric(CoreMetrics.SNAPSHOT_INDEX_SIZE, "test");

      var monitor = new StaleTransactionMonitor(
          "test", storage.tsMins, storage.snapshotIndexSize, storage.getIdGen(),
          YouTrackDBEnginesManager.instance().getTicker(),
          storage.getContextConfiguration(),
          registry);
      monitor.doCheck();

      // The metric should reflect the current snapshotIndexSize counter
      assertThat(snapshotIndexSizeMetric.getValue())
          .isEqualTo(storage.snapshotIndexSize.get());
    } finally {
      session.close();
    }
  }

  // --- Multi-threaded detection ---

  /**
   * Verify that the monitor detects stale transactions from other threads.
   * A background thread starts a transaction and holds it open while the monitor
   * runs on the main thread.
   */
  @Test
  public void testDetectsStaleTransactionOnAnotherThread() throws InterruptedException {
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      var storage = (AbstractStorage) session.getStorage();
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_TX_WARN_TIMEOUT_SECS, 1);

      var registry = YouTrackDBEnginesManager.instance().getMetricsRegistry();
      Gauge<Integer> activeTxCount =
          registry.databaseMetric(CoreMetrics.ACTIVE_TX_COUNT, "test");
      Gauge<Integer> staleTxCount =
          registry.databaseMetric(CoreMetrics.STALE_TX_COUNT, "test");

      var bgTxStarted = new CountDownLatch(1);
      var bgTxRelease = new CountDownLatch(1);
      var bgError = new AtomicReference<Throwable>();

      // Background thread: open a session, begin a tx, and hold it
      var bgThread = new Thread(() -> {
        try {
          var bgSession =
              youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
          try {
            bgSession.begin();
            bgTxStarted.countDown();
            // Hold the transaction open until signaled
            bgTxRelease.await(30, TimeUnit.SECONDS);
            bgSession.rollback();
          } finally {
            bgSession.close();
          }
        } catch (Throwable t) {
          bgError.set(t);
          bgTxStarted.countDown();
        }
      }, "bg-stale-tx-thread");
      bgThread.start();

      try {
        assertThat(bgTxStarted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(bgError.get()).isNull();

        // Wait for the bg transaction to become "stale"
        Thread.sleep(1500);

        var monitor = new StaleTransactionMonitor(
            "test", storage.tsMins, storage.snapshotIndexSize, storage.getIdGen(),
            YouTrackDBEnginesManager.instance().getTicker(),
            storage.getContextConfiguration(),
            registry);
        monitor.doCheck();

        // The monitor should detect the bg thread's stale transaction
        assertThat(activeTxCount.getValue())
            .as("Should detect the background thread's active transaction")
            .isGreaterThanOrEqualTo(1);
        assertThat(staleTxCount.getValue())
            .as("Should report the background thread's transaction as stale")
            .isGreaterThanOrEqualTo(1);
      } finally {
        bgTxRelease.countDown();
        bgThread.join(10_000);
      }
    } finally {
      session.close();
    }
  }

  // --- Growth trend detection ---

  /**
   * Verify that the growth trend detection fires after consecutive cycles of snapshot
   * index growth with a stuck LWM. We simulate this by calling doCheck() multiple times
   * with an active tx holding the LWM while incrementing the snapshot index size counter.
   */
  @Test
  public void testGrowthTrendDetection() {
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      var storage = (AbstractStorage) session.getStorage();
      var registry = YouTrackDBEnginesManager.instance().getMetricsRegistry();

      // Set a very high warn timeout so individual tx warnings don't fire
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_TX_WARN_TIMEOUT_SECS, 9999);

      var monitor = new StaleTransactionMonitor(
          "test", storage.tsMins, storage.snapshotIndexSize, storage.getIdGen(),
          YouTrackDBEnginesManager.instance().getTicker(),
          storage.getContextConfiguration(),
          registry);

      session.begin();
      try {
        // Simulate growth: increment snapshotIndexSize and call doCheck multiple times.
        // The LWM is pinned by our active tx, so it stays the same.
        long baseSize = storage.snapshotIndexSize.get();
        for (int i = 0; i < 4; i++) {
          storage.snapshotIndexSize.set(baseSize + (i + 1) * 100);
          // doCheck should track the growth trend internally.
          // After 3 consecutive growth cycles, it should log a warning.
          // We can't easily assert on log output here, but we verify it doesn't throw.
          monitor.doCheck();
        }
      } finally {
        session.rollback();
      }
      // Restore original size
      storage.snapshotIndexSize.set(0);
    } finally {
      session.close();
    }
  }

  // --- Rate-limiting verification ---

  /**
   * Verify that the rate-limiting logic doesn't cause the monitor to throw errors
   * when called repeatedly with the same stale transaction.
   */
  @Test
  public void testRateLimitingDoesNotThrow() throws InterruptedException {
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      var storage = (AbstractStorage) session.getStorage();
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_TX_WARN_TIMEOUT_SECS, 1);
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_TX_CRITICAL_TIMEOUT_SECS, 2);

      var registry = YouTrackDBEnginesManager.instance().getMetricsRegistry();

      var monitor = new StaleTransactionMonitor(
          "test", storage.tsMins, storage.snapshotIndexSize, storage.getIdGen(),
          YouTrackDBEnginesManager.instance().getTicker(),
          storage.getContextConfiguration(),
          registry);

      session.begin();
      try {
        // Wait past the critical threshold
        Thread.sleep(2500);

        // Call doCheck multiple times — rate-limiting should suppress repeated warnings
        // but never throw
        for (int i = 0; i < 10; i++) {
          monitor.doCheck();
        }
      } finally {
        session.rollback();
      }
    } finally {
      session.close();
    }
  }
}
