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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.core.exception.SecurityException;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Shape pin for {@link UserSymmetricKeyConfig}. PSI all-scope {@code ReferencesSearch} confirms
 * its only caller is {@link SymmetricKeySecurity#authenticate} (also dead-pinned). Since
 * {@link SymmetricKeySecurity} is unreachable from production, the constructor of this
 * config class is also unreachable.
 *
 * <p>The class is a synchronous map-driven config holder — no I/O, no JCE — so the pin
 * exercises the constructor's three-branch dispatch ({@code key}, {@code keyFile},
 * {@code keyStore}) plus the missing-required-field guards.
 *
 * <p>WHEN-FIXED: YTDB-772 — delete {@link UserSymmetricKeyConfig}.
 */
public class UserSymmetricKeyConfigDeadCodeTest {

  // -------------------------------------------------------------------
  // Class-shape pin: public concrete implementing SymmetricKeyConfig.
  // -------------------------------------------------------------------
  @Test
  public void classImplementsSymmetricKeyConfigAndIsPublicConcrete() {
    int mods = UserSymmetricKeyConfig.class.getModifiers();
    assertTrue("UserSymmetricKeyConfig must be public", Modifier.isPublic(mods));
    assertTrue("UserSymmetricKeyConfig must implement SymmetricKeyConfig",
        SymmetricKeyConfig.class.isAssignableFrom(UserSymmetricKeyConfig.class));
  }

  // -------------------------------------------------------------------
  // Constructor pin: takes a Map<String, Object>.
  // -------------------------------------------------------------------
  @Test
  public void constructorTakesMapStringObject() throws Exception {
    var ctor = UserSymmetricKeyConfig.class.getDeclaredConstructor(Map.class);
    assertTrue("constructor must be public", Modifier.isPublic(ctor.getModifiers()));
  }

  // -------------------------------------------------------------------
  // Behavioural pin: 'key' branch — key + keyAlgorithm both populated → usesKeyString returns
  // true and the other two branches return false.
  // -------------------------------------------------------------------
  @Test
  public void keyStringBranchReportsUsesKeyStringTrueAndOthersFalse() {
    var config = new HashMap<String, Object>();
    var props = new HashMap<String, Object>();
    props.put("key", "abc==");
    props.put("keyAlgorithm", "AES");
    config.put("properties", props);

    var c = new UserSymmetricKeyConfig(config);
    assertEquals("getKeyString must return the configured key", "abc==", c.getKeyString());
    assertEquals("getKeyAlgorithm must return AES", "AES", c.getKeyAlgorithm());
    assertTrue("usesKeyString must be true", c.usesKeyString());
    assertFalse("usesKeyFile must be false", c.usesKeyFile());
    assertFalse("usesKeystore must be false", c.usesKeystore());
  }

  // -------------------------------------------------------------------
  // Behavioural pin: 'keyFile' branch — keyFile + keyAlgorithm.
  // -------------------------------------------------------------------
  @Test
  public void keyFileBranchReportsUsesKeyFileTrueAndOthersFalse() {
    var config = new HashMap<String, Object>();
    var props = new HashMap<String, Object>();
    props.put("keyFile", "/etc/keys/symmetric.key");
    props.put("keyAlgorithm", "AES");
    config.put("properties", props);

    var c = new UserSymmetricKeyConfig(config);
    assertNull("getKeyString must remain null when keyFile branch fires",
        c.getKeyString());
    assertEquals("getKeyFile round-trip", "/etc/keys/symmetric.key", c.getKeyFile());
    assertEquals("getKeyAlgorithm round-trip", "AES", c.getKeyAlgorithm());
    assertFalse("usesKeyString must be false", c.usesKeyString());
    assertTrue("usesKeyFile must be true", c.usesKeyFile());
    assertFalse("usesKeystore must be false", c.usesKeystore());
  }

  // -------------------------------------------------------------------
  // Behavioural pin: 'keyStore' branch — file + keyAlias populated.
  // -------------------------------------------------------------------
  @Test
  public void keyStoreBranchReportsUsesKeystoreTrueAndOthersFalse() {
    var config = new HashMap<String, Object>();
    var props = new HashMap<String, Object>();
    var ks = new HashMap<String, Object>();
    ks.put("file", "/etc/keys/symmetric.jceks");
    ks.put("keyAlias", "myalias");
    props.put("keyStore", ks);
    config.put("properties", props);

    var c = new UserSymmetricKeyConfig(config);
    assertEquals("getKeystoreFile round-trip", "/etc/keys/symmetric.jceks",
        c.getKeystoreFile());
    assertEquals("getKeystoreKeyAlias round-trip", "myalias", c.getKeystoreKeyAlias());
    assertFalse("usesKeyString must be false", c.usesKeyString());
    assertFalse("usesKeyFile must be false", c.usesKeyFile());
    assertTrue("usesKeystore must be true", c.usesKeystore());
  }

  // -------------------------------------------------------------------
  // Boundary pin: 'key' supplied without 'keyAlgorithm' → SecurityException at line 116.
  // -------------------------------------------------------------------
  @Test
  public void keyBranchWithoutKeyAlgorithmThrowsSecurityException() {
    var config = new HashMap<String, Object>();
    var props = new HashMap<String, Object>();
    props.put("key", "abc==");
    config.put("properties", props);

    try {
      new UserSymmetricKeyConfig(config);
      fail("missing keyAlgorithm with key set must throw SecurityException");
    } catch (SecurityException expected) {
      assertNotNull("SecurityException must carry a diagnostic message", expected.getMessage());
      assertTrue("the message must mention keyAlgorithm",
          expected.getMessage().contains("keyAlgorithm"));
    }
  }

