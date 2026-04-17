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
package com.jetbrains.youtrackdb.internal.core.sql.functions.conversion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import java.math.BigDecimal;
import org.junit.Test;

/**
 * Tests for {@link SQLMethodConvert} — converts the subject to a target type named either by a
 * fully-qualified Java class name ({@code java.lang.Integer}) or by a YouTrackDB
 * {@link com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal} name
 * ({@code INTEGER}).
 *
 * <p>Uses {@link DbTestBase} because the branches delegate to
 * {@code PropertyTypeInternal.convert(db, value, class)}, which may use the session to determine
 * timezone / locale defaults.
 *
 * <p>Covered branches (ALL four from the production if/else):
 *
 * <ul>
 *   <li>{@code iThis == null} → null.
 *   <li>{@code iParams[0] == null} → null.
 *   <li>Java-class path ({@code contains(".")}): "java.lang.Integer" → Integer.
 *   <li>Java-class path with an unknown class → ClassNotFoundException caught, method returns
 *       null (pin the caught-and-logged behaviour).
 *   <li>PropertyTypeInternal path (no dot): "INTEGER" / "LONG" / "STRING" / "DOUBLE" → correct
 *       boxed primitive.
 *   <li>PropertyTypeInternal path is case-INSENSITIVE (valueOf is called on
 *       {@code destType.toUpperCase}).
 *   <li>Unknown PropertyTypeInternal name → IllegalArgumentException (from valueOf) propagates.
 *   <li>Same-type no-op (e.g. INTEGER from an Integer input) returns the input as-is.
 *   <li>Metadata: name, min/max, syntax.
 * </ul>
 */
public class SQLMethodConvertTest extends DbTestBase {

  private BasicCommandContext ctx() {
    var c = new BasicCommandContext();
    c.setDatabaseSession(session);
    return c;
  }

  private SQLMethodConvert method() {
    return new SQLMethodConvert();
  }

  // ---------------------------------------------------------------------------
  // Early exits
  // ---------------------------------------------------------------------------

  @Test
  public void nullIThisReturnsNull() {
    assertNull(method().execute(null, null, ctx(), null, new Object[] {"INTEGER"}));
  }

  @Test
  public void nullFirstParamReturnsNull() {
    assertNull(method().execute("42", null, ctx(), null, new Object[] {null}));
  }

  // ---------------------------------------------------------------------------
  // Java-class path (contains dot)
  // ---------------------------------------------------------------------------

  @Test
  public void javaLangIntegerParsesIntegerFromString() {
    var result = method().execute("42", null, ctx(), null, new Object[] {"java.lang.Integer"});

    assertEquals(Integer.valueOf(42), result);
  }

  @Test
  public void javaLangLongParsesLongFromString() {
    var result = method().execute("9876543210", null, ctx(), null,
        new Object[] {"java.lang.Long"});

    assertEquals(Long.valueOf(9876543210L), result);
  }

  @Test
  public void javaMathBigDecimalParsesFromNumericString() {
    var result = method().execute("3.14", null, ctx(), null,
        new Object[] {"java.math.BigDecimal"});

    assertEquals(new BigDecimal("3.14"), result);
  }

  @Test
  public void unknownJavaClassNameReturnsNullViaCaughtCnfe() {
    // The branch catches ClassNotFoundException, logs it, and (since the try/catch is inside an
    // if-block with no else return) falls through to the final `return null`. Pin this.
    var result = method().execute("42", null, ctx(), null,
        new Object[] {"no.such.Class_Definitely_Missing"});

    assertNull(result);
  }

  // ---------------------------------------------------------------------------
  // PropertyTypeInternal path (no dot)
  // ---------------------------------------------------------------------------

  @Test
  public void integerTypeNameReturnsInteger() {
    var result = method().execute("42", null, ctx(), null, new Object[] {"INTEGER"});

    assertEquals(Integer.valueOf(42), result);
  }

  @Test
  public void longTypeNameReturnsLong() {
    var result = method().execute("42", null, ctx(), null, new Object[] {"LONG"});

    assertEquals(Long.valueOf(42L), result);
  }

  @Test
  public void stringTypeNameReturnsString() {
    // Integer input + STRING target — the STRING branch converts via toString / valueOf.
    var result = method().execute(Integer.valueOf(42), null, ctx(), null, new Object[] {"STRING"});

    assertEquals("42", result);
  }

  @Test
  public void doubleTypeNameReturnsDouble() {
    var result = method().execute("3.14", null, ctx(), null, new Object[] {"DOUBLE"});

    assertEquals(Double.valueOf(3.14), result);
  }

  @Test
  public void typeNameIsCaseInsensitive() {
    // destType.toUpperCase(Locale.ENGLISH) normalises the input; "integer", "Integer", "INTEGER"
    // all resolve to PropertyTypeInternal.INTEGER.
    var r1 = method().execute("42", null, ctx(), null, new Object[] {"integer"});
    var r2 = method().execute("42", null, ctx(), null, new Object[] {"Integer"});

    assertEquals(Integer.valueOf(42), r1);
    assertEquals(Integer.valueOf(42), r2);
  }

  @Test
  public void unknownPropertyTypeThrowsIllegalArgumentException() {
    // valueOf("NOT_A_TYPE") throws IllegalArgumentException — the method does not catch it.
    try {
      method().execute("42", null, ctx(), null, new Object[] {"NOT_A_TYPE"});
      fail("expected IllegalArgumentException from unknown PropertyTypeInternal");
    } catch (IllegalArgumentException expected) {
      // pinned — pinned by type; message is "No enum constant …" + fully-qualified enum name.
      assertTrue("message should reference the bad name, saw: " + expected.getMessage(),
          expected.getMessage() == null || expected.getMessage().contains("NOT_A_TYPE"));
    }
  }

  @Test
  public void sameTypeNoOpReturnsInputAsIs() {
    // PropertyTypeInternal.convert short-circuits when value.getClass() == targetClass — Integer
    // in, Integer target → identity.
    var input = Integer.valueOf(42);

    var result = method().execute(input, null, ctx(), null, new Object[] {"INTEGER"});

    assertEquals(input, result);
  }

  @Test
  public void paramCoercedViaToString() {
    // iParams[0] is passed through toString() before the dot-check, so any Object whose toString
    // yields a valid type name works.
    var arg = new Object() {
      @Override
      public String toString() {
        return "INTEGER";
      }
    };

    var result = method().execute("42", null, ctx(), null, new Object[] {arg});

    assertEquals(Integer.valueOf(42), result);
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void nameMinMaxAndSyntaxMatchContract() {
    var m = method();

    assertEquals("convert", SQLMethodConvert.NAME);
    assertEquals("convert", m.getName());
    assertEquals(1, m.getMinParams());
    assertEquals(1, m.getMaxParams(null));
    assertEquals("convert(<type>)", m.getSyntax());
  }
}
