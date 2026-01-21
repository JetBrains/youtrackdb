/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.auto.junit;

import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of ByteArrayKeyTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/ByteArrayKeyTest.java
 * <p>
 * Tests byte array keys in indexes.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ByteArrayKeyTest extends BaseDBTest {

  /**
   * Original: beforeClass (line 17) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/ByteArrayKeyTest.java
   */
  @BeforeClass
  public static void setUpClass() throws Exception {
    ByteArrayKeyTest instance = new ByteArrayKeyTest();
    instance.beforeClass();
    instance.createByteArrayTestSchema();
  }

  /**
   * Creates test schema for ByteArrayKeyTest. Called from setUpClass on an instance to have access
   * to session.
   */
  private void createByteArrayTestSchema() {
    if (!session.getMetadata().getSchema().existsClass("ByteArrayKeyTest")) {
      final var byteArrayKeyTest =
          session.getMetadata().getSchema().createClass("ByteArrayKeyTest");
      byteArrayKeyTest.createProperty("byteArrayKey", PropertyType.BINARY);
      byteArrayKeyTest.createIndex("byteArrayKeyIndex", SchemaClass.INDEX_TYPE.UNIQUE,
          "byteArrayKey");
    }

    if (!session.getMetadata().getSchema().existsClass("CompositeByteArrayKeyTest")) {
      final var compositeByteArrayKeyTest =
          session.getMetadata().getSchema().createClass("CompositeByteArrayKeyTest");
      compositeByteArrayKeyTest.createProperty("byteArrayKey", PropertyType.BINARY);
      compositeByteArrayKeyTest.createProperty("intKey", PropertyType.INTEGER);
      compositeByteArrayKeyTest.createIndex(
          "compositeByteArrayKey", SchemaClass.INDEX_TYPE.UNIQUE, "byteArrayKey", "intKey");
    }
  }

  /**
   * Original: testAutomaticUsage (line 37) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/ByteArrayKeyTest.java
   */
  @Test
  public void test01_AutomaticUsage() {
    var key1 =
        new byte[]{
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 0, 1
        };

    session.begin();
    var doc1 = ((EntityImpl) session.newEntity("ByteArrayKeyTest"));
    doc1.setProperty("byteArrayKey", key1);

    var key2 =
        new byte[]{
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 0, 2
        };
    var doc2 = ((EntityImpl) session.newEntity("ByteArrayKeyTest"));
    doc2.setProperty("byteArrayKey", key2);

    session.commit();

    session.begin();
    var index =
        session.getSharedContext().getIndexManager().getIndex("byteArrayKeyIndex");
    final var tx = session.getActiveTransaction();
    try (var stream = index.getRids(session, key1)) {
      Assert.assertEquals(
          stream.findAny().map(rid -> {
            var transaction = session.getActiveTransaction();
            return transaction.load(rid);
          }).orElse(null),
          tx.load(doc1)
      );
    }
    try (var stream = index.getRids(session, key2)) {
      Assert.assertEquals(
          stream.findAny().map(rid -> {
            var transaction = session.getActiveTransaction();
            return transaction.load(rid);
          }).orElse(null),
          tx.load(doc2)
      );
    }
    session.commit();
  }

  /**
   * Original: testAutomaticCompositeUsage (line 86) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/ByteArrayKeyTest.java
   */
  @Test
  public void test02_AutomaticCompositeUsage() {
    var key1 = new byte[]{1, 2, 3};
    var key2 = new byte[]{4, 5, 6};

    session.begin();
    var doc1 = ((EntityImpl) session.newEntity("CompositeByteArrayKeyTest"));
    doc1.setProperty("byteArrayKey", key1);
    doc1.setProperty("intKey", 1);

    var doc2 = ((EntityImpl) session.newEntity("CompositeByteArrayKeyTest"));
    doc2.setProperty("byteArrayKey", key2);
    doc2.setProperty("intKey", 2);

    session.commit();

    session.begin();
    var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("compositeByteArrayKey");
    final var tx = session.getActiveTransaction();
    try (var stream = index.getRids(session, new CompositeKey(key1, 1))) {
      Assert.assertEquals(
          stream.findAny().map(rid -> {
            var transaction = session.getActiveTransaction();
            return transaction.load(rid);
          }).orElse(null),
          tx.load(doc1)
      );
    }
    try (var stream = index.getRids(session, new CompositeKey(key2, 2))) {
      Assert.assertEquals(
          stream.findAny().map(rid -> {
            var transaction = session.getActiveTransaction();
            return transaction.load(rid);
          }).orElse(null),
          tx.load(doc2)
      );
    }
    session.commit();
  }

  /**
   * Original: testAutomaticCompositeUsageInTX (line 130) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/ByteArrayKeyTest.java
   */
  @Test
  public void test03_AutomaticCompositeUsageInTX() {
    var key1 = new byte[]{7, 8, 9};
    var key2 = new byte[]{10, 11, 12};

    session.begin();
    var doc1 = ((EntityImpl) session.newEntity("CompositeByteArrayKeyTest"));
    doc1.setProperty("byteArrayKey", key1);
    doc1.setProperty("intKey", 1);

    var doc2 = ((EntityImpl) session.newEntity("CompositeByteArrayKeyTest"));
    doc2.setProperty("byteArrayKey", key2);
    doc2.setProperty("intKey", 2);

    session.commit();

    session.begin();
    var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("compositeByteArrayKey");
    final var tx = session.getActiveTransaction();
    try (var stream = index.getRids(session, new CompositeKey(key1, 1))) {
      Assert.assertEquals(
          stream.findAny().map(rid -> {
            var transaction = session.getActiveTransaction();
            return transaction.load(rid);
          }).orElse(null),
          tx.load(doc1)
      );
    }
    try (var stream = index.getRids(session, new CompositeKey(key2, 2))) {
      Assert.assertEquals(
          stream.findAny().map(rid -> {
            var transaction = session.getActiveTransaction();
            return transaction.load(rid);
          }).orElse(null),
          tx.load(doc2)
      );
    }
    session.commit();
  }

  /**
   * Original: testContains (line 174) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/ByteArrayKeyTest.java Depends on:
   * testAutomaticUsage
   */
  @Test
  public void test04_Contains() {
    var key1 =
        new byte[]{
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 0, 1
        };
    var key2 =
        new byte[]{
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 0, 2
        };

    var autoIndex =
        session.getSharedContext().getIndexManager().getIndex("byteArrayKeyIndex");
    try (var stream = autoIndex.getRids(session, key1)) {
      Assert.assertTrue(stream.findFirst().isPresent());
    }
    try (var stream = autoIndex.getRids(session, key2)) {
      Assert.assertTrue(stream.findFirst().isPresent());
    }
  }
}
