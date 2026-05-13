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
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.util.Date;
import org.junit.Test;

/**
 * Tests for {@link SQLMethodAsDecimal} — converts {@code iThis} to {@link BigDecimal}. Three
 * branches: null → null, {@link Date} → BigDecimal of epoch-ms, otherwise {@code new
 * BigDecimal(iThis.toString().trim())}.
 *
 * <p>No DB required — the method never dereferences the context.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>{@code iThis == null} → null.
 *   <li>{@link Date} → BigDecimal with the same epoch-ms value.
 *   <li>Integer / Long / Double (as their toString) parse without loss.
 *   <li>BigDecimal input round-trips via toString() (NOT short-circuit — goes through the
 *       String-path which may introduce scale changes); pin the value equality.
 *   <li>Whitespace around numeric string is trimmed.
 *   <li>Non-numeric string → NumberFormatException (BigDecimal ctor).
 *   <li>Empty string (after trim) → NumberFormatException.
 *   <li>Metadata: name, min/max, syntax.
 * </ul>
 */
public class SQLMethodAsDecimalTest {

  private SQLMethodAsDecimal method() {
    return new SQLMethodAsDecimal();
  }

  // ---------------------------------------------------------------------------
  // Null short-circuit
  // ---------------------------------------------------------------------------

  @Test
  public void nullIThisReturnsNull() {
    // No Date branch match → falls to the ternary → null.
    assertNull(method().execute(null, null, null, null, new Object[] {}));
  }

  // ---------------------------------------------------------------------------
  // Date branch
  // ---------------------------------------------------------------------------

  @Test
  public void dateReturnsEpochMsAsBigDecimal() {
    var epochMs = 1_700_000_000_000L;
    var date = new Date(epochMs);

    var result = method().execute(date, null, null, null, new Object[] {});

    assertEquals(new BigDecimal(epochMs), result);
  }

  @Test
  public void dateAtEpochZeroProducesZero() {
    // Production uses `new BigDecimal(d.getTime())` which constructs via the long overload —
    // scale 0 and value 0. Compare against the SAME constructor rather than BigDecimal.ZERO to
    // avoid a scale-sensitive mismatch if production ever switches to BigDecimal.valueOf.
    var result = method().execute(new Date(0), null, null, null, new Object[] {});

    assertEquals(new BigDecimal(0L), result);
  }

  // ---------------------------------------------------------------------------
  // Numeric toString paths
  // ---------------------------------------------------------------------------

  @Test
  public void stringNumericPathParsesInteger() {
    var result = method().execute("42", null, null, null, new Object[] {});

    assertEquals(new BigDecimal("42"), result);
  }

  @Test
  public void stringNumericPathParsesDecimal() {
    var result = method().execute("3.14159", null, null, null, new Object[] {});

    assertEquals(new BigDecimal("3.14159"), result);
  }

  @Test
  public void boxedIntegerViaToString() {
    var result = method().execute(Integer.valueOf(99), null, null, null, new Object[] {});

    assertEquals(new BigDecimal("99"), result);
  }

  @Test
  public void boxedLongViaToString() {
    var result = method().execute(Long.valueOf(9_876_543_210L), null, null, null, new Object[] {});

    assertEquals(new BigDecimal("9876543210"), result);
  }

  @Test
  public void boxedDoubleViaToString() {
    // Double.toString(1.5) = "1.5" — the resulting BigDecimal matches that textual form.
    var result = method().execute(Double.valueOf(1.5), null, null, null, new Object[] {});

    assertEquals(new BigDecimal("1.5"), result);
  }

  @Test
  public void bigDecimalInputRoundTripsViaToStringNotIdentity() {
    // BigDecimal is not a Date, so it falls through the String path: new BigDecimal(bd.toString()).
    // Pin value equality (scale-preserving for the toString form).
    var bd = new BigDecimal("12.340");

    var result = method().execute(bd, null, null, null, new Object[] {});

    assertEquals(bd, result);
  }

  @Test
  public void whitespaceIsTrimmed() {
    // trim() happens inside SQLMethodAsDecimal before constructing the BigDecimal.
    var result = method().execute("  7.5  ", null, null, null, new Object[] {});

    assertEquals(new BigDecimal("7.5"), result);
  }

  // ---------------------------------------------------------------------------
  // Error paths
  // ---------------------------------------------------------------------------

  @Test
  public void nonNumericStringThrowsNumberFormat() {
    try {
      method().execute("abc", null, null, null, new Object[] {});
      fail("expected NumberFormatException");
    } catch (NumberFormatException expected) {
      // pinned
    }
  }

  @Test
  public void emptyStringAfterTrimThrowsNumberFormat() {
    try {
      method().execute("   ", null, null, null, new Object[] {});
      fail("expected NumberFormatException for whitespace-only input");
    } catch (NumberFormatException expected) {
      // pinned
    }
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void nameMinMaxAndSyntaxMatchContract() {
    var m = method();

    assertEquals("asdecimal", SQLMethodAsDecimal.NAME);
    assertEquals("asdecimal", m.getName());
    assertEquals(0, m.getMinParams());
    assertEquals(0, m.getMaxParams(null));
    assertEquals("asDecimal()", m.getSyntax());
  }
}
