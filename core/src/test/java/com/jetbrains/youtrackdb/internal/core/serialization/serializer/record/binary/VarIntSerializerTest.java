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
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import org.junit.Test;

/**
 * Direct coverage for {@link VarIntSerializer} focusing on what is not covered by
 * {@link VarIntAndHelperClassesReadBytesContainerTest} (which already exercises the
 * BytesContainer / ReadBytesContainer overloads):
 *
 * <ul>
 *   <li>The {@link DataInput}/{@link DataOutput} overloads — used by network and
 *       file-based callers that work with streams rather than {@code byte[]}-backed
 *       containers.
 *   <li>The 63-bit overflow guard inside the unsigned-VarLong reader — exercised by
 *       crafting a malformed 10-byte sequence with the high-continuation bit set on
 *       byte 9.
 *   <li>Canonical byte-shape pins for known values: a regression that changes the
 *       canonical encoding (e.g. flipping zigzag direction or swapping LSB/MSB byte
 *       order) would silently re-pin every persisted record. The byte-shape pins
 *       lock the wire format.
 *   <li>Sign-flip boundaries: {@code Long.MIN_VALUE}, {@code -1L}, {@code 0L},
 *       {@code 1L}, {@code Long.MAX_VALUE}.
 * </ul>
 */
public class VarIntSerializerTest {

  // --- DataOutput / DataInput round-trips ---

  @Test
  public void dataOutputDataInputRoundTripZero() throws IOException {
    assertDataStreamRoundTrip(0L);
  }

  @Test
  public void dataOutputDataInputRoundTripPositiveOne() throws IOException {
    assertDataStreamRoundTrip(1L);
  }

  @Test
  public void dataOutputDataInputRoundTripNegativeOne() throws IOException {
    assertDataStreamRoundTrip(-1L);
  }

  @Test
  public void dataOutputDataInputRoundTripLongMinValue() throws IOException {
    assertDataStreamRoundTrip(Long.MIN_VALUE);
  }

  @Test
  public void dataOutputDataInputRoundTripLongMaxValue() throws IOException {
    assertDataStreamRoundTrip(Long.MAX_VALUE);
  }

  @Test
  public void dataOutputDataInputRoundTripVariety() throws IOException {
    long[] values = {
        2L, -2L, 63L, -63L, 64L, -64L, 127L, -127L, 128L, -128L,
        16383L, 16384L, Integer.MAX_VALUE, Integer.MIN_VALUE
    };
    for (var v : values) {
      assertDataStreamRoundTrip(v);
    }
  }

  @Test
  public void readAsIntFromDataInputMatchesReadAsLong() throws IOException {
    var out = new ByteArrayOutputStream();
    VarIntSerializer.write(new DataOutputStream(out), 12345L);
    DataInput in = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(12345, VarIntSerializer.readAsInt(in));

    out = new ByteArrayOutputStream();
    VarIntSerializer.write(new DataOutputStream(out), 99L);
    in = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(99L, VarIntSerializer.readAsLong(in));
  }

  @Test
  public void unsignedVarLongDataOutputMatchesContainerBytes() throws IOException {
    // Pin: writing the same value through DataOutput and through BytesContainer yields
    // identical bytes (so a network-side write and a record-side write produce a wire
    // form a peer can decode). Use 300L which encodes in 2 bytes.
    var bos = new ByteArrayOutputStream();
    VarIntSerializer.writeUnsignedVarLong(300L, new DataOutputStream(bos));

    var bc = new BytesContainer();
    VarIntSerializer.writeUnsignedVarLong(300L, bc);
    assertArrayEquals(bos.toByteArray(), bc.fitBytes());
  }

  // --- Overflow guard: more than 9 continuation bytes triggers IllegalArgumentException ---

  @Test
  public void readUnsignedVarLongRejectsOverflowOnBytesContainer() {
    // 10 bytes all with high bit set: 0x80, 0x80, ..., 0x80. This makes the loop run
    // past i=63 (i increments by 7 each iteration: 0,7,14,21,28,35,42,49,56,63 — the
    // 10th iteration increments i to 70, tripping the guard).
    var malformed = new byte[10];
    for (var i = 0; i < malformed.length; i++) {
      malformed[i] = (byte) 0x80; // high-bit set, low 7 bits = 0
    }
    var bc = new BytesContainer(malformed);
    assertThrows(IllegalArgumentException.class, () -> VarIntSerializer.readUnsignedVarLong(bc));
  }

  @Test
  public void readUnsignedVarLongRejectsOverflowOnReadBytesContainer() {
    var malformed = new byte[10];
    for (var i = 0; i < malformed.length; i++) {
      malformed[i] = (byte) 0x80;
    }
    var rbc = new ReadBytesContainer(malformed);
    assertThrows(IllegalArgumentException.class, () -> VarIntSerializer.readUnsignedVarLong(rbc));
  }

  @Test
  public void readUnsignedVarLongRejectsOverflowOnDataInput() {
    var malformed = new byte[10];
    for (var i = 0; i < malformed.length; i++) {
      malformed[i] = (byte) 0x80;
    }
    var in = new DataInputStream(new ByteArrayInputStream(malformed));
    assertThrows(IllegalArgumentException.class, () -> VarIntSerializer.readUnsignedVarLong(in));
  }

