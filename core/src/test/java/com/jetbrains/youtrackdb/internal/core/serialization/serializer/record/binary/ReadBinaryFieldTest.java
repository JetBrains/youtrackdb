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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import java.lang.reflect.Modifier;
import org.junit.Test;

/**
 * Direct coverage for {@link ReadBinaryField} — the read-side counterpart of
 * {@link BinaryField}. It is a Java {@code record}, so equals / hashCode / toString /
 * accessors are auto-generated; the value of pinning here is to lock the canonical
 * accessor names and the reference-identity behavior of the {@code bytes} component
 * (records do not deep-copy their components).
 */
public class ReadBinaryFieldTest {

  @Test
  public void classIsAFinalRecord() {
    var clazz = ReadBinaryField.class;
    assertTrue("ReadBinaryField is a record", clazz.isRecord());
    assertTrue("records are implicitly final", Modifier.isFinal(clazz.getModifiers()));
  }

  @Test
  public void recordHasFourComponentsInExpectedOrder() {
    var components = ReadBinaryField.class.getRecordComponents();
    assertEquals(4, components.length);
    assertEquals("name", components[0].getName());
    assertEquals(String.class, components[0].getType());
    assertEquals("type", components[1].getName());
    assertEquals(PropertyTypeInternal.class, components[1].getType());
    assertEquals("bytes", components[2].getName());
    assertEquals(ReadBytesContainer.class, components[2].getType());
    assertEquals("collate", components[3].getName());
    assertEquals(Collate.class, components[3].getType());
  }

  @Test
  public void accessorsReturnComponentValuesByReference() {
    var rbc = new ReadBytesContainer(new byte[] {1, 2, 3});
    var collate = (Collate) new DefaultCollate();
    var f = new ReadBinaryField("title", PropertyTypeInternal.STRING, rbc, collate);

    assertEquals("title", f.name());
    assertEquals(PropertyTypeInternal.STRING, f.type());
    assertSame("bytes accessor returns same ReadBytesContainer reference", rbc, f.bytes());
    assertSame(collate, f.collate());
  }

  @Test
  public void nullCollateIsAllowedAndAccessorReturnsNull() {
    var f = new ReadBinaryField("x", PropertyTypeInternal.INTEGER,
        new ReadBytesContainer(new byte[0]), null);
    assertNull(f.collate());
  }

  @Test
  public void recordEqualsConsidersAllComponents() {
    var rbc = new ReadBytesContainer(new byte[] {1, 2});
    var c = (Collate) new DefaultCollate();

    var a = new ReadBinaryField("a", PropertyTypeInternal.STRING, rbc, c);
    var b = new ReadBinaryField("a", PropertyTypeInternal.STRING, rbc, c);
    assertEquals("identical components -> equal records", a, b);
    assertEquals("hashCode consistent with equals", a.hashCode(), b.hashCode());

    var differentName =
        new ReadBinaryField("b", PropertyTypeInternal.STRING, rbc, c);
    assertNotEquals(a, differentName);

    var differentType =
        new ReadBinaryField("a", PropertyTypeInternal.INTEGER, rbc, c);
    assertNotEquals(a, differentType);

    var differentBytes =
        new ReadBinaryField("a", PropertyTypeInternal.STRING,
            new ReadBytesContainer(new byte[] {1, 2}), c);
    // ReadBytesContainer does NOT override equals — record equality compares by
    // reference identity. Two distinct ReadBytesContainer instances with identical
    // bytes are NOT equal under record semantics.
    assertNotEquals(
        "two ReadBytesContainer instances with same bytes are not equal — pin the"
            + " reference-identity contract",
        a,
        differentBytes);
  }

  @Test
  public void recordToStringContainsComponentValues() {
    var f = new ReadBinaryField("hello", PropertyTypeInternal.LONG,
        new ReadBytesContainer(new byte[0]), null);
    var str = f.toString();
    // Record toString format: ClassName[name=value, ...]
    assertTrue("toString contains class name", str.contains("ReadBinaryField"));
    assertTrue("toString contains the name component", str.contains("name=hello"));
    assertTrue("toString contains the type component", str.contains("type=LONG"));
    assertFalse("toString does not crash on null collate", str.isEmpty());
  }
}
