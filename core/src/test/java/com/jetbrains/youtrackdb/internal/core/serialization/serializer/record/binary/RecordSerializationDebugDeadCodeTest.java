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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import org.junit.Test;

/**
 * Shape pin for {@link RecordSerializationDebug}. The class is a public POJO with no
 * callers in {@code core/src/main/java} (verified via grep at the time of writing) — it
 * was apparently intended as the return type of a debug-mode deserialization path that
 * never landed. This test pins its public surface so that a future deletion is
 * deliberate, not silent.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete {@code RecordSerializationDebug} and
 * {@code RecordSerializationDebugProperty} together once the never-landed debug path is
 * confirmed truly unreachable. When deletion lands, both this test and the companion
 * pin should be removed.
 */
public class RecordSerializationDebugDeadCodeTest {

  @Test
  public void classIsPublicNonAbstractNonFinal() {
    var mods = RecordSerializationDebug.class.getModifiers();
    assertTrue("class must be public", Modifier.isPublic(mods));
    assertFalse("class must not be abstract", Modifier.isAbstract(mods));
    assertFalse("class must not be final", Modifier.isFinal(mods));
  }

  @Test
  public void allFieldsArePublicAndMutable() throws Exception {
    var clazz = RecordSerializationDebug.class;
    var expectedFields = new String[] {
        "className", "properties", "readingFailure", "readingException", "failPosition"
    };
    for (var name : expectedFields) {
      Field f = clazz.getDeclaredField(name);
      var mods = f.getModifiers();
      assertTrue("field '" + name + "' must be public", Modifier.isPublic(mods));
      assertFalse("field '" + name + "' must not be final", Modifier.isFinal(mods));
      assertFalse("field '" + name + "' must not be static", Modifier.isStatic(mods));
    }
  }

  @Test
  public void fieldTypeMatchesExpectedShape() throws Exception {
    var clazz = RecordSerializationDebug.class;
    assertEquals(String.class, clazz.getDeclaredField("className").getType());
    assertEquals(ArrayList.class, clazz.getDeclaredField("properties").getType());
    assertEquals(boolean.class, clazz.getDeclaredField("readingFailure").getType());
    assertEquals(RuntimeException.class, clazz.getDeclaredField("readingException").getType());
    assertEquals(int.class, clazz.getDeclaredField("failPosition").getType());
  }

  @Test
  public void freshInstanceHasDefaultZeroValues() {
    var d = new RecordSerializationDebug();
    assertNull(d.className);
    assertNull(d.properties);
    assertFalse(d.readingFailure);
    assertNull(d.readingException);
    assertEquals(0, d.failPosition);
  }

  @Test
  public void fieldAssignmentsAreObservable() {
    var d = new RecordSerializationDebug();
    d.className = "MyClass";
    d.properties = new ArrayList<>();
    d.properties.add(new RecordSerializationDebugProperty());
    d.readingFailure = true;
    d.readingException = new RuntimeException("boom");
    d.failPosition = 42;

    assertEquals("MyClass", d.className);
    assertNotNull(d.properties);
    assertEquals(1, d.properties.size());
    assertTrue(d.readingFailure);
    assertEquals("boom", d.readingException.getMessage());
    assertEquals(42, d.failPosition);
  }

  @Test
  public void instancesAreIndependent() {
    var a = new RecordSerializationDebug();
    var b = new RecordSerializationDebug();
    a.className = "A";
    b.className = "B";
    assertEquals("A", a.className);
    assertEquals("B", b.className);
    assertNull("a.properties remains independent of b", a.properties);
  }

  @Test
  public void propertiesListReferenceIdentityIsRetained() {
    // The class is a thin POJO — no defensive copy on assignment. A future change that
    // started cloning the list on assignment would break callers expecting to mutate
    // through the field.
    var list = new ArrayList<RecordSerializationDebugProperty>();
    var d = new RecordSerializationDebug();
    d.properties = list;
    assertSame("no defensive copy on field assignment", list, d.properties);
  }
}
