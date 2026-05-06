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
package com.jetbrains.youtrackdb.internal.core.security.kerberos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityException;
import com.jetbrains.youtrackdb.internal.core.security.CredentialInterceptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Shape pin for {@link KerberosCredentialInterceptor}'s dead surface. PSI all-scope
 * {@code ReferencesSearch} confirms zero non-self callers across all five Maven modules; the only
 * reference is the file itself. The interceptor is a non-instantiated leaf of the dead
 * {@link CredentialInterceptor} SPI chain — {@code SecurityManager.newCredentialInterceptor()}
 * (the only loader) has no callers, and {@code GlobalConfiguration.CLIENT_CREDENTIAL_INTERCEPTOR}
 * is read by no one else.
 *
 * <p><strong>Hard rule (Phase A risk discovery):</strong> {@code intercept(...)} at line 145 of
 * the production class calls
 * {@code System.setProperty("java.security.krb5.conf", config)} <em>before</em> the LoginContext
 * call. That mutation is JVM-global — every concurrent surefire fork would inherit the new value
 * for the rest of the process. This pin therefore stops at <strong>reflective signature
 * inspection plus the parameter-null guards</strong> (lines 70, 83 of the production code) and
 * never invokes {@code intercept} with valid arguments.
 *
 * <p>WHEN-FIXED: Track 22 — delete {@link KerberosCredentialInterceptor} together with the rest
 * of the {@code core/security/kerberos} package and the dead {@link CredentialInterceptor} SPI.
 *
 * <p>Standalone: no database session needed; pure reflection plus two safe-throw paths against
 * the parameter-null guard.
 *
 * <p>Tagged {@link SequentialTest} as a defensive measure: the boundary-safety tests observe
 * {@code java.security.krb5.conf} (a JVM-global system property) on entry and re-check it on
 * exit. A concurrent surefire fork mutating that property mid-test would invalidate the
 * before/after comparison and produce a false-positive failure. Sequential gating guarantees a
 * stable observation window. The {@code @Before}/{@code @After} pair additionally save and
 * restore the property so a future test that mutates it does not pollute later tests in the
 * same fork.
 */
@Category(SequentialTest.class)
public class KerberosCredentialInterceptorDeadCodeTest {

  /** Snapshot of {@code java.security.krb5.conf} so each test restores it on exit. */
  private String savedKrb5Conf;
  private boolean krb5ConfWasSet;

  @Before
  public void saveKrb5Conf() {
    savedKrb5Conf = System.getProperty("java.security.krb5.conf");
    krb5ConfWasSet = savedKrb5Conf != null;
  }

  @After
  public void restoreKrb5Conf() {
    if (krb5ConfWasSet) {
      System.setProperty("java.security.krb5.conf", savedKrb5Conf);
    } else {
      System.clearProperty("java.security.krb5.conf");
    }
  }

  // -------------------------------------------------------------------
  // Class-shape pin: confirm the interceptor remains a public concrete class implementing the
  // dead CredentialInterceptor SPI. Track 22 must drop the class and the SPI in lockstep.
  // -------------------------------------------------------------------
  @Test
  public void classIsPublicConcreteImplementingCredentialInterceptor() {
    int mods = KerberosCredentialInterceptor.class.getModifiers();
    assertTrue("KerberosCredentialInterceptor must be public", Modifier.isPublic(mods));
    assertFalse("KerberosCredentialInterceptor must be a concrete class",
        Modifier.isAbstract(mods));
    assertFalse("KerberosCredentialInterceptor must not be an interface",
        KerberosCredentialInterceptor.class.isInterface());
    assertTrue("KerberosCredentialInterceptor must implement CredentialInterceptor",
        CredentialInterceptor.class.isAssignableFrom(KerberosCredentialInterceptor.class));
  }

