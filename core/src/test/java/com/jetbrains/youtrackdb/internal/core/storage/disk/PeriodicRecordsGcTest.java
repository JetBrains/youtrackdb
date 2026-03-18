package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import org.junit.Test;

/**
 * Unit tests for {@link PeriodicRecordsGc}, verifying that the runnable correctly
 * delegates to {@link AbstractStorage#periodicRecordsGc()} and handles exceptions.
 *
 * <p>Targets the surviving mutant at PeriodicRecordsGc line 25: "removed call to
 * periodicRecordsGc".
 */
public class PeriodicRecordsGcTest {

  // Verifies that run() delegates to storage.periodicRecordsGc().
  // Targets mutant: "removed call to periodicRecordsGc" at line 25.
  @Test
  public void runDelegatesToStoragePeriodicRecordsGc() {
    var storage = mock(AbstractStorage.class);
    var task = new PeriodicRecordsGc(storage);

    task.run();

    verify(storage, times(1)).periodicRecordsGc();
  }

  // Verifies that a RuntimeException from periodicRecordsGc is caught and logged,
  // not propagated (so the scheduled executor doesn't cancel the task).
  @Test
  public void runCatchesRuntimeException() {
    var storage = mock(AbstractStorage.class);
    doThrow(new RuntimeException("test error")).when(storage).periodicRecordsGc();

    var task = new PeriodicRecordsGc(storage);

    // Should not throw
    task.run();

    verify(storage, times(1)).periodicRecordsGc();
  }

  // Verifies that multiple invocations each call periodicRecordsGc.
  @Test
  public void runCanBeCalledMultipleTimes() {
    var storage = mock(AbstractStorage.class);
    var task = new PeriodicRecordsGc(storage);

    task.run();
    task.run();
    task.run();

    verify(storage, times(3)).periodicRecordsGc();
  }
}
