package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.MemoryWriteAheadLog;
import java.util.UUID;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link WALVacuum}. The class is a thin {@link Runnable} adapter that
 * forwards {@link Runnable#run()} to {@link AbstractStorage#runWALVacuum()} on the
 * configured storage. These tests pin the delegation contract — every {@code run()}
 * triggers exactly one downstream call, and exceptions propagate so the scheduled
 * executor's uncaught-exception handler can record them.
 */
public class WALVacuumTest {

  private String databaseName;

  @Before
  public void startMetrics() {
    YouTrackDBEnginesManager.startUp(false);
    databaseName = "vacuumMetrics" + UUID.randomUUID();
  }

  private AbstractStorage storage() {
    var storage = mock(AbstractStorage.class);
    when(storage.getName()).thenReturn(databaseName);
    return storage;
  }

  /** Memory tasks count and accumulate time even though they have no segment files to remove. */
  @Test
  public void memoryStorageCountsRunsWithoutRemovals() throws Exception {
    var storage = mock(AbstractStorage.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    storage.writeAheadLog = new MemoryWriteAheadLog();
    var registry = YouTrackDBEnginesManager.instance().getMetricsRegistry();
    var runs = registry.databaseMetric(CoreMetrics.WAL_VACUUM_RUN_COUNT, databaseName);
    var time = registry.databaseMetric(CoreMetrics.WAL_VACUUM_TIME_NANOS, databaseName);
    var removals = registry.databaseMetric(CoreMetrics.WAL_VACUUM_REMOVAL_RUN_COUNT, databaseName);
    setField(storage, "walVacuumRunCount", runs);
    setField(storage, "walVacuumTimeNanos", time);
    setField(storage, "walVacuumRemovalRunCount", removals);
    setField(storage, "stateLock",
        new com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock());
    setField(storage, "walVacuumInProgress", new java.util.concurrent.atomic.AtomicBoolean());
    new WALVacuum(storage).run();
    assertThat(runs.getValue()).isEqualTo(1);
    assertThat(time.getValue()).isPositive();
    assertThat(removals.getValue()).isZero();
  }

  private static void setField(AbstractStorage storage, String name, Object value)
      throws Exception {
    var field = AbstractStorage.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(storage, value);
  }

  /**
   * A single {@code run()} invocation must trigger exactly one
   * {@code AbstractStorage.runWALVacuum()} call. Pins the delegation invariant.
   */
  @Test
  public void testRunDelegatesToStorageRunWALVacuum() {
    var storage = storage();
    doNothing().when(storage).runWALVacuum();
    var vacuum = new WALVacuum(storage);

    vacuum.run();

    verify(storage, times(1)).runWALVacuum();
  }

  /**
   * Multiple {@code run()} calls must produce one delegated call each.
   */
  @Test
  public void testRunDelegatesEachInvocation() {
    var storage = storage();
    doNothing().when(storage).runWALVacuum();
    var vacuum = new WALVacuum(storage);

    vacuum.run();
    vacuum.run();
    vacuum.run();

    verify(storage, times(3)).runWALVacuum();
  }

  /**
   * If {@code AbstractStorage.runWALVacuum()} throws a runtime exception, the {@code run()}
   * method must propagate it. The scheduled executor service catches and reports such
   * failures via its uncaught-exception handler — swallowing the exception in the adapter
   * would mask underlying failures and break that contract.
   */
  @Test
  public void testRunPropagatesRuntimeException() {
    var storage = storage();
    var failure = new IllegalStateException("storage failure under test");
    doThrow(failure).when(storage).runWALVacuum();
    var vacuum = new WALVacuum(storage);

    Throwable caught = null;
    try {
      vacuum.run();
    } catch (RuntimeException e) {
      caught = e;
    }

    assertThat(caught).as("runtime exception from runWALVacuum must propagate").isSameAs(failure);
    verify(storage, atLeastOnce()).runWALVacuum();
  }
}
