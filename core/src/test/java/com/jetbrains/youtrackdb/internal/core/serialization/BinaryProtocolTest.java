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
package com.jetbrains.youtrackdb.internal.core.serialization;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;

/**
 * Round-trip and on-wire layout pins for {@link BinaryProtocol}, the static helper that the
 * pre-binary-record-serializer code paths use to encode primitive values into byte arrays and
 * streams.
 *
 * <p>Each scalar type has three encode flavours and two/three decode flavours:
 *
 * <ul>
 *   <li>{@code TYPE2bytes(value, byte[], offset)} — write into an existing buffer at a given
 *       offset.
 *   <li>{@code TYPE2bytes(value)} — convenience wrapper that allocates a fresh buffer.
 *   <li>{@code TYPE2bytes(value, OutputStream)} — write to an arbitrary {@link
 *       java.io.OutputStream}; returns the begin offset in the stream when the stream is a
 *       {@link MemoryStream} and {@code -1} otherwise.
 *   <li>{@code bytes2TYPE(byte[], offset)} / {@code bytes2TYPE(byte[])} / {@code
 *       bytes2TYPE(InputStream)} — read back the value.
 * </ul>
 *
 * <p>The encoding is fixed big-endian; tests pin the on-wire byte layout for one high-bit value
 * per type so a regression that flipped to little-endian or shifted the wrong width would
 * fail. Pure round-trip tests then cover boundary values (MIN/MAX/0/-1) at offset 0 and a
 * non-zero offset.
 */
public class BinaryProtocolTest {

  // ---------------------------------------------------------------------------
  // Size constants — pin the contract.
  // ---------------------------------------------------------------------------

  /** Documents the on-wire size constants exposed by BinaryProtocol. */
  @Test
  public void sizeConstantsMatchTypeWidths() {
    Assert.assertEquals(1, BinaryProtocol.SIZE_BYTE);
    Assert.assertEquals(2, BinaryProtocol.SIZE_CHAR);
    Assert.assertEquals(2, BinaryProtocol.SIZE_SHORT);
    Assert.assertEquals(4, BinaryProtocol.SIZE_INT);
    Assert.assertEquals(8, BinaryProtocol.SIZE_LONG);
  }

  // ---------------------------------------------------------------------------
  // char ↔ bytes
  // ---------------------------------------------------------------------------

  /** Pins big-endian on-wire layout for char2bytes; a regression that swapped bytes would fail. */
  @Test
  public void char2bytesIsBigEndian() {
    var buffer = new byte[2];
    BinaryProtocol.char2bytes((char) 0xA028, buffer, 0);
    Assert.assertArrayEquals(new byte[] {(byte) 0xA0, 0x28}, buffer);
  }

  /** Round-trips a high-bit char via byte-array encode/decode at offset 0. */
  @Test
  public void char2bytesAndBytes2charRoundTrip() {
    var buffer = new byte[2];
    BinaryProtocol.char2bytes((char) 0xA028, buffer, 0);
    Assert.assertEquals((char) 0xA028, BinaryProtocol.bytes2char(buffer, 0));
  }

  /** Pins offset arithmetic: writing at non-zero offset leaves leading bytes untouched. */
  @Test
  public void char2bytesHonoursOffset() {
    var buffer = new byte[] {(byte) 0xAA, 0x00, 0x00, (byte) 0xBB};
    BinaryProtocol.char2bytes((char) 0xA028, buffer, 1);
    Assert.assertArrayEquals(
        new byte[] {(byte) 0xAA, (byte) 0xA0, 0x28, (byte) 0xBB}, buffer);
    Assert.assertEquals((char) 0xA028, BinaryProtocol.bytes2char(buffer, 1));
  }

  /** Char boundary round-trips: MIN_VALUE, MAX_VALUE. */
  @Test
  public void char2bytesBoundaryValuesRoundTrip() {
    for (var value : new char[] {Character.MIN_VALUE, Character.MAX_VALUE, ' ', '\u00FF',
        '\uFF00'}) {
      var buffer = new byte[2];
      BinaryProtocol.char2bytes(value, buffer, 0);
      Assert.assertEquals(value, BinaryProtocol.bytes2char(buffer, 0));
    }
  }