  // -------------------------------------------------------------------
  // Default-constructor pin: SecurityManager.newCredentialInterceptor() does
  // `cls.newInstance()`, so a no-arg public constructor must exist for the SPI loader to even
  // compile. The pin is safe — the constructor doesn't touch any system property or JAAS state.
  // -------------------------------------------------------------------
  @Test
  public void publicNoArgConstructorExistsForSpiInstantiation() throws Exception {
    var ctor = KerberosCredentialInterceptor.class.getDeclaredConstructor();
    assertTrue("default constructor must be public", Modifier.isPublic(ctor.getModifiers()));
    var instance = ctor.newInstance();
    assertNotNull("default constructor must yield a usable instance", instance);
    // Ensure both initial fields are null before any intercept(...) call.
    assertNull("a freshly-constructed interceptor must report null username",
        ((CredentialInterceptor) instance).getUsername());
    assertNull("a freshly-constructed interceptor must report null password",
        ((CredentialInterceptor) instance).getPassword());
  }

  // -------------------------------------------------------------------
  // Field-shape pin: principal and serviceTicket are private String fields. The dead-code
  // deletion must drop them together with the class. Reflection allows us to confirm shape
  // without instantiating the JAAS subsystem.
  // -------------------------------------------------------------------
  @Test
  public void principalAndServiceTicketAreDeclaredAsPrivateStringFields() throws Exception {
    Field principal = KerberosCredentialInterceptor.class.getDeclaredField("principal");
    assertSame("principal field must be a String", String.class, principal.getType());
    assertTrue("principal field must be private", Modifier.isPrivate(principal.getModifiers()));
    assertFalse("principal field must not be static", Modifier.isStatic(principal.getModifiers()));

    Field serviceTicket = KerberosCredentialInterceptor.class.getDeclaredField("serviceTicket");
    assertSame("serviceTicket field must be a String", String.class, serviceTicket.getType());
    assertTrue("serviceTicket field must be private",
        Modifier.isPrivate(serviceTicket.getModifiers()));
    assertFalse("serviceTicket field must not be static",
        Modifier.isStatic(serviceTicket.getModifiers()));
  }

  // -------------------------------------------------------------------
  // Method-shape pin: intercept(String, String, String) throws SecurityException. We MUST NOT
  // call intercept with valid arguments (it sets java.security.krb5.conf system-wide). The pin
  // is purely reflective.
  // -------------------------------------------------------------------
  @Test
  public void interceptMethodSignatureMatchesCredentialInterceptorContract() throws Exception {
    Method m = KerberosCredentialInterceptor.class
        .getDeclaredMethod("intercept", String.class, String.class, String.class);
    assertTrue("intercept must be public", Modifier.isPublic(m.getModifiers()));
    assertSame("intercept must return void", void.class, m.getReturnType());
    assertEquals("intercept must take three String parameters", 3, m.getParameterCount());
    assertSame("intercept first parameter must be String (url)",
        String.class, m.getParameterTypes()[0]);
    assertSame("intercept second parameter must be String (principal)",
        String.class, m.getParameterTypes()[1]);
    assertSame("intercept third parameter must be String (spn)",
        String.class, m.getParameterTypes()[2]);
    assertTrue("intercept must declare SecurityException in throws clause",
        Arrays.asList(m.getExceptionTypes()).contains(SecurityException.class));
  }

  // -------------------------------------------------------------------
  // Boundary safety pin: intercept(null, null, null) throws SecurityException at line 71 of the
  // production code, BEFORE reaching the System.setProperty mutation at line 145. The pin
  // exercises the parameter-null guard and proves the JVM-global hazard is gated behind valid
  // input.
  // -------------------------------------------------------------------
  @Test
  public void interceptWithNullPrincipalThrowsSecurityExceptionBeforeJaasMutation()
      throws Exception {
    var interceptor = new KerberosCredentialInterceptor();
    String beforeJaas = System.getProperty("java.security.krb5.conf");
    try {
      // url=null, principal=null, spn=null — the principal guard fires at line 71.
      interceptor.intercept(null, null, null);
      fail("intercept(null, null, null) must throw SecurityException at the principal guard");
    } catch (SecurityException expected) {
      assertNotNull("SecurityException must carry a diagnostic message", expected.getMessage());
      assertTrue("the message must mention the principal guard",
          expected.getMessage().contains("Principal cannot be null"));
    }
    String afterJaas = System.getProperty("java.security.krb5.conf");
    // Either both are null or both are equal — the guard short-circuited the mutation.
    if (beforeJaas == null) {
      assertNull(
          "intercept must NOT have set java.security.krb5.conf when the principal guard fires",
          afterJaas);
    } else {
      assertEquals(
          "intercept must NOT have changed java.security.krb5.conf when the principal guard fires",
          beforeJaas, afterJaas);
    }
  }

