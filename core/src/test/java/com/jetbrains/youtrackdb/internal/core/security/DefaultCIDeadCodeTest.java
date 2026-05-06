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
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Modifier;
import org.junit.Test;

/**
 * Shape pin for {@link DefaultCI}, the no-op default {@link CredentialInterceptor}. PSI
 * all-scope {@code ReferencesSearch} confirms <strong>zero</strong> non-self references — the
 * default-class slot for the {@code CLIENT_CREDENTIAL_INTERCEPTOR} configuration is read by no
 * production code; it is a phantom default whose only role would be to satisfy reflection in
 * {@link SecurityManager#newCredentialInterceptor()} (itself dead-pinned in
 * {@link SecurityManagerNewCredentialInterceptorDeadCodeTest}).
 *
 * <p>The class is safe to construct and exercise — it has no JVM-global side effects (unlike
 * the Kerberos interceptor). The pin therefore includes a behavioural round-trip on the
 * trivially-implemented {@code intercept(...)} body that just stores the username + password
 * fields.
 *
 * <p>WHEN-FIXED: Track 22 — delete {@link DefaultCI} together with the
 * {@link CredentialInterceptor} SPI and {@code SecurityManager.newCredentialInterceptor()}.
 */
public class DefaultCIDeadCodeTest {

  // -------------------------------------------------------------------
  // Class-shape pin: public concrete implementing CredentialInterceptor.
  // -------------------------------------------------------------------
  @Test
  public void classImplementsCredentialInterceptorAndIsPublicConcrete() {
    int mods = DefaultCI.class.getModifiers();
    assertTrue("DefaultCI must be public", Modifier.isPublic(mods));
    assertTrue("DefaultCI must implement CredentialInterceptor",
        CredentialInterceptor.class.isAssignableFrom(DefaultCI.class));
  }

  // -------------------------------------------------------------------
  // Default-constructor pin (matches the SPI loader's cls.newInstance() requirement).
  // -------------------------------------------------------------------
  @Test
  public void publicNoArgConstructorMatchesSpiLoaderShape() throws Exception {
    var ctor = DefaultCI.class.getDeclaredConstructor();
    assertTrue("default constructor must be public", Modifier.isPublic(ctor.getModifiers()));
    var instance = ctor.newInstance();
    assertNotNull(instance);
    assertNull("freshly-constructed DefaultCI must have null username",
        ((CredentialInterceptor) instance).getUsername());
    assertNull("freshly-constructed DefaultCI must have null password",
        ((CredentialInterceptor) instance).getPassword());
  }

  // -------------------------------------------------------------------
  // Behavioural pin: intercept(url, user, pass) just stores the user + pass fields. No JCE,
  // no JAAS, no JVM-global state. After intercept, getUsername / getPassword return the
  // stored values.
  // -------------------------------------------------------------------
  @Test
  public void interceptStoresUsernameAndPasswordVerbatim() {
    var ci = new DefaultCI();
    ci.intercept("any-url", "alice", "s3cr3t");
    assertEquals("getUsername must echo the intercepted user", "alice", ci.getUsername());
    assertEquals("getPassword must echo the intercepted password", "s3cr3t", ci.getPassword());
  }

  // -------------------------------------------------------------------
  // Defensive pin: intercept(null, null, null) is allowed (DefaultCI does NOT validate inputs)
  // — the only guard is the absence of any throw. This pins that the no-op default behaviour
  // is preserved.
  // -------------------------------------------------------------------
  @Test
  public void interceptDoesNotValidateNullInputs() {
    var ci = new DefaultCI();
    ci.intercept(null, null, null);
    assertNull("after intercept(null, null, null), username remains null", ci.getUsername());
    assertNull("after intercept(null, null, null), password remains null", ci.getPassword());
  }
}
