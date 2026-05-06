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
package com.jetbrains.youtrackdb.internal.core.metadata.security.jwt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * Shape pin for the {@link JwtPayload} interface. PSI all-scope {@code ReferencesSearch}
 * confirms exactly one self-reference (the {@code JsonWebToken.getPayload()} return type) and
 * <strong>zero</strong> implementers in the codebase. The interface is a phantom abstraction
 * that exists only because {@link JsonWebToken} names it.
 *
 * <p>The {@code TokenPayload} parent (and its other concrete implementer
 * {@code BinaryTokenPayloadImpl}) is live through the binary-token cluster — but the
 * binary-token cluster is itself dead-pinned (see
 * {@code BinaryTokenPayloadImplDeadCodeTest}). The {@link JwtPayload} extension adds claims
 * that no production code consumes.
 *
 * <p>WHEN-FIXED: Track 22 — delete {@link JwtPayload} together with {@link JsonWebToken}.
 */
public class JwtPayloadDeadCodeTest {

  // -------------------------------------------------------------------
  // Class-shape pin: public interface extending TokenPayload.
  // -------------------------------------------------------------------
  @Test
  public void classIsPublicInterfaceExtendingTokenPayload() {
    int mods = JwtPayload.class.getModifiers();
    assertTrue("JwtPayload must be public", Modifier.isPublic(mods));
    assertTrue("JwtPayload must be an interface", JwtPayload.class.isInterface());
    assertTrue("JwtPayload must extend TokenPayload",
        TokenPayload.class.isAssignableFrom(JwtPayload.class));
  }

  // -------------------------------------------------------------------
  // Method-shape pin: pin every JWT-claim getter / setter declared on JwtPayload (issuer,
  // issuedAt, notBefore, userName setter, audience, tokenId, database, databaseType). All
  // 12 methods stay together — partial deletion is not meaningful for a JWT claim set.
  // -------------------------------------------------------------------
  @Test
  public void allDeclaredMethodsArePinnedAsAbstractMembersOfTheJwtClaimSet() {
    // Enumerate method names + parameter shapes and confirm the set matches the JWT claim
    // surface exactly. Any addition or rename must update this assertion explicitly.
    Set<String> sigs = new HashSet<>();
    for (Method m : JwtPayload.class.getDeclaredMethods()) {
      assertTrue("each declared method must be abstract",
          Modifier.isAbstract(m.getModifiers()));
      var params = java.util.Arrays.stream(m.getParameterTypes())
          .map(Class::getSimpleName).reduce((a, b) -> a + "," + b).orElse("");
      sigs.add(m.getName() + "(" + params + "):" + m.getReturnType().getSimpleName());
    }
    Set<String> expected = new HashSet<>();
    expected.add("getIssuer():String");
    expected.add("setIssuer(String):void");
    expected.add("getIssuedAt():long");
    expected.add("setIssuedAt(long):void");
    expected.add("getNotBefore():long");
    expected.add("setNotBefore(long):void");
    expected.add("setUserName(String):void");
    expected.add("getAudience():String");
    expected.add("setAudience(String):void");
    expected.add("getTokenId():String");
    expected.add("setTokenId(String):void");
    expected.add("setDatabase(String):void");
    expected.add("setDatabaseType(String):void");
    assertEquals("the JWT claim surface must remain exactly these 13 methods",
        expected, sigs);
  }

  // -------------------------------------------------------------------
  // Cross-check: confirm the parent TokenPayload is still observable on the surface; the
  // dead-pin must not silently turn the parent into a phantom too.
  // -------------------------------------------------------------------
  @Test
  public void parentTokenPayloadInterfaceRemainsObservableInTheClasspath() {
    assertNotNull(TokenPayload.class);
    assertTrue("TokenPayload must remain an interface",
        TokenPayload.class.isInterface());
  }

  // -------------------------------------------------------------------
  // Cross-check: there must be zero classes implementing JwtPayload at the time of pinning.
  // We can't reflectively enumerate "every class on the classpath implementing X" without an
  // index, but we CAN sanity-check via method declaration: no method on JwtPayload exists with
  // a default body, which means the interface cannot be used "just for its defaults".
  // -------------------------------------------------------------------
  @Test
  public void interfaceDeclaresNoDefaultMethodsConfirmingItHasNoStandaloneSemantics()
      throws Exception {
    for (Method m : JwtPayload.class.getDeclaredMethods()) {
      assertSame("each method must be a pure abstract declaration (no default body)",
          false, m.isDefault());
    }
  }
}
