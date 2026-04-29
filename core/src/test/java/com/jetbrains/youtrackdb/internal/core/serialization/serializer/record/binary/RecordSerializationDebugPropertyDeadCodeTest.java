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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.Test;

/**
 * Shape pin for {@link RecordSerializationDebugProperty}. Like its sibling
 * {@link RecordSerializationDebug}, this is a zero-callers POJO retained as the
 * element type of the never-landed debug-deserialization path. Pin the public surface
 * so a future deletion is deliberate.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete this class together with
 * {@link RecordSerializationDebug} once the unused debug path is confirmed
 * unreachable. When deletion lands, this test should be removed.
 *
 * <p>Note: the type field name {@code faildToRead} (sic) is preserved verbatim for
 * the same reason. The shape is locked even where the original code has typos —
 * renaming would silently break any downstream consumer accessing the field via
 * reflection. The deferred-cleanup track should fix the typo at the same time it
 * deletes the class.
 */
public class RecordSerializationDebugPropertyDeadCodeTest {

  @Test
  public void classIsPublicNonAbstractNonFinal() {
    var mods = RecordSerializationDebugProperty.class.getModifiers();
    assertTrue(Modifier.isPublic(mods));
    assertFalse(Modifier.isAbstract(mods));
    assertFalse(Modifier.isFinal(mods));
  }

  @Test
  public void allFieldsArePublicMutableInstanceFields() throws Exception {
    // Note the typo "faildToRead" — preserved deliberately as part of the shape pin.
    var expectedFields = new String[] {
        "name", "globalId", "type", "readingException", "faildToRead",
        "failPosition", "value", "valuePos"
    };
    for (var name : expectedFields) {
      Field f = RecordSerializationDebugProperty.class.getDeclaredField(name);
      var mods = f.getModifiers();
      assertTrue("field '" + name + "' must be public", Modifier.isPublic(mods));
      assertFalse("field '" + name + "' must not be final", Modifier.isFinal(mods));
      assertFalse("field '" + name + "' must not be static", Modifier.isStatic(mods));
    }
  }

  @Test
  public void fieldTypesMatchExpectedShape() throws Exception {
    var clazz = RecordSerializationDebugProperty.class;
    assertEquals(String.class, clazz.getDeclaredField("name").getType());
    assertEquals(int.class, clazz.getDeclaredField("globalId").getType());
    assertEquals(PropertyTypeInternal.class, clazz.getDeclaredField("type").getType());
    assertEquals(RuntimeException.class, clazz.getDeclaredField("readingException").getType());
    assertEquals(boolean.class, clazz.getDeclaredField("faildToRead").getType());
    assertEquals(int.class, clazz.getDeclaredField("failPosition").getType());
    assertEquals(Object.class, clazz.getDeclaredField("value").getType());
    assertEquals(int.class, clazz.getDeclaredField("valuePos").getType());
  }

  @Test
  public void freshInstanceHasDefaultZeroValues() {
    var p = new RecordSerializationDebugProperty();
    assertNull(p.name);
    assertEquals(0, p.globalId);
    assertNull(p.type);
    assertNull(p.readingException);
    assertFalse(p.faildToRead);
    assertEquals(0, p.failPosition);
    assertNull(p.value);
    assertEquals(0, p.valuePos);
  }

  @Test
  public void fieldAssignmentsAreObservableAndIndependent() {
    var p = new RecordSerializationDebugProperty();
    p.name = "x";
    p.globalId = 7;
    p.type = PropertyTypeInternal.INTEGER;
    p.readingException = new RuntimeException("e");
    p.faildToRead = true;
    p.failPosition = 99;
    p.value = "v";
    p.valuePos = 12;

    assertEquals("x", p.name);
    assertEquals(7, p.globalId);
    assertEquals(PropertyTypeInternal.INTEGER, p.type);
    assertEquals("e", p.readingException.getMessage());
    assertTrue(p.faildToRead);
    assertEquals(99, p.failPosition);
    assertSame("value field stores reference identity", "v", p.value);
    assertEquals(12, p.valuePos);

    // Assigning to one instance does not affect another.
    var q = new RecordSerializationDebugProperty();
    assertNull("independent instances", q.name);
  }
}
