package com.jetbrains.youtrackdb.internal.common.concur.lock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.exception.SystemException;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import org.junit.Test;

/**
 * Tests for {@link ThreadInterruptedException} verifying constructor behavior and exception
 * hierarchy.
 */
public class ThreadInterruptedExceptionTest {

  /**
   * Constructor with message preserves the message.
   */
  @Test
  public void testMessageConstructor() {
    var ex = new ThreadInterruptedException("thread was interrupted");
    assertEquals("Message should be preserved",
        "thread was interrupted", ex.getMessage());
  }

  /**
   * Copy constructor preserves the original exception's message.
   */
  @Test
  public void testCopyConstructor() {
    var original = new ThreadInterruptedException("original");
    var copy = new ThreadInterruptedException(original);
    assertEquals("Copy should preserve message", "original", copy.getMessage());
  }

  /**
   * ThreadInterruptedException extends SystemException which extends BaseException
   * (RuntimeException).
   */
  @Test
  public void testExceptionHierarchy() {
    var ex = new ThreadInterruptedException("test");
    assertTrue("Should be a SystemException", ex instanceof SystemException);
    assertTrue("Should be a BaseException", ex instanceof BaseException);
    assertTrue("Should be a RuntimeException", ex instanceof RuntimeException);
  }
}
