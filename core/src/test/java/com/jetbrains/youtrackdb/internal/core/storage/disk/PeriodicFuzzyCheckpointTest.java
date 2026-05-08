package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.Test;

/**
 * Unit tests for {@link PeriodicFuzzyCheckpoint}, verifying that the runnable correctly
 * delegates to {@link DiskStorage#makeFuzzyCheckpoint()} and handles exceptions.
 *
 * <p>Parallels {@link PeriodicRecordsGcTest}: both tasks use the same
 * catch-log-swallow pattern for {@code RuntimeException} so the scheduled
 * executor never cancels the task on a transient failure.
 */
public class PeriodicFuzzyCheckpointTest {

  // Verifies that run() delegates to storage.makeFuzzyCheckpoint().
  @Test
  public void runDelegatesToStorageMakeFuzzyCheckpoint() {
    var storage = mock(DiskStorage.class);
    var task = new PeriodicFuzzyCheckpoint(storage);

    task.run();

    verify(storage, times(1)).makeFuzzyCheckpoint();
  }

  // Verifies that a RuntimeException thrown by makeFuzzyCheckpoint is caught and
  // logged rather than propagated, so the scheduled executor keeps the task alive.
  @Test
  public void runCatchesRuntimeException() {
    var storage = mock(DiskStorage.class);
    doThrow(new RuntimeException("simulated checkpoint failure")).when(storage)
        .makeFuzzyCheckpoint();

    var task = new PeriodicFuzzyCheckpoint(storage);

    // Must not throw — exception is caught inside run()
    task.run();

    verify(storage, times(1)).makeFuzzyCheckpoint();
  }

  // Verifies that repeated invocations each delegate to makeFuzzyCheckpoint.
  @Test
  public void runCanBeCalledMultipleTimes() {
    var storage = mock(DiskStorage.class);
    var task = new PeriodicFuzzyCheckpoint(storage);

    task.run();
    task.run();
    task.run();

    verify(storage, times(3)).makeFuzzyCheckpoint();
  }
}
