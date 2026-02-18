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
package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.testng.Assert;
import org.testng.annotations.Test;

@Test
public class TruncateClassTest extends BaseDBTest {

  @SuppressWarnings("unchecked")
  @Test
  public void testTruncateClass() {

    Schema schema = session.getMetadata().getSchema();
    var testClass = getOrCreateClass(schema);

    final var index = getOrCreateIndex(testClass);

    session.execute("truncate class test_class").close();

    session.begin();
    var e1 = ((EntityImpl) session.newEntity(testClass));
    e1.setProperty("name", "x");
    e1.setPropertyInChain("data", session.newEmbeddedList(List.of(1, 2)));

    ((EntityImpl) session.newEntity(testClass)).setPropertyInChain("name", "y")
        .setPropertyInChain("data", session.newEmbeddedList(List.of(3, 0)));
    session.commit();

    session.execute("truncate class test_class").close();

    session.begin();
    ((EntityImpl) session.newEntity(testClass)).setPropertyInChain("name", "x")
        .setPropertyInChain("data", session.newEmbeddedList(List.of(5, 6, 7)));
    ((EntityImpl) session.newEntity(testClass)).setPropertyInChain("name", "y")
        .setPropertyInChain("data", session.newEmbeddedList(List.of(8, 9, -1)));
    session.commit();

    session.begin();
    var result =
        session.query("select from test_class").stream().collect(Collectors.toList());
    Assert.assertEquals(result.size(), 2);
    Set<Integer> set = new HashSet<Integer>();
    for (var document : result) {
      set.addAll(document.getProperty("data"));
    }
    Assert.assertTrue(set.containsAll(Arrays.asList(5, 6, 7, 8, 9, -1)));

    Assert.assertEquals(index.size(session), 6);

    Iterator<RawPair<Object, RID>> indexIterator;
    try (var stream = index.stream(session)) {
      indexIterator = stream.iterator();

      while (indexIterator.hasNext()) {
        var entry = indexIterator.next();
        Assert.assertTrue(set.contains((Integer) entry.first()));
      }
    }
    session.commit();

    schema.dropClass("test_class");
  }

  @Test
  public void testTruncateVertexClass() {
    session.execute("create class TestTruncateVertexClass extends V").close();
    session.begin();
    session.execute("create vertex TestTruncateVertexClass set name = 'foo'").close();
    session.commit();

    try {
      session.execute("truncate class TestTruncateVertexClass ").close();
      Assert.fail();
    } catch (Exception e) {
    }

    session.begin();
    var result = session.query("select from TestTruncateVertexClass");
    Assert.assertEquals(result.stream().count(), 1);
    session.commit();

    session.execute("truncate class TestTruncateVertexClass unsafe").close();
    result = session.query("select from TestTruncateVertexClass");
    Assert.assertEquals(result.stream().count(), 0);
  }

  @Test
  public void testTruncateVertexClassSubclasses() {

    session.execute("create class TestTruncateVertexClassSuperclass").close();
    session
        .execute(
            "create class TestTruncateVertexClassSubclass extends"
                + " TestTruncateVertexClassSuperclass")
        .close();

    session.begin();
    session.execute("insert into TestTruncateVertexClassSuperclass set name = 'foo'").close();
    session.execute("insert into TestTruncateVertexClassSubclass set name = 'bar'").close();
    session.commit();

    session.begin();
    var result = session.query("select from TestTruncateVertexClassSuperclass");
    Assert.assertEquals(result.stream().count(), 2);
    session.commit();

    session.execute("truncate class TestTruncateVertexClassSuperclass ").close();

    session.begin();
    result = session.query("select from TestTruncateVertexClassSubclass");
    Assert.assertEquals(result.stream().count(), 1);
    session.commit();

    session.execute("truncate class TestTruncateVertexClassSuperclass polymorphic").close();
    session.begin();
    result = session.query("select from TestTruncateVertexClassSubclass");
    Assert.assertEquals(result.stream().count(), 0);
    session.commit();
  }

  @Test
  public void testTruncateVertexClassSubclassesWithIndex() {

    session.execute("create class TestTruncateVertexClassSuperclassWithIndex").close();
    session
        .execute("create property TestTruncateVertexClassSuperclassWithIndex.name STRING")
        .close();
    session
        .execute(
            "create index TestTruncateVertexClassSuperclassWithIndex_index on"
                + " TestTruncateVertexClassSuperclassWithIndex (name) NOTUNIQUE")
        .close();

    session
        .execute(
            "create class TestTruncateVertexClassSubclassWithIndex extends"
                + " TestTruncateVertexClassSuperclassWithIndex")
        .close();

    session.begin();
    session
        .execute("insert into TestTruncateVertexClassSuperclassWithIndex set name = 'foo'")
        .close();
    session
        .execute("insert into TestTruncateVertexClassSubclassWithIndex set name = 'bar'")
        .close();
    session.commit();

    final var index = getIndex("TestTruncateVertexClassSuperclassWithIndex_index");
    session.begin();
    Assert.assertEquals(index.size(session), 2);
    session.rollback();

    session.execute("truncate class TestTruncateVertexClassSubclassWithIndex").close();
    session.begin();
    Assert.assertEquals(index.size(session), 1);
    session.rollback();

    session
        .execute("truncate class TestTruncateVertexClassSuperclassWithIndex polymorphic")
        .close();
    session.begin();
    Assert.assertEquals(index.size(session), 0);
    session.rollback();
  }

  private Index getOrCreateIndex(SchemaClass testClass) {
    var index =
        session.getSharedContext().getIndexManager().getIndex("test_class_by_data");
    if (index == null) {
      testClass.createProperty("data", PropertyType.EMBEDDEDLIST, PropertyType.INTEGER);
      testClass.createIndex("test_class_by_data", SchemaClass.INDEX_TYPE.UNIQUE,
          "data");
    }
    return session.getSharedContext().getIndexManager()
        .getIndex("test_class_by_data");
  }

  private SchemaClass getOrCreateClass(Schema schema) {
    SchemaClass testClass;
    if (schema.existsClass("test_class")) {
      testClass = schema.getClass("test_class");
    } else {
      testClass = schema.createClass("test_class");
    }
    return testClass;
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testTruncateClassWithCommandCache() {

    Schema schema = session.getMetadata().getSchema();
    var testClass = getOrCreateClass(schema);

    session.execute("truncate class test_class").close();

    session.begin();
    ((EntityImpl) session.newEntity(testClass)).setPropertyInChain("name", "x")
        .setPropertyInChain("data", session.newEmbeddedList(List.of(1, 2)));
    ((EntityImpl) session.newEntity(testClass)).setPropertyInChain("name", "y")
        .setPropertyInChain("data", session.newEmbeddedList(List.of(3, 0)));
    session.commit();

    session.begin();
    var result = session.query("select from test_class");
    Assert.assertEquals(result.stream().count(), 2);
    session.commit();

    session.execute("truncate class test_class").close();

    session.begin();
    result = session.query("select from test_class");
    Assert.assertEquals(result.stream().count(), 0);
    session.commit();

    schema.dropClass("test_class");
  }
}
