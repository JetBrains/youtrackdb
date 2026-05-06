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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

/**
 * Shape pin for the {@link JsonWebToken} interface. PSI all-scope {@code ReferencesSearch}
 * confirms <strong>zero</strong> references and zero implementers anywhere in the codebase —
 * the interface is a phantom JWT abstraction that no concrete class realises. The
 * generic-token plumbing in the same package ({@link TokenHeader}, {@link TokenPayload},
 * {@link TokenMetaInfo}, {@link KeyProvider}, {@link BinaryTokenPayload},
 * {@link TokenPayloadDeserializer}) is live through the {@code Token} / {@code TokenSignImpl}
 * path and is NOT part of this dead surface.
 *
 * <p>WHEN-FIXED: Track 22 — delete {@link JsonWebToken} together with {@link JwtPayload} and
 * {@link YouTrackDBJwtHeader} (the only header impl that names the dead JWT abstraction
 * indirectly).
 */
public class JsonWebTokenDeadCodeTest {

  // -------------------------------------------------------------------
  // Class-shape pin: JsonWebToken is a public interface.
  // -------------------------------------------------------------------
  @Test
  public void classIsPublicInterface() {
    int mods = JsonWebToken.class.getModifiers();
    assertTrue("JsonWebToken must be public", Modifier.isPublic(mods));
    assertTrue("JsonWebToken must be an interface", JsonWebToken.class.isInterface());
  }

  // -------------------------------------------------------------------
  // Abstract-method pin: exactly two methods declared (getHeader + getPayload).
  // -------------------------------------------------------------------
  @Test
  public void interfaceDeclaresExactlyTwoAbstractMethods() {
    Method[] methods = JsonWebToken.class.getDeclaredMethods();
    assertEquals("JsonWebToken must declare exactly two abstract methods",
        2, methods.length);
  }

  @Test
  public void getHeaderReturnsTokenHeaderAndIsAbstract() throws Exception {
    Method m = JsonWebToken.class.getDeclaredMethod("getHeader");
    assertTrue("getHeader must be abstract", Modifier.isAbstract(m.getModifiers()));
    assertSame("getHeader must return TokenHeader", TokenHeader.class, m.getReturnType());
    assertEquals("getHeader must take zero parameters", 0, m.getParameterCount());
  }

  @Test
  public void getPayloadReturnsJwtPayloadAndIsAbstract() throws Exception {
    Method m = JsonWebToken.class.getDeclaredMethod("getPayload");
    assertTrue("getPayload must be abstract", Modifier.isAbstract(m.getModifiers()));
    assertSame("getPayload must return JwtPayload", JwtPayload.class, m.getReturnType());
    assertEquals("getPayload must take zero parameters", 0, m.getParameterCount());
  }
}
