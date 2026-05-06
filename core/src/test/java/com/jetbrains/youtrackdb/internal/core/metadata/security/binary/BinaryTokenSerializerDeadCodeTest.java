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
package com.jetbrains.youtrackdb.internal.core.metadata.security.binary;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.TokenMetaInfo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

/**
 * Shape pin for {@link BinaryTokenSerializer}. PSI all-scope {@code ReferencesSearch} confirms
 * its only callers are within the same dead {@code core/metadata/security/binary} cluster
 * (BinaryTokenPayloadImpl + BinaryTokenPayloadDeserializer, both also dead-pinned). The class
 * implements {@link TokenMetaInfo} but no production code outside the dead cluster looks up the
 * binary token codec.
 *
 * <p>The serializer is a pure in-memory codec — no JCE, no I/O outside the test-controlled
 * streams — so the pin can do a full round-trip on a synthetic {@link BinaryToken}: construct,
 * serialise, deserialise, observe equality. That gives a stronger pin than reflective shape
 * alone while remaining standalone.
 *
 * <p>WHEN-FIXED: Track 22 — delete {@link BinaryTokenSerializer} together with the rest of the
 * binary-token cluster.
 */
public class BinaryTokenSerializerDeadCodeTest {

  // -------------------------------------------------------------------
  // Class-shape pin: public concrete class implementing TokenMetaInfo.
  // -------------------------------------------------------------------
  @Test
  public void classImplementsTokenMetaInfoAndIsPublicConcrete() {
    int mods = BinaryTokenSerializer.class.getModifiers();
    assertTrue("BinaryTokenSerializer must be public", Modifier.isPublic(mods));
    assertFalse("BinaryTokenSerializer must be a concrete class",
        Modifier.isAbstract(mods));
    assertFalse("BinaryTokenSerializer must not be an interface",
        BinaryTokenSerializer.class.isInterface());
    assertTrue("BinaryTokenSerializer must implement TokenMetaInfo",
        TokenMetaInfo.class.isAssignableFrom(BinaryTokenSerializer.class));
  }

  // -------------------------------------------------------------------
  // No-arg constructor pin: this is the constructor used internally by the dead cluster (the
  // 4-arg variant takes the metadata arrays directly).
  // -------------------------------------------------------------------
  @Test
  public void noArgConstructorYieldsADefaultMetadataConfiguration() throws Exception {
    var ctor = BinaryTokenSerializer.class.getDeclaredConstructor();
    assertTrue("no-arg constructor must be public",
        Modifier.isPublic(ctor.getModifiers()));
    var s = new BinaryTokenSerializer();
    assertNotNull(s);
    // Confirm the default db-type table includes "disk" and "memory" — pinned via the public
    // TokenMetaInfo contract.
    assertEquals("default db-type id 0 must be 'disk'", "disk", s.getDbType(0));
    assertEquals("default db-type id 1 must be 'memory'", "memory", s.getDbType(1));
    assertEquals("getDbTypeID('disk') must round-trip to 0", 0, s.getDbTypeID("disk"));
    assertEquals("getDbTypeID('memory') must round-trip to 1", 1, s.getDbTypeID("memory"));
  }

  // -------------------------------------------------------------------
  // Four-arg constructor pin: (dbTypes, keys, algorithms, entityTypes) — used by the
  // serialiser SPI for custom configurations. Pin the parameter shape so a refactor that
  // swaps the parameter order or array element type is caught.
  // -------------------------------------------------------------------
  @Test
  public void fourArgConstructorAcceptsFourStringArrayMetadataArguments() throws Exception {
    var ctor = BinaryTokenSerializer.class.getDeclaredConstructor(
        String[].class, String[].class, String[].class, String[].class);
    assertTrue("4-arg constructor must be public",
        Modifier.isPublic(ctor.getModifiers()));
    assertEquals("4-arg constructor must take four String[] parameters",
        4, ctor.getParameterCount());
    var s = new BinaryTokenSerializer(
        new String[] {"a", "b"},
        new String[] {"k1"},
        new String[] {"alg1"},
        new String[] {"YouTrackDB"});
    assertEquals("custom db-type id 0", "a", s.getDbType(0));
    assertEquals("custom db-type id 1", "b", s.getDbType(1));
    assertEquals("getDbTypeID('a')", 0, s.getDbTypeID("a"));
  }

