/*
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.functions.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.core.sql.functions.DefaultSQLFunctionFactory;
import java.util.IllegalFormatConversionException;
import java.util.MissingFormatArgumentException;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionFormat} (text variant) — delegates to {@code String.format} with the
 * first param as pattern and all following params as args.
 *
 * <p>This is the REGISTERED variant. The misc-package duplicate
 * ({@link com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLFunctionFormat}) is dead
 * code covered separately in {@code SQLFunctionFormatMiscDeadTest}.
 *
 * <p>No DB required.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>Happy path — pattern with single arg, with multiple args, with non-String args (Integer,
 *       Double).
 *   <li>Pattern without format specifiers and empty args → the pattern string unchanged.
 *   <li>{@code aggregateResults()} returns {@code false} (inherited).
 *   <li>Mismatched arg count → {@link MissingFormatArgumentException} surfaces (pin).
 *   <li>Incompatible arg type for a specifier (e.g. %d + String) → {@link
 *       IllegalFormatConversionException}.
 *   <li>Null pattern throws NullPointerException (String.format(null, ...)).
 *   <li>Registered in {@link DefaultSQLFunctionFactory} under the name "format" — registration
 *       cross-check.
 *   <li>Metadata: name, min/max, syntax.
 * </ul>
 */
public class SQLFunctionFormatTest {

  private SQLFunctionFormat function() {
    return new SQLFunctionFormat();
  }

  // ---------------------------------------------------------------------------
  // Happy paths
  // ---------------------------------------------------------------------------

  @Test
  public void singleArgStringPattern() {
    var f = function();

    var result = f.execute(null, null, null, new Object[] {"hello %s", "world"}, null);

    assertEquals("hello world", result);
  }

  @Test
  public void multiArgMixedTypePattern() {
    var f = function();

    var result = f.execute(null, null, null,
        new Object[] {"%s=%d (%.1f)", "x", Integer.valueOf(3), Double.valueOf(1.25)}, null);

    assertEquals("x=3 (1.3)", result);
  }

  @Test
  public void patternWithNoSpecifiersReturnsPatternUnchanged() {
    // args are non-empty but the pattern has no specifiers → format ignores the extras.
    var f = function();

    var result = f.execute(null, null, null, new Object[] {"static", "ignored"}, null);

    assertEquals("static", result);
  }

  @Test
  public void patternWithNoSpecifiersAndNoExtraArgsReturnsItself() {
    // The function's min=1 allows a single pattern arg; args[] will be length 0.
    var f = function();

    var result = f.execute(null, null, null, new Object[] {"no-specifiers"}, null);

    assertEquals("no-specifiers", result);
  }

  // ---------------------------------------------------------------------------
  // Error paths — surfaced from String.format
  // ---------------------------------------------------------------------------

  @Test
  public void missingArgThrowsMissingFormatArgumentException() {
    var f = function();

    try {
      f.execute(null, null, null, new Object[] {"need %s and %s", "only-one"}, null);
      fail("expected MissingFormatArgumentException");
    } catch (MissingFormatArgumentException expected) {
      // pinned
    }
  }

  @Test
  public void wrongTypeThrowsIllegalFormatConversionException() {
    var f = function();

    try {
      f.execute(null, null, null, new Object[] {"%d", "not-a-number"}, null);
      fail("expected IllegalFormatConversionException");
    } catch (IllegalFormatConversionException expected) {
      // pinned
    }
  }

  @Test
  public void nullPatternThrowsNullPointerException() {
    // String.format((String) null, args) → NPE.
    var f = function();

    try {
      f.execute(null, null, null, new Object[] {null, "x"}, null);
      fail("expected NullPointerException");
    } catch (NullPointerException expected) {
      // pinned
    }
  }

  // ---------------------------------------------------------------------------
  // Contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void aggregateResultsIsFalse() {
    assertFalse(function().aggregateResults());
  }

  @Test
  public void nameMinMaxAndSyntaxMatchContract() {
    var f = function();

    assertEquals("format", SQLFunctionFormat.NAME);
    assertEquals("format", f.getName(null));
    assertEquals(1, f.getMinParams());
    assertEquals(-1, f.getMaxParams(null));
    assertEquals("format(<format>, <arg1> [,<argN>]*)", f.getSyntax(null));
  }

  // ---------------------------------------------------------------------------
  // Registration cross-check
  // ---------------------------------------------------------------------------

  @Test
  public void registeredInDefaultFactoryAsTextVariant() {
    // Pins that DefaultSQLFunctionFactory registers "format" to the TEXT variant (this class),
    // not the dead misc.SQLFunctionFormat. We inspect the raw map via getFunctions() to avoid
    // needing a session (createFunction requires one).
    var factory = new DefaultSQLFunctionFactory();
    factory.registerDefaultFunctions(null);

    var registered = factory.getFunctions().get("format");
    assertNotNull("DefaultSQLFunctionFactory must register 'format'", registered);
    // Registered as an instance (see DefaultSQLFunctionFactory line for SQLFunctionFormat).
    assertEquals("must be the TEXT variant, not the dead misc duplicate",
        SQLFunctionFormat.class, registered.getClass());
    assertFalse("class name must not live under .misc.",
        registered.getClass().getName().contains(".misc."));
  }
}
