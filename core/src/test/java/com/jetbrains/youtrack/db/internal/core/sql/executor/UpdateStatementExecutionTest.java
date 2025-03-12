package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import static com.jetbrains.youtrack.db.internal.core.sql.executor.ExecutionPlanPrintUtils.printExecutionPlan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Assert;
import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 *
 */
public class UpdateStatementExecutionTest {

  @Rule
  public TestName name = new TestName();

  private DatabaseSessionInternal session;

  private String className;
  private YouTrackDB youTrackDB;

  @Before
  public void before() {
    youTrackDB =
        CreateDatabaseUtil.createDatabase(
            name.getMethodName(), DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_MEMORY);
    session =
        (DatabaseSessionInternal)
            youTrackDB.open(name.getMethodName(), "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);

    className = name.getMethodName();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    for (var i = 0; i < 10; i++) {
      var entity = session.newEntity(className);
      entity.setProperty("name", "name" + i);
      entity.setProperty("surname", "surname" + i);
      entity.setProperty("number", 4L);

      List<String> tagsList = new ArrayList<>();
      tagsList.add("foo");
      tagsList.add("bar");
      tagsList.add("baz");
      entity.newEmbeddedList("tagsList", tagsList);

      Map<String, String> tagsMap = new HashMap<>();
      tagsMap.put("foo", "foo");
      tagsMap.put("bar", "bar");
      tagsMap.put("baz", "baz");
      entity.newEmbeddedMap("tagsMap", tagsMap);

    }
    session.commit();
  }

  @After
  public void after() {
    session.close();

    youTrackDB.close();
  }

  @Test
  public void testSetString() {
    session.begin();
    var result = session.command("update " + className + " set surname = 'foo'");
    session.commit();

    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals((Object) 10L, item.getProperty("count"));

    Assert.assertFalse(result.hasNext());
    result.close();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("foo", item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testCopyField() {
    session.begin();
    var result = session.command("update " + className + " set surname = name");
    session.commit();

    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals((Object) 10L, item.getProperty("count"));
    Assert.assertFalse(result.hasNext());
    result.close();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals((Object) item.getProperty("name"), item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testSetExpression() {
    session.begin();
    var result = session.command("update " + className + " set surname = 'foo'+name ");
    session.commit();

    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals((Object) 10L, item.getProperty("count"));
    Assert.assertFalse(result.hasNext());
    result.close();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("foo" + item.getProperty("name"), item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testConditionalSet() {
    session.begin();
    var result =
        session.command("update " + className + " set surname = 'foo' where name = 'name3'");
    session.commit();

    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals((Object) 1L, item.getProperty("count"));
    Assert.assertFalse(result.hasNext());
    result.close();

    session.begin();
    result = session.query("select from " + className);
    var found = false;
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      item = result.next();
      Assert.assertNotNull(item);
      if ("name3".equals(item.getProperty("name"))) {
        Assert.assertEquals("foo", item.getProperty("surname"));
        found = true;
      }
    }
    Assert.assertTrue(found);
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testSetOnList() {
    session.begin();
    var result =
        session.command("update " + className + " set tagsList[0] = 'abc' where name = 'name3'");
    session.commit();

    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals((Object) 1L, item.getProperty("count"));
    Assert.assertFalse(result.hasNext());
    result.close();

    session.begin();
    result = session.query("select from " + className);
    var found = false;
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      item = result.next();
      Assert.assertNotNull(item);
      if ("name3".equals(item.getProperty("name"))) {
        List<String> tags = new ArrayList<>();
        tags.add("abc");
        tags.add("bar");
        tags.add("baz");
        Assert.assertEquals(tags, item.getProperty("tagsList"));
        found = true;
      }
    }
    Assert.assertTrue(found);
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testSetOnList2() {
    session.begin();
    var result =
        session.command("update " + className + " set tagsList[6] = 'abc' where name = 'name3'");
    session.commit();

    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals((Object) 1L, item.getProperty("count"));
    Assert.assertFalse(result.hasNext());
    result.close();

    session.begin();
    result = session.query("select from " + className);
    var found = false;
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      item = result.next();
      Assert.assertNotNull(item);
      if ("name3".equals(item.getProperty("name"))) {
        List<String> tags = new ArrayList<>();
        tags.add("foo");
        tags.add("bar");
        tags.add("baz");
        tags.add(null);
        tags.add(null);
        tags.add(null);
        tags.add("abc");
        Assert.assertEquals(tags, item.getProperty("tagsList"));
        found = true;
      }
    }
    Assert.assertTrue(found);
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testSetOnMap() {
    session.begin();
    var result =
        session.command("update " + className + " set tagsMap['foo'] = 'abc' where name = 'name3'");
    session.commit();

    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals((Object) 1L, item.getProperty("count"));
    Assert.assertFalse(result.hasNext());
    result.close();

    session.begin();
    result = session.query("select from " + className);
    var found = false;
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      item = result.next();
      Assert.assertNotNull(item);
      if ("name3".equals(item.getProperty("name"))) {
        Map<String, String> tags = new HashMap<>();
        tags.put("foo", "abc");
        tags.put("bar", "bar");
        tags.put("baz", "baz");
        Assert.assertEquals(tags, item.getProperty("tagsMap"));
        found = true;
      } else {
        Map<String, String> tags = new HashMap<>();
        tags.put("foo", "foo");
        tags.put("bar", "bar");
        tags.put("baz", "baz");
        Assert.assertEquals(tags, item.getProperty("tagsMap"));
      }
    }
    Assert.assertTrue(found);
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testPlusAssign() {
    session.begin();
    var result =
        session.command(
            "update " + className + " set name += 'foo', newField += 'bar', number += 5");
    session.commit();

    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals((Object) 10L, item.getProperty("count"));
    Assert.assertFalse(result.hasNext());
    result.close();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      item = result.next();
      Assert.assertNotNull(item);
      Assert.assertTrue(
          item.getProperty("name").toString().endsWith("foo")); // test concatenate string to string
      Assert.assertEquals(8, item.getProperty("name").toString().length());
      Assert.assertEquals("bar", item.getProperty("newField")); // test concatenate null to string
      Assert.assertEquals((Object) 9L, item.getProperty("number")); // test sum numbers
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testMinusAssign() {
    session.begin();
    var result = session.command("update " + className + " set number -= 5");
    session.commit();

    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals((Object) 10L, item.getProperty("count"));
    Assert.assertFalse(result.hasNext());
    result.close();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals((Object) (-1L), item.getProperty("number"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testStarAssign() {
    session.begin();
    var result = session.command("update " + className + " set number *= 5");
    session.commit();

    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals((Object) 10L, item.getProperty("count"));
    Assert.assertFalse(result.hasNext());
    result.close();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals((Object) 20L, item.getProperty("number"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testSlashAssign() {
    session.begin();
    var result = session.command("update " + className + " set number /= 2");
    session.commit();

    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals((Object) 10L, item.getProperty("count"));
    Assert.assertFalse(result.hasNext());
    result.close();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals((Object) 2L, item.getProperty("number"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testRemove() {
    session.begin();
    var result = session.query("select from " + className);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("surname"));
    }

    result.close();
    session.begin();
    result = session.command("update " + className + " remove surname");

    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals((Object) 10L, item.getProperty("count"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();

    result = session.query("select from " + className);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNull(item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testContent() {

    session.begin();
    var result =
        session.command("update " + className + " content {'name': 'foo', 'secondName': 'bar'}");
    session.commit();

    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals((Object) 10L, item.getProperty("count"));
    Assert.assertFalse(result.hasNext());
    result.close();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("foo", item.getProperty("name"));
      Assert.assertEquals("bar", item.getProperty("secondName"));
      Assert.assertNull(item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testMerge() {

    session.begin();
    var result =
        session.command("update " + className + " merge {'name': 'foo', 'secondName': 'bar'}");
    session.commit();

    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals((Object) 10L, item.getProperty("count"));
    Assert.assertFalse(result.hasNext());
    result.close();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("foo", item.getProperty("name"));
      Assert.assertEquals("bar", item.getProperty("secondName"));
      Assert.assertTrue(item.getProperty("surname").toString().startsWith("surname"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testUpsert1() {

    session.begin();
    var result =
        session.command("update " + className + " set foo = 'bar' upsert where name = 'name1'");
    session.commit();

    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals((Object) 1L, item.getProperty("count"));
    Assert.assertFalse(result.hasNext());
    result.close();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      item = result.next();
      Assert.assertNotNull(item);
      String name = item.getProperty("name");
      Assert.assertNotNull(name);
      if ("name1".equals(name)) {
        Assert.assertEquals("bar", item.getProperty("foo"));
      } else {
        Assert.assertNull(item.getProperty("foo"));
      }
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testUpsertAndReturn() {

    session.begin();
    var result =
        session.command(
            "update " + className + " set foo = 'bar' upsert  return after  where name = 'name1' ");
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals("bar", item.getProperty("foo"));
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testUpsert2() {
    session.begin();
    var result =
        session.command("update " + className + " set foo = 'bar' upsert where name = 'name11'");
    session.commit();

    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals((Object) 1L, item.getProperty("count"));
    Assert.assertFalse(result.hasNext());
    result.close();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      item = result.next();
      Assert.assertNotNull(item);
      String name = item.getProperty("name");
      Assert.assertNotNull(name);
      if ("name11".equals(name)) {
        Assert.assertEquals("bar", item.getProperty("foo"));
      } else {
        Assert.assertNull(item.getProperty("foo"));
      }
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testRemove1() {
    var className = "overridden" + this.className;

    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("theProperty", PropertyType.EMBEDDEDLIST);

    session.begin();
    var doc = session.newEntity(className);
    var theList = new ArrayList<String>();
    for (var i = 0; i < 10; i++) {
      theList.add("n" + i);
    }
    doc.newEmbeddedList("theProperty", theList);

    session.commit();

    session.begin();
    var result = session.command("update " + className + " remove theProperty[0]");
    session.commit();

    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertFalse(result.hasNext());
    result.close();

    session.begin();
    result = session.query("select from " + className);
    Assert.assertTrue(result.hasNext());
    item = result.next();
    Assert.assertNotNull(item);
    List ls = item.getProperty("theProperty");
    Assert.assertNotNull(ls);
    Assert.assertEquals(9, ls.size());
    Assert.assertFalse(ls.contains("n0"));
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testRemove2() {
    var className = "overridden" + this.className;
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("theProperty", PropertyType.EMBEDDEDLIST);

    session.begin();
    var entity = session.newInstance(className);
    var theList = new ArrayList<String>();
    for (var i = 0; i < 10; i++) {
      theList.add("n" + i);
    }
    entity.newEmbeddedList("theProperty", theList);

    session.commit();

    session.begin();
    var result = session.command("update " + className + " remove theProperty[0, 1, 3]");
    session.commit();

    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertFalse(result.hasNext());
    result.close();

    session.begin();
    result = session.query("select from " + className);
    Assert.assertTrue(result.hasNext());
    item = result.next();
    Assert.assertNotNull(item);
    List ls = item.getProperty("theProperty");

    Assertions.assertThat(ls)
        .isNotNull()
        .hasSize(7)
        .doesNotContain("n0")
        .doesNotContain("n1")
        .contains("n2")
        .doesNotContain("n3")
        .contains("n4");
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testRemove3() {
    var className = "overriden" + this.className;
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("theProperty", PropertyType.EMBEDDED);

    session.begin();
    var doc = session.newInstance(className);
    var emb = ((EntityImpl) session.newEmbeddedEntity());
    emb.setProperty("sub", "foo");
    emb.setProperty("aaa", "bar");
    doc.setProperty("theProperty", emb);

    session.commit();

    session.begin();
    var result = session.command("update " + className + " remove theProperty.sub");
    session.commit();

    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertFalse(result.hasNext());
    result.close();

    session.begin();
    result = session.query("select from " + className);
    Assert.assertTrue(result.hasNext());
    item = result.next();
    Assert.assertNotNull(item);
    Result ls = item.getProperty("theProperty");
    Assert.assertNotNull(ls);
    Assert.assertFalse(ls.getPropertyNames().contains("sub"));
    Assert.assertEquals("bar", ls.getProperty("aaa"));
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testRemoveFromMapSquare() {

    session.begin();
    session.command("UPDATE " + className + " REMOVE tagsMap[\"bar\"]").close();
    session.commit();

    session.begin();
    var result = session.query("SELECT tagsMap FROM " + className);
    printExecutionPlan(result);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals(2, ((Map) item.getProperty("tagsMap")).size());
      Assert.assertFalse(((Map) item.getProperty("tagsMap")).containsKey("bar"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testRemoveFromMapEquals() {

    session.begin();
    session.command("UPDATE " + className + " REMOVE tagsMap = \"bar\"").close();
    session.commit();

    session.begin();
    var result = session.query("SELECT tagsMap FROM " + className);
    printExecutionPlan(result);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals(2, ((Map) item.getProperty("tagsMap")).size());
      Assert.assertFalse(((Map) item.getProperty("tagsMap")).containsKey("bar"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testUpdateWhereSubquery() {

    session.begin();
    var vertex = session.newVertex();
    vertex.setProperty("one", "two");
    var identity = vertex.getIdentity();
    session.commit();

    session.begin();
    try (var result =
        session.command(
            "update v set first='value' where @rid in (select @rid from [" + identity + "]) ")) {

      assertEquals((long) result.next().getProperty("count"), 1L);
    }
    session.commit();

    session.begin();
    try (var result =
        session.command(
            "update v set other='value' where @rid in (select * from [" + identity + "]) ")) {
      assertEquals((long) result.next().getProperty("count"), 1L);
    }
    session.commit();
  }
}
