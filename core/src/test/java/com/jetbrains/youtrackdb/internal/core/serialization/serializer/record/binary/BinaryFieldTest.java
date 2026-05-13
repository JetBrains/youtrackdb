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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import java.lang.reflect.Modifier;
import org.junit.Test;

/**
 * Direct coverage for {@link BinaryField} — a four-field POJO holding a
 * {@code BytesContainer} payload alongside its property name, type, and collate.
 * Pin the public-final-field shape and the {@link BinaryField#copy()} semantics
 * (deep-copies the bytes container while keeping the other fields aliased).
 */
public class BinaryFieldTest {

  @Test
  public void allFieldsArePublicFinal() throws Exception {
    var clazz = BinaryField.class;
    var fields = new String[] {"name", "type", "bytes", "collate"};
    for (var n : fields) {
      var f = clazz.getDeclaredField(n);
      var mods = f.getModifiers();
      assertTrue("field '" + n + "' must be public", Modifier.isPublic(mods));
      assertTrue("field '" + n + "' must be final", Modifier.isFinal(mods));
    }
  }

  @Test
  public void constructorRetainsAllFields() {
    var bytes = new BytesContainer();
    var collate = (Collate) new DefaultCollate();
    var f = new BinaryField("title", PropertyTypeInternal.STRING, bytes, collate);
    assertEquals("title", f.name);
    assertEquals(PropertyTypeInternal.STRING, f.type);
    assertSame("bytes container retained by reference (no defensive copy)", bytes, f.bytes);
    assertSame("collate retained by reference", collate, f.collate);
  }

  @Test
  public void constructorAcceptsNullName() {
    var f = new BinaryField(null, PropertyTypeInternal.INTEGER, new BytesContainer(), null);
    assertNull(f.name);
    assertNull(f.collate);
    assertEquals(PropertyTypeInternal.INTEGER, f.type);
  }

  @Test
  public void copyProducesNewBinaryFieldButRetainsImmutableFieldIdentity() {
    var bytes = new BytesContainer();
    bytes.alloc(4);
    bytes.bytes[0] = 1;
    bytes.bytes[1] = 2;
    bytes.bytes[2] = 3;
    bytes.bytes[3] = 4;

    var collate = (Collate) new DefaultCollate();
    var original = new BinaryField("x", PropertyTypeInternal.INTEGER, bytes, collate);
    var copy = original.copy();

    assertNotSame("copy returns a fresh BinaryField", original, copy);
    assertEquals("name aliased (immutable)", original.name, copy.name);
    assertEquals("type aliased (enum)", original.type, copy.type);
    assertSame("collate aliased (interface — only reference identity)",
        original.collate, copy.collate);

    // The bytes container is the only mutable component — copy must produce a new
    // BytesContainer instance, BUT BytesContainer.copy() shares the underlying byte[].
    // Pin this exact behavior.
    assertNotSame("bytes container instance is fresh", original.bytes, copy.bytes);
    assertSame("underlying byte[] is shared", original.bytes.bytes, copy.bytes.bytes);
    assertEquals("offset preserved", original.bytes.offset, copy.bytes.offset);
  }

  @Test
  public void copyOffsetIsIndependent() {
    var bytes = new BytesContainer();
    bytes.alloc(2);
    var f = new BinaryField("a", PropertyTypeInternal.SHORT, bytes, null);
    var copy = f.copy();

    // Advancing the copy's offset must not affect the original — they are now two
    // independent cursors over the same array.
    copy.bytes.skip(5);
    assertEquals("original offset unchanged", 2, f.bytes.offset);
    assertEquals(7, copy.bytes.offset);
  }

  @Test
  public void copyReflectsByteMutationsThroughSharedArray() {
    // Mutation through original.bytes is visible through copy.bytes — copy() shares
    // the byte[]. This is intentional for in-place inspection patterns.
    var bytes = new BytesContainer(new byte[] {0, 0});
    var f = new BinaryField("a", PropertyTypeInternal.BYTE, bytes, null);
    var copy = f.copy();
    f.bytes.bytes[0] = 7;
    assertEquals("mutation through original visible via copy",
        7, copy.bytes.bytes[0]);
  }
}
