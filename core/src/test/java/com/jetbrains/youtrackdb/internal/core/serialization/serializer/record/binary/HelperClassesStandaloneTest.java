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

package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import org.junit.Test;

/**
 * Standalone (no DB session required) coverage for {@link HelperClasses} static
 * helpers. The DB-dependent methods (read/writeOptimizedLink, read/writeLinkMap,
 * read/writeLinkCollection — all need a session because they hit refreshRid) are
 * covered separately in {@link HelperClassesGuardTest} and by
 * {@link VarIntAndHelperClassesReadBytesContainerTest}.
 *
 * <p>This test focuses on:
 * <ul>
 *   <li>String/byte conversion: {@code stringFromBytes}, {@code bytesFromString}
 *   <li>Type-from-value inference: {@code getTypeFromValueEmbedded}
 *   <li>Time-zone day conversion: {@code convertDayToTimezone}
 *   <li>Public constants: {@code CHARSET_UTF_8}, {@code MILLISEC_PER_DAY}
 *   <li>The {@code BytesContainer} read/write helpers that don't take a session:
 *       {@code writeByte}, {@code writeBinary}, {@code readBinary}, {@code writeString},
 *       {@code readString}, {@code writeOType}, {@code readOType}, {@code readType},
 *       {@code readByte}, {@code readInteger}, {@code readLong}
 *   <li>The {@code Tuple<T1, T2>} inner class
 * </ul>
 */
public class HelperClassesStandaloneTest {

  // --- Constants ---

  @Test
  public void charsetUtf8Constant() {
    assertEquals("UTF-8", HelperClasses.CHARSET_UTF_8);
  }

  @Test
  public void millisecPerDayConstant() {
    // 24 * 60 * 60 * 1000 — pinned so any future "let's switch to seconds" change is
    // a deliberate one.
    assertEquals(86_400_000L, HelperClasses.MILLISEC_PER_DAY);
  }

  // --- String / bytes conversion ---

  @Test
  public void bytesFromStringEncodesUtf8() {
    var bytes = HelperClasses.bytesFromString("ABC");
    assertArrayEquals(new byte[] {0x41, 0x42, 0x43}, bytes);
  }

  @Test
  public void bytesFromStringEncodesEmpty() {
    var bytes = HelperClasses.bytesFromString("");
    assertEquals(0, bytes.length);
  }

  @Test
  public void bytesFromStringEncodesMultibyteCharacters() {
    // Snowman ☃ is 0xE2 0x98 0x83 in UTF-8.
    var bytes = HelperClasses.bytesFromString("☃");
    assertArrayEquals(new byte[] {(byte) 0xE2, (byte) 0x98, (byte) 0x83}, bytes);
  }