  // -------------------------------------------------------------------
  // Boundary safety pin: with a non-null principal but null url AND null spn, line 84 of the
  // production code throws SecurityException before reaching the System.setProperty mutation.
  // The pin proves the second guard is also active.
  // -------------------------------------------------------------------
  @Test
  public void interceptWithNonNullPrincipalButNullUrlAndSpnThrowsSecurityException()
      throws Exception {
    var interceptor = new KerberosCredentialInterceptor();
    String beforeJaas = System.getProperty("java.security.krb5.conf");
    try {
      // principal=non-null, url=null, spn=null — line 84 throws before line 145.
      interceptor.intercept(null, "principal@EXAMPLE", null);
      fail("intercept(null, principal, null) must throw SecurityException at the url-or-spn guard");
    } catch (SecurityException expected) {
      assertNotNull("SecurityException must carry a diagnostic message", expected.getMessage());
      assertTrue("the message must mention the url-or-spn guard",
          expected.getMessage().contains("URL and SPN"));
    }
    String afterJaas = System.getProperty("java.security.krb5.conf");
    if (beforeJaas == null) {
      assertNull(
          "intercept must NOT have set java.security.krb5.conf when the url-or-spn guard fires",
          afterJaas);
    } else {
      assertEquals(
          "intercept must NOT have changed java.security.krb5.conf when the url-or-spn guard "
              + "fires",
          beforeJaas, afterJaas);
    }
  }

  // -------------------------------------------------------------------
  // getUsername / getPassword surface pin: both are simple field-readers. They are part of the
  // CredentialInterceptor contract and must remain on the surface until the class is deleted.
  // -------------------------------------------------------------------
  @Test
  public void getUsernameReturnsThePrincipalFieldDirectly() throws Exception {
    Method m = KerberosCredentialInterceptor.class.getDeclaredMethod("getUsername");
    assertTrue("getUsername must be public", Modifier.isPublic(m.getModifiers()));
    assertSame("getUsername must return String", String.class, m.getReturnType());
    assertEquals("getUsername must take zero parameters", 0, m.getParameterCount());

    var interceptor = new KerberosCredentialInterceptor();
    // Reflectively set the principal field — this never touches JAAS state.
    Field principal = KerberosCredentialInterceptor.class.getDeclaredField("principal");
    principal.setAccessible(true);
    principal.set(interceptor, "alice@EXAMPLE");
    assertEquals("getUsername must return the principal field value",
        "alice@EXAMPLE", interceptor.getUsername());
  }

  @Test
  public void getPasswordReturnsTheServiceTicketFieldDirectly() throws Exception {
    Method m = KerberosCredentialInterceptor.class.getDeclaredMethod("getPassword");
    assertTrue("getPassword must be public", Modifier.isPublic(m.getModifiers()));
    assertSame("getPassword must return String", String.class, m.getReturnType());
    assertEquals("getPassword must take zero parameters", 0, m.getParameterCount());

    var interceptor = new KerberosCredentialInterceptor();
    Field serviceTicket = KerberosCredentialInterceptor.class.getDeclaredField("serviceTicket");
    serviceTicket.setAccessible(true);
    serviceTicket.set(interceptor, "fake-base64-ticket");
    assertEquals("getPassword must return the serviceTicket field value",
        "fake-base64-ticket", interceptor.getPassword());
  }
}
