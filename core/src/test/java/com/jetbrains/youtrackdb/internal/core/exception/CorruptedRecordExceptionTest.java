package com.jetbrains.youtrackdb.internal.core.exception;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import org.junit.Test;

/**
 * Tests {@link CorruptedRecordException} construction and hierarchy. Verifies that the exception
 * extends {@link DatabaseException} and preserves messages through all constructors.
 */
public class CorruptedRecordExceptionTest {

  @Test
  public void extendsDatabaseException() {
    var ex = new CorruptedRecordException("test message");
    assertTrue(ex instanceof DatabaseException);
  }

  @Test
  public void messageOnlyConstructorPreservesMessage() {
    var ex = new CorruptedRecordException("corrupted size: 999999");
    assertEquals("corrupted size: 999999", ex.getMessage());
  }

  @Test
  public void dbNameConstructorPreservesMessage() {
    var ex = new CorruptedRecordException("testdb", "bad header length");
    assertNotNull(ex.getMessage());
    assertTrue(ex.getMessage().contains("bad header length"));
  }

  @Test
  public void copyConstructorPreservesMessage() {
    var original = new CorruptedRecordException("original message");
    var copy = new CorruptedRecordException(original);
    assertNotNull(copy.getMessage());
    assertTrue(copy.getMessage().contains("original message"));
  }

  @Test
  public void sessionConstructorPreservesMessage() {
    // Exercises the CorruptedRecordException(DatabaseSessionEmbedded, String) constructor.
    // Pass null session — the superclass stores it but doesn't dereference at construction time.
    var ex = new CorruptedRecordException(
        (DatabaseSessionEmbedded) null, "corrupt record data");
    assertNotNull(ex.getMessage());
    assertTrue(ex.getMessage().contains("corrupt record data"));
  }
}