  // ---------------------------------------------------------------------------
  // short ↔ bytes
  // ---------------------------------------------------------------------------

  /** Pins big-endian on-wire layout for short2bytes(byte[], offset). */
  @Test
  public void short2bytesIsBigEndian() {
    var buffer = new byte[2];
    BinaryProtocol.short2bytes((short) 0xA028, buffer, 0);
    Assert.assertArrayEquals(new byte[] {(byte) 0xA0, 0x28}, buffer);
  }

  /** The no-buffer wrapper allocates a fresh 2-byte array of identical content. */
  @Test
  public void short2bytesWrapperAllocatesFreshBuffer() {
    var fromWrapper = BinaryProtocol.short2bytes((short) 0xA028);
    Assert.assertArrayEquals(new byte[] {(byte) 0xA0, 0x28}, fromWrapper);

    // Mutating the returned buffer must not affect a subsequent wrapper call.
    fromWrapper[0] = 0;
    var fromWrapper2 = BinaryProtocol.short2bytes((short) 0xA028);
    Assert.assertArrayEquals(new byte[] {(byte) 0xA0, 0x28}, fromWrapper2);
  }

  /** Round-trip for short via byte-array at non-zero offset. */
  @Test
  public void short2bytesHonoursOffset() {
    var buffer = new byte[] {(byte) 0xAA, 0x00, 0x00, (byte) 0xBB};
    BinaryProtocol.short2bytes((short) 0xA028, buffer, 1);
    Assert.assertArrayEquals(
        new byte[] {(byte) 0xAA, (byte) 0xA0, 0x28, (byte) 0xBB}, buffer);
    Assert.assertEquals((short) 0xA028, BinaryProtocol.bytes2short(buffer, 1));
  }

  /** Short boundary round-trips: MIN_VALUE, MAX_VALUE, 0, -1. */
  @Test
  public void short2bytesBoundaryValuesRoundTrip() {
    for (var value : new short[] {Short.MIN_VALUE, Short.MAX_VALUE, (short) 0, (short) -1,
        (short) 1}) {
      var buffer = new byte[2];
      BinaryProtocol.short2bytes(value, buffer, 0);
      Assert.assertEquals(value, BinaryProtocol.bytes2short(buffer, 0));
    }
  }

  /** short2bytes(short, OutputStream) writes 2 big-endian bytes and returns -1 for non-MemoryStream. */
  @Test
  public void short2bytesToOutputStreamIsBigEndianAndReturnsMinusOneForGenericStream()
      throws IOException {
    var bos = new ByteArrayOutputStream();
    var offset = BinaryProtocol.short2bytes((short) 0xA028, bos);
    Assert.assertEquals(-1, offset);
    Assert.assertArrayEquals(new byte[] {(byte) 0xA0, 0x28}, bos.toByteArray());

    var bis = new ByteArrayInputStream(bos.toByteArray());
    Assert.assertEquals((short) 0xA028, BinaryProtocol.bytes2short(bis));
  }

  /** short2bytes(short, MemoryStream) returns the begin position, exposing it for back-patching. */
  @Test
  public void short2bytesToMemoryStreamReturnsBeginPosition() throws IOException {
    var ms = new MemoryStream();
    ms.fill(3); // advance position to 3
    var offset = BinaryProtocol.short2bytes((short) 0xBEEF, ms);
    Assert.assertEquals(3, offset);
    Assert.assertEquals(5, ms.getPosition());
  }

  // ---------------------------------------------------------------------------
  // int ↔ bytes
  // ---------------------------------------------------------------------------

