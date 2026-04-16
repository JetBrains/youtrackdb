package com.jetbrains.youtrackdb.internal.common.concur.lock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.exception.SystemException;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import org.junit.Test;

/**
 * Tests for {@link LockException} verifying constructor behavior and exception hierarchy.
 */
public class LockExceptionTest {

  /**
   * Constructor with message preserves the message.
   */
  @Test
  public void testMessageConstructor() {
    var ex = new LockException("lock failed");
    assertEquals("Message should be preserved", "lock failed", ex.getMessage());
  }

  /**
   * Copy constructor preserves the original exception's message.
   */
  @Test
  public void testCopyConstructor() {
    var original = new LockException("original");
    var copy = new LockException(original);
    assertEquals("Copy should preserve message", "original", copy.getMessage());
  }

  /**
   * LockException extends SystemException which extends BaseException (RuntimeException).
   */
  @Test
  public void testExceptionHierarchy() {
    var ex = new LockException("test");
    assertTrue("Should be a SystemException", ex instanceof SystemException);
    assertTrue("Should be a BaseException", ex instanceof BaseException);
    assertTrue("Should be a RuntimeException", ex instanceof RuntimeException);
  }
}