  @Test
  public void exactlyNineContinuationBytesDoesNotTripGuard() {
    // 9 continuation bytes (i = 0,7,...,56) followed by a terminator byte. The loop
    // exits when terminator byte's high bit is 0, leaving i=63 — the guard is
    // strictly i > 63, so this must succeed. This pins the boundary: a future change
    // tightening the guard to >= 63 would break legitimate 9-byte encodings.
    var input = new byte[10];
    for (var i = 0; i < 9; i++) {
      input[i] = (byte) 0x80;
    }
    input[9] = 0x00; // terminator
    var bc = new BytesContainer(input);
    // No exception expected; result is irrelevant — the point is the guard does not fire.
    VarIntSerializer.readUnsignedVarLong(bc);
  }

  // --- Truncated / EOF handling on DataInput ---

  @Test
  public void readUnsignedVarLongFromTruncatedDataInputThrowsEof() {
    // A byte with high bit set but no follow-up byte produces EOF on the DataInput
    // path — pin the failure mode (callers can distinguish truncation from overflow).
    var truncated = new byte[] {(byte) 0x80};
    var in = new DataInputStream(new ByteArrayInputStream(truncated));
    assertThrows(EOFException.class, () -> VarIntSerializer.readUnsignedVarLong(in));
  }

  // --- Canonical byte-shape pins ---
  // These pin the on-wire / on-disk format so a regression that changes byte order or
  // zigzag mapping fails loudly. Values were derived from the writer at the time of
  // writing. If the wire format intentionally changes, update these pins with the
  // matching readSignedVarLong assertion left untouched as the round-trip check.

  @Test
  public void canonicalEncodingZeroSigned() {
    var bc = new BytesContainer();
    VarIntSerializer.write(bc, 0L);
    // signedToUnsigned(0) = 0; writeUnsignedVarLong(0) -> single byte 0x00
    assertArrayEquals(new byte[] {0x00}, bc.fitBytes());
  }

  @Test
  public void canonicalEncodingPositiveOne() {
    var bc = new BytesContainer();
    VarIntSerializer.write(bc, 1L);
    // zigzag(1) = 2; writeUnsignedVarLong(2) -> single byte 0x02
    assertArrayEquals(new byte[] {0x02}, bc.fitBytes());
  }

  @Test
  public void canonicalEncodingNegativeOne() {
    var bc = new BytesContainer();
    VarIntSerializer.write(bc, -1L);
    // zigzag(-1) = 1; writeUnsignedVarLong(1) -> single byte 0x01
    assertArrayEquals(new byte[] {0x01}, bc.fitBytes());
  }

  @Test
  public void canonicalEncodingPositive63() {
    var bc = new BytesContainer();
    VarIntSerializer.write(bc, 63L);
    // zigzag(63) = 126 = 0x7E (single byte, no continuation)
    assertArrayEquals(new byte[] {0x7E}, bc.fitBytes());
  }

  @Test
  public void canonicalEncodingPositive64() {
    var bc = new BytesContainer();
    VarIntSerializer.write(bc, 64L);
    // zigzag(64) = 128; >127 so two bytes: low 7 bits | 0x80, high 7 bits.
    // 128 -> low7=0x00 | continuation 0x80, then 128 >>> 7 = 1
    assertArrayEquals(new byte[] {(byte) 0x80, 0x01}, bc.fitBytes());
  }

  @Test
  public void canonicalEncodingUnsigned300() {
    var bc = new BytesContainer();
    VarIntSerializer.writeUnsignedVarLong(300L, bc);
    // 300 = 0b100101100
    //   first byte:  0b10101100 = 0xAC (low 7 bits 0x2C with continuation bit)
    //   second byte: 300 >>> 7 = 0x02
    assertArrayEquals(new byte[] {(byte) 0xAC, 0x02}, bc.fitBytes());
  }

  @Test
  public void canonicalEncodingLongMaxValue() {
    var bc = new BytesContainer();
    VarIntSerializer.write(bc, Long.MAX_VALUE);
    // zigzag(Long.MAX_VALUE) = 0xFFFFFFFFFFFFFFFE; encoded as 9 0xFE/0xFF bytes ending
    // in a terminator. Round-trip and verify byte length explicitly — the byte values
    // are deterministic but verbose; pin the length and the round-trip identity.
    var encoded = bc.fitBytes();
    assertEquals("Long.MAX_VALUE encodes in 10 bytes (zigzag widens by 1 bit)", 10, encoded.length);

    var decoded = VarIntSerializer.readSignedVarLong(new BytesContainer(encoded));
    assertEquals(Long.MAX_VALUE, decoded);
  }

  @Test
  public void canonicalEncodingLongMinValue() {
    var bc = new BytesContainer();
    VarIntSerializer.write(bc, Long.MIN_VALUE);
    var encoded = bc.fitBytes();
    assertEquals("Long.MIN_VALUE encodes in 10 bytes (zigzag widens by 1 bit)", 10, encoded.length);

    var decoded = VarIntSerializer.readSignedVarLong(new BytesContainer(encoded));
    assertEquals(Long.MIN_VALUE, decoded);
  }

  // --- BytesContainer write returns starting offset ---

  @Test
  public void bytesContainerWriteReturnsPreWriteOffset() {
    var bc = new BytesContainer();
    bc.alloc(5); // bump offset to 5 first
    var pos = VarIntSerializer.write(bc, 99L);
    assertEquals("write must return the offset BEFORE writing", 5, pos);
  }

  // --- helpers ---

  private void assertDataStreamRoundTrip(long value) throws IOException {
    var out = new ByteArrayOutputStream();
    VarIntSerializer.write(new DataOutputStream(out), value);

    DataInput in = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(value, VarIntSerializer.readAsLong(in));
  }
}
