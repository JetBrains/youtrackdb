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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Modifier;
import org.junit.Test;

/**
 * Shape pin for {@link DistributedBinaryTokenPayload}. PSI all-scope {@code ReferencesSearch}
 * confirms <strong>zero</strong> references anywhere in the codebase — not even from sibling
 * dead-cluster classes. The class is the most clearly dead leaf in the binary-token cluster:
 * its own header comment notes it "may be removed if we do not support runtime compatibility
 * with 3.1 or less". Track 22 will absorb the deletion.
 *
 * <p>WHEN-FIXED: Track 22 — delete {@link DistributedBinaryTokenPayload}.
 */
public class DistributedBinaryTokenPayloadDeadCodeTest {

  // -------------------------------------------------------------------
  // Class-shape pin: public concrete subclass of BinaryTokenPayloadImpl.
  // -------------------------------------------------------------------
  @Test
  public void classExtendsBinaryTokenPayloadImpl() {
    int mods = DistributedBinaryTokenPayload.class.getModifiers();
    assertTrue("DistributedBinaryTokenPayload must be public", Modifier.isPublic(mods));
    assertSame("DistributedBinaryTokenPayload must extend BinaryTokenPayloadImpl",
        BinaryTokenPayloadImpl.class,
        DistributedBinaryTokenPayload.class.getSuperclass());
  }

  // -------------------------------------------------------------------
  // PayloadType pin: the override returns the literal "node". This is the only piece of
  // behaviour that distinguishes the class from its parent; if the override is dropped
  // accidentally, the parent's "YouTrackDB" payload-type would silently match the wrong codec
  // branch in BinaryTokenSerializer.getForType.
  // -------------------------------------------------------------------
  @Test
  public void getPayloadTypeReturnsTheLiteralNode() {
    var p = new DistributedBinaryTokenPayload();
    assertEquals("the distributed payload type must be the literal 'node'",
        "node", p.getPayloadType());
  }

  // -------------------------------------------------------------------
  // Default-constructor pin.
  // -------------------------------------------------------------------
  @Test
  public void publicNoArgConstructorIsDeclared() throws Exception {
    var ctor = DistributedBinaryTokenPayload.class.getDeclaredConstructor();
    assertTrue("default constructor must be public", Modifier.isPublic(ctor.getModifiers()));
    var instance = ctor.newInstance();
    assertNotNull(instance);
  }
}
