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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.metadata.security.Token;
import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.BinaryTokenPayload;
import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.TokenHeader;
import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.YouTrackDBJwtHeader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

/**
 * Shape pin for {@link BinaryToken}. PSI all-scope {@code ReferencesSearch} confirms zero
 * non-self callers across all five Maven modules; the only references are within the dead
 * {@code core/metadata/security/binary} package itself ({@link BinaryTokenSerializer} for
 * serialise/deserialise, also dead-pinned).
 *
 * <p>The class is dead together with its sibling cluster: {@link BinaryTokenSerializer},
 * {@link BinaryTokenPayloadImpl}, {@link BinaryTokenPayloadDeserializer}, and
 * {@link DistributedBinaryTokenPayload}. A Track 22 deletion must drop the entire
 * {@code core/metadata/security/binary} package together with the SPI plumbing in
 * {@link com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.TokenMetaInfo} that wires
 * the binary-token codec.
 *
 * <p>Standalone: the class is a plain POJO over the {@link Token} contract; no database session
 * is required to construct an instance and read back its fields.
 *
 * <p>WHEN-FIXED: Track 22 — delete {@link BinaryToken}.
 */
public class BinaryTokenDeadCodeTest {

  // -------------------------------------------------------------------
  // Class-shape pin: BinaryToken is a public concrete class implementing the Token interface.
  // -------------------------------------------------------------------
  @Test
  public void classIsPublicConcreteImplementingToken() {
    int mods = BinaryToken.class.getModifiers();
    assertTrue("BinaryToken must be public", Modifier.isPublic(mods));
    assertFalse("BinaryToken must be a concrete class", Modifier.isAbstract(mods));
    assertFalse("BinaryToken must not be an interface", BinaryToken.class.isInterface());
    assertTrue("BinaryToken must implement Token",
        Token.class.isAssignableFrom(BinaryToken.class));
  }

  // -------------------------------------------------------------------
  // Field-shape pin: header + payload are private mutable references; valid + verified are
  // primitive booleans.
  // -------------------------------------------------------------------
  @Test
  public void fieldShapeIncludesHeaderPayloadValidAndVerified() throws Exception {
    Field header = BinaryToken.class.getDeclaredField("header");
    assertSame("header field must be a TokenHeader", TokenHeader.class, header.getType());
    assertTrue("header field must be private", Modifier.isPrivate(header.getModifiers()));

    Field payload = BinaryToken.class.getDeclaredField("payload");
    assertSame("payload field must be a BinaryTokenPayload",
        BinaryTokenPayload.class, payload.getType());
    assertTrue("payload field must be private", Modifier.isPrivate(payload.getModifiers()));

    Field valid = BinaryToken.class.getDeclaredField("valid");
    assertSame("valid field must be primitive boolean", boolean.class, valid.getType());
    assertTrue("valid field must be private", Modifier.isPrivate(valid.getModifiers()));

    Field verified = BinaryToken.class.getDeclaredField("verified");
    assertSame("verified field must be primitive boolean", boolean.class, verified.getType());
    assertTrue("verified field must be private", Modifier.isPrivate(verified.getModifiers()));
  }

  // -------------------------------------------------------------------
  // Header / payload setter shape pins.
  // -------------------------------------------------------------------
  @Test
  public void setHeaderSignatureAcceptsTokenHeader() throws Exception {
    Method m = BinaryToken.class.getDeclaredMethod("setHeader", TokenHeader.class);
    assertTrue("setHeader must be public", Modifier.isPublic(m.getModifiers()));
    assertSame("setHeader must return void", void.class, m.getReturnType());
  }

  @Test
  public void setPayloadSignatureAcceptsBinaryTokenPayload() throws Exception {
    Method m = BinaryToken.class.getDeclaredMethod("setPayload", BinaryTokenPayload.class);
    assertTrue("setPayload must be public", Modifier.isPublic(m.getModifiers()));
    assertSame("setPayload must return void", void.class, m.getReturnType());
  }

  // -------------------------------------------------------------------
  // Behavioural pin: a freshly-constructed BinaryToken returns the field defaults via the
  // Token contract. Constructing a default instance is safe (no I/O, no JCE).
  // -------------------------------------------------------------------
  @Test
  public void freshlyConstructedTokenReportsFalseValidAndFalseVerified() {
    var token = new BinaryToken();
    assertFalse("a fresh BinaryToken must report getIsValid() == false", token.getIsValid());
    assertFalse("a fresh BinaryToken must report getIsVerified() == false",
        token.getIsVerified());
    assertNull("a fresh BinaryToken must have a null header", token.getHeader());
    assertNull("a fresh BinaryToken must have a null payload", token.getPayload());
  }

  // -------------------------------------------------------------------
  // Behavioural pin: setIsValid / setIsVerified flip the underlying field. Both flags toggle
  // independently.
  // -------------------------------------------------------------------
  @Test
  public void setIsValidAndSetIsVerifiedFlipFieldsIndependently() {
    var token = new BinaryToken();
    token.setIsValid(true);
    token.setIsVerified(true);
    assertTrue("after setIsValid(true), getIsValid must be true", token.getIsValid());
    assertTrue("after setIsVerified(true), getIsVerified must be true", token.getIsVerified());
    token.setIsValid(false);
    assertFalse("after setIsValid(false), getIsValid must flip back to false",
        token.getIsValid());
    assertTrue("setIsValid must not affect verified", token.getIsVerified());
  }

