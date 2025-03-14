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
package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Collectors;
import org.testng.Assert;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class TruncateClassTest extends BaseDBTest {

  @Parameters(value = "remote")
  public TruncateClassTest(boolean remote) {
    super(remote);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testTruncateClass() {
    checkEmbeddedDB();

    Schema schema = session.getMetadata().getSchema();
    var testClass = getOrCreateClass(schema);

    final var index = getOrCreateIndex(testClass);

    session.command("truncate class test_class").close();

    session.begin();
    ((EntityImpl) session.newEntity(testClass)).field("name", "x")
        .field("data", Arrays.asList(1, 2));
    ((EntityImpl) session.newEntity(testClass)).field("name", "y")
        .field("data", Arrays.asList(3, 0));
    session.commit();

    session.command("truncate class test_class").close();

    session.begin();
    ((EntityImpl) session.newEntity(testClass)).field("name", "x")
        .field("data", Arrays.asList(5, 6, 7));
    ((EntityImpl) session.newEntity(testClass)).field("name", "y")
        .field("data", Arrays.asList(8, 9, -1));
    session.commit();

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
        Assert.assertTrue(set.contains((Integer) entry.first));
      }
    }

    schema.dropClass("test_class");
  }

  @Test
  public void testTruncateVertexClass() {
    session.command("create class TestTruncateVertexClass extends V").close();
    session.begin();
    session.command("create vertex TestTruncateVertexClass set name = 'foo'").close();
    session.commit();

    try {
      session.command("truncate class TestTruncateVertexClass ").close();
      Assert.fail();
    } catch (Exception e) {
    }
    var result = session.query("select from TestTruncateVertexClass");
    Assert.assertEquals(result.stream().count(), 1);

    session.command("truncate class TestTruncateVertexClass unsafe").close();
    result = session.query("select from TestTruncateVertexClass");
    Assert.assertEquals(result.stream().count(), 0);
  }

  @Test
  public void testTruncateVertexClassSubclasses() {

    session.command("create class TestTruncateVertexClassSuperclass").close();
    session
        .command(
            "create class TestTruncateVertexClassSubclass extends"
                + " TestTruncateVertexClassSuperclass")
        .close();

    session.begin();
    session.command("insert into TestTruncateVertexClassSuperclass set name = 'foo'").close();
    session.command("insert into TestTruncateVertexClassSubclass set name = 'bar'").close();
    session.commit();

    var result = session.query("select from TestTruncateVertexClassSuperclass");
    Assert.assertEquals(result.stream().count(), 2);

    session.command("truncate class TestTruncateVertexClassSuperclass ").close();
    result = session.query("select from TestTruncateVertexClassSubclass");
    Assert.assertEquals(result.stream().count(), 1);

    session.command("truncate class TestTruncateVertexClassSuperclass polymorphic").close();
    result = session.query("select from TestTruncateVertexClassSubclass");
    Assert.assertEquals(result.stream().count(), 0);
  }

  @Test
  public void testTruncateVertexClassSubclassesWithIndex() {
    checkEmbeddedDB();

    session.command("create class TestTruncateVertexClassSuperclassWithIndex").close();
    session
        .command("create property TestTruncateVertexClassSuperclassWithIndex.name STRING")
        .close();
    session
        .command(
            "create index TestTruncateVertexClassSuperclassWithIndex_index on"
                + " TestTruncateVertexClassSuperclassWithIndex (name) NOTUNIQUE")
        .close();

    session
        .command(
            "create class TestTruncateVertexClassSubclassWithIndex extends"
                + " TestTruncateVertexClassSuperclassWithIndex")
        .close();

    session.begin();
    session
        .command("insert into TestTruncateVertexClassSuperclassWithIndex set name = 'foo'")
        .close();
    session
        .command("insert into TestTruncateVertexClassSubclassWithIndex set name = 'bar'")
        .close();
    session.commit();

    final var index = getIndex("TestTruncateVertexClassSuperclassWithIndex_index");
    Assert.assertEquals(index.size(session), 2);

    session.command("truncate class TestTruncateVertexClassSubclassWithIndex").close();
    Assert.assertEquals(index.size(session), 1);

    session
        .command("truncate class TestTruncateVertexClassSuperclassWithIndex polymorphic")
        .close();
    Assert.assertEquals(index.size(session), 0);
  }

  private Index getOrCreateIndex(SchemaClass testClass) {
    var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, "test_class_by_data");
    if (index == null) {
      testClass.createProperty("data", PropertyType.EMBEDDEDLIST, PropertyType.INTEGER);
      testClass.createIndex("test_class_by_data", SchemaClass.INDEX_TYPE.UNIQUE,
          "data");
    }
    return session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "test_class_by_data");
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

    session.command("truncate class test_class").close();

    session.begin();
    ((EntityImpl) session.newEntity(testClass)).field("name", "x")
        .field("data", Arrays.asList(1, 2));
    ((EntityImpl) session.newEntity(testClass)).field("name", "y")
        .field("data", Arrays.asList(3, 0));
    session.commit();

    var result = session.query("select from test_class");
    Assert.assertEquals(result.stream().count(), 2);

    session.command("truncate class test_class").close();

    result = session.query("select from test_class");
    Assert.assertEquals(result.stream().count(), 0);

    schema.dropClass("test_class");
  }
}
