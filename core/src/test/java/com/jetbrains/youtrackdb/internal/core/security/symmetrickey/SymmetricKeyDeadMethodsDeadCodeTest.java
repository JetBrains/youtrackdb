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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

/**
 * Per-method dead-code pin for {@link SymmetricKey}. PSI all-scope {@code ReferencesSearch}
 * across all five Maven modules confirmed that the methods pinned individually below are
 * reachable only through dead consumers ({@link SymmetricKeyCI},
 * {@link SymmetricKeySecurity} — both pinned in their own {@code *DeadCodeTest} files) or have
 * zero callers anywhere.
 *
 * <p>The class itself is <strong>live</strong>: the four constructors,
 * {@link SymmetricKey#getKeyAlgorithm(String)} (test-pinned in {@code SymmetricKeyTest}),
 * {@link SymmetricKey#getBase64Key()}, the {@code encrypt(...)} family,
 * {@link SymmetricKey#decrypt(String)}, and the {@code encrypt}/{@code decrypt} round-trip
 * shape are covered by {@code SymmetricKeyTest}'s live tests (Step 5 carry-forward). This file
 * is exclusively about the dead surface so a partial deletion (drop one method, keep the rest)
 * leaves the pin set valid (Track 15 {@code EntityHelper} precedent).
 *
 * <p>Each {@code @Test} below is its own atomic pin so that Track 22 can delete one method at a
 * time without breaking unrelated assertions.
 *
 * <p>WHEN-FIXED: YTDB-772 — delete each method below from {@link SymmetricKey} after the
 * associated dead consumer is removed. Deletion order:
 * <ul>
 *   <li>Drop {@link SymmetricKeyCI} → delete {@code setDefaultCipherTransform},
 *       {@code fromString(String, String)}, {@code fromFile(String, String)},
 *       {@code fromKeystore(String, String, String, String)},
 *       {@code fromKeystore(InputStream, String, String, String)},
 *       {@code fromStream(String, InputStream)}, {@code separateAlgorithm(String)}.</li>
 *   <li>Drop {@link SymmetricKeySecurity} → delete {@code fromConfig(SymmetricKeyConfig)},
 *       {@code decryptAsString(String)}.</li>
 *   <li>Independent (no remaining callers): the 6 dead getters
 *       (getDefaultCipherTransform, getIteration, getKeySize, getSaltLength,
 *       getSeedAlgorithm, getSeedPhrase) and 7 dead setters (setDefaultCipherTransform,
 *       setIteration, setKeyAlgorithm, setKeySize, setSaltLength, setSeedAlgorithm,
 *       setSeedPhrase) — all are pure field accessors with no callers.</li>
 * </ul>
 */
public class SymmetricKeyDeadMethodsDeadCodeTest {

  // ===================================================================
  // Group 1 — Dead getters (6 methods).
  // ===================================================================