  /** Pins big-endian on-wire layout for int2bytes(byte[], offset). */
  @Test
  public void int2bytesIsBigEndian() {
    var buffer = new byte[4];
    BinaryProtocol.int2bytes(0xFE23A067, buffer, 0);
    Assert.assertArrayEquals(new byte[] {(byte) 0xFE, 0x23, (byte) 0xA0, 0x67}, buffer);
  }

  /** The no-buffer wrapper allocates a fresh 4-byte array of identical content. */
  @Test
  public void int2bytesWrapperAllocatesFreshBuffer() {
    var fromWrapper = BinaryProtocol.int2bytes(0xFE23A067);
    Assert.assertArrayEquals(new byte[] {(byte) 0xFE, 0x23, (byte) 0xA0, 0x67}, fromWrapper);
  }

  /** Round-trip for int via byte-array at non-zero offset. */
  @Test
  public void int2bytesHonoursOffset() {
    var buffer = new byte[] {(byte) 0xAA, 0x00, 0x00, 0x00, 0x00, (byte) 0xBB};
    BinaryProtocol.int2bytes(0xFE23A067, buffer, 1);
    Assert.assertArrayEquals(
        new byte[] {(byte) 0xAA, (byte) 0xFE, 0x23, (byte) 0xA0, 0x67, (byte) 0xBB}, buffer);
    Assert.assertEquals(0xFE23A067, BinaryProtocol.bytes2int(buffer, 1));
  }

