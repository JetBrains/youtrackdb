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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.BinaryTokenPayload;
import java.lang.reflect.Modifier;
import org.junit.Test;

/**
 * Shape pin for {@link BinaryTokenPayloadImpl}. PSI all-scope {@code ReferencesSearch} confirms
 * its only callers are within the same dead {@code core/metadata/security/binary} cluster
 * ({@link BinaryTokenPayloadDeserializer} and {@link DistributedBinaryTokenPayload}). The class
 * is a POJO over the {@link BinaryTokenPayload} interface — pure field plumbing, no I/O, no
 * JCE.
 *
 * <p>WHEN-FIXED: Track 22 — delete {@link BinaryTokenPayloadImpl} together with the rest of the
 * binary-token cluster.
 */
public class BinaryTokenPayloadImplDeadCodeTest {

  // -------------------------------------------------------------------
  // Class-shape pin: public concrete class implementing BinaryTokenPayload.
  // -------------------------------------------------------------------
  @Test
  public void classImplementsBinaryTokenPayloadAndIsPublicConcrete() {
    int mods = BinaryTokenPayloadImpl.class.getModifiers();
    assertTrue("BinaryTokenPayloadImpl must be public", Modifier.isPublic(mods));
    assertFalse("BinaryTokenPayloadImpl must be a concrete class",
        Modifier.isAbstract(mods));
    assertTrue("BinaryTokenPayloadImpl must implement BinaryTokenPayload",
        BinaryTokenPayload.class.isAssignableFrom(BinaryTokenPayloadImpl.class));
  }

  // -------------------------------------------------------------------
  // Constructor pin: the no-arg constructor is the only one declared (POJO with field
  // setters).
  // -------------------------------------------------------------------
  @Test
  public void publicNoArgConstructorIsTheOnlyDeclaredConstructor() throws Exception {
    var ctors = BinaryTokenPayloadImpl.class.getDeclaredConstructors();
    assertEquals("BinaryTokenPayloadImpl must declare exactly one constructor",
        1, ctors.length);
    assertEquals("the constructor must be no-arg",
        0, ctors[0].getParameterCount());
    assertTrue("the constructor must be public", Modifier.isPublic(ctors[0].getModifiers()));
  }

  // -------------------------------------------------------------------
  // Behavioural pin: every getter / setter pair round-trips through the underlying field.
  // Pinning all field shapes through observable behaviour catches a refactor that swaps types
  // or drops a setter.
  // -------------------------------------------------------------------
  @Test
  public void allGetterSetterPairsRoundTripThroughTheirFields() {
    var p = new BinaryTokenPayloadImpl();

    p.setUserName("alice");
    assertEquals("userName round-trip", "alice", p.getUserName());

    p.setDatabase("db");
    assertEquals("database round-trip", "db", p.getDatabase());

    p.setDatabaseType("disk");
    assertEquals("databaseType round-trip", "disk", p.getDatabaseType());

    p.setExpiry(42L);
    assertEquals("expiry round-trip", 42L, p.getExpiry());

    var rid = new RecordId(7, 13);
    p.setUserRid(rid);
    assertSame("userRid round-trip", rid, p.getUserRid());

    p.setProtocolVersion((short) 99);
    assertEquals("protocolVersion round-trip", (short) 99, p.getProtocolVersion());

    p.setSerializer("ser");
    assertEquals("serializer round-trip", "ser", p.getSerializer());

    p.setDriverName("drv");
    assertEquals("driverName round-trip", "drv", p.getDriverName());

    p.setDriverVersion("v1");
    assertEquals("driverVersion round-trip", "v1", p.getDriverVersion());

    p.setServerUser(true);
    assertTrue("serverUser round-trip (true)", p.isServerUser());
    p.setServerUser(false);
    assertFalse("serverUser round-trip (false)", p.isServerUser());
  }

  // -------------------------------------------------------------------
  // Defaults pin: a freshly-constructed payload has all-default field values.
  // -------------------------------------------------------------------
  @Test
  public void freshlyConstructedPayloadHasAllNullOrZeroFieldDefaults() {
    var p = new BinaryTokenPayloadImpl();
    assertNull("userName default", p.getUserName());
    assertNull("database default", p.getDatabase());
    assertNull("databaseType default", p.getDatabaseType());
    assertEquals("expiry default", 0L, p.getExpiry());
    assertNull("userRid default", p.getUserRid());
    assertEquals("protocolVersion default", (short) 0, p.getProtocolVersion());
    assertNull("serializer default", p.getSerializer());
    assertNull("driverName default", p.getDriverName());
    assertNull("driverVersion default", p.getDriverVersion());
    assertFalse("serverUser default", p.isServerUser());
  }

  // -------------------------------------------------------------------
  // PayloadType pin: the constant string "YouTrackDB" gates the
  // BinaryTokenSerializer.getForType dispatch in the dead cluster. Renaming this string would
  // silently break the deserialiser.
  // -------------------------------------------------------------------
  @Test
  public void getPayloadTypeReturnsTheLiteralYouTrackDb() {
    var p = new BinaryTokenPayloadImpl();
    assertEquals("the payload type must be the literal 'YouTrackDB'",
        "YouTrackDB", p.getPayloadType());
  }

  // -------------------------------------------------------------------
  // Sanity: subclass DistributedBinaryTokenPayload overrides getPayloadType. The override is
  // pinned in DistributedBinaryTokenPayloadDeadCodeTest; here we confirm the impl class itself
  // does NOT have a final modifier (otherwise the subclass override would not compile).
  // -------------------------------------------------------------------
  @Test
  public void classIsNotFinalSoTheDistributedSubclassCanOverridePayloadType() {
    assertFalse("BinaryTokenPayloadImpl must not be final",
        Modifier.isFinal(BinaryTokenPayloadImpl.class.getModifiers()));
    var sub = new DistributedBinaryTokenPayload();
    assertNotNull(sub);
  }
}
