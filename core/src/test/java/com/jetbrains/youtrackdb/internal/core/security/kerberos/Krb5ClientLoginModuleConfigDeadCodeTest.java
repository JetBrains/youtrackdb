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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import org.junit.Test;

/**
 * Shape pin for {@link Krb5ClientLoginModuleConfig}'s dead surface. PSI all-scope
 * {@code ReferencesSearch} confirms the only reference is from {@link
 * KerberosCredentialInterceptor} (itself dead-pinned). The class extends {@link Configuration} —
 * a JAAS abstract base — and is reachable only through the dead Kerberos interceptor.
 *
 * <p>The constructors and {@link #getAppConfigurationEntry(String)} are themselves safe to
 * exercise: the class only builds an in-memory options map; it does NOT mutate any JVM-global
 * state (unlike {@link KerberosCredentialInterceptor#intercept}). The pin therefore goes
 * slightly beyond reflective shape — it constructs the config and confirms the option map is
 * populated — without risking JAAS contamination.
 *
 * <p>WHEN-FIXED: Track 22 — delete {@link Krb5ClientLoginModuleConfig} together with
 * {@link KerberosCredentialInterceptor} (the only caller) and the rest of the
 * {@code core/security/kerberos} package.
 *
 * <p>Standalone: pure JAAS-config in-memory shape; no database session needed.
 */
public class Krb5ClientLoginModuleConfigDeadCodeTest {

  // -------------------------------------------------------------------
  // Class-shape pin: subclass of javax.security.auth.login.Configuration.
  // -------------------------------------------------------------------
  @Test
  public void classExtendsJaasConfiguration() {
    int mods = Krb5ClientLoginModuleConfig.class.getModifiers();
    assertTrue("Krb5ClientLoginModuleConfig must be public", Modifier.isPublic(mods));
    assertSame("Krb5ClientLoginModuleConfig must extend javax.security.auth.login.Configuration",
        Configuration.class, Krb5ClientLoginModuleConfig.class.getSuperclass());
  }

  // -------------------------------------------------------------------
  // Three-arg constructor pin: (principal, ccPath, ktPath). This is the constructor used by
  // KerberosCredentialInterceptor.intercept (line 148 of that class).
  // -------------------------------------------------------------------
  @Test
  public void threeArgConstructorSignatureMatchesInterceptorCallSite() throws Exception {
    var ctor = Krb5ClientLoginModuleConfig.class
        .getDeclaredConstructor(String.class, String.class, String.class);
    assertTrue("3-arg constructor must be public", Modifier.isPublic(ctor.getModifiers()));
    assertEquals("3-arg constructor must take three parameters", 3, ctor.getParameterCount());
    // Exercise it: pure in-memory options map — does not touch JVM-global state.
    var cfg = new Krb5ClientLoginModuleConfig("principal@EXAMPLE", "/tmp/cc", "/tmp/keytab.kt");
    assertNotNull("3-arg constructor must yield a usable instance", cfg);
  }

  // -------------------------------------------------------------------
  // Four-arg constructor pin: (principal, useTicketCache, ccPath, ktPath). Documents the
  // boolean-toggle entry-point used internally by the 3-arg overload (the 3-arg form delegates
  // here with useTicketCache=true).
  // -------------------------------------------------------------------
  @Test
  public void fourArgConstructorSignatureMatchesInternalDelegate() throws Exception {
    var ctor = Krb5ClientLoginModuleConfig.class
        .getDeclaredConstructor(String.class, boolean.class, String.class, String.class);
    assertTrue("4-arg constructor must be public", Modifier.isPublic(ctor.getModifiers()));
    assertEquals("4-arg constructor must take four parameters", 4, ctor.getParameterCount());
    assertSame("4-arg constructor's boolean parameter must be primitive boolean",
        boolean.class, ctor.getParameterTypes()[1]);
    // Exercise it with both branches of the useTicketCache toggle.
    var cfgTrue = new Krb5ClientLoginModuleConfig("principal@EXAMPLE", true, "/tmp/cc", null);
    assertNotNull(cfgTrue);
    var cfgFalse = new Krb5ClientLoginModuleConfig("principal@EXAMPLE", false, "/tmp/cc", null);
    assertNotNull(cfgFalse);
  }

  // -------------------------------------------------------------------
  // getAppConfigurationEntry shape pin. Implementing JAAS Configuration's only abstract method.
  // The implementation returns a single-element AppConfigurationEntry[] regardless of
  // applicationName — pin that shape so a deletion that drops the override is detected.
  // -------------------------------------------------------------------
  @Test
  public void getAppConfigurationEntryReturnsSingleElementArrayRegardlessOfAppName()
      throws Exception {
    Method m = Krb5ClientLoginModuleConfig.class
        .getDeclaredMethod("getAppConfigurationEntry", String.class);
    assertTrue("getAppConfigurationEntry must be public",
        Modifier.isPublic(m.getModifiers()));
    assertSame("getAppConfigurationEntry must return AppConfigurationEntry[]",
        AppConfigurationEntry[].class, m.getReturnType());
    var cfg = new Krb5ClientLoginModuleConfig("principal@EXAMPLE", "/tmp/cc", "/tmp/keytab.kt");
    AppConfigurationEntry[] entries = cfg.getAppConfigurationEntry("any-name");
    assertNotNull("getAppConfigurationEntry must never return null", entries);
    assertEquals("the entry array must have exactly one element",
        1, entries.length);
    assertNotNull("the single entry must be non-null", entries[0]);
    // Pin the login module class name — it is hardcoded in the production class.
    assertEquals("the entry must wire up the Krb5LoginModule",
        "com.sun.security.auth.module.Krb5LoginModule",
        entries[0].getLoginModuleName());
  }
}
