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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
import java.text.ParseException;
import java.util.Base64;
import java.util.Date;
import org.junit.Test;

/**
 * Drives the per-arm {@link PropertyTypeInternal#convert(Object, PropertyTypeInternal,
 * com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass,
 * com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded)} body for the
 * datetime / binary {@code PropertyTypeInternal} arms — {@code DATETIME}, {@code DATE},
 * {@code BINARY} — over the canonical input shapes:
 * <ul>
 *   <li><b>null</b> → returns {@code null}</li>
 *   <li>same-type identity ({@code Date}, {@code byte[]})</li>
 *   <li>cross-type conversion ({@code Number} → {@code Date(ms)}, numeric String,
 *       formatted String, Base64 String)</li>
 *   <li>parse failure / wrong-type → {@link DatabaseException}</li>
 * </ul>
 *
 * <p>The {@code session} argument is passed as {@code null} throughout to confirm the contract
 * holds without a live database. {@code DATETIME}'s String-parse path relies on
 * {@link DateHelper#getDateTimeFormatInstance(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded)}
 * which falls back to {@code SimpleDateFormat("yyyy-MM-dd HH:mm:ss")} with the JVM default
 * timezone when the session is null. To stay deterministic across timezones, the expected
 * {@code Date} for every formatted-String row is parsed via the same {@code DateHelper}
 * factory — never compared against a hard-coded epoch.
 *
 * <p>This test is <b>standalone</b> (no {@code DbTestBase}). The {@code DATETIME} parse-failure
 * arm wraps a {@link ParseException} into a {@link DatabaseException} via
 * {@code BaseException.wrapException(... session)} which is null-safe, so the throw path is
 * also reachable without a live database.
 *
 * <p>Class-instance specials covered here:
 * <ul>
 *   <li>{@code DATE} short-circuits {@code Date} input directly and delegates every other
 *       value to {@code DATETIME.convert(...)} — pinned via a "same Date instance" identity
 *       check (Date arm) and a Number-via-DATETIME row.</li>
 *   <li>{@code BINARY} short-circuits {@code byte[]} as identity (no defensive copy on the
 *       convert path — the {@code copy()} sibling is what clones, not {@code convert()})
 *       and delegates non-{@code byte[]} input to
 *       {@link com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper#getBinaryContent}
 *       which Base64-decodes Strings.</li>
 * </ul>
 *
 * <p>Why not {@link org.junit.runners.Parameterized}: the three arms have heterogeneous expected
 * types ({@code Date}, {@code byte[]}, identity assertions) that don't fold cleanly into a
 * single {@code (input, expected)} schema; per-arm {@code @Test} methods give clearer failure
 * names and let each arm's specials (identity-vs-equals on {@code Date}, {@code assertSame}
 * on {@code byte[]} pass-through, {@code assertArrayEquals} on Base64-decoded bytes) use the
 * right assertion shape.
 */
public class PropertyTypeInternalDateTimeBinaryConvertTest {

  // ============================================================================
  // DATETIME
  // ============================================================================

  @Test
  public void datetimeNullReturnsNull() {
    assertNull(PropertyTypeInternal.DATETIME.convert(null, null, null, null));
  }

  /**
   * Date input is short-circuited as identity: the same instance is returned, no defensive
   * copy. This pins the contract so a future regression introducing {@code new Date(d.getTime())}
   * is a deliberate, visible event — many callers rely on the lack of copy for object identity
   * tracking.
   */
  @Test
  public void datetimeDatePassesThroughAsIdentity() {
    var d = new Date(1_700_000_000_000L);
    var result = PropertyTypeInternal.DATETIME.convert(d, null, null, null);
    assertSame(d, result);
  }

