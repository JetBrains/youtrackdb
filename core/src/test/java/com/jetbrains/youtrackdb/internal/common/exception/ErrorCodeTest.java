package com.jetbrains.youtrackdb.internal.common.exception;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.core.exception.QueryParsingException;
import org.junit.Test;

/**
 * Tests for {@link ErrorCode} enum — error code mapping, exception instantiation,
 * and the static lookup table.
 */
public class ErrorCodeTest {

  // --- getCode ---

  @Test
  public void testGetCodeReturnsAssignedCode() {
    // Each ErrorCode has a unique integer code assigned in the constructor.
    assertEquals(1, ErrorCode.QUERY_PARSE_ERROR.getCode());
    assertEquals(2, ErrorCode.BACKUP_IN_PROGRESS.getCode());
    assertEquals(3, ErrorCode.MVCC_ERROR.getCode());
    assertEquals(4, ErrorCode.VALIDATION_ERROR.getCode());
    assertEquals(5, ErrorCode.GENERIC_ERROR.getCode());
    assertEquals(6, ErrorCode.LINKS_CONSISTENCY_ERROR.getCode());
  }

  // --- getErrorCode (static lookup) ---

  @Test
  public void testGetErrorCodeValidCodes() {
    // getErrorCode(code) should return the matching ErrorCode for all defined codes.
    assertEquals(ErrorCode.QUERY_PARSE_ERROR, ErrorCode.getErrorCode(1));
    assertEquals(ErrorCode.BACKUP_IN_PROGRESS, ErrorCode.getErrorCode(2));
    assertEquals(ErrorCode.MVCC_ERROR, ErrorCode.getErrorCode(3));
    assertEquals(ErrorCode.VALIDATION_ERROR, ErrorCode.getErrorCode(4));
    assertEquals(ErrorCode.GENERIC_ERROR, ErrorCode.getErrorCode(5));
    assertEquals(ErrorCode.LINKS_CONSISTENCY_ERROR, ErrorCode.getErrorCode(6));
  }

  @Test
  public void testGetErrorCodeZeroReturnsNull() {
    // Code 0 is not assigned to any ErrorCode — the lookup array slot is null.
    assertNull(ErrorCode.getErrorCode(0));
  }

  @Test(expected = ArrayIndexOutOfBoundsException.class)
  public void testGetErrorCodeNegativeThrows() {
    // Negative codes are out of bounds for the lookup array.
    ErrorCode.getErrorCode(-1);
  }

  @Test(expected = ArrayIndexOutOfBoundsException.class)
  public void testGetErrorCodeBeyondMaxThrows() {
    // Code 7+ is beyond the lookup array size (array length is 7, indices 0-6).
    ErrorCode.getErrorCode(7);
  }

  // --- newException: successful path (QUERY_PARSE_ERROR has a String constructor) ---

  @Test
  public void testNewExceptionCreatesCorrectType() {
    // QUERY_PARSE_ERROR uses QueryParsingException which has a String constructor,
    // so newException successfully creates an instance via reflection.
    var exc = ErrorCode.QUERY_PARSE_ERROR.newException("test error", null);
    assertNotNull(exc);
    assertTrue("Expected QueryParsingException", exc instanceof QueryParsingException);
    assertTrue(exc.getMessage().contains("test error"));
  }

  @Test
  public void testNewExceptionFormatsMessageWithCategoryAndCode() {
    // The message format is: "%06d_%06d - %s" (category.code, errorCode, message).
    // ErrorCategory.SQL_PARSING = 3, ErrorCode.QUERY_PARSE_ERROR = 1.
    var exc = ErrorCode.QUERY_PARSE_ERROR.newException("parse failed", null);
    assertNotNull(exc);
    assertTrue("Message should contain formatted prefix",
        exc.getMessage().contains("000003_000001 - parse failed"));
  }

  @Test
  public void testNewExceptionWithParentCauseSetsChain() {
    // When a parent throwable is provided, wrapException sets it as the cause.
    var cause = new RuntimeException("root cause");
    var exc = ErrorCode.QUERY_PARSE_ERROR.newException("with cause", cause);
    assertNotNull(exc);
    assertEquals(cause, exc.getCause());
  }

  // --- newException: reflection failure paths ---

  @Test
  public void testNewExceptionReturnsNullForAbstractBaseException() {
    // VALIDATION_ERROR and GENERIC_ERROR use BaseException.class, which is abstract.
    // Reflection cannot instantiate an abstract class, so newException returns null.
    assertNull(ErrorCode.VALIDATION_ERROR.newException("msg", null));
    assertNull(ErrorCode.GENERIC_ERROR.newException("msg", null));
  }

