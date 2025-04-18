package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.common.query.BasicResult;
import com.jetbrains.youtrack.db.api.common.query.BasicResultSet;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class TruncateClassStatementExecutionTest extends BaseMemoryInternalDatabase {

  @SuppressWarnings("unchecked")
  @Test
  public void testTruncateClass() {

    Schema schema = session.getMetadata().getSchema();
    var testClass = getOrCreateClass(schema);

    final var index = getOrCreateIndex(testClass);

    session.execute("truncate class test_class");

    session.begin();
    var e1 = ((EntityImpl) session.newEntity(testClass));
    e1.setProperty("name", "x");
    e1.newEmbeddedList("data", Arrays.asList(1, 2));
    var e2 = ((EntityImpl) session.newEntity(testClass));
    e2.setProperty("name", "y");
    e2.newEmbeddedList("data", Arrays.asList(3, 0));
    session.commit();

    session.execute("truncate class test_class").close();

    session.begin();
    var e3 = ((EntityImpl) session.newEntity(testClass));
    e3.setProperty("name", "x");
    e3.newEmbeddedList("data", Arrays.asList(5, 6, 7));

    var e4 = ((EntityImpl) session.newEntity(testClass));
    e4.setProperty("name", "y");
    e4.newEmbeddedList("data", Arrays.asList(8, 9, -1));
    session.commit();

    session.begin();
    var result = session.query("select from test_class");
    //    Assert.assertEquals(result.size(), 2);

    Set<Integer> set = new HashSet<Integer>();
    while (result.hasNext()) {
      set.addAll(result.next().getProperty("data"));
    }
    result.close();
    Assert.assertTrue(set.containsAll(Arrays.asList(5, 6, 7, 8, 9, -1)));

    Assert.assertEquals(index.size(session), 6);

    try (var stream = index.stream(session)) {
      stream.forEach(
          (entry) -> {
            Assert.assertTrue(set.contains((Integer) entry.first()));
          });
    }
    session.commit();

    schema.dropClass("test_class");
  }

  @Test
  public void testTruncateVertexClass() {
    session.execute("create class TestTruncateVertexClass extends V");

    session.begin();
    session.execute("create vertex TestTruncateVertexClass set name = 'foo'");
    session.commit();

    try {
      session.execute("truncate class TestTruncateVertexClass");
      Assert.fail();
    } catch (Exception e) {
    }

    session.begin();
    var result = session.query("select from TestTruncateVertexClass");
    Assert.assertTrue(result.hasNext());
    result.close();

    session.execute("truncate class TestTruncateVertexClass unsafe");
    result = session.query("select from TestTruncateVertexClass");
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testTruncateVertexClassSubclasses() {

    session.execute("create class TestTruncateVertexClassSuperclass");
    session.execute(
        "create class TestTruncateVertexClassSubclass extends TestTruncateVertexClassSuperclass");

    session.begin();
    session.execute("insert into TestTruncateVertexClassSuperclass set name = 'foo'");
    session.execute("insert into TestTruncateVertexClassSubclass set name = 'bar'");
    session.commit();

    session.begin();
    var result = session.query("select from TestTruncateVertexClassSuperclass");
    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      result.next();
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();

    session.execute("truncate class TestTruncateVertexClassSuperclass ");
    session.begin();
    result = session.query("select from TestTruncateVertexClassSubclass");
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();

    session.execute("truncate class TestTruncateVertexClassSuperclass polymorphic");

    session.begin();
    result = session.query("select from TestTruncateVertexClassSubclass");
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testTruncateVertexClassSubclassesWithIndex() {

    session.execute("create class TestTruncateVertexClassSuperclassWithIndex");
    session.execute("create property TestTruncateVertexClassSuperclassWithIndex.name STRING");
    session.execute(
        "create index TestTruncateVertexClassSuperclassWithIndex_index on"
            + " TestTruncateVertexClassSuperclassWithIndex (name) NOTUNIQUE");

    session.execute(
        "create class TestTruncateVertexClassSubclassWithIndex extends"
            + " TestTruncateVertexClassSuperclassWithIndex");

    session.begin();
    session.execute("insert into TestTruncateVertexClassSuperclassWithIndex set name = 'foo'");
    session.execute("insert into TestTruncateVertexClassSubclassWithIndex set name = 'bar'");
    session.commit();

    if (!session.getStorage().isRemote()) {
      final var indexManager = session.getSharedContext().getIndexManager();
      final var indexOne =
          indexManager.getIndex(session, "TestTruncateVertexClassSuperclassWithIndex_index");
      Assert.assertEquals(2, indexOne.size(session));

      session.execute("truncate class TestTruncateVertexClassSubclassWithIndex");
      Assert.assertEquals(1, indexOne.size(session));

      session.execute("truncate class TestTruncateVertexClassSuperclassWithIndex polymorphic");
      Assert.assertEquals(0, indexOne.size(session));
    }
  }

  private List<BasicResult> toList(BasicResultSet input) {
    List<BasicResult> result = new ArrayList<>();
    while (input.hasNext()) {
      result.add(input.next());
    }
    return result;
  }

  private Index getOrCreateIndex(SchemaClass testClass) {
    var index = session.getSharedContext().getIndexManager()
        .getIndex(session, "test_class_by_data");
    if (index == null) {
      testClass.createProperty("data", PropertyType.EMBEDDEDLIST, PropertyType.INTEGER);
      testClass.createIndex("test_class_by_data", SchemaClass.INDEX_TYPE.UNIQUE,
          "data");
    }
    return session.getSharedContext().getIndexManager().getIndex(session, "test_class_by_data");
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

    session.execute("truncate class test_class");

    session.begin();
    var e1 = ((EntityImpl) session.newEntity(testClass));
    e1.setProperty("name", "x");
    e1.newEmbeddedList("data", Arrays.asList(1, 2));

    var e2 = ((EntityImpl) session.newEntity(testClass));
    e2.setProperty("name", "y");
    e2.newEmbeddedList("data", Arrays.asList(3, 0));
    session.commit();

    session.begin();
    var result = session.query("select from test_class");
    Assert.assertEquals(toList(result).size(), 2);

    result.close();
    session.execute("truncate class test_class");

    result = session.query("select from test_class");
    Assert.assertEquals(toList(result).size(), 0);
    result.close();
    session.commit();

    schema.dropClass("test_class");
  }
}
