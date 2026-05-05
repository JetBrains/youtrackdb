/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * Drives the per-arm {@link PropertyTypeInternal#convert(Object, PropertyTypeInternal,
 * com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass,
 * com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded)} body of every numeric
 * {@code PropertyTypeInternal} arm — {@code BOOLEAN}, {@code INTEGER}, {@code SHORT},
 * {@code LONG}, {@code FLOAT}, {@code DOUBLE}, {@code BYTE}, {@code DECIMAL} — over the
 * five canonical input shapes:
 * <ul>
 *   <li><b>null</b> → returns {@code null}</li>
 *   <li><b>same-type</b> identity (e.g. {@code Integer} → {@code Integer})</li>
 *   <li><b>String</b> → parsed via the per-arm boxed {@code valueOf} or
 *       {@code BigDecimal} ctor</li>
 *   <li><b>cross-{@link Number}</b> narrowing (e.g. {@code Double} → {@code Integer}
 *       truncated)</li>
 *   <li><b>wrong-type</b> sentinel value → {@link DatabaseException}</li>
 * </ul>
 *
 * <p>Per-arm specials are also pinned: {@code BOOLEAN} parses {@code "true"}/{@code "false"}
 * via {@link Boolean#valueOf(String)} (any non-{@code "true"} string returns {@code false},
 * including {@code "invalid"}) and treats zero / non-zero numbers as boolean false / true;
 * {@code INTEGER} short-circuits empty strings to {@code null}; {@code DOUBLE} has a dedicated
 * {@code Float} arm that round-trips via {@code Double.valueOf(value.toString())} (preserving
 * the textual form to avoid widening artifacts); {@code LONG} has a dedicated {@code Date} arm
 * returning the epoch millis; {@code DECIMAL} accepts {@code BigDecimal} pass-through and
 * {@code BigInteger}/{@code Number} via {@code value.toString()}.
 *
 * <p>The test class is <b>standalone</b> (no {@code DbTestBase}) because every numeric arm's
 * convert body operates on the input value alone — no record context, no schema lookup, no
 * date-format resolution. The {@code session} argument is passed as {@code null} to confirm
 * the contract holds without a live database. The wrong-type exception path is reached
 * directly (no {@link IllegalArgumentException}-catching dispatcher between caller and arm)
 * so the {@code null}-session NPE risk in the static
 * {@code convert(session, value, targetClass)} dispatcher (line 1699 of {@code
 * PropertyTypeInternal} — {@code session.getDatabaseName()} on the IAE catch) is
 * <i>not</i> exercised here. Coverage of the static dispatcher path is owned by the existing
 * {@code SchemaPropertyTypeConvertTest} which holds a live session.
 */
@RunWith(Parameterized.class)
public class PropertyTypeInternalNumericConvertTest {

  /** Sentinel object instance used to exercise the {@code default ->} arm of every switch. */
  private static final Object WRONG_TYPE = new Object();

  @Parameter(0)
  public String label;

  @Parameter(1)
  public PropertyTypeInternal arm;

  @Parameter(2)
  public Object input;

  /** Expected output; ignored when {@link #expectsException} is {@code true}. */
  @Parameter(3)
  public Object expected;

  /** {@code true} when the row asserts a {@link DatabaseException} is thrown. */
  @Parameter(4)
  public boolean expectsException;

