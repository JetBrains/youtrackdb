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
package com.jetbrains.youtrackdb.internal.core.security.symmetrickey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.core.exception.SecurityException;
import com.jetbrains.youtrackdb.internal.core.security.CredentialInterceptor;
import com.jetbrains.youtrackdb.internal.core.security.SecurityManagerNewCredentialInterceptorDeadCodeTest;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.Test;

/**
 * Shape pin for {@link SymmetricKeyCI}, the symmetric-key flavour of the
 * {@link CredentialInterceptor} SPI. PSI all-scope {@code ReferencesSearch} confirms
 * <strong>zero</strong> non-self references to the class — no production code instantiates it,
 * and the SPI loader (see {@link SecurityManagerNewCredentialInterceptorDeadCodeTest}) has zero
 * callers.
 *
 * <p>The class is also the only on-tree caller of several dead {@link SymmetricKey} static
 * factories ({@code fromString}, {@code fromFile}, {@code fromKeystore},
 * {@code setDefaultCipherTransform}, {@code separateAlgorithm}). Track 22 must drop
 * {@link SymmetricKeyCI} together with those methods.
 *
 * <p>The pin stops at parameter-null guards — {@code intercept(null, null, null)} throws at
 * line 60 of the production class before any JCE invocation, so the safe path proves the
 * guard is wired without exercising the live JCE round-trip.
 *
 * <p>WHEN-FIXED: Track 22 — delete {@link SymmetricKeyCI}.
 */
public class SymmetricKeyCIDeadCodeTest {

  // -------------------------------------------------------------------
  // Class-shape pin.
  // -------------------------------------------------------------------
  @Test
  public void classImplementsCredentialInterceptorAndIsPublicConcrete() {
    int mods = SymmetricKeyCI.class.getModifiers();
    assertTrue("SymmetricKeyCI must be public", Modifier.isPublic(mods));
    assertTrue("SymmetricKeyCI must implement CredentialInterceptor",
        CredentialInterceptor.class.isAssignableFrom(SymmetricKeyCI.class));
  }

  // -------------------------------------------------------------------
  // Default-constructor pin.
  // -------------------------------------------------------------------
  @Test
  public void publicNoArgConstructorIsDeclared() throws Exception {
    var ctor = SymmetricKeyCI.class.getDeclaredConstructor();
    assertTrue("default constructor must be public", Modifier.isPublic(ctor.getModifiers()));
    var instance = (CredentialInterceptor) ctor.newInstance();
    assertNotNull(instance);
    assertNull("freshly-constructed SymmetricKeyCI must have null username",
        instance.getUsername());
    // The encodedJSON field defaults to "" (an empty string); pin the observable.
    assertEquals("freshly-constructed SymmetricKeyCI must have empty-string password",
        "", instance.getPassword());
  }

  // -------------------------------------------------------------------
  // Method-shape pin: intercept(String, String, String) declares SecurityException.
  // -------------------------------------------------------------------
  @Test
  public void interceptMethodSignatureMatchesCredentialInterceptorContract() throws Exception {
    Method m = SymmetricKeyCI.class.getDeclaredMethod(
        "intercept", String.class, String.class, String.class);
    assertTrue("intercept must be public", Modifier.isPublic(m.getModifiers()));
    assertSame("intercept must return void", void.class, m.getReturnType());
    assertTrue("intercept must declare SecurityException in throws",
        Arrays.asList(m.getExceptionTypes()).contains(SecurityException.class));
  }

  // -------------------------------------------------------------------
  // Boundary safety pin: intercept(null, null, null) throws at line 60 of the production
  // method (the username guard) BEFORE any JCE invocation.
  // -------------------------------------------------------------------
  @Test
  public void interceptWithNullUsernameThrowsSecurityExceptionAtUsernameGuard() {
    var ci = new SymmetricKeyCI();
    try {
      ci.intercept(null, null, null);
      fail("intercept(null, null, null) must throw SecurityException at the username guard");
    } catch (SecurityException expected) {
      assertNotNull("SecurityException must carry a diagnostic message", expected.getMessage());
      assertTrue("the message must reference the username guard",
          expected.getMessage().contains("username is not valid"));
    }
  }

  // -------------------------------------------------------------------
  // Boundary safety pin: intercept(url, "alice", null) throws at line 63 (password guard).
  // -------------------------------------------------------------------
  @Test
  public void interceptWithNullPasswordThrowsSecurityExceptionAtPasswordGuard() {
    var ci = new SymmetricKeyCI();
    try {
      ci.intercept("any-url", "alice", null);
      fail("intercept(*, alice, null) must throw SecurityException at the password guard");
    } catch (SecurityException expected) {
      assertNotNull("SecurityException must carry a diagnostic message", expected.getMessage());
      assertTrue("the message must reference the password guard",
          expected.getMessage().contains("password is not valid"));
    }
  }
}