  /**
   * Number → {@code new Date(longValue())}: the arm wraps the millis directly. {@code Long}
   * exercises {@code longValue()} as identity; {@code Integer} confirms widening via
   * {@code Number#longValue()}; {@code Double} confirms truncation (no rounding).
   */
  @Test
  public void datetimeNumberConvertsToDate() {
    assertEquals(new Date(0L),
        PropertyTypeInternal.DATETIME.convert(0L, null, null, null));
    assertEquals(new Date(1_234_567L),
        PropertyTypeInternal.DATETIME.convert(1_234_567L, null, null, null));
    assertEquals(new Date(42L),
        PropertyTypeInternal.DATETIME.convert(Integer.valueOf(42), null, null, null));
    // Double 99.9 → longValue() truncates to 99, not 100 — pin truncation contract.
    assertEquals(new Date(99L),
        PropertyTypeInternal.DATETIME.convert(99.9d, null, null, null));
  }

  /**
   * Numeric String is detected by {@code IOUtils.isLong} (digits-only, no leading sign or
   * decimal point) and short-circuited to {@code new Date(Long.parseLong(s))} — bypasses the
   * {@link java.text.SimpleDateFormat} parse path entirely. The "0" boundary case confirms
   * the digits-only check accepts the full-zero string and produces the epoch-zero Date.
   */
  @Test
  public void datetimeNumericStringConvertsToDate() {
    assertEquals(new Date(1_700_000_000_000L),
        PropertyTypeInternal.DATETIME.convert("1700000000000", null, null, null));
    assertEquals(new Date(0L),
        PropertyTypeInternal.DATETIME.convert("0", null, null, null));
  }

  /**
   * A {@code yyyy-MM-dd HH:mm:ss}-formatted String is parsed via
   * {@link DateHelper#getDateTimeFormatInstance(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded)}.
   * The expected Date is parsed via the same factory call to stay timezone-independent —
   * comparing against a hard-coded epoch would flake on JVMs whose default timezone is not
   * UTC.
   */
  @Test
  public void datetimeFormattedStringConvertsToDate() throws ParseException {
    var dateString = "2024-03-15 12:34:56";
    var expected = DateHelper.getDateTimeFormatInstance(null).parse(dateString);
    assertEquals(expected,
        PropertyTypeInternal.DATETIME.convert(dateString, null, null, null));
  }

  /**
   * When the {@code yyyy-MM-dd HH:mm:ss} parse fails, the arm falls back to the date-only
   * format {@code yyyy-MM-dd}. This pins the two-stage parse contract: the second-stage
   * fallback is the documented escape hatch for date-only string inputs to a DATETIME-typed
   * property.
   */
  @Test
  public void datetimeDateOnlyStringFallsBackToDateFormat() throws ParseException {
    var dateString = "2024-03-15";
    var expected = DateHelper.getDateFormatInstance(null).parse(dateString);
    assertEquals(expected,
        PropertyTypeInternal.DATETIME.convert(dateString, null, null, null));
  }

  /**
   * A non-numeric, non-date-formatted string fails both parse stages and the arm wraps the
   * resulting {@code ParseException} into a {@link DatabaseException}. This is the only
   * path on the DATETIME arm that depends on {@code BaseException.wrapException(...)}
   * tolerating a null session — pinning the throw with null session confirms the
   * documented null-safe-on-error contract.
   */
  @Test
  public void datetimeUnparseableStringThrowsDatabaseException() {
    assertThrows(DatabaseException.class,
        () -> PropertyTypeInternal.DATETIME.convert("not-a-date", null, null, null));
  }

  /**
   * Non-Number, non-Date, non-String input (a sentinel Object) hits the {@code default ->}
   * arm of the switch and throws.
   */
  @Test
  public void datetimeWrongTypeThrowsDatabaseException() {
    assertThrows(DatabaseException.class,
        () -> PropertyTypeInternal.DATETIME.convert(new Object(), null, null, null));
  }

  // ============================================================================
  // DATE
  // ============================================================================

  @Test
  public void dateNullReturnsNull() {
    assertNull(PropertyTypeInternal.DATE.convert(null, null, null, null));
  }

  /**
   * DATE's body is a two-line short-circuit: Date input → identity, everything else →
   * {@code DATETIME.convert(...)} delegate. This pins the identity arm.
   */
  @Test
  public void dateDatePassesThroughAsIdentity() {
    var d = new Date(2_000_000L);
    var result = PropertyTypeInternal.DATE.convert(d, null, null, null);
    assertSame(d, result);
  }

