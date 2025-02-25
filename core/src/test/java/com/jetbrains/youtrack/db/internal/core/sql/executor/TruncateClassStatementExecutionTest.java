package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
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

    session.command("truncate class test_class");

    session.begin();
    ((EntityImpl) session.newEntity(testClass)).field("name", "x")
        .newEmbeddedList("data", Arrays.asList(1, 2));
    ((EntityImpl) session.newEntity(testClass)).field("name", "y")
        .newEmbeddedList("data", Arrays.asList(3, 0));
    session.commit();

    session.command("truncate class test_class").close();

    session.begin();
    ((EntityImpl) session.newEntity(testClass)).field("name", "x")
        .field("data", Arrays.asList(5, 6, 7));
    ((EntityImpl) session.newEntity(testClass)).field("name", "y")
        .field("data", Arrays.asList(8, 9, -1));
    session.commit();

    var result = session.query("select from test_class");
    //    Assert.assertEquals(result.size(), 2);

    Set<Integer> set = new HashSet<Integer>();
    while (result.hasNext()) {
      set.addAll(result.next().getProperty("data"));
    }
    result.close();
    Assert.assertTrue(set.containsAll(Arrays.asList(5, 6, 7, 8, 9, -1)));

    Assert.assertEquals(index.getInternal().size(session), 6);

    try (var stream = index.getInternal().stream(session)) {
      stream.forEach(
          (entry) -> {
            Assert.assertTrue(set.contains((Integer) entry.first));
          });
    }

    schema.dropClass("test_class");
  }

  @Test
  public void testTruncateVertexClass() {
    session.command("create class TestTruncateVertexClass extends V");

    session.begin();
    session.command("create vertex TestTruncateVertexClass set name = 'foo'");
    session.commit();

    try {
      session.command("truncate class TestTruncateVertexClass");
      Assert.fail();
    } catch (Exception e) {
    }
    var result = session.query("select from TestTruncateVertexClass");
    Assert.assertTrue(result.hasNext());
    result.close();

    session.command("truncate class TestTruncateVertexClass unsafe");
    result = session.query("select from TestTruncateVertexClass");
    Assert.assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testTruncateVertexClassSubclasses() {

    session.command("create class TestTruncateVertexClassSuperclass");
    session.command(
        "create class TestTruncateVertexClassSubclass extends TestTruncateVertexClassSuperclass");

    session.begin();
    session.command("insert into TestTruncateVertexClassSuperclass set name = 'foo'");
    session.command("insert into TestTruncateVertexClassSubclass set name = 'bar'");
    session.commit();

    var result = session.query("select from TestTruncateVertexClassSuperclass");
    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      result.next();
    }
    Assert.assertFalse(result.hasNext());
    result.close();

    session.command("truncate class TestTruncateVertexClassSuperclass ");
    result = session.query("select from TestTruncateVertexClassSubclass");
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();

    session.command("truncate class TestTruncateVertexClassSuperclass polymorphic");
    result = session.query("select from TestTruncateVertexClassSubclass");
    Assert.assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testTruncateVertexClassSubclassesWithIndex() {

    session.command("create class TestTruncateVertexClassSuperclassWithIndex");
    session.command("create property TestTruncateVertexClassSuperclassWithIndex.name STRING");
    session.command(
        "create index TestTruncateVertexClassSuperclassWithIndex_index on"
            + " TestTruncateVertexClassSuperclassWithIndex (name) NOTUNIQUE");

    session.command(
        "create class TestTruncateVertexClassSubclassWithIndex extends"
            + " TestTruncateVertexClassSuperclassWithIndex");

    session.begin();
    session.command("insert into TestTruncateVertexClassSuperclassWithIndex set name = 'foo'");
    session.command("insert into TestTruncateVertexClassSubclassWithIndex set name = 'bar'");
    session.commit();

    if (!session.getStorage().isRemote()) {
      final var indexManager = session.getMetadata().getIndexManagerInternal();
      final var indexOne =
          indexManager.getIndex(session, "TestTruncateVertexClassSuperclassWithIndex_index");
      Assert.assertEquals(2, indexOne.getInternal().size(session));

      session.command("truncate class TestTruncateVertexClassSubclassWithIndex");
      Assert.assertEquals(1, indexOne.getInternal().size(session));

      session.command("truncate class TestTruncateVertexClassSuperclassWithIndex polymorphic");
      Assert.assertEquals(0, indexOne.getInternal().size(session));
    }
  }

  private List<Result> toList(ResultSet input) {
    List<Result> result = new ArrayList<>();
    while (input.hasNext()) {
      result.add(input.next());
    }
    return result;
  }

  private Index getOrCreateIndex(SchemaClass testClass) {
    var index = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "test_class_by_data");
    if (index == null) {
      testClass.createProperty(session, "data", PropertyType.EMBEDDEDLIST, PropertyType.INTEGER);
      testClass.createIndex(session, "test_class_by_data", SchemaClass.INDEX_TYPE.UNIQUE,
          "data");
    }
    return session.getMetadata().getIndexManagerInternal().getIndex(session, "test_class_by_data");
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

    session.command("truncate class test_class");

    session.begin();
    ((EntityImpl) session.newEntity(testClass)).field("name", "x")
        .newEmbeddedList("data", Arrays.asList(1, 2));
    ((EntityImpl) session.newEntity(testClass)).field("name", "y")
        .newEmbeddedList("data", Arrays.asList(3, 0));
    session.commit();

    var result = session.query("select from test_class");
    Assert.assertEquals(toList(result).size(), 2);

    result.close();
    session.command("truncate class test_class");

    result = session.query("select from test_class");
    Assert.assertEquals(toList(result).size(), 0);
    result.close();

    schema.dropClass("test_class");
  }
}
