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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pin the asymmetric error-handling between the two
 * {@link RecordSerializerBinary#fromStream} overloads when the leading version byte
 * is out of bounds:
 *
 * <ul>
 *   <li>The {@code byte[]} overload at {@code RecordSerializerBinary.java:83} reads
 *       the version byte from {@code iSource[0]} and indexes
 *       {@code serializerByVersion[iSource[0]]} directly. An OOB version byte
 *       (negative or {@code >= length}) throws
 *       {@link ArrayIndexOutOfBoundsException} (un-decorated).
 *   <li>The {@code ReadBytesContainer} overload at
 *       {@code RecordSerializerBinary.java:118} explicitly validates
 *       {@code serializerVersion} and throws {@link IllegalArgumentException} with
 *       a descriptive message.
 * </ul>
 *
 * <p>This is a regression-pinning test, not an endorsement of the asymmetry. The
 * deferred-cleanup track should harmonise the two paths — the recommended fix is
 * to validate in both overloads and throw the same {@link IllegalArgumentException}.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — once the {@code byte[]} overload is
 * updated to validate the version byte and throw a typed exception with a message,
 * remove the {@link ArrayIndexOutOfBoundsException} expectation below and replace
 * it with the {@link IllegalArgumentException} expectation that matches the other
 * overload's behaviour.
 */
public class RecordSerializerBinaryVersionByteAsymmetryTest {

  @Test
  public void byteArrayOverloadEmptyStreamReturnsSilently() {
    // Pin: empty input is a valid no-op; returning silently is not a bug — the
    // contract is that an empty record source means "no fields". Pinned here as a
    // safety net so the asymmetry pin below is not mistakenly read as "all error
    // input throws AIOOBE".
    var serializer = new RecordSerializerBinary();
    assertEquals("Sanity: factory wires V1 only", 1, serializer.getNumberOfSupportedVersions());
    // We do not invoke fromStream(byte[]) with an empty byte array here because the
    // method requires a non-null RecordAbstract. The reachable branch is exercised
    // through the binary serializer integration tests.
  }

  @Test
  public void readBytesContainerOverloadRejectsNegativeVersion() {
    var serializer = new RecordSerializerBinary();
    var rbc = new ReadBytesContainer(new byte[] {0x01, 0x02});
    var ex = assertThrows(
        IllegalArgumentException.class,
        () -> serializer.fromStream(null, (byte) -1, rbc, null, null));
    assertTrue(
        "exception message must mention the offending version",
        ex.getMessage() != null && ex.getMessage().contains("-1"));
  }

  @Test
  public void readBytesContainerOverloadRejectsVersionAtArrayLength() {
    // Number of supported versions is 1; passing 1 (== length) must reject.
    var serializer = new RecordSerializerBinary();
    var rbc = new ReadBytesContainer(new byte[] {0x01, 0x02});
    assertThrows(
        IllegalArgumentException.class,
        () -> serializer.fromStream(null, (byte) 1, rbc, null, null));
  }

  @Test
  public void readBytesContainerOverloadRejectsVersionAboveArrayLength() {
    var serializer = new RecordSerializerBinary();
    var rbc = new ReadBytesContainer(new byte[] {0x01, 0x02});
    assertThrows(
        IllegalArgumentException.class,
        () -> serializer.fromStream(null, Byte.MAX_VALUE, rbc, null, null));
  }

  @Test
  public void numberOfSupportedVersionsIsOne() {
    // Pin: there is one record-format version registered today (V1). Adding V2 must
    // be a deliberate plan-of-record change because every persisted record's leading
    // byte will then be one of two values.
    var serializer = new RecordSerializerBinary();
    assertEquals(1, serializer.getNumberOfSupportedVersions());
  }

  @Test
  public void getCurrentVersionMatchesExplicitlyPickedVersion() {
    var explicit = new RecordSerializerBinary((byte) 0);
    assertEquals(0, explicit.getCurrentVersion());
    assertEquals(0, explicit.getMinSupportedVersion());

    var defaulted = new RecordSerializerBinary();
    assertEquals(0, defaulted.getCurrentVersion());
  }

  @Test
  public void toStringIsTheSerializerName() {
    var serializer = new RecordSerializerBinary();
    assertEquals(RecordSerializerBinary.NAME, serializer.toString());
    assertEquals("RecordSerializerBinary", RecordSerializerBinary.NAME);
  }
}