  @Parameters(name = "{index}: {0}")
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] {
        // ---------------- BOOLEAN ----------------
        // null is short-circuited at the top of every arm.
        {"BOOLEAN null", PropertyTypeInternal.BOOLEAN, null, null, false},
        // Boolean → Boolean: identity arm.
        {"BOOLEAN Boolean.TRUE pass-through", PropertyTypeInternal.BOOLEAN, Boolean.TRUE,
            Boolean.TRUE, false},
        {"BOOLEAN Boolean.FALSE pass-through", PropertyTypeInternal.BOOLEAN, Boolean.FALSE,
            Boolean.FALSE, false},
        // String → Boolean.valueOf: only "true" (case-insensitive) returns TRUE; everything else
        // returns FALSE — including "invalid". This pins that contract so a future regression to
        // strict parsing is a deliberate, visible event.
        {"BOOLEAN String 'true' parses true", PropertyTypeInternal.BOOLEAN, "true",
            Boolean.TRUE, false},
        {"BOOLEAN String 'TRUE' parses true (case-insensitive)", PropertyTypeInternal.BOOLEAN,
            "TRUE", Boolean.TRUE, false},
        {"BOOLEAN String 'false' parses false", PropertyTypeInternal.BOOLEAN, "false",
            Boolean.FALSE, false},
        {"BOOLEAN String 'invalid' parses false (Boolean.valueOf contract)",
            PropertyTypeInternal.BOOLEAN, "invalid", Boolean.FALSE, false},
        // Number → boolean: zero is FALSE, any non-zero int is TRUE (Number#intValue rounds).
        {"BOOLEAN Integer 0 → false", PropertyTypeInternal.BOOLEAN, 0, Boolean.FALSE, false},
        {"BOOLEAN Integer 1 → true", PropertyTypeInternal.BOOLEAN, 1, Boolean.TRUE, false},
        {"BOOLEAN Integer -5 → true", PropertyTypeInternal.BOOLEAN, -5, Boolean.TRUE, false},
        // 0.5d truncates to 0 (Number#intValue), so it's FALSE — pin this so a future change to
        // round-instead-of-truncate is caught.
        {"BOOLEAN Double 0.5 truncates to 0 → false", PropertyTypeInternal.BOOLEAN, 0.5d,
            Boolean.FALSE, false},
        {"BOOLEAN wrong-type sentinel throws", PropertyTypeInternal.BOOLEAN, WRONG_TYPE, null,
            true},

        // ---------------- INTEGER ----------------
        {"INTEGER null", PropertyTypeInternal.INTEGER, null, null, false},
        {"INTEGER Integer pass-through", PropertyTypeInternal.INTEGER, Integer.valueOf(42),
            Integer.valueOf(42), false},
        {"INTEGER String '10' parses 10", PropertyTypeInternal.INTEGER, "10",
            Integer.valueOf(10), false},
        {"INTEGER String '-1' parses -1", PropertyTypeInternal.INTEGER, "-1",
            Integer.valueOf(-1), false},
        // Empty-string short-circuit: arm-specific contract returning null instead of throwing
        // NumberFormatException. Pin this for stability — many callers depend on it.
        {"INTEGER empty String → null", PropertyTypeInternal.INTEGER, "", null, false},
        // Cross-Number narrowing: Long → Integer truncates the upper 32 bits.
        {"INTEGER Long 99 → 99", PropertyTypeInternal.INTEGER, 99L, Integer.valueOf(99),
            false},
        {"INTEGER Double 3.9 → 3 (truncate)", PropertyTypeInternal.INTEGER, 3.9d,
            Integer.valueOf(3), false},
        {"INTEGER Short pass-through narrowed", PropertyTypeInternal.INTEGER, (short) 7,
            Integer.valueOf(7), false},
        {"INTEGER wrong-type sentinel throws", PropertyTypeInternal.INTEGER, WRONG_TYPE, null,
            true},

        // ---------------- SHORT ----------------
        {"SHORT null", PropertyTypeInternal.SHORT, null, null, false},
        {"SHORT Short pass-through", PropertyTypeInternal.SHORT, (short) 11, (short) 11, false},
        {"SHORT String '12' parses 12", PropertyTypeInternal.SHORT, "12", (short) 12, false},
        {"SHORT Integer 100 → short 100 (narrowing)", PropertyTypeInternal.SHORT, 100,
            (short) 100, false},
        // 70_000 narrowed via shortValue: 70_000 mod 65_536 = 4_464 → short 4_464.
        {"SHORT Integer 70000 narrows via shortValue", PropertyTypeInternal.SHORT, 70_000,
            (short) 4_464, false},
        {"SHORT Double 5.7 → short 5 (truncate)", PropertyTypeInternal.SHORT, 5.7d,
            (short) 5, false},
        {"SHORT wrong-type sentinel throws", PropertyTypeInternal.SHORT, WRONG_TYPE, null,
            true},

        // ---------------- LONG ----------------
        {"LONG null", PropertyTypeInternal.LONG, null, null, false},
        {"LONG Long pass-through", PropertyTypeInternal.LONG, 99L, 99L, false},
        {"LONG String '100' parses 100L", PropertyTypeInternal.LONG, "100", 100L, false},
        // LONG-only specialised arm: Date → Date.getTime() (epoch millis).
        {"LONG Date → epoch millis", PropertyTypeInternal.LONG, new Date(123_456L), 123_456L,
            false},
        {"LONG Integer 5 → 5L (widening)", PropertyTypeInternal.LONG, 5, 5L, false},
        {"LONG Double 6.7 → 6L (truncate)", PropertyTypeInternal.LONG, 6.7d, 6L, false},
        {"LONG wrong-type sentinel throws", PropertyTypeInternal.LONG, WRONG_TYPE, null, true},

        // ---------------- FLOAT ----------------
        {"FLOAT null", PropertyTypeInternal.FLOAT, null, null, false},
        {"FLOAT Float pass-through", PropertyTypeInternal.FLOAT, 1.5f, 1.5f, false},
        {"FLOAT String '2.25' parses 2.25f", PropertyTypeInternal.FLOAT, "2.25", 2.25f, false},
        {"FLOAT Integer 7 → 7.0f (widening)", PropertyTypeInternal.FLOAT, 7, 7.0f, false},
        {"FLOAT Double 8.5 → 8.5f (narrowing)", PropertyTypeInternal.FLOAT, 8.5d, 8.5f, false},
        {"FLOAT wrong-type sentinel throws", PropertyTypeInternal.FLOAT, WRONG_TYPE, null,
            true},

        // ---------------- DOUBLE ----------------
        {"DOUBLE null", PropertyTypeInternal.DOUBLE, null, null, false},
        {"DOUBLE Double pass-through", PropertyTypeInternal.DOUBLE, 5.4d, 5.4d, false},
        {"DOUBLE String '5.4' parses 5.4d", PropertyTypeInternal.DOUBLE, "5.4", 5.4d, false},
        // DOUBLE-only specialised arm: Float → Double via value.toString() (avoids the
        // Float→Double widening artifact where 5.4f.doubleValue() = 5.400000095367432).
        {"DOUBLE Float 5.4f → 5.4d via toString()", PropertyTypeInternal.DOUBLE, 5.4f, 5.4d,
            false},
        // Generic Number arm: Integer.doubleValue() = 5.0d (no toString detour).
        {"DOUBLE Integer 5 → 5.0d", PropertyTypeInternal.DOUBLE, 5, 5.0d, false},
        {"DOUBLE Long 100L → 100.0d", PropertyTypeInternal.DOUBLE, 100L, 100.0d, false},
        {"DOUBLE wrong-type sentinel throws", PropertyTypeInternal.DOUBLE, WRONG_TYPE, null,
            true},

        // ---------------- BYTE ----------------
        {"BYTE null", PropertyTypeInternal.BYTE, null, null, false},
        {"BYTE Byte pass-through", PropertyTypeInternal.BYTE, (byte) 7, (byte) 7, false},
        {"BYTE String '8' parses (byte) 8", PropertyTypeInternal.BYTE, "8", (byte) 8, false},
        {"BYTE Integer 9 → (byte) 9", PropertyTypeInternal.BYTE, 9, (byte) 9, false},
        // 200 narrowed via byteValue: 200 - 256 = -56.
        {"BYTE Integer 200 narrows via byteValue", PropertyTypeInternal.BYTE, 200, (byte) -56,
            false},
        {"BYTE Double 3.7 → (byte) 3 (truncate)", PropertyTypeInternal.BYTE, 3.7d, (byte) 3,
            false},
        {"BYTE wrong-type sentinel throws", PropertyTypeInternal.BYTE, WRONG_TYPE, null, true},

        // ---------------- DECIMAL ----------------
        {"DECIMAL null", PropertyTypeInternal.DECIMAL, null, null, false},
        {"DECIMAL BigDecimal pass-through", PropertyTypeInternal.DECIMAL, new BigDecimal("3.14"),
            new BigDecimal("3.14"), false},
        {"DECIMAL String '10.65' parses BigDecimal('10.65')", PropertyTypeInternal.DECIMAL,
            "10.65", new BigDecimal("10.65"), false},
        // Number arm uses value.toString() — Integer 4 → "4" → BigDecimal("4"), not 4.0.
        {"DECIMAL Integer 4 → BigDecimal('4')", PropertyTypeInternal.DECIMAL, 4,
            new BigDecimal("4"), false},
        // Double 4.98 → BigDecimal("4.98") via toString (avoids the
        // BigDecimal.valueOf(4.98) → "4.98" vs new BigDecimal(4.98) → long-decimal artifact).
        {"DECIMAL Double 4.98 → BigDecimal('4.98') via toString",
            PropertyTypeInternal.DECIMAL, 4.98d, new BigDecimal("4.98"), false},
        {"DECIMAL Long 100L → BigDecimal('100')", PropertyTypeInternal.DECIMAL, 100L,
            new BigDecimal("100"), false},
        // BigInteger ⊂ Number: hits the Number arm, value.toString() produces a clean integer
        // representation.
        {"DECIMAL BigInteger 999 → BigDecimal('999')", PropertyTypeInternal.DECIMAL,
            new BigInteger("999"), new BigDecimal("999"), false},
        {"DECIMAL wrong-type sentinel throws", PropertyTypeInternal.DECIMAL, WRONG_TYPE, null,
            true},
    });
  }

  /**
   * Drives every parameterized row through the per-arm {@code convert(value, linkedType,
   * linkedClass, session)} body with all three "context" arguments null. {@code linkedType} and
   * {@code linkedClass} are unused by every numeric arm (they only matter for collection /
   * link / embedded paths, which are exercised by the sibling collection / link / embedded
   * convert tests). {@code session} is null because no
   * numeric arm reads from it on the happy path; the wrong-type exception path uses
   * {@code session != null ? session.getDatabaseName() : null} explicitly so null is also
   * safe on the throw path.
   */
  @Test
  public void convertProducesExpectedResultPerArm() {
    if (expectsException) {
      assertThrows(DatabaseException.class,
          () -> arm.convert(input, null, null, null));
      return;
    }
    var actual = arm.convert(input, null, null, null);
    if (expected == null) {
      assertNull(actual);
      return;
    }
    assertEquals(expected, actual);
  }
}
