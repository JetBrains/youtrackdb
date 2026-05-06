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
package com.jetbrains.youtrackdb.internal.core.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Shape pin for the dead {@link SecurityManager#newCredentialInterceptor()} factory and the
 * accompanying {@link GlobalConfiguration#CLIENT_CREDENTIAL_INTERCEPTOR} configuration slot.
 * PSI all-scope {@code ReferencesSearch} confirms <strong>zero</strong> callers of the factory
 * method anywhere in the codebase, which is the load-bearing fact that makes the entire
 * {@link CredentialInterceptor} SPI dead.
 *
 * <p>The factory is safe to invoke (it is a reflective {@code Class.forName} +
 * {@code newInstance()} of the configured class name; failure paths log silently and return
 * null). The pin therefore exercises the factory itself end-to-end:
 *
 * <ul>
 *   <li>Default global configuration → returns null (no class configured).</li>
 *   <li>{@code DefaultCI} configured → returns a {@code DefaultCI} instance.</li>
 *   <li>Bad class name configured → returns null (silent ClassNotFoundException).</li>
 * </ul>
 *
 * <p>The configuration slot value is restored in {@code @After} so the JVM-global state goes
 * back to the surefire-fork baseline.
 *
 * <p>WHEN-FIXED: Track 22 — delete {@link SecurityManager#newCredentialInterceptor()} together
 * with {@link GlobalConfiguration#CLIENT_CREDENTIAL_INTERCEPTOR}, {@link CredentialInterceptor}
 * (the SPI), {@link DefaultCI} (the no-op default), and the dead Kerberos / SymmetricKey CI
 * implementations.
 */
public class SecurityManagerNewCredentialInterceptorDeadCodeTest {

  /** Snapshot of {@code CLIENT_CREDENTIAL_INTERCEPTOR} so each test restores it on exit. */
  private Object savedInterceptor;

  @Before
  public void saveCi() {
    savedInterceptor = GlobalConfiguration.CLIENT_CREDENTIAL_INTERCEPTOR.getValue();
  }

  @After
  public void restoreCi() {
    GlobalConfiguration.CLIENT_CREDENTIAL_INTERCEPTOR.setValue(savedInterceptor);
  }

  // -------------------------------------------------------------------
  // Method-shape pin: SecurityManager.newCredentialInterceptor() returns CredentialInterceptor
  // and takes no arguments.
  // -------------------------------------------------------------------
  @Test
  public void newCredentialInterceptorMethodSignatureMatchesSpiContract() throws Exception {
    Method m = SecurityManager.class.getDeclaredMethod("newCredentialInterceptor");
    assertTrue("newCredentialInterceptor must be public", Modifier.isPublic(m.getModifiers()));
    assertSame("newCredentialInterceptor must return CredentialInterceptor",
        CredentialInterceptor.class, m.getReturnType());
    assertEquals("newCredentialInterceptor must take zero parameters", 0, m.getParameterCount());
  }

  // -------------------------------------------------------------------
  // Behavioural pin: with the configuration slot set to null (or unset), the factory returns
  // null. The default value is null in the YouTrackDB GlobalConfiguration enum entry.
  // -------------------------------------------------------------------
  @Test
  public void factoryReturnsNullWhenInterceptorClassConfigIsNull() {
    GlobalConfiguration.CLIENT_CREDENTIAL_INTERCEPTOR.setValue(null);
    var ci = SecurityManager.instance().newCredentialInterceptor();
    assertNull("with no class configured, the factory must return null", ci);
  }

  // -------------------------------------------------------------------
  // Behavioural pin: with DefaultCI's FQN configured, the factory loads and instantiates the
  // class via reflection.
  // -------------------------------------------------------------------
  @Test
  public void factoryReturnsConfiguredCiInstanceWhenClassIsResolvable() {
    GlobalConfiguration.CLIENT_CREDENTIAL_INTERCEPTOR.setValue(DefaultCI.class.getName());
    var ci = SecurityManager.instance().newCredentialInterceptor();
    assertNotNull("with DefaultCI configured, the factory must return a non-null instance", ci);
    assertSame("the returned instance must be a DefaultCI", DefaultCI.class, ci.getClass());
  }

  // -------------------------------------------------------------------
  // Behavioural pin: a bogus class name is silently swallowed (the factory logs at debug and
  // returns null). This matches the SPI's "lenient discovery" pattern documented in the source.
  // -------------------------------------------------------------------
  @Test
  public void factoryReturnsNullForUnresolvableClassNameWithoutThrowing() {
    GlobalConfiguration.CLIENT_CREDENTIAL_INTERCEPTOR.setValue(
        "com.example.does.not.exist.NeverDefined");
    var ci = SecurityManager.instance().newCredentialInterceptor();
    assertNull("an unresolvable class name must yield null, not a throw", ci);
  }

  // -------------------------------------------------------------------
  // Configuration-slot pin: CLIENT_CREDENTIAL_INTERCEPTOR is a String-typed global config
  // entry. Pin its existence + type so a deletion that drops it together with the SPI is
  // recognised as a deliberate change.
  // -------------------------------------------------------------------
  @Test
  public void clientCredentialInterceptorSlotIsAStringTypedGlobalConfig() {
    var slot = GlobalConfiguration.CLIENT_CREDENTIAL_INTERCEPTOR;
    assertNotNull("CLIENT_CREDENTIAL_INTERCEPTOR slot must remain on GlobalConfiguration", slot);
    assertSame("CLIENT_CREDENTIAL_INTERCEPTOR slot must be String-typed",
        String.class, slot.getType());
  }
}
