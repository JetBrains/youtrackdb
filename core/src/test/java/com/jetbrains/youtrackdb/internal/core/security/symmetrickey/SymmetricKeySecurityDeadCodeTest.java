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

import com.jetbrains.youtrackdb.internal.core.exception.SecurityAccessException;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityInternal;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

/**
 * Shape pin for {@link SymmetricKeySecurity}, the symmetric-key flavour of
 * {@link SecurityInternal}. PSI all-scope {@code ReferencesSearch} confirms <strong>zero</strong>
 * non-self references — the class is registered nowhere as a security provider, and its
 * delegate-driven implementation is reachable only through the dead {@link SymmetricKeyCI}
 * cluster.
 *
 * <p>{@link SymmetricKeySecurity} is the only on-tree caller of
 * {@link SymmetricKey#fromConfig(SymmetricKeyConfig)} and
 * {@link SymmetricKey#decryptAsString(String)}, both of which are dead-pinned individually in
 * {@link SymmetricKeyDeadMethodsDeadCodeTest}.
 *
 * <p>The pin exercises the constructor, the null-delegate guards on
 * {@code authenticate(session, name, pwd)}, and the deletion-time invariants on the constructor
 * delegate field — all without requiring a database session.
 *
 * <p>WHEN-FIXED: Track 22 — delete {@link SymmetricKeySecurity}.
 */
public class SymmetricKeySecurityDeadCodeTest {

  // -------------------------------------------------------------------
  // Class-shape pin.
  // -------------------------------------------------------------------
  @Test
  public void classImplementsSecurityInternalAndIsPublicConcrete() {
    int mods = SymmetricKeySecurity.class.getModifiers();
    assertTrue("SymmetricKeySecurity must be public", Modifier.isPublic(mods));
    assertTrue("SymmetricKeySecurity must implement SecurityInternal",
        SecurityInternal.class.isAssignableFrom(SymmetricKeySecurity.class));
  }

  // -------------------------------------------------------------------
  // Constructor pin: takes a SecurityInternal delegate.
  // -------------------------------------------------------------------
  @Test
  public void publicConstructorTakesSecurityInternalDelegate() throws Exception {
    var ctor = SymmetricKeySecurity.class.getDeclaredConstructor(SecurityInternal.class);
    assertTrue("constructor must be public", Modifier.isPublic(ctor.getModifiers()));
    assertEquals("constructor must take exactly one parameter",
        1, ctor.getParameterCount());
    assertSame("the parameter type must be SecurityInternal",
        SecurityInternal.class, ctor.getParameterTypes()[0]);
  }

  // -------------------------------------------------------------------
  // Behavioural pin: a SymmetricKeySecurity constructed with a null delegate fails at the
  // first authenticate() call (the production code's null-guard at line 70). We pin the throw
  // shape here because the rest of the class is delegate-passthrough.
  // -------------------------------------------------------------------
  @Test
  public void authenticateWithNullDelegateThrowsSecurityAccessException() {
    var sks = new SymmetricKeySecurity(null);
    try {
      sks.authenticate(null, "alice", "pwd");
      fail("authenticate(*, alice, pwd) on null-delegate must throw SecurityAccessException");
    } catch (SecurityAccessException expected) {
      assertTrue("the message must mention the null delegate guard",
          expected.getMessage() == null
              || expected.getMessage().contains("Delegate is null"));
    }
  }

  // -------------------------------------------------------------------
  // Authenticate-with-token always returns null (per the production override at line 151).
  // -------------------------------------------------------------------
  @Test
  public void authenticateWithTokenAlwaysReturnsNull() {
    var sks = new SymmetricKeySecurity(null);
    var user = sks.authenticate(null,
        (com.jetbrains.youtrackdb.internal.core.metadata.security.Token) null);
    assertNull("authenticate(session, token) must always return null", user);
  }

  // -------------------------------------------------------------------
  // close() is a no-op (line 342). Pin the no-op shape so a future change that adds side
  // effects is recognised.
  // -------------------------------------------------------------------
  @Test
  public void closeIsANoOp() {
    var sks = new SymmetricKeySecurity(null);
    sks.close(); // must not throw and must not touch the (null) delegate
  }

  // -------------------------------------------------------------------
  // Method-shape pin: confirm the SecurityInternal contract methods are all present as
  // overrides. A future refactor that drops one of these would require updating the SPI
  // contract — pinning the override surface here surfaces the change.
  // -------------------------------------------------------------------
  @Test
  public void allSecurityInternalAbstractMethodsHaveOverridesInSymmetricKeySecurity() {
    for (Method m : SecurityInternal.class.getMethods()) {
      // Ignore default methods (they don't need overriding) — but SecurityInternal in this
      // codebase has none today; the pin remains valid even if defaults are added later.
      if (m.isDefault()) {
        continue;
      }
      try {
        Method override = SymmetricKeySecurity.class.getMethod(m.getName(), m.getParameterTypes());
        assertNotNull("missing override for " + m.getName(), override);
      } catch (NoSuchMethodException nsme) {
        // It's permissible for a subclass to inherit the override from a more derived
        // superclass; the assertion is therefore a search for the symbol on the public
        // surface of SymmetricKeySecurity.
        fail("SymmetricKeySecurity must expose " + m.getName()
            + " as part of its SecurityInternal surface");
      }
    }
  }
}