  // -------------------------------------------------------------------
  // Boundary pin: 'keyFile' supplied without 'keyAlgorithm' → SecurityException at line 122.
  // Mirrors the 'key'-without-'keyAlgorithm' test above so the second arm of the
  // missing-keyAlgorithm guard is also pinned.
  // -------------------------------------------------------------------
  @Test
  public void keyFileBranchWithoutKeyAlgorithmThrowsSecurityException() {
    var config = new HashMap<String, Object>();
    var props = new HashMap<String, Object>();
    props.put("keyFile", "/etc/keys/symmetric.key");
    config.put("properties", props);

    try {
      new UserSymmetricKeyConfig(config);
      fail("missing keyAlgorithm with keyFile set must throw SecurityException");
    } catch (SecurityException expected) {
      assertNotNull("SecurityException must carry a diagnostic message", expected.getMessage());
      assertTrue("the message must mention keyAlgorithm",
          expected.getMessage().contains("keyAlgorithm"));
    }
  }

  // -------------------------------------------------------------------
  // Boundary pin: missing 'properties' field → SecurityException at line 104.
  // -------------------------------------------------------------------
  @Test
  public void missingPropertiesFieldThrowsSecurityException() {
    var config = new HashMap<String, Object>();
    try {
      new UserSymmetricKeyConfig(config);
      fail("missing 'properties' field must throw SecurityException");
    } catch (SecurityException expected) {
      assertNotNull("SecurityException must carry a diagnostic message", expected.getMessage());
      assertTrue("the message must mention 'properties is null'",
          expected.getMessage().contains("properties is null"));
    }
  }

  // -------------------------------------------------------------------
  // Boundary pin: keyStore branch with missing 'file' raises SecurityException at line 140.
  // -------------------------------------------------------------------
  @Test
  public void keyStoreBranchWithoutFileThrowsSecurityException() {
    var config = new HashMap<String, Object>();
    var props = new HashMap<String, Object>();
    var ks = new HashMap<String, Object>();
    ks.put("keyAlias", "myalias");
    props.put("keyStore", ks);
    config.put("properties", props);

    try {
      new UserSymmetricKeyConfig(config);
      fail("missing keyStore.file must throw SecurityException");
    } catch (SecurityException expected) {
      assertNotNull("SecurityException must carry a diagnostic message", expected.getMessage());
      assertTrue("the message must mention keyStore.file",
          expected.getMessage().contains("keyStore.file"));
    }
  }

  // -------------------------------------------------------------------
  // Boundary pin: keyStore branch with missing keyAlias → SecurityException at line 144.
  // -------------------------------------------------------------------
  @Test
  public void keyStoreBranchWithoutKeyAliasThrowsSecurityException() {
    var config = new HashMap<String, Object>();
    var props = new HashMap<String, Object>();
    var ks = new HashMap<String, Object>();
    ks.put("file", "/etc/keys/symmetric.jceks");
    props.put("keyStore", ks);
    config.put("properties", props);

    try {
      new UserSymmetricKeyConfig(config);
      fail("missing keyStore.keyAlias must throw SecurityException");
    } catch (SecurityException expected) {
      assertNotNull("SecurityException must carry a diagnostic message", expected.getMessage());
      assertTrue("the message must mention keyStore.keyAlias",
          expected.getMessage().contains("keyStore.keyAlias"));
    }
  }

  // -------------------------------------------------------------------
  // Latent-bug pin: line 133 NPE in the no-recognised-keys fall-through branch.
  // When the properties map is non-null but contains none of `key`, `keyFile`,
  // or `keyStore`, the constructor falls through to the keyStore else-branch
  // where it dereferences a null `ksMap` (line 133 in the production source).
  // The current observable is NullPointerException; the documented contract for
  // an unrecognised properties map should be SecurityException with a
  // diagnostic message.
  //
  // WHEN-FIXED: YTDB-772 — either delete UserSymmetricKeyConfig outright (the
  // class is dead-pinned in this test) or, if the production fix lands first,
  // replace this test's assertThrows(NullPointerException.class, ...) with
  // assertThrows(SecurityException.class, ...) and assert the message mentions
  // the missing key / keyFile / keyStore property.
  // -------------------------------------------------------------------
  @Test
  public void unrecognizedPropertiesKeyNpesOnLine133LatentBugPin() {
    var config = new HashMap<String, Object>();
    var props = new HashMap<String, Object>();
    // Non-null props with NONE of key / keyFile / keyStore present.
    props.put("unrelated", "value");
    config.put("properties", props);

    try {
      new UserSymmetricKeyConfig(config);
      fail("unrecognised properties keys must currently NPE on the dead-code path");
    } catch (NullPointerException expected) {
      // Pin the current observable. WHEN-FIXED: YTDB-772 — flip to
      // SecurityException once the constructor either rejects the unrecognised
      // input cleanly or the class is deleted outright.
      // No message assertion: NPE diagnostic depends on the JVM.
    }
  }
}
