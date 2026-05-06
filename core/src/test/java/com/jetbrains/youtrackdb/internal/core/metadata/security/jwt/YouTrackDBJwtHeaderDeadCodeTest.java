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
package com.jetbrains.youtrackdb.internal.core.metadata.security.jwt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Modifier;
import org.junit.Test;

/**
 * Shape pin for {@link YouTrackDBJwtHeader}. PSI all-scope {@code ReferencesSearch} confirms
 * its only callers are inside the dead {@code BinaryTokenSerializer} (also pinned) — the
 * deserialise path constructs an instance and populates its fields from the binary stream. No
 * other production code constructs a {@link YouTrackDBJwtHeader}.
 *
 * <p>WHEN-FIXED: Track 22 — delete {@link YouTrackDBJwtHeader} together with the rest of the
 * binary-token cluster.
 */
public class YouTrackDBJwtHeaderDeadCodeTest {

  // -------------------------------------------------------------------
  // Class-shape pin: public concrete class implementing TokenHeader.
  // -------------------------------------------------------------------
  @Test
  public void classImplementsTokenHeaderAndIsPublicConcrete() {
    int mods = YouTrackDBJwtHeader.class.getModifiers();
    assertTrue("YouTrackDBJwtHeader must be public", Modifier.isPublic(mods));
    assertTrue("YouTrackDBJwtHeader must implement TokenHeader",
        TokenHeader.class.isAssignableFrom(YouTrackDBJwtHeader.class));
  }

  // -------------------------------------------------------------------
  // Default-constructor pin (the deserialise path uses it directly).
  // -------------------------------------------------------------------
  @Test
  public void publicNoArgConstructorIsDeclared() throws Exception {
    var ctor = YouTrackDBJwtHeader.class.getDeclaredConstructor();
    assertTrue("default constructor must be public", Modifier.isPublic(ctor.getModifiers()));
    var instance = ctor.newInstance();
    assertNotNull(instance);
  }

  // -------------------------------------------------------------------
  // Behavioural pin: every getter / setter pair round-trips through its private field.
  // -------------------------------------------------------------------
  @Test
  public void allTokenHeaderGetterSetterPairsRoundTrip() {
    var h = new YouTrackDBJwtHeader();
    assertNull("type default", h.getType());
    assertNull("alg default", h.getAlgorithm());
    assertNull("kid default", h.getKeyId());

    h.setType("JWT");
    h.setAlgorithm("HS256");
    h.setKeyId("k1");
    assertEquals("type round-trip", "JWT", h.getType());
    assertEquals("alg round-trip", "HS256", h.getAlgorithm());
    assertEquals("kid round-trip", "k1", h.getKeyId());
  }
}