  // -------------------------------------------------------------------
  // Behavioural pin: setHeader / setPayload + delegating getters wire through to the payload's
  // own getters. The pin uses fresh BinaryTokenPayloadImpl instances (also dead-pinned).
  // -------------------------------------------------------------------
  @Test
  public void delegatingGettersForwardToPayloadFieldsAndHeaderField() {
    var header = new YouTrackDBJwtHeader();
    header.setAlgorithm("HS256");
    var payload = new BinaryTokenPayloadImpl();
    payload.setUserName("alice");
    payload.setDatabase("db");
    payload.setDatabaseType("plocal");
    payload.setExpiry(123L);
    payload.setProtocolVersion((short) 42);
    payload.setSerializer("ser");
    payload.setDriverName("drv");
    payload.setDriverVersion("v1");
    payload.setServerUser(true);

    var token = new BinaryToken();
    token.setHeader(header);
    token.setPayload(payload);

    assertSame("getHeader must return the assigned header", header, token.getHeader());
    assertSame("getPayload must return the assigned payload", payload, token.getPayload());
    assertEquals("getUserName must delegate to payload", "alice", token.getUserName());
    assertEquals("getDatabaseName must delegate to payload", "db", token.getDatabaseName());
    assertEquals("getDatabaseType must delegate to payload", "plocal", token.getDatabaseType());
    assertNull("getUserId must return the payload's null userRid (default)",
        token.getUserId());
    assertEquals("getExpiry must delegate to payload", 123L, token.getExpiry());
    assertEquals("getProtocolVersion must delegate to payload", (short) 42,
        token.getProtocolVersion());
    assertEquals("getSerializer must delegate to payload", "ser", token.getSerializer());
    assertEquals("getDriverName must delegate to payload", "drv", token.getDriverName());
    assertEquals("getDriverVersion must delegate to payload", "v1", token.getDriverVersion());
    assertTrue("isServerUser must delegate to payload", token.isServerUser());
  }

  // -------------------------------------------------------------------
  // Behavioural pin: setExpiry forwards to payload.setExpiry and getExpiry reads it back.
  // -------------------------------------------------------------------
  @Test
  public void setExpiryAndGetExpiryRoundtripThroughPayload() {
    var payload = new BinaryTokenPayloadImpl();
    var token = new BinaryToken();
    token.setPayload(payload);
    token.setExpiry(7777L);
    assertEquals("setExpiry must forward to payload",
        7777L, payload.getExpiry());
    assertEquals("getExpiry must read back the same value",
        7777L, token.getExpiry());
  }

  // -------------------------------------------------------------------
  // Behavioural pin: isNowValid + isCloseToExpire compare against System.currentTimeMillis().
  // The 120000 ms threshold is a hardcoded constant in the production class; pin its semantic
  // (an expiry 5 minutes in the future is "valid and not close").
  // -------------------------------------------------------------------
  @Test
  public void isNowValidIsTrueWhenExpiryIsFiveMinutesInTheFuture() {
    var payload = new BinaryTokenPayloadImpl();
    payload.setExpiry(System.currentTimeMillis() + 5 * 60_000L);
    var token = new BinaryToken();
    token.setPayload(payload);
    assertTrue("expiry 5 min in the future must be reported as nowValid",
        token.isNowValid());
    assertFalse(
        "expiry 5 min in the future is more than 120 seconds out, so isCloseToExpire is false",
        token.isCloseToExpire());
  }

  @Test
  public void isCloseToExpireIsTrueWhenExpiryIsWithinTheTwoMinuteWindow() {
    var payload = new BinaryTokenPayloadImpl();
    payload.setExpiry(System.currentTimeMillis() + 30_000L);
    var token = new BinaryToken();
    token.setPayload(payload);
    assertTrue("expiry 30 s out must be reported as nowValid", token.isNowValid());
    assertTrue("expiry 30 s out must be reported as close-to-expire (< 120 000 ms)",
        token.isCloseToExpire());
  }

  // -------------------------------------------------------------------
  // SPI signature pin: Token interface methods that must remain on BinaryToken's surface for
  // the dead chain to compile until the lockstep deletion lands.
  // -------------------------------------------------------------------
  @Test
  public void allTokenInterfaceMethodsHaveOverridesInBinaryToken() throws Exception {
    Method[] tokenMethods = Token.class.getDeclaredMethods();
    for (Method m : tokenMethods) {
      // Each Token method must have a concrete override on BinaryToken.
      Method override = BinaryToken.class.getMethod(m.getName(), m.getParameterTypes());
      assertNotNull("BinaryToken must override Token." + m.getName(), override);
      assertFalse("the override must be concrete (non-abstract)",
          Modifier.isAbstract(override.getModifiers()));
    }
  }
}