  // -------------------------------------------------------------------
  // Method-shape pin: serialize(BinaryToken, OutputStream) throws IOException.
  // -------------------------------------------------------------------
  @Test
  public void serializeMethodSignaturePinsBinaryTokenAndOutputStream() throws Exception {
    Method m = BinaryTokenSerializer.class.getDeclaredMethod(
        "serialize", BinaryToken.class, java.io.OutputStream.class);
    assertTrue("serialize must be public", Modifier.isPublic(m.getModifiers()));
    assertSame("serialize must return void", void.class, m.getReturnType());
    assertTrue("serialize must declare IOException",
        java.util.Arrays.asList(m.getExceptionTypes()).contains(IOException.class));
  }

  // -------------------------------------------------------------------
  // Method-shape pin: deserialize(InputStream) throws IOException, returns BinaryToken.
  // -------------------------------------------------------------------
  @Test
  public void deserializeMethodSignaturePinsInputStreamReturningBinaryToken() throws Exception {
    Method m = BinaryTokenSerializer.class.getDeclaredMethod(
        "deserialize", java.io.InputStream.class);
    assertTrue("deserialize must be public", Modifier.isPublic(m.getModifiers()));
    assertSame("deserialize must return BinaryToken", BinaryToken.class, m.getReturnType());
    assertTrue("deserialize must declare IOException",
        java.util.Arrays.asList(m.getExceptionTypes()).contains(IOException.class));
  }

  // -------------------------------------------------------------------
  // Round-trip pin: construct a synthetic BinaryToken with header + payload, serialise, parse
  // back, observe equality on the load-bearing payload fields. Stronger than reflective shape
  // and exercises the codec end-to-end.
  // -------------------------------------------------------------------
  @Test
  public void serialiseDeserialiseRoundTripPreservesPayloadFields() throws Exception {
    var serializer = new BinaryTokenSerializer();

    var header =
        new com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.YouTrackDBJwtHeader();
    header.setType("YouTrackDB");
    header.setKeyId("dafault"); // matches the serializer's default key table (typo retained)
    header.setAlgorithm("HmacSHA256");

    var payload = new BinaryTokenPayloadImpl();
    payload.setDatabase("dbname");
    payload.setDatabaseType("disk");
    payload.setExpiry(99_999L);
    payload.setProtocolVersion((short) 7);
    payload.setSerializer("ser");
    payload.setDriverName("drv");
    payload.setDriverVersion("v1");
    payload.setServerUser(false);

    var token = new BinaryToken();
    token.setHeader(header);
    token.setPayload(payload);

    var out = new ByteArrayOutputStream();
    serializer.serialize(token, out);
    var bytes = out.toByteArray();
    assertTrue("serialised form must be non-empty", bytes.length > 0);

    var parsed = serializer.deserialize(new ByteArrayInputStream(bytes));
    assertNotNull("deserialise must return a non-null BinaryToken", parsed);
    assertEquals("database round-trip", "dbname", parsed.getDatabaseName());
    assertEquals("database type round-trip", "disk", parsed.getDatabaseType());
    assertEquals("expiry round-trip", 99_999L, parsed.getExpiry());
    assertEquals("protocol version round-trip", (short) 7, parsed.getProtocolVersion());
    assertEquals("serializer round-trip", "ser", parsed.getSerializer());
    assertEquals("driver name round-trip", "drv", parsed.getDriverName());
    assertEquals("driver version round-trip", "v1", parsed.getDriverVersion());
    assertFalse("server-user round-trip (false branch)", parsed.isServerUser());
  }

  // -------------------------------------------------------------------
  // writeString pin: the static helper writes a 16-bit length prefix + UTF-8 bytes, or -1 for
  // null. Pin shape + null behaviour.
  // -------------------------------------------------------------------
  @Test
  public void writeStringNullEncodesAsMinusOneShort() throws Exception {
    var bos = new ByteArrayOutputStream();
    var out = new java.io.DataOutputStream(bos);
    BinaryTokenSerializer.writeString(out, null);
    out.flush();
    var bytes = bos.toByteArray();
    // -1 short = 0xFFFF in two bytes.
    assertArrayEquals("writeString(null) must encode as 0xFF 0xFF",
        new byte[] {(byte) 0xFF, (byte) 0xFF}, bytes);
  }

  @Test
  public void writeStringNonNullEncodesLengthPrefixedUtf8Bytes() throws Exception {
    var bos = new ByteArrayOutputStream();
    var out = new java.io.DataOutputStream(bos);
    BinaryTokenSerializer.writeString(out, "hi");
    out.flush();
    var bytes = bos.toByteArray();
    // 0x0002 prefix + "hi" (UTF-8: 0x68 0x69)
    assertArrayEquals("writeString('hi') must encode as 0x00 0x02 'h' 'i'",
        new byte[] {0x00, 0x02, 0x68, 0x69}, bytes);
  }
}