  /** Int boundary round-trips: MIN_VALUE, MAX_VALUE, 0, -1. */
  @Test
  public void int2bytesBoundaryValuesRoundTrip() {
    for (var value : new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -1, 1}) {
      var buffer = new byte[4];
      BinaryProtocol.int2bytes(value, buffer, 0);
      Assert.assertEquals(value, BinaryProtocol.bytes2int(buffer, 0));
    }
  }

  /** bytes2int(byte[]) is a wrapper for offset 0. */
  @Test
  public void bytes2intNoOffsetWrapperReadsAtOffsetZero() {
    var buffer = new byte[] {(byte) 0xFE, 0x23, (byte) 0xA0, 0x67};
    Assert.assertEquals(0xFE23A067, BinaryProtocol.bytes2int(buffer));
  }

  /** int2bytes(int, OutputStream) writes 4 BE bytes and returns -1 for non-MemoryStream. */
  @Test
  public void int2bytesToOutputStreamIsBigEndianAndReturnsMinusOneForGenericStream()
      throws IOException {
    var bos = new ByteArrayOutputStream();
    var offset = BinaryProtocol.int2bytes(0xFE23A067, bos);
    Assert.assertEquals(-1, offset);
    Assert.assertArrayEquals(new byte[] {(byte) 0xFE, 0x23, (byte) 0xA0, 0x67}, bos.toByteArray());

    var bis = new ByteArrayInputStream(bos.toByteArray());
    Assert.assertEquals(0xFE23A067, BinaryProtocol.bytes2int(bis));
  }

  /** int2bytes(int, MemoryStream) returns the begin position. */
  @Test
  public void int2bytesToMemoryStreamReturnsBeginPosition() throws IOException {
    var ms = new MemoryStream();
    ms.fill(2);
    var offset = BinaryProtocol.int2bytes(0xCAFEBABE, ms);
    Assert.assertEquals(2, offset);
    Assert.assertEquals(6, ms.getPosition());
  }

  // ---------------------------------------------------------------------------
  // long ↔ bytes
  // ---------------------------------------------------------------------------

  /** Pins big-endian on-wire layout for long2bytes(byte[], offset). */
  @Test
  public void long2bytesIsBigEndian() {
    var buffer = new byte[8];
    BinaryProtocol.long2bytes(0xFE23A067ED890C14L, buffer, 0);
    Assert.assertArrayEquals(
        new byte[] {(byte) 0xFE, 0x23, (byte) 0xA0, 0x67, (byte) 0xED, (byte) 0x89, 0x0C, 0x14},
        buffer);
  }

  /** The no-buffer wrapper allocates a fresh 8-byte array of identical content. */
  @Test
  public void long2bytesWrapperAllocatesFreshBuffer() {
    var fromWrapper = BinaryProtocol.long2bytes(0xFE23A067ED890C14L);
    Assert.assertArrayEquals(
        new byte[] {(byte) 0xFE, 0x23, (byte) 0xA0, 0x67, (byte) 0xED, (byte) 0x89, 0x0C, 0x14},
        fromWrapper);
  }

  /** Round-trip for long via byte-array at non-zero offset. */
  @Test
  public void long2bytesHonoursOffset() {
    var buffer = new byte[10];
    buffer[0] = (byte) 0xAA;
    buffer[9] = (byte) 0xBB;
    BinaryProtocol.long2bytes(0xFE23A067ED890C14L, buffer, 1);
    Assert.assertArrayEquals(
        new byte[] {
            (byte) 0xAA,
            (byte) 0xFE,
            0x23,
            (byte) 0xA0,
            0x67,
            (byte) 0xED,
            (byte) 0x89,
            0x0C,
            0x14,
            (byte) 0xBB
        },
        buffer);
    Assert.assertEquals(0xFE23A067ED890C14L, BinaryProtocol.bytes2long(buffer, 1));
  }

  /** Long boundary round-trips: MIN_VALUE, MAX_VALUE, 0, -1. */
  @Test
  public void long2bytesBoundaryValuesRoundTrip() {
    for (var value : new long[] {Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, 1L}) {
      var buffer = new byte[8];
      BinaryProtocol.long2bytes(value, buffer, 0);
      Assert.assertEquals(value, BinaryProtocol.bytes2long(buffer, 0));
    }
  }

  /** bytes2long(byte[]) is a wrapper for offset 0. */
  @Test
  public void bytes2longNoOffsetWrapperReadsAtOffsetZero() {
    var buffer = new byte[8];
    BinaryProtocol.long2bytes(0xCAFEBABEDEADBEEFL, buffer, 0);
    Assert.assertEquals(0xCAFEBABEDEADBEEFL, BinaryProtocol.bytes2long(buffer));
  }

  /** long2bytes(long, OutputStream) writes 8 BE bytes and returns -1 for non-MemoryStream. */
  @Test
  public void long2bytesToOutputStreamIsBigEndianAndReturnsMinusOneForGenericStream()
      throws IOException {
    var bos = new ByteArrayOutputStream();
    var offset = BinaryProtocol.long2bytes(0xFE23A067ED890C14L, bos);
    Assert.assertEquals(-1, offset);
    Assert.assertArrayEquals(
        new byte[] {(byte) 0xFE, 0x23, (byte) 0xA0, 0x67, (byte) 0xED, (byte) 0x89, 0x0C, 0x14},
        bos.toByteArray());

    var bis = new ByteArrayInputStream(bos.toByteArray());
    Assert.assertEquals(0xFE23A067ED890C14L, BinaryProtocol.bytes2long(bis));
  }

  /** long2bytes(long, MemoryStream) returns the begin position. */
  @Test
  public void long2bytesToMemoryStreamReturnsBeginPosition() throws IOException {
    var ms = new MemoryStream();
    ms.fill(7);
    var offset = BinaryProtocol.long2bytes(0xDEADBEEFCAFEBABEL, ms);
    Assert.assertEquals(7, offset);
    Assert.assertEquals(15, ms.getPosition());
  }

  // ---------------------------------------------------------------------------
  // Wire format for narrowed inputs
  //
  // The encode helpers take a Java primitive (char/short/int/long) by value, so
  // the explicit narrowing cast at the call site ((short)/(char)) drops the high
  // bits before the helper sees the value — the helpers themselves never see a
  // wider input. The two tests below pin the resulting on-wire bytes for a
  // narrowed input, documenting the cast-then-encode pipeline rather than any
  // truncation behaviour internal to BinaryProtocol.
  // ---------------------------------------------------------------------------

  /**
   * The narrowing cast at the call site ((short) 0x12345678 == 0x5678) drops the high 16 bits;
   * pin the resulting wire layout {@code {0x56, 0x78}}.
   */
  @Test
  public void short2bytesWritesLowSixteenBitsOfNarrowedShort() {
    var buffer = new byte[2];
    BinaryProtocol.short2bytes((short) 0x12345678, buffer, 0);
    Assert.assertArrayEquals(new byte[] {0x56, 0x78}, buffer);
    Assert.assertEquals((short) 0x5678, BinaryProtocol.bytes2short(buffer, 0));
  }

  /**
   * The narrowing cast at the call site ((char) 0x12345678 == 0x5678) drops the high 16 bits;
   * pin the resulting wire layout {@code {0x56, 0x78}}.
   */
  @Test
  public void char2bytesWritesLowSixteenBitsOfNarrowedChar() {
    var buffer = new byte[2];
    BinaryProtocol.char2bytes((char) 0x12345678, buffer, 0);
    Assert.assertArrayEquals(new byte[] {0x56, 0x78}, buffer);
    Assert.assertEquals((char) 0x5678, BinaryProtocol.bytes2char(buffer, 0));
  }

  // ---------------------------------------------------------------------------
  // Stream short-read pin
  //
  // Reading from a stream that returns -1 (end-of-stream) before the requested
  // bytes are fulfilled means the bit-shift composes the value from -1 sign bits.
  // We pin the documented behaviour: bytes2int from an empty InputStream returns -1
  // because every byte read returns -1, all bits set. This is the historical
  // contract; a future change to detect short-reads would need to update the test.
  // ---------------------------------------------------------------------------

  /** bytes2int over an empty InputStream produces -1; documents short-read behaviour. */
  @Test
  public void bytes2intOverEmptyStreamReturnsMinusOne() throws IOException {
    var bis = new ByteArrayInputStream(new byte[0]);
    Assert.assertEquals(-1, BinaryProtocol.bytes2int(bis));
  }

  /** bytes2long over an empty InputStream produces -1L; documents short-read behaviour. */
  @Test
  public void bytes2longOverEmptyStreamReturnsMinusOne() throws IOException {
    var bis = new ByteArrayInputStream(new byte[0]);
    Assert.assertEquals(-1L, BinaryProtocol.bytes2long(bis));
  }

  /**
   * bytes2short on an empty InputStream produces -1 — same short-read pathology as the int and
   * long forms; previously unpinned for the 16-bit form even though the documented contract is
   * symmetric.
   */
  @Test
  public void bytes2shortOverEmptyStreamReturnsMinusOne() throws IOException {
    var bis = new ByteArrayInputStream(new byte[0]);
    Assert.assertEquals(-1, BinaryProtocol.bytes2short(bis));
  }

  /**
   * Partial-stream pin: reading {@code bytes2int} over a 3-byte stream composes the first three
   * bytes plus a 4th byte of {@code 0xFF} (every read past EOF returns -1). Pinning the partial
   * case alongside the fully-empty case catches a regression where short-read semantics differ
   * for partial inputs (e.g., a future hardening that throws on EOF mid-read would surface here).
   */
  @Test
  public void bytes2intOverPartialStreamComposesAvailableBytesPlusEofFill() throws IOException {
    var bis = new ByteArrayInputStream(new byte[] {0x12, 0x34, 0x56});
    Assert.assertEquals(0x123456FF, BinaryProtocol.bytes2int(bis));
  }

  /**
   * Partial-stream pin for the long form — symmetric with the int partial-read pin above. Reads
   * 7 bytes from the input then composes -1 into the low byte.
   */
  @Test
  public void bytes2longOverPartialStreamComposesAvailableBytesPlusEofFill() throws IOException {
    var bis = new ByteArrayInputStream(new byte[] {0x12, 0x34, 0x56, 0x78, (byte) 0x9A,
        (byte) 0xBC, (byte) 0xDE});
    Assert.assertEquals(0x123456789ABCDEFFL, BinaryProtocol.bytes2long(bis));
  }
}
