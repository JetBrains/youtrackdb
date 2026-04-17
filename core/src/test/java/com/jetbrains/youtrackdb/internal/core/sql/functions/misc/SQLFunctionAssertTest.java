/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.functions.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionAssert} — the Java-{@code assert}-backed SQL function.
 *
 * <p>Core module tests run with {@code -ea} (see {@code core/pom.xml} argLine), so the
 * {@code assert result : message} statement actually fires on a falsy condition. This means
 * this test class verifies BOTH switch dispatch and the assert-fires-on-false behaviour.
 *
 * <p>Condition dispatch: {@link Boolean}, {@link String} (Boolean.parseBoolean), {@link Number}
 * ({@code intValue() > 0}), null / other → {@link CommandExecutionException} with a pinned
 * "Unsupported condition type" substring.
 */
public class SQLFunctionAssertTest {

  @Test
  public void booleanTrueReturnsTrueAndDoesNotThrow() {
    final var fn = new SQLFunctionAssert();
    assertEquals(true,
        fn.execute(null, null, null, new Object[] {Boolean.TRUE}, new BasicCommandContext()));
  }

  @Test
  public void stringTrueIsParsedAsTrue() {
    final var fn = new SQLFunctionAssert();
    assertEquals(true,
        fn.execute(null, null, null, new Object[] {"true"}, new BasicCommandContext()));
    assertEquals(true,
        fn.execute(null, null, null, new Object[] {"TRUE"}, new BasicCommandContext()));
  }

  @Test
  public void positiveNumberIsTrue() {
    final var fn = new SQLFunctionAssert();
    assertEquals(true,
        fn.execute(null, null, null, new Object[] {1}, new BasicCommandContext()));
    assertEquals(true,
        fn.execute(null, null, null, new Object[] {42L}, new BasicCommandContext()));
    // Double > 0 — intValue() stays > 0.
    assertEquals(true,
        fn.execute(null, null, null, new Object[] {1.9}, new BasicCommandContext()));
  }

  @Test
  public void booleanFalseTriggersAssertionError() {
    // -ea is enabled in the test JVM (core/pom.xml). `assert result : message` throws on false.
    final var fn = new SQLFunctionAssert();
    try {
      fn.execute(null, null, null, new Object[] {Boolean.FALSE}, new BasicCommandContext());
      fail("Expected AssertionError");
    } catch (AssertionError e) {
      // Message is the default empty string when only 1 param was given.
      assertEquals("", String.valueOf(e.getMessage()));
    }
  }

  @Test
  public void booleanFalseWithMessageAttachesMessageToAssertionError() {
    final var fn = new SQLFunctionAssert();
    try {
      fn.execute(null, null, null, new Object[] {Boolean.FALSE, "value must be positive"},
          new BasicCommandContext());
      fail("Expected AssertionError");
    } catch (AssertionError e) {
      assertEquals("value must be positive", e.getMessage());
    }
  }

  @Test
  public void zeroNumberIsFalseAndThrowsAssertionError() {
    final var fn = new SQLFunctionAssert();
    try {
      fn.execute(null, null, null, new Object[] {0, "nope"}, new BasicCommandContext());
      fail("Expected AssertionError");
    } catch (AssertionError e) {
      assertEquals("nope", e.getMessage());
    }
  }

  @Test
  public void negativeNumberIsFalseAndThrowsAssertionError() {
    final var fn = new SQLFunctionAssert();
    try {
      fn.execute(null, null, null, new Object[] {-3}, new BasicCommandContext());
      fail("Expected AssertionError");
    } catch (AssertionError e) {
      // expected
    }
  }

  @Test
  public void stringNonTrueIsParsedAsFalseAndThrowsAssertionError() {
    final var fn = new SQLFunctionAssert();
    try {
      fn.execute(null, null, null, new Object[] {"false"}, new BasicCommandContext());
      fail("Expected AssertionError");
    } catch (AssertionError e) {
      // expected
    }
  }

  @Test
  public void nullConditionThrowsCommandExecutionException() {
    final var fn = new SQLFunctionAssert();
    try {
      fn.execute(null, null, null, new Object[] {null}, new BasicCommandContext());
      fail("Expected CommandExecutionException");
    } catch (CommandExecutionException e) {
      assertTrue("message should name the condition type, was: " + e.getMessage(),
          e.getMessage().startsWith("Unsupported condition type: "));
      // null.toString() in String concat produces "null".
      assertTrue(e.getMessage().endsWith("null"));
    }
  }

  @Test
  public void unsupportedConditionTypeThrowsCommandExecutionException() {
    final var fn = new SQLFunctionAssert();
    try {
      fn.execute(null, null, null, new Object[] {new Object()}, new BasicCommandContext());
      fail("Expected CommandExecutionException");
    } catch (CommandExecutionException e) {
      assertTrue(e.getMessage().startsWith("Unsupported condition type: "));
    }
  }

  @Test
  public void metadataSurfaceIsPinned() {
    final var fn = new SQLFunctionAssert();
    assertEquals("assert", fn.getName(null));
    assertEquals(1, fn.getMinParams());
    assertEquals(2, fn.getMaxParams(null));
    assertEquals("assert(<field|value|expression>[, message])", fn.getSyntax(null));
  }
}
