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

package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.serialization.SerializableStream;
import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import org.junit.Test;

/**
 * Shape pin for {@link SerializableWrapper}. The class has zero callers in
 * {@code core/src/main/java} (verified via grep at the time of writing); it is a thin
 * Java-Serialization based wrapper around {@link Serializable} payloads. This pin locks
 * its public surface and behavioral invariants so that a deletion (the recommended fate
 * — see WHEN-FIXED) is a deliberate change rather than a silent rename/move that breaks
 * a downstream consumer who reaches for the class via reflection.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete the entire {@code SerializableWrapper}
 * class once Java-Serialization-based payload wrapping is confirmed unused outside the
 * core module (driver/server already use binary record serialization). When deletion
 * lands, this whole test file should be removed.
 */
public class SerializableWrapperDeadCodeTest {

  @Test
  public void classIsPublicAndImplementsSerializableStream() {
    var clazz = SerializableWrapper.class;
    assertTrue("class should be public", Modifier.isPublic(clazz.getModifiers()));
    assertFalse("class should not be abstract", Modifier.isAbstract(clazz.getModifiers()));
    assertFalse("class should not be final", Modifier.isFinal(clazz.getModifiers()));
    assertTrue(
        "must implement SerializableStream",
        SerializableStream.class.isAssignableFrom(clazz));
  }

  @Test
  public void noArgConstructorIsPublicAndProducesNullPayload() throws Exception {
    var ctor = SerializableWrapper.class.getDeclaredConstructor();
    assertTrue("no-arg ctor must be public", Modifier.isPublic(ctor.getModifiers()));
    var instance = ctor.newInstance();
    assertNull("payload starts null", instance.getSerializable());
  }

  @Test
  public void serializableArgConstructorRetainsPayload() {
    var payload = (Serializable) "hello";
    var w = new SerializableWrapper(payload);
    // Reference identity matters here — the ctor must NOT defensively copy the payload.
    assertSame("ctor must store the same Serializable reference", payload, w.getSerializable());
  }

  @Test
  public void toStreamFromStreamRoundTripsArbitraryPayload() {
    var original = new ArrayList<Integer>();
    original.add(1);
    original.add(2);
    original.add(3);

    var sender = new SerializableWrapper(original);
    var stream = sender.toStream();
    assertNotNull("toStream must produce bytes", stream);
    assertTrue("Java-Serialization output is non-empty", stream.length > 0);

    // fromStream returns this; pin that contract.
    var receiver = new SerializableWrapper();
    var returned = receiver.fromStream(stream);
    assertSame("fromStream must return the receiver itself", receiver, returned);

    var roundTripped = receiver.getSerializable();
    assertEquals("payload contents survive Java-Serialization", original, roundTripped);
  }

  @Test
  public void malformedPayloadStreamThrowsDatabaseException() {
    // Pin the wrapping shape: the wrapper catches IOException/ClassNotFoundException
    // from ObjectInputStream and rethrows as DatabaseException via
    // BaseException.wrapException. Note that the declared `throws SerializationException`
    // clause on fromStream is misleading — DatabaseException and SerializationException
    // are siblings under CoreException, so this test pins the actual runtime type.
    var w = new SerializableWrapper();
    var ex = assertThrows(
        DatabaseException.class, () -> w.fromStream(new byte[] {0x00, 0x01}));
    assertTrue(
        "exception message must mention deserialization",
        ex.getMessage() != null && ex.getMessage().contains("deserialization"));
    assertNotNull("wrapped IOException must be retained as the cause", ex.getCause());
  }

  @Test
  public void toStreamWithNullPayloadProducesNonEmptyStream() {
    // Java-Serialization writes a header even for a null reference, so toStream() with
    // a null payload must still return a non-empty array. This pins the
    // "no special-case" behavior — a future "skip null" optimization would change the
    // wire shape and break disk/wire compatibility for any persisted payload.
    var sender = new SerializableWrapper();
    assertNull("precondition", sender.getSerializable());
    var stream = sender.toStream();
    assertTrue("null payload still emits the Java-Serialization header", stream.length > 0);

    var receiver = new SerializableWrapper(((Serializable) "preset"));
    receiver.fromStream(stream);
    assertNull("round-trip of null payload deserializes back to null", receiver.getSerializable());
  }

  @Test
  public void roundTripPreservesByteIdentityAcrossWrappers() {
    // Two senders constructed with the same Integer should produce identical bytes —
    // Java-Serialization is deterministic for primitives. This is a byte-shape pin: a
    // change to Java-Serialization version markers or to the wrapper itself would break
    // it.
    var a = new SerializableWrapper(42);
    var b = new SerializableWrapper(42);
    assertArrayEquals(
        "same Serializable payload must produce identical Java-Serialization output",
        a.toStream(),
        b.toStream());
  }
}
