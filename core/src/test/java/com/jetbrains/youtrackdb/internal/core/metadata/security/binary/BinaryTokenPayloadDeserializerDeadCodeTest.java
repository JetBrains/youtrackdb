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
package com.jetbrains.youtrackdb.internal.core.metadata.security.binary;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.BinaryTokenPayload;
import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.TokenPayloadDeserializer;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.Test;

/**
 * Shape pin for {@link BinaryTokenPayloadDeserializer}. PSI all-scope {@code ReferencesSearch}
 * confirms the only caller is {@link BinaryTokenSerializer#deserialize(java.io.InputStream)}
 * (also dead-pinned). The class implements the {@link TokenPayloadDeserializer} SPI but no
 * production code outside the dead binary-token cluster registers a custom deserialiser.
 *
 * <p>WHEN-FIXED: Track 22 — delete {@link BinaryTokenPayloadDeserializer} together with the
 * rest of the binary-token cluster.
 */
public class BinaryTokenPayloadDeserializerDeadCodeTest {

  // -------------------------------------------------------------------
  // Class-shape pin: public concrete implementing the dead TokenPayloadDeserializer SPI.
  // -------------------------------------------------------------------
  @Test
  public void classImplementsTokenPayloadDeserializerAndIsPublicConcrete() {
    int mods = BinaryTokenPayloadDeserializer.class.getModifiers();
    assertTrue("BinaryTokenPayloadDeserializer must be public", Modifier.isPublic(mods));
    assertFalse("BinaryTokenPayloadDeserializer must be a concrete class",
        Modifier.isAbstract(mods));
    assertTrue("BinaryTokenPayloadDeserializer must implement TokenPayloadDeserializer",
        TokenPayloadDeserializer.class.isAssignableFrom(BinaryTokenPayloadDeserializer.class));
  }

  // -------------------------------------------------------------------
  // No-arg constructor pin (matches the call site at BinaryTokenSerializer.getForType, line 43
  // of that file).
  // -------------------------------------------------------------------
  @Test
  public void publicNoArgConstructorMatchesGetForTypeCallSite() throws Exception {
    var ctor = BinaryTokenPayloadDeserializer.class.getDeclaredConstructor();
    assertTrue("no-arg constructor must be public", Modifier.isPublic(ctor.getModifiers()));
    var instance = ctor.newInstance();
    assertNotNull(instance);
  }

  // -------------------------------------------------------------------
  // Method-shape pin: deserialize(DataInputStream, TokenMetaInfo) returns BinaryTokenPayload
  // and declares IOException.
  // -------------------------------------------------------------------
  @Test
  public void deserializeMethodReturnsBinaryTokenPayloadAndDeclaresIoException()
      throws Exception {
    Method m = BinaryTokenPayloadDeserializer.class.getDeclaredMethod(
        "deserialize", DataInputStream.class,
        com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.TokenMetaInfo.class);
    assertTrue("deserialize must be public", Modifier.isPublic(m.getModifiers()));
    assertSame("deserialize must return BinaryTokenPayload",
        BinaryTokenPayload.class, m.getReturnType());
    assertTrue("deserialize must declare IOException",
        Arrays.asList(m.getExceptionTypes()).contains(IOException.class));
  }
}
