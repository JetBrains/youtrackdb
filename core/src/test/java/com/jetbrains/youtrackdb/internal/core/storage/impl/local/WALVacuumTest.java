package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.Test;

/**
 * Unit tests for {@link WALVacuum}. The class is a thin {@link Runnable} adapter that
 * forwards {@link Runnable#run()} to {@link AbstractStorage#runWALVacuum()} on the
 * configured storage. These tests pin the delegation contract — every {@code run()}
 * triggers exactly one downstream call, and exceptions propagate so the scheduled
 * executor's uncaught-exception handler can record them.
 */
public class WALVacuumTest {

  /**
   * A single {@code run()} invocation must trigger exactly one
   * {@code AbstractStorage.runWALVacuum()} call. Pins the delegation invariant.
   */
  @Test
  public void testRunDelegatesToStorageRunWALVacuum() {
    var storage = mock(AbstractStorage.class);
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
    var storage = mock(AbstractStorage.class);
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
    var storage = mock(AbstractStorage.class);
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