  // -------------------------------------------------------------------
  // getDefaultCipherTransform(String) — 0 callers anywhere. Note the parameter is unused
  // (the method just returns the field), and pinning the parameter type catches a refactor
  // that would change the signature.
  // -------------------------------------------------------------------
  @Test
  public void getDefaultCipherTransformIsPublicReturningStringTakingString() throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod("getDefaultCipherTransform", String.class);
    int mods = m.getModifiers();
    assertTrue("getDefaultCipherTransform must be public", Modifier.isPublic(mods));
    assertSame("getDefaultCipherTransform must return String", String.class, m.getReturnType());
    assertSame("getDefaultCipherTransform must take a String parameter",
        String.class, m.getParameterTypes()[0]);
  }

  // -------------------------------------------------------------------
  // getIteration(int) — 0 callers anywhere.
  // -------------------------------------------------------------------
  @Test
  public void getIterationIsPublicReturningIntTakingInt() throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod("getIteration", int.class);
    int mods = m.getModifiers();
    assertTrue("getIteration must be public", Modifier.isPublic(mods));
    assertSame("getIteration must return primitive int", int.class, m.getReturnType());
    assertSame("getIteration must take a primitive int parameter",
        int.class, m.getParameterTypes()[0]);
  }

  // -------------------------------------------------------------------
  // getKeySize(int) — 0 callers anywhere.
  // -------------------------------------------------------------------
  @Test
  public void getKeySizeIsPublicReturningIntTakingInt() throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod("getKeySize", int.class);
    int mods = m.getModifiers();
    assertTrue("getKeySize must be public", Modifier.isPublic(mods));
    assertSame("getKeySize must return primitive int", int.class, m.getReturnType());
    assertSame("getKeySize must take a primitive int parameter",
        int.class, m.getParameterTypes()[0]);
  }

  // -------------------------------------------------------------------
  // getSaltLength(int) — 0 callers anywhere.
  // -------------------------------------------------------------------
  @Test
  public void getSaltLengthIsPublicReturningIntTakingInt() throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod("getSaltLength", int.class);
    int mods = m.getModifiers();
    assertTrue("getSaltLength must be public", Modifier.isPublic(mods));
    assertSame("getSaltLength must return primitive int", int.class, m.getReturnType());
    assertSame("getSaltLength must take a primitive int parameter",
        int.class, m.getParameterTypes()[0]);
  }

  // -------------------------------------------------------------------
  // getSeedAlgorithm(String) — 0 callers anywhere.
  // -------------------------------------------------------------------
  @Test
  public void getSeedAlgorithmIsPublicReturningStringTakingString() throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod("getSeedAlgorithm", String.class);
    int mods = m.getModifiers();
    assertTrue("getSeedAlgorithm must be public", Modifier.isPublic(mods));
    assertSame("getSeedAlgorithm must return String", String.class, m.getReturnType());
    assertSame("getSeedAlgorithm must take a String parameter",
        String.class, m.getParameterTypes()[0]);
  }

  // -------------------------------------------------------------------
  // getSeedPhrase(String) — 0 callers anywhere.
  // -------------------------------------------------------------------
  @Test
  public void getSeedPhraseIsPublicReturningStringTakingString() throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod("getSeedPhrase", String.class);
    int mods = m.getModifiers();
    assertTrue("getSeedPhrase must be public", Modifier.isPublic(mods));
    assertSame("getSeedPhrase must return String", String.class, m.getReturnType());
    assertSame("getSeedPhrase must take a String parameter",
        String.class, m.getParameterTypes()[0]);
  }

  // ===================================================================
  // Group 2 — Dead setters (7 methods).
  // ===================================================================

  // -------------------------------------------------------------------
  // setDefaultCipherTransform(String) — only caller is dead SymmetricKeyCI.
  // -------------------------------------------------------------------
  @Test
  public void setDefaultCipherTransformIsPublicReturningSymmetricKeyTakingString()
      throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod("setDefaultCipherTransform", String.class);
    int mods = m.getModifiers();
    assertTrue("setDefaultCipherTransform must be public", Modifier.isPublic(mods));
    assertSame("setDefaultCipherTransform must return SymmetricKey (fluent setter)",
        SymmetricKey.class, m.getReturnType());
    assertSame("setDefaultCipherTransform must take a String parameter",
        String.class, m.getParameterTypes()[0]);
  }

  // -------------------------------------------------------------------
  // setIteration(int) — 0 callers.
  // -------------------------------------------------------------------
  @Test
  public void setIterationIsPublicReturningSymmetricKeyTakingInt() throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod("setIteration", int.class);
    int mods = m.getModifiers();
    assertTrue("setIteration must be public", Modifier.isPublic(mods));
    assertSame("setIteration must return SymmetricKey (fluent setter)",
        SymmetricKey.class, m.getReturnType());
    assertSame("setIteration must take a primitive int parameter",
        int.class, m.getParameterTypes()[0]);
  }

  // -------------------------------------------------------------------
  // setKeyAlgorithm(String) — 0 callers.
  // -------------------------------------------------------------------
  @Test
  public void setKeyAlgorithmIsPublicReturningSymmetricKeyTakingString() throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod("setKeyAlgorithm", String.class);
    int mods = m.getModifiers();
    assertTrue("setKeyAlgorithm must be public", Modifier.isPublic(mods));
    assertSame("setKeyAlgorithm must return SymmetricKey (fluent setter)",
        SymmetricKey.class, m.getReturnType());
    assertSame("setKeyAlgorithm must take a String parameter",
        String.class, m.getParameterTypes()[0]);
  }

  // -------------------------------------------------------------------
  // setKeySize(int) — 0 callers.
  // -------------------------------------------------------------------
  @Test
  public void setKeySizeIsPublicReturningSymmetricKeyTakingInt() throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod("setKeySize", int.class);
    int mods = m.getModifiers();
    assertTrue("setKeySize must be public", Modifier.isPublic(mods));
    assertSame("setKeySize must return SymmetricKey (fluent setter)",
        SymmetricKey.class, m.getReturnType());
    assertSame("setKeySize must take a primitive int parameter",
        int.class, m.getParameterTypes()[0]);
  }

  // -------------------------------------------------------------------
  // setSaltLength(int) — 0 callers.
  // -------------------------------------------------------------------
  @Test
  public void setSaltLengthIsPublicReturningSymmetricKeyTakingInt() throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod("setSaltLength", int.class);
    int mods = m.getModifiers();
    assertTrue("setSaltLength must be public", Modifier.isPublic(mods));
    assertSame("setSaltLength must return SymmetricKey (fluent setter)",
        SymmetricKey.class, m.getReturnType());
    assertSame("setSaltLength must take a primitive int parameter",
        int.class, m.getParameterTypes()[0]);
  }

  // -------------------------------------------------------------------
  // setSeedAlgorithm(String) — 0 callers.
  // -------------------------------------------------------------------
  @Test
  public void setSeedAlgorithmIsPublicReturningSymmetricKeyTakingString() throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod("setSeedAlgorithm", String.class);
    int mods = m.getModifiers();
    assertTrue("setSeedAlgorithm must be public", Modifier.isPublic(mods));
    assertSame("setSeedAlgorithm must return SymmetricKey (fluent setter)",
        SymmetricKey.class, m.getReturnType());
    assertSame("setSeedAlgorithm must take a String parameter",
        String.class, m.getParameterTypes()[0]);
  }

  // -------------------------------------------------------------------
  // setSeedPhrase(String) — 0 callers.
  // -------------------------------------------------------------------
  @Test
  public void setSeedPhraseIsPublicReturningSymmetricKeyTakingString() throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod("setSeedPhrase", String.class);
    int mods = m.getModifiers();
    assertTrue("setSeedPhrase must be public", Modifier.isPublic(mods));
    assertSame("setSeedPhrase must return SymmetricKey (fluent setter)",
        SymmetricKey.class, m.getReturnType());
    assertSame("setSeedPhrase must take a String parameter",
        String.class, m.getParameterTypes()[0]);
  }

  // ===================================================================
  // Group 3 — Dead static factory methods (6 methods, all reachable only via
  // SymmetricKeyCI/SymmetricKeySecurity).
  // ===================================================================

  // -------------------------------------------------------------------
  // fromConfig(SymmetricKeyConfig) — only caller is dead SymmetricKeySecurity.
  // -------------------------------------------------------------------
  @Test
  public void fromConfigIsPublicStaticReturningSymmetricKeyTakingSymmetricKeyConfig()
      throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod("fromConfig", SymmetricKeyConfig.class);
    int mods = m.getModifiers();
    assertTrue("fromConfig must be public", Modifier.isPublic(mods));
    assertTrue("fromConfig must be static", Modifier.isStatic(mods));
    assertSame("fromConfig must return SymmetricKey", SymmetricKey.class, m.getReturnType());
    assertSame("fromConfig must take a SymmetricKeyConfig parameter",
        SymmetricKeyConfig.class, m.getParameterTypes()[0]);
  }

  // -------------------------------------------------------------------
  // fromString(String, String) — only non-self caller is dead SymmetricKeyCI (the self call
  // is from fromStream which is itself dead).
  // -------------------------------------------------------------------
  @Test
  public void fromStringIsPublicStaticReturningSymmetricKeyTakingTwoStrings() throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod("fromString", String.class, String.class);
    int mods = m.getModifiers();
    assertTrue("fromString must be public", Modifier.isPublic(mods));
    assertTrue("fromString must be static", Modifier.isStatic(mods));
    assertSame("fromString must return SymmetricKey", SymmetricKey.class, m.getReturnType());
    assertEquals("fromString must take two parameters", 2, m.getParameterCount());
    assertSame(String.class, m.getParameterTypes()[0]);
    assertSame(String.class, m.getParameterTypes()[1]);
  }

  // -------------------------------------------------------------------
  // fromFile(String, String) — only non-self caller is dead SymmetricKeyCI.
  // -------------------------------------------------------------------
  @Test
  public void fromFileIsPublicStaticReturningSymmetricKeyTakingAlgorithmAndPath()
      throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod("fromFile", String.class, String.class);
    int mods = m.getModifiers();
    assertTrue("fromFile must be public", Modifier.isPublic(mods));
    assertTrue("fromFile must be static", Modifier.isStatic(mods));
    assertSame("fromFile must return SymmetricKey", SymmetricKey.class, m.getReturnType());
  }

  // -------------------------------------------------------------------
  // fromStream(String, InputStream) — only caller (fromFile) is itself dead. No external
  // refs.
  // -------------------------------------------------------------------
  @Test
  public void fromStreamIsPublicStaticReturningSymmetricKeyTakingAlgorithmAndStream()
      throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod("fromStream", String.class, InputStream.class);
    int mods = m.getModifiers();
    assertTrue("fromStream must be public", Modifier.isPublic(mods));
    assertTrue("fromStream must be static", Modifier.isStatic(mods));
    assertSame("fromStream must return SymmetricKey", SymmetricKey.class, m.getReturnType());
    assertSame("fromStream first parameter must be String",
        String.class, m.getParameterTypes()[0]);
    assertSame("fromStream second parameter must be InputStream",
        InputStream.class, m.getParameterTypes()[1]);
  }

  // -------------------------------------------------------------------
  // fromKeystore(String, String, String, String) — only non-self caller is dead
  // SymmetricKeyCI.
  // -------------------------------------------------------------------
  @Test
  public void fromKeystoreFourStringIsPublicStaticReturningSymmetricKey() throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod(
        "fromKeystore", String.class, String.class, String.class, String.class);
    int mods = m.getModifiers();
    assertTrue("fromKeystore(4xString) must be public", Modifier.isPublic(mods));
    assertTrue("fromKeystore(4xString) must be static", Modifier.isStatic(mods));
    assertSame("fromKeystore(4xString) must return SymmetricKey",
        SymmetricKey.class, m.getReturnType());
    assertEquals("fromKeystore(4xString) must take four parameters",
        4, m.getParameterCount());
  }

  // -------------------------------------------------------------------
  // fromKeystore(InputStream, String, String, String) — only non-self caller is dead
  // SymmetricKeyCI; also called from the (dead) String overload above.
  // -------------------------------------------------------------------
  @Test
  public void fromKeystoreStreamThreeStringIsPublicStaticReturningSymmetricKey()
      throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod(
        "fromKeystore", InputStream.class, String.class, String.class, String.class);
    int mods = m.getModifiers();
    assertTrue("fromKeystore(InputStream, 3xString) must be public", Modifier.isPublic(mods));
    assertTrue("fromKeystore(InputStream, 3xString) must be static", Modifier.isStatic(mods));
    assertSame("fromKeystore(InputStream, 3xString) must return SymmetricKey",
        SymmetricKey.class, m.getReturnType());
    assertEquals("fromKeystore(InputStream, 3xString) must take four parameters",
        4, m.getParameterCount());
    assertSame("first parameter must be InputStream",
        InputStream.class, m.getParameterTypes()[0]);
  }

  // ===================================================================
  // Group 4 — decryptAsString(String). Only caller is dead SymmetricKeySecurity.
  // ===================================================================

  // -------------------------------------------------------------------
  // decryptAsString(String) — only non-self caller is dead SymmetricKeySecurity. The live
  // counterpart decrypt(String) is covered in SymmetricKeyTest.
  // -------------------------------------------------------------------
  @Test
  public void decryptAsStringIsPublicReturningStringTakingString() throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod("decryptAsString", String.class);
    int mods = m.getModifiers();
    assertTrue("decryptAsString must be public", Modifier.isPublic(mods));
    assertSame("decryptAsString must return String", String.class, m.getReturnType());
    assertSame("decryptAsString must take a String parameter",
        String.class, m.getParameterTypes()[0]);
  }

  // ===================================================================
  // Group 5 — Dead protected static helper. Only caller is dead SymmetricKeyCI.
  // ===================================================================

  // -------------------------------------------------------------------
  // separateAlgorithm(String) — protected static helper that splits a JCE cipher transformation
  // (e.g. "AES/CBC/PKCS5Padding") on '/' and returns the leading algorithm token. PSI all-scope
  // ReferencesSearch confirms the only on-tree caller is SymmetricKeyCI (itself dead-pinned).
  // Per the per-method-pinning precedent, every dead method gets its own atomic shape pin so
  // partial deletion stays valid.
  // -------------------------------------------------------------------
  @Test
  public void separateAlgorithmIsProtectedStaticReturningStringTakingString() throws Exception {
    Method m = SymmetricKey.class.getDeclaredMethod("separateAlgorithm", String.class);
    int mods = m.getModifiers();
    assertTrue("separateAlgorithm must be protected", Modifier.isProtected(mods));
    assertTrue("separateAlgorithm must be static", Modifier.isStatic(mods));
    assertSame("separateAlgorithm must return String", String.class, m.getReturnType());
    assertEquals("separateAlgorithm must take exactly one parameter",
        1, m.getParameterCount());
    assertSame("separateAlgorithm must take a String parameter",
        String.class, m.getParameterTypes()[0]);
  }
}
