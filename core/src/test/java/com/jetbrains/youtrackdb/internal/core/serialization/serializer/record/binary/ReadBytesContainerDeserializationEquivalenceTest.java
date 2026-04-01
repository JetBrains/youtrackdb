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
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end equivalence tests validating that the ReadBytesContainer deserialization path
 * produces identical results to the byte[] BytesContainer path. Since fromStream now wires
 * through ReadBytesContainer internally, all existing tests also exercise this path. These tests
 * provide explicit verification with both heap and direct ByteBuffer backing.
 */
public class ReadBytesContainerDeserializationEquivalenceTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;

  @Before
  public void setUp() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance("memory:");
    youTrackDB.create(
        "readBytesEquiv",
        DatabaseType.MEMORY,
        new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));
    session = (DatabaseSessionEmbedded) youTrackDB.open("readBytesEquiv", "admin", "adminpwd");
  }

  @After
  public void tearDown() {
    try {
      if (session != null && !session.isClosed()) {
        session.close();
      }
    } finally {
      if (youTrackDB != null) {
        try {
          if (youTrackDB.exists("readBytesEquiv")) {
            youTrackDB.drop("readBytesEquiv");
          }
        } finally {
          youTrackDB.close();
        }
      }
    }
  }

  // --- Primitive types ---

  @Test
  public void testAllPrimitiveTypes() {
    session.begin();
    var serialized = serializeEntity(entity -> {
      entity.setProperty("intField", 42);
      entity.setProperty("longField", 9876543210L);
      entity.setProperty("shortField", (short) 1234);
      entity.setProperty("byteField", (byte) 7);
      entity.setProperty("floatField", 3.14f);
      entity.setProperty("doubleField", 2.71828);
      entity.setProperty("boolTrue", true);
      entity.setProperty("boolFalse", false);
      entity.setProperty("stringField", "Hello World");
      entity.setProperty("dateField", new Date(1000000000000L));
      entity.setProperty("dateTimeField", new Date(1617235200000L));
    });

    var rbcResult = deserializeViaReadBytesContainer(serialized);
    assertEquals(42, (int) rbcResult.getProperty("intField"));
    assertEquals(9876543210L, (long) rbcResult.getProperty("longField"));
    assertEquals((short) 1234, (short) rbcResult.getProperty("shortField"));
    assertEquals((byte) 7, (byte) rbcResult.getProperty("byteField"));
    assertEquals(3.14f, (float) rbcResult.getProperty("floatField"), 0.001f);
    assertEquals(2.71828, (double) rbcResult.getProperty("doubleField"), 0.00001);
    assertTrue(rbcResult.getProperty("boolTrue"));
    assertFalse(rbcResult.getProperty("boolFalse"));
    assertEquals("Hello World", rbcResult.getProperty("stringField"));
    assertNotNull(rbcResult.getProperty("dateField"));
    assertEquals(new Date(1617235200000L), rbcResult.getProperty("dateTimeField"));
    session.rollback();
  }

  @Test
  public void testDecimalNotLastField() {
    // DECIMAL followed by another field — validates correct offset advancement
    session.begin();
    var serialized = serializeEntity(entity -> {
      entity.setProperty("decimal1", new BigDecimal("123.456"));
      entity.setProperty("afterDecimal", "check offset");
    });

    var rbcResult = deserializeViaReadBytesContainer(serialized);
    assertEquals(new BigDecimal("123.456"), rbcResult.getProperty("decimal1"));
    assertEquals("check offset", rbcResult.getProperty("afterDecimal"));
    session.rollback();
  }

  @Test
  public void testBinaryType() {
    session.begin();
    var binData = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    var serialized = serializeEntity(entity -> {
      entity.setProperty("binaryField", binData);
      entity.setProperty("afterBinary", 42);
    });

    var rbcResult = deserializeViaReadBytesContainer(serialized);
    assertArrayEquals(binData, rbcResult.getProperty("binaryField"));
    assertEquals(42, (int) rbcResult.getProperty("afterBinary"));
    session.rollback();
  }

  // --- String edge cases ---

  @Test
  public void testNonAsciiStrings() {
    session.begin();
    var serialized = serializeEntity(entity -> {
      entity.setProperty("cyrillic", "Привет");
      entity.setProperty("emoji", "\uD83D\uDE00\uD83D\uDE01");
      entity.setProperty("chinese", "你好世界");
    });

    var rbcResult = deserializeViaReadBytesContainer(serialized);
    assertEquals("Привет", rbcResult.getProperty("cyrillic"));
    assertEquals("\uD83D\uDE00\uD83D\uDE01", rbcResult.getProperty("emoji"));
    assertEquals("你好世界", rbcResult.getProperty("chinese"));
    session.rollback();
  }

  // --- Collection types ---

  @Test
  public void testEmbeddedList() {
    session.begin();
    var serialized = serializeEntity(entity -> {
      List<String> list = session.newEmbeddedList();
      list.add("a");
      list.add("b");
      list.add("c");
      entity.setProperty("list", list);
    });

    var rbcResult = deserializeViaReadBytesContainer(serialized);
    List<?> list = rbcResult.getProperty("list");
    assertNotNull(list);
    assertEquals(3, list.size());
    assertEquals("a", list.get(0));
    assertEquals("b", list.get(1));
    assertEquals("c", list.get(2));
    session.rollback();
  }

  @Test
  public void testEmbeddedSet() {
    session.begin();
    var serialized = serializeEntity(entity -> {
      Set<Integer> set = session.newEmbeddedSet();
      set.add(1);
      set.add(2);
      set.add(3);
      entity.setProperty("set", set);
    });

    var rbcResult = deserializeViaReadBytesContainer(serialized);
    Set<?> set = rbcResult.getProperty("set");
    assertNotNull(set);
    assertEquals(3, set.size());
    session.rollback();
  }

  @Test
  public void testEmbeddedMap() {
    session.begin();
    var serialized = serializeEntity(entity -> {
      Map<String, Object> map = session.newEmbeddedMap();
      map.put("key1", "val1");
      map.put("key2", 42);
      entity.setProperty("map", map);
    });

    var rbcResult = deserializeViaReadBytesContainer(serialized);
    Map<?, ?> map = rbcResult.getProperty("map");
    assertNotNull(map);
    assertEquals("val1", map.get("key1"));
    assertEquals(42, map.get("key2"));
    session.rollback();
  }

  // --- Null values ---

  @Test
  public void testNullValues() {
    session.begin();
    var serialized = serializeEntity(entity -> {
      entity.setProperty("nonNull", "present");
      entity.setProperty("nullField", (String) null);
    });

    var rbcResult = deserializeViaReadBytesContainer(serialized);
    assertEquals("present", rbcResult.getProperty("nonNull"));
    assertNull(rbcResult.getProperty("nullField"));
    session.rollback();
  }

  // --- Large records ---

  @Test
  public void testLargeRecord() {
    session.begin();
    var serialized = serializeEntity(entity -> {
      for (var i = 0; i < 50; i++) {
        entity.setProperty("field_" + i, "value_" + i);
      }
    });

    var rbcResult = deserializeViaReadBytesContainer(serialized);
    for (var i = 0; i < 50; i++) {
      assertEquals("value_" + i, rbcResult.getProperty("field_" + i));
    }
    session.rollback();
  }

  // --- Partial deserialization ---

  @Test
  public void testPartialDeserializeSingleField() {
    session.begin();
    var serialized = serializeEntity(entity -> {
      entity.setProperty("a", 1);
      entity.setProperty("b", 2);
      entity.setProperty("c", 3);
    });

    var rbcResult = deserializePartialViaReadBytesContainer(serialized, "b");
    assertEquals(2, (int) rbcResult.getProperty("b"));
    // Unrequested fields must not be populated
    assertFalse(rbcResult.hasProperty("a"));
    assertFalse(rbcResult.hasProperty("c"));
    session.rollback();
  }

  @Test
  public void testPartialDeserializeMultipleFields() {
    session.begin();
    var serialized = serializeEntity(entity -> {
      entity.setProperty("a", "alpha");
      entity.setProperty("b", "beta");
      entity.setProperty("c", "gamma");
    });

    var rbcResult = deserializePartialViaReadBytesContainer(serialized, "a", "c");
    assertEquals("alpha", rbcResult.getProperty("a"));
    assertEquals("gamma", rbcResult.getProperty("c"));
    // Unrequested field must not be populated
    assertFalse(rbcResult.hasProperty("b"));
    session.rollback();
  }

  // --- Direct ByteBuffer ---

  @Test
  public void testDirectByteBufferBacking() {
    session.begin();
    var serialized = serializeEntity(entity -> {
      entity.setProperty("field1", "direct buffer test");
      entity.setProperty("field2", 42);
    });

    // Deserialize from a direct ByteBuffer (simulating PageFrame zero-copy)
    var directBuf = ByteBuffer.allocateDirect(serialized.length - 1);
    directBuf.put(serialized, 1, serialized.length - 1);
    directBuf.flip();

    var container = new ReadBytesContainer(directBuf);
    var entity = (EntityImpl) session.newEntity();
    var serializer = RecordSerializerBinary.INSTANCE.getSerializer(serialized[0]);
    serializer.deserialize(session, entity, container);

    assertEquals("direct buffer test", entity.getProperty("field1"));
    assertEquals(42, (int) entity.getProperty("field2"));
    session.rollback();
  }

  // --- Embedded entity ---

  @Test
  public void testEmbeddedEntity() {
    session.begin();
    var serialized = serializeEntity(entity -> {
      var embedded = session.newEmbeddedEntity();
      ((EntityImpl) embedded).setProperty("innerField", "innerValue");
      ((EntityImpl) embedded).setProperty("innerInt", 99);
      entity.setProperty("embedded", embedded);
      entity.setProperty("outerField", "outerValue");
    });

    var rbcResult = deserializeViaReadBytesContainer(serialized);
    assertNotNull(rbcResult.getProperty("embedded"));
    assertEquals("outerValue", rbcResult.getProperty("outerField"));

    var embeddedRbc = (EntityImpl) rbcResult.getProperty("embedded");
    assertEquals("innerValue", embeddedRbc.getProperty("innerField"));
    assertEquals(99, (int) embeddedRbc.getProperty("innerInt"));
    session.rollback();
  }

  // --- Helper methods ---

  @FunctionalInterface
  private interface EntityConfigurer {
    void configure(EntityImpl entity);
  }

  private byte[] serializeEntity(EntityConfigurer configurer) {
    var entity = (EntityImpl) session.newEntity();
    configurer.configure(entity);
    return RecordSerializerBinary.INSTANCE.toStream(session, entity);
  }

  private EntityImpl deserializeViaReadBytesContainer(byte[] serialized) {
    var container = new ReadBytesContainer(serialized, 1);
    var entity = (EntityImpl) session.newEntity();
    var serializer = RecordSerializerBinary.INSTANCE.getSerializer(serialized[0]);
    serializer.deserialize(session, entity, container);
    return entity;
  }

  private EntityImpl deserializePartialViaReadBytesContainer(
      byte[] serialized, String... fields) {
    var container = new ReadBytesContainer(serialized, 1);
    var entity = (EntityImpl) session.newEntity();
    var serializer = RecordSerializerBinary.INSTANCE.getSerializer(serialized[0]);
    serializer.deserializePartial(session, entity, container, fields);
    return entity;
  }
}
