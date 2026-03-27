package com.jetbrains.youtrackdb.internal.core.storage.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Tests for OptimisticReadFailedException — verifies singleton identity and no stack trace
 * (zero-allocation throws on the hot path).
 */
public class OptimisticReadFailedExceptionTest {

  @Test
  public void testSingletonIdentity() {
    // The INSTANCE field should always return the same object.
    assertSame(
        OptimisticReadFailedException.INSTANCE,
        OptimisticReadFailedException.INSTANCE);
  }

  @Test
  public void testNoStackTrace() {
    // Stack trace suppression avoids allocation overhead on the hot path.
    var exception = OptimisticReadFailedException.INSTANCE;
    assertEquals(0, exception.getStackTrace().length);
  }

  @Test
  public void testNoMessage() {
    // Message is null — this is a control-flow signal, not an error.
    assertNull(OptimisticReadFailedException.INSTANCE.getMessage());
  }

  @Test
  public void testNoCause() {
    // No cause — this is a standalone signal.
    assertNull(OptimisticReadFailedException.INSTANCE.getCause());
  }

  @Test
  public void testIsRuntimeException() {
    // Must be a RuntimeException so it can be thrown from lambdas without checked
    // exception declarations.
    assertSame(
        RuntimeException.class,
        OptimisticReadFailedException.class.getSuperclass());
  }
}