  @Test
  public void testNewExceptionReturnsNullWhenNoStringConstructor() {
    // BackupInProgressException, ConcurrentModificationException, and
    // LinksConsistencyException lack a (String) constructor, so reflection fails
    // and newException returns null.
    assertNull(ErrorCode.BACKUP_IN_PROGRESS.newException("msg", null));
    assertNull(ErrorCode.MVCC_ERROR.newException("msg", null));
    assertNull(ErrorCode.LINKS_CONSISTENCY_ERROR.newException("msg", null));
  }

  // --- throwException: successful path ---

  @Test
  public void testThrowExceptionNoArgs() {
    // throwException() with no args uses the default description as the message.
    // Message format: "%06d_%06d - %s" (category.code, errorCode, description).
    try {
      ErrorCode.QUERY_PARSE_ERROR.throwException();
      fail("Expected QueryParsingException");
    } catch (QueryParsingException e) {
      assertEquals("000003_000001 - query parse error", e.getMessage());
    }
  }

  @Test
  public void testThrowExceptionWithMessage() {
    // throwException(message) uses the provided custom message.
    try {
      ErrorCode.QUERY_PARSE_ERROR.throwException("custom parse error");
      fail("Expected QueryParsingException");
    } catch (QueryParsingException e) {
      assertEquals("000003_000001 - custom parse error", e.getMessage());
    }
  }

  @Test
  public void testThrowExceptionWithParent() {
    // throwException(parent) uses the default description and sets the cause.
    var cause = new RuntimeException("original");
    try {
      ErrorCode.QUERY_PARSE_ERROR.throwException(cause);
      fail("Expected QueryParsingException");
    } catch (QueryParsingException e) {
      assertEquals(cause, e.getCause());
      assertEquals("000003_000001 - query parse error", e.getMessage());
    }
  }

  @Test
  public void testThrowExceptionWithMessageAndParent() {
    // throwException(message, parent) uses both the custom message and cause.
    var cause = new RuntimeException("cause");
    try {
      ErrorCode.QUERY_PARSE_ERROR.throwException("bad query", cause);
      fail("Expected QueryParsingException");
    } catch (QueryParsingException e) {
      assertEquals("000003_000001 - bad query", e.getMessage());
      assertEquals(cause, e.getCause());
    }
  }

  @Test
  public void testThrowExceptionNpeWhenReflectionFails() {
    // KNOWN LIMITATION: When newException returns null (reflection failure),
    // throwException throws NullPointerException from 'throw null'. This is
    // not intentional error handling — it's a missing null check.
    try {
      ErrorCode.GENERIC_ERROR.throwException();
      fail("Expected NullPointerException from 'throw null'");
    } catch (NullPointerException e) {
      // The JVM generates a descriptive message for 'throw null' (JDK 14+
      // helpful NPE messages). Verify this is the throw-null path, not some
      // other NullPointerException from a missing field or method call.
      assertNotNull("JVM should provide a helpful NPE message", e.getMessage());
      assertTrue("NPE message should indicate a null throw target",
          e.getMessage().contains("null"));
    }
  }

  // --- ErrorCategory ---

  @Test
  public void testErrorCategoryCodes() {
    // Each ErrorCategory has a specific integer code.
    assertEquals(1, ErrorCategory.GENERIC.code);
    assertEquals(2, ErrorCategory.SQL_GENERIC.code);
    assertEquals(3, ErrorCategory.SQL_PARSING.code);
    assertEquals(4, ErrorCategory.STORAGE.code);
    assertEquals(5, ErrorCategory.CONCURRENCY_RETRY.code);
    assertEquals(6, ErrorCategory.VALIDATION.code);
    assertEquals(7, ErrorCategory.CONCURRENCY.code);
  }

  @Test
  public void testAllErrorCategoriesHaveUniqueCodes() {
    // All category codes should be unique.
    var categories = ErrorCategory.values();
    for (var i = 0; i < categories.length; i++) {
      for (var j = i + 1; j < categories.length; j++) {
        assertTrue("Duplicate code between " + categories[i] + " and " + categories[j],
            categories[i].code != categories[j].code);
      }
    }
  }

  @Test
  public void testAllErrorCodesHaveUniqueCodes() {
    // All ErrorCode integer codes should be unique.
    var codes = ErrorCode.values();
    for (var i = 0; i < codes.length; i++) {
      for (var j = i + 1; j < codes.length; j++) {
        assertTrue("Duplicate code between " + codes[i] + " and " + codes[j],
            codes[i].getCode() != codes[j].getCode());
      }
    }
  }
}
