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

package com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import org.junit.Test;

/**
 * Shape pin for {@link MockSerializer} — a sentinel placeholder registered for the
 * {@code EMBEDDED} property type slot in {@link BinarySerializerFactory}. Every
 * non-trivial method is a no-op or returns zero/null. Pinning the sentinel shape
 * matters because if the factory ever genuinely dispatches an EMBEDDED serialization
 * through this object, the silent zero/null return values will corrupt records without
 * any error path; tests on consumers that depend on EMBEDDED dispatch must therefore
 * not silently catch the misroute by re-implementing this stub.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — replace with a real {@code EMBEDDED}
 * serializer or remove the registration in
 * {@link BinarySerializerFactory#create(int)} (its registration loop today) once it is confirmed
 * the EMBEDDED slot is never reached through the factory dispatch path. Note that
 * MockSerializer is still wired in by the factory, so deletion requires deleting the
 * factory registration in lockstep.
 */
public class MockSerializerDeadCodeTest {

  @Test
  public void classShape() {
    var clazz = MockSerializer.class;
    assertTrue("class is public", Modifier.isPublic(clazz.getModifiers()));
    assertFalse("class is not abstract", Modifier.isAbstract(clazz.getModifiers()));
    assertFalse("class is not final", Modifier.isFinal(clazz.getModifiers()));
    assertTrue(
        "class implements BinarySerializer", BinarySerializer.class.isAssignableFrom(clazz));
  }

  @Test
  public void instanceFieldIsPublicStaticAndStable() throws Exception {
    var f = MockSerializer.class.getDeclaredField("INSTANCE");
    var mods = f.getModifiers();
    assertTrue("INSTANCE must be public", Modifier.isPublic(mods));
    assertTrue("INSTANCE must be static", Modifier.isStatic(mods));
    // Field is not final today — pin the present shape so a future change is deliberate.
    assertFalse(
        "INSTANCE is not final today (assignable). Deferred-cleanup track should make"
            + " it final or delete the field along with the class.",
        Modifier.isFinal(mods));

    var first = (MockSerializer) f.get(null);
    var second = (MockSerializer) f.get(null);
    assertSame("INSTANCE returns the same singleton each access", first, second);
    assertNotNull(first);
  }

  @Test
  public void protectedNoArgConstructor() throws Exception {
    var ctor = MockSerializer.class.getDeclaredConstructor();
    assertTrue("no-arg ctor is protected", Modifier.isProtected(ctor.getModifiers()));
    // Subclass instantiation through reflection works (verifies "protected", not "private").
    ctor.setAccessible(true);
    var fresh = ctor.newInstance();
    assertNotNull(fresh);
    assertNotSame("fresh instance distinct from INSTANCE", MockSerializer.INSTANCE, fresh);
  }

  // --- Behavioral pins: every override is a no-op or returns zero/null. ---

  @Test
  public void getIdReturnsNegativeTen() {
    // -10 is the sentinel id picked because the BinarySerializer id space is
    // non-negative for real serializers; a negative id signals "this is a stub".
    assertEquals((byte) -10, MockSerializer.INSTANCE.getId());
  }

  @Test
  public void isFixedLengthReturnsFalse() {
    assertFalse(MockSerializer.INSTANCE.isFixedLength());
  }

  @Test
  public void getFixedLengthReturnsZero() {
    assertEquals(0, MockSerializer.INSTANCE.getFixedLength());
  }

  @Test
  public void allObjectSizeOverridesReturnZero() {
    var s = MockSerializer.INSTANCE;
    var bytes = new byte[16];
    var bb = ByteBuffer.allocate(16);

    assertEquals(0, s.getObjectSize(null, (EntityImpl) null));
    assertEquals(0, s.getObjectSize(null, bytes, 0));
    assertEquals(0, s.getObjectSizeNative(null, bytes, 0));
    assertEquals(0, s.getObjectSizeInByteBuffer(null, bb));
    assertEquals(0, s.getObjectSizeInByteBuffer(null, 0, bb));
    assertEquals(0, s.getObjectSizeInByteBuffer(bb, null, 0));
  }

  @Test
  public void allDeserializeOverridesReturnNull() {
    var s = MockSerializer.INSTANCE;
    var bytes = new byte[16];
    var bb = ByteBuffer.allocate(16);

    assertNull(s.deserialize(null, bytes, 0));
    assertNull(s.deserializeNativeObject(null, bytes, 0));
    assertNull(s.deserializeFromByteBufferObject(null, bb));
    assertNull(s.deserializeFromByteBufferObject(null, 0, bb));
    assertNull(s.deserializeFromByteBufferObject(null, bb, null, 0));
  }

  @Test
  public void preprocessReturnsNullForNullInput() {
    var s = MockSerializer.INSTANCE;
    // The contract usually calls for preprocess to return its input; the sentinel
    // returns null instead. Pin this behavioral divergence so a future "fix" doesn't
    // accidentally start returning input — which would change record serialization
    // semantics for callers that reach this stub. Even non-null inputs would return
    // null here, but we pass null to avoid constructing an EntityImpl (which requires
    // a session).
    assertNull(
        "MockSerializer.preprocess returns null — sentinel shape",
        s.preprocess(null, (EntityImpl) null));
  }

  @Test
  public void allWriteOverridesAreNoOps() {
    var s = MockSerializer.INSTANCE;
    var bytes = new byte[8];
    bytes[0] = 0x12;
    bytes[7] = 0x34;
    var bb = ByteBuffer.allocate(8);
    bb.put(0, (byte) 0x56);
    bb.put(7, (byte) 0x78);

    // Pass null as the EntityImpl payload — the stub's body is empty, so it never
    // dereferences the parameter, and we avoid the session-dependent ctor.
    s.serialize((EntityImpl) null, null, bytes, 0);
    s.serializeNativeObject((EntityImpl) null, null, bytes, 0);
    s.serializeInByteBufferObject(null, (EntityImpl) null, bb);

    // No-op: bytes[0] / bytes[7] / bb position-0 / position-7 are unchanged.
    assertEquals(0x12, bytes[0]);
    assertEquals(0x34, bytes[7]);
    assertEquals(0x56, bb.get(0));
    assertEquals(0x78, bb.get(7));
  }

  @Test
  public void registeredInFactoryForEmbeddedSlot() {
    var factory =
        BinarySerializerFactory.create(BinarySerializerFactory.currentBinaryFormatVersion());
    BinarySerializer<?> embedded = factory.getObjectSerializer(PropertyTypeInternal.EMBEDDED);
    assertNotNull("EMBEDDED slot must resolve to a serializer", embedded);
    assertSame(
        "EMBEDDED slot must resolve to the MockSerializer singleton",
        MockSerializer.INSTANCE,
        embedded);
  }
}
