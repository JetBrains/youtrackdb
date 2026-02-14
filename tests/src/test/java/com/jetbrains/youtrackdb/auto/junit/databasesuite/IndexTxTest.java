/*
 * JUnit 4 version of IndexTxTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxTest.java
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
package com.jetbrains.youtrackdb.auto.junit.databasesuite;

import com.jetbrains.youtrackdb.auto.junit.BaseDBTest;
import com.jetbrains.youtrackdb.auto.junit.BaseTest;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of IndexTxTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class IndexTxTest extends BaseDBTest {

  private static IndexTxTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new IndexTxTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 21) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxTest.java
   */
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    final Schema schema = session.getMetadata().getSchema();
    final var cls = schema.createClass("IndexTxTestClass");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("IndexTxTestIndex", SchemaClass.INDEX_TYPE.UNIQUE, "name");
  }

  /**
   * Original: beforeMethod (line 31) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxTest.java
   */
  @Override
  @Before
  public void beforeMethod() throws Exception {
    super.beforeMethod();

    var schema = session.getMetadata().getSchema();
    var cls = schema.getClassInternal("IndexTxTestClass");
    if (cls != null) {
      cls.truncate();
    }
  }

  /**
   * Original: testIndexCrossReferencedDocuments (line 44) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxTest.java
   */
  @Test
  public void test01_IndexCrossReferencedDocuments() {

    session.begin();

    final var doc1 = ((EntityImpl) session.newEntity("IndexTxTestClass"));
    final var doc2 = ((EntityImpl) session.newEntity("IndexTxTestClass"));

    doc1.setProperty("ref", doc2.getIdentity());
    doc1.setProperty("name", "doc1");
    doc2.setProperty("ref", doc1.getIdentity());
    doc2.setProperty("name", "doc2");

    session.commit();

    Map<String, RID> expectedResult = new HashMap<>();
    expectedResult.put("doc1", doc1.getIdentity());
    expectedResult.put("doc2", doc2.getIdentity());

    var index = getIndex("IndexTxTestIndex");
    Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream()) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (String) keyIterator.next();

        final var expectedValue = expectedResult.get(key);
        final RID value;
        try (var stream = index.getRids(session, key)) {
          value = stream.findAny().orElse(null);
        }

        Assert.assertNotNull(value);
        Assert.assertTrue(value.isPersistent());
        Assert.assertEquals(value, expectedValue);
      }
    }
  }
}