  /**
   * Non-Date input delegates to {@code DATETIME.convert(...)} — verified here for {@code Long}
   * (millis), numeric String, and formatted String. The delegated paths are tested in detail
   * by the {@code datetime*} tests above; this test confirms only that DATE forwards rather
   * than reimplementing.
   */
  @Test
  public void dateNonDateInputDelegatesToDatetime() throws ParseException {
    // Long → Date(ms) via DATETIME's Number arm.
    assertEquals(new Date(1_234_567L),
        PropertyTypeInternal.DATE.convert(1_234_567L, null, null, null));
    // Numeric String → Date via DATETIME's IOUtils.isLong shortcut.
    assertEquals(new Date(1_700_000_000_000L),
        PropertyTypeInternal.DATE.convert("1700000000000", null, null, null));
    // Formatted String → Date via DATETIME's date-time format parse.
    var dateString = "2024-03-15 12:34:56";
    var expected = DateHelper.getDateTimeFormatInstance(null).parse(dateString);
    assertEquals(expected,
        PropertyTypeInternal.DATE.convert(dateString, null, null, null));
  }

  /**
   * Wrong-type input delegates to DATETIME and surfaces the DATETIME default-arm throw —
   * DATE itself has no exception-throwing branch, so this confirms the delegated throw.
   */
  @Test
  public void dateWrongTypeThrowsDatabaseException() {
    assertThrows(DatabaseException.class,
        () -> PropertyTypeInternal.DATE.convert(new Object(), null, null, null));
  }

  // ============================================================================
  // BINARY
  // ============================================================================

  @Test
  public void binaryNullReturnsNull() {
    assertNull(PropertyTypeInternal.BINARY.convert(null, null, null, null));
  }

  /**
   * byte[] input is returned as identity (no defensive copy). This pins the contract — the
   * sibling {@code copy(value, session)} method is what clones the array, not {@code convert(...)}.
   * A regression introducing a defensive copy on the convert path would silently change object
   * identity for downstream consumers.
   */
  @Test
  public void binaryByteArrayPassesThroughAsIdentity() {
    var bytes = new byte[] {1, 2, 3, 4, 5};
    var result = (byte[]) PropertyTypeInternal.BINARY.convert(bytes, null, null, null);
    assertSame(bytes, result);
  }

  /**
   * String input delegates to {@code StringSerializerHelper.getBinaryContent} which
   * Base64-decodes the string. Pin a known-good Base64 round-trip to confirm the delegate
   * is wired correctly.
   */
  @Test
  public void binaryStringDecodesBase64() {
    var original = new byte[] {10, 20, 30, 40};
    var b64 = Base64.getEncoder().encodeToString(original);
    var decoded = (byte[]) PropertyTypeInternal.BINARY.convert(b64, null, null, null);
    assertNotNull(decoded);
    assertArrayEquals(original, decoded);
  }

  /**
   * String wrapped in single quotes (legacy {@code '...'} compatibility marker) has the
   * markers stripped before Base64 decoding. This pins the @COMPATIBILITY 1.0rc7-SNAPSHOT
   * support path inside {@code StringSerializerHelper.getBinaryContent}.
   */
  @Test
  public void binaryStringWithLegacyQuoteMarkersStripsAndDecodes() {
    var original = new byte[] {7, 8, 9};
    var b64 = Base64.getEncoder().encodeToString(original);
    var quoted = "'" + b64 + "'";
    var decoded = (byte[]) PropertyTypeInternal.BINARY.convert(quoted, null, null, null);
    assertNotNull(decoded);
    assertArrayEquals(original, decoded);
  }

  /**
   * Non-byte[], non-String input falls into {@code StringSerializerHelper.getBinaryContent}'s
   * else branch and throws {@code IllegalArgumentException}. The BINARY arm itself does not
   * catch it, so the IAE propagates up unchanged. (The static
   * {@code convert(session, value, Class)} dispatcher would catch it; the per-arm body
   * exercised here does not.)
   */
  @Test
  public void binaryWrongTypeThrowsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class,
        () -> PropertyTypeInternal.BINARY.convert(new Object(), null, null, null));
  }
}