  @Test
  public void bytesFromStringEncodesSurrogatePairs() {
    // U+1F600 (grinning face) — surrogate pair in UTF-16, 4 bytes in UTF-8.
    var bytes = HelperClasses.bytesFromString(new String(Character.toChars(0x1F600)));
    assertArrayEquals(
        new byte[] {(byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80}, bytes);
  }

  @Test
  public void stringFromBytesDecodesUtf8WithOffsetAndLength() {
    var bytes = new byte[] {0x10, 0x41, 0x42, 0x43, 0x10};
    assertEquals("ABC", HelperClasses.stringFromBytes(bytes, 1, 3));
  }

  @Test
  public void stringFromBytesDecodesEmptySlice() {
    var bytes = new byte[] {0x41, 0x42};
    assertEquals("", HelperClasses.stringFromBytes(bytes, 0, 0));
  }

  @Test
  public void roundTripStringPreservesContent() {
    var input = "hello, world! ☃ 😀";
    assertEquals(input,
        HelperClasses.stringFromBytes(
            HelperClasses.bytesFromString(input), 0,
            HelperClasses.bytesFromString(input).length));
  }

  // --- Type-from-value inference ---

  @Test
  public void getTypeFromValueEmbeddedReturnsNullForNullInput() {
    // PropertyTypeInternal.getTypeByValue(null) returns null; the method preserves
    // the null pass-through.
    assertNull(HelperClasses.getTypeFromValueEmbedded(null));
  }

  @Test
  public void getTypeFromValueEmbeddedReturnsBaseTypeForScalars() {
    assertEquals(PropertyTypeInternal.INTEGER, HelperClasses.getTypeFromValueEmbedded(42));
    assertEquals(PropertyTypeInternal.LONG, HelperClasses.getTypeFromValueEmbedded(42L));
    assertEquals(PropertyTypeInternal.STRING, HelperClasses.getTypeFromValueEmbedded("x"));
    assertEquals(PropertyTypeInternal.BOOLEAN, HelperClasses.getTypeFromValueEmbedded(true));
  }

  // --- Time-zone day conversion ---

  @Test
  public void convertDayToTimezoneIdentityForSameZone() {
    var utc = TimeZone.getTimeZone("UTC");
    var noon = makeUtcMillis(2025, Calendar.JUNE, 15, 12, 0);
    var dayStart = makeUtcMillis(2025, Calendar.JUNE, 15, 0, 0);
    var converted = HelperClasses.convertDayToTimezone(utc, utc, noon);
    assertEquals("same zone -> midnight of the same date in UTC",
        dayStart, converted);
  }

  @Test
  public void convertDayToTimezoneCarriesDateAcrossZones() {
    // Take 2 AM UTC on a known date, convert to a +5 hour zone. The day-component is
    // the SOURCE-zone date (so 2AM UTC is still 15 June UTC, not 16 June +5). The
    // result is midnight (in the destination zone) of THAT date.
    var utc = TimeZone.getTimeZone("UTC");
    var plusFive = TimeZone.getTimeZone("GMT+05:00");
    var twoAmUtc = makeUtcMillis(2025, Calendar.JUNE, 15, 2, 0);
    var converted = HelperClasses.convertDayToTimezone(utc, plusFive, twoAmUtc);

    // Verify the result is "midnight 15 June" interpreted in the +5 zone (= 19:00 of
    // 14 June UTC, == twoAmUtc - 7 hours).
    var expected = makeUtcMillis(2025, Calendar.JUNE, 14, 19, 0);
    assertEquals(expected, converted);
  }

  // --- BytesContainer read/write helpers (no DB session) ---

  @Test
  public void writeByteAndReadByteRoundTrip() {
    var bc = new BytesContainer();
    HelperClasses.writeByte(bc, (byte) 0x42);
    bc.offset = 0; // rewind
    assertEquals((byte) 0x42, HelperClasses.readByte(bc));
  }

  @Test
  public void writeBinaryReadBinaryRoundTrip() {
    var bc = new BytesContainer();
    var payload = new byte[] {1, 2, 3, 4, 5};
    HelperClasses.writeBinary(bc, payload);
    bc.offset = 0;
    assertArrayEquals(payload, HelperClasses.readBinary(bc));
  }

  @Test
  public void writeBinaryReadBinaryEmpty() {
    var bc = new BytesContainer();
    HelperClasses.writeBinary(bc, new byte[0]);
    bc.offset = 0;
    assertArrayEquals(new byte[0], HelperClasses.readBinary(bc));
  }

  @Test
  public void writeStringReadStringRoundTrip() {
    var bc = new BytesContainer();
    HelperClasses.writeString(bc, "hello");
    bc.offset = 0;
    assertEquals("hello", HelperClasses.readString(bc));
  }

  @Test
  public void writeStringReadStringEmpty() {
    var bc = new BytesContainer();
    HelperClasses.writeString(bc, "");
    bc.offset = 0;
    assertEquals("", HelperClasses.readString(bc));
  }

  @Test
  public void writeOTypeReadOType() {
    var bc = new BytesContainer();
    var pos = bc.alloc(1);
    HelperClasses.writeOType(bc, pos, PropertyTypeInternal.STRING);
    bc.offset = 0;
    assertEquals(PropertyTypeInternal.STRING, HelperClasses.readOType(bc, false));
  }

  @Test
  public void writeOTypeNullProducesMinusOneSentinel() {
    var bc = new BytesContainer();
    var pos = bc.alloc(1);
    HelperClasses.writeOType(bc, pos, null);
    assertEquals("null type sentinel is -1", -1, bc.bytes[pos]);
    bc.offset = 0;
    assertNull(HelperClasses.readOType(bc, false));
  }

  @Test
  public void readOTypeJustRunThroughSkipsByteAndReturnsNull() {
    var bc = new BytesContainer();
    var pos = bc.alloc(1);
    HelperClasses.writeOType(bc, pos, PropertyTypeInternal.INTEGER);
    bc.offset = 0;
    var read = HelperClasses.readOType(bc, true);
    assertNull("justRunThrough returns null even for non-null type", read);
    assertEquals("offset was advanced past the type byte", 1, bc.offset);
  }

  @Test
  public void readTypeUsesReadOnlyByteSlot() {
    // readType is the BytesContainer overload that does NOT pre-check just-run.
    var bc = new BytesContainer();
    var pos = bc.alloc(1);
    bc.bytes[pos] = (byte) PropertyTypeInternal.LONG.getId();
    bc.offset = 0;
    assertEquals(PropertyTypeInternal.LONG, HelperClasses.readType(bc));
  }

  @Test
  public void readTypeReturnsNullOnSentinel() {
    var bc = new BytesContainer();
    var pos = bc.alloc(1);
    bc.bytes[pos] = -1;
    bc.offset = 0;
    assertNull(HelperClasses.readType(bc));
  }

  @Test
  public void readIntegerAdvancesByFourBytes() {
    var bc = new BytesContainer();
    var pos = bc.alloc(4);
    bc.bytes[pos] = 0x00;
    bc.bytes[pos + 1] = 0x00;
    bc.bytes[pos + 2] = 0x12;
    bc.bytes[pos + 3] = 0x34;
    bc.offset = 0;
    assertEquals(0x1234, HelperClasses.readInteger(bc));
    assertEquals(4, bc.offset);
  }

  @Test
  public void readLongAdvancesByEightBytes() {
    var bc = new BytesContainer();
    var pos = bc.alloc(8);
    for (var i = 0; i < 8; i++) {
      bc.bytes[pos + i] = (byte) (i + 1);
    }
    bc.offset = 0;
    var result = HelperClasses.readLong(bc);
    assertEquals(8, bc.offset);
    // Big-endian interpretation: 0x0102030405060708
    assertEquals(0x0102030405060708L, result);
  }

  // --- Inner classes ---

  @Test
  public void tupleAccessorsReturnComponents() {
    var t = new HelperClasses.Tuple<String, Integer>("k", 42);
    assertEquals("k", t.getFirstVal());
    assertEquals(Integer.valueOf(42), t.getSecondVal());
  }

  @Test
  public void tupleAcceptsNullsAndDistinctTypes() {
    var t = new HelperClasses.Tuple<String, Boolean>(null, Boolean.TRUE);
    assertNull(t.getFirstVal());
    assertEquals(Boolean.TRUE, t.getSecondVal());
  }

  @Test
  public void tupleInstancesAreIndependent() {
    var a = new HelperClasses.Tuple<>("a", 1);
    var b = new HelperClasses.Tuple<>("b", 2);
    assertNotNull(a);
    assertNotNull(b);
    assertEquals("a", a.getFirstVal());
    assertEquals("b", b.getFirstVal());
  }

  // --- helpers ---

  private static long makeUtcMillis(int y, int mo, int d, int h, int mi) {
    var cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
    cal.clear();
    cal.set(y, mo, d, h, mi, 0);
    return cal.getTimeInMillis();
  }
}
