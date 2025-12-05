package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionPlanPrintUtils.printExecutionPlan;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.query.BasicResult;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class InsertStatementExecutionTest extends DbTestBase {

  @Test
  public void testInsertSet() {
    var className = "testInsertSet";
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var result = session.execute("insert into " + className + " set name = 'name1'");

    printExecutionPlan(result);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("name1", item.getProperty("name"));
    }
    Assert.assertFalse(result.hasNext());
    session.commit();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("name1", item.getProperty("name"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testInsertValue() {
    var className = "testInsertValue";
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var result =
        session.execute(
            "insert into " + className + "  (name, surname) values ('name1', 'surname1')");

    printExecutionPlan(result);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("name1", item.getProperty("name"));
      Assert.assertEquals("surname1", item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    session.commit();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("name1", item.getProperty("name"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testInsertValue2() {
    var className = "testInsertValue2";
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var result =
        session.execute(
            "insert into "
                + className
                + "  (name, surname) values ('name1', 'surname1'), ('name2', 'surname2')");

    printExecutionPlan(result);

    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("name" + (i + 1), item.getProperty("name"));
      Assert.assertEquals("surname" + (i + 1), item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    session.commit();

    Set<String> names = new HashSet<>();
    names.add("name1");
    names.add("name2");
    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("name"));
      names.remove(item.getProperty("name"));
      Assert.assertNotNull(item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    Assert.assertTrue(names.isEmpty());
    result.close();
    session.commit();
  }

  @Test
  public void testInsertFromSelect1() {
    var className1 = "testInsertFromSelect1";
    session.getMetadata().getSchema().createClass(className1);

    var className2 = "testInsertFromSelect1_1";
    session.getMetadata().getSchema().createClass(className2);
    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className1);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result = session.execute(
        "insert into " + className2 + " from select from " + className1);

    printExecutionPlan(result);

    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("name"));
      Assert.assertNotNull(item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    session.commit();

    Set<String> names = new HashSet<>();
    for (var i = 0; i < 10; i++) {
      names.add("name" + i);
    }
    session.begin();
    result = session.query("select from " + className2);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("name"));
      names.remove(item.getProperty("name"));
      Assert.assertNotNull(item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    Assert.assertTrue(names.isEmpty());
    result.close();
    session.commit();
  }

  @Test
  public void testInsertFromSelect2() {
    var className1 = "testInsertFromSelect2";
    session.getMetadata().getSchema().createClass(className1);

    var className2 = "testInsertFromSelect2_1";
    session.getMetadata().getSchema().createClass(className2);
    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className1);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.execute("insert into " + className2 + " ( select from " + className1 + ")");

    printExecutionPlan(result);

    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("name"));
      Assert.assertNotNull(item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    session.commit();

    Set<String> names = new HashSet<>();
    for (var i = 0; i < 10; i++) {
      names.add("name" + i);
    }
    session.begin();
    result = session.query("select from " + className2);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("name"));
      names.remove(item.getProperty("name"));
      Assert.assertNotNull(item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    Assert.assertTrue(names.isEmpty());
    result.close();
    session.commit();
  }

  @Test
  public void testContent() {
    var className = "testContent";
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var result =
        session.execute(
            "insert into " + className + " content {'name':'name1', 'surname':'surname1'}");

    printExecutionPlan(result);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("name1", item.getProperty("name"));
    }
    Assert.assertFalse(result.hasNext());
    session.commit();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("name1", item.getProperty("name"));
      Assert.assertEquals("surname1", item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testContentMultiple() {
    var className = "testContent";
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var result =
        session.execute(
            "insert into "
                + className
                + " content {'name':'name1', 'surname':'surname1'},{'name':'name1',"
                + " 'surname':'surname1'}");

    printExecutionPlan(result);
    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("name1", item.getProperty("name"));
    }
    Assert.assertFalse(result.hasNext());
    session.commit();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("name1", item.getProperty("name"));
      Assert.assertEquals("surname1", item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testContentWithParam() {
    var className = "testContentWithParam";
    session.getMetadata().getSchema().createClass(className);

    Map<String, Object> theContent = new HashMap<>();
    theContent.put("name", "name1");
    theContent.put("surname", "surname1");
    Map<String, Object> params = new HashMap<>();
    params.put("theContent", theContent);

    session.begin();
    var result = session.execute("insert into " + className + " content :theContent", params);

    printExecutionPlan(result);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("name1", item.getProperty("name"));
    }
    Assert.assertFalse(result.hasNext());
    session.commit();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("name1", item.getProperty("name"));
      Assert.assertEquals("surname1", item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testLinkConversion() {
    var className1 = "testLinkConversion1";
    var className2 = "testLinkConversion2";

    session.execute("CREATE CLASS " + className1).close();

    session.begin();
    session.execute("INSERT INTO " + className1 + " SET name='Active';").close();
    session.execute("INSERT INTO " + className1 + " SET name='Inactive';").close();
    session.commit();

    session.execute("CREATE CLASS " + className2 + ";").close();
    session.execute("CREATE PROPERTY " + className2 + ".processingType LINK " + className1 + ";")
        .close();

    session.begin();
    session.execute(
            "INSERT INTO "
                + className2
                + " SET name='Active', processingType = (SELECT FROM "
                + className1
                + " WHERE name = 'Active') ;")
        .close();
    session.execute(
            "INSERT INTO "
                + className2
                + " SET name='Inactive', processingType = (SELECT FROM "
                + className1
                + " WHERE name = 'Inactive') ;")
        .close();
    session.commit();

    session.begin();
    var result = session.query("SELECT FROM " + className2);
    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      var row = result.next();
      var val = row.getProperty("processingType");
      Assert.assertNotNull(val);
      Assert.assertTrue(val instanceof Identifiable);
    }
    result.close();
    session.commit();
  }

  @Test
  public void testEmbeddedlistConversion() {
    var className1 = "testEmbeddedlistConversion1";
    var className2 = "testEmbeddedlistConversion2";

    session.execute("CREATE CLASS " + className1 + " abstract").close();

    session.execute("CREATE CLASS " + className2 + ";").close();
    session.execute("CREATE PROPERTY " + className2 + ".sub EMBEDDEDLIST " + className1 + ";")
        .close();

    session.begin();
    session.execute("INSERT INTO " + className2 + " SET name='Active', sub = [{'name':'foo'}];")
        .close();
    session.commit();

    session.begin();
    var result = session.query("SELECT FROM " + className2);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var row = result.next();
      var list = row.getProperty("sub");
      Assert.assertNotNull(list);
      Assert.assertTrue(list instanceof List);
      Assert.assertEquals(1, ((List) list).size());

      var o = ((List) list).get(0);
      Assert.assertTrue(o instanceof Result);
      Assert.assertEquals("foo", ((BasicResult) o).getProperty("name"));
      Assert.assertEquals(className1,
          ((Result) o).asEntity().getSchemaClassName());
    }
    result.close();
    session.commit();
  }

  @Test
  public void testEmbeddedlistConversion2() {
    var className1 = "testEmbeddedlistConversion21";
    var className2 = "testEmbeddedlistConversion22";

    session.execute("CREATE CLASS " + className1 + " abstract").close();

    session.execute("CREATE CLASS " + className2 + ";").close();
    session.execute("CREATE PROPERTY " + className2 + ".sub EMBEDDEDLIST " + className1 + ";")
        .close();

    session.begin();
    session.execute(
            "INSERT INTO " + className2 + " (name, sub) values ('Active', [{'name':'foo'}]);")
        .close();
    session.commit();

    session.begin();
    var result = session.query("SELECT FROM " + className2);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var row = result.next();
      var list = row.getProperty("sub");
      Assert.assertNotNull(list);
      Assert.assertTrue(list instanceof List);
      Assert.assertEquals(1, ((List) list).size());

      var o = ((List) list).get(0);
      Assert.assertTrue(o instanceof Result);
      Assert.assertEquals("foo", ((BasicResult) o).getProperty("name"));
      Assert.assertEquals(className1,
          ((Result) o).asEntity().getSchemaClassName());
    }
    result.close();
    session.commit();
  }

  @Test
  public void testInsertReturn() {
    var className = "testInsertReturn";
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var result =
        session.execute("insert into " + className + " set name = 'name1' RETURN 'OK' as result");

    printExecutionPlan(result);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("OK", item.getProperty("result"));
    }
    Assert.assertFalse(result.hasNext());
    session.commit();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("name1", item.getProperty("name"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testNestedInsert() {
    var className = "testNestedInsert";
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var result =
        session.execute(
            "insert into "
                + className
                + " set name = 'parent', children = (INSERT INTO "
                + className
                + " SET name = 'child')");
    session.commit();

    result.close();

    session.begin();
    result = session.query("SELECT FROM " + className);

    for (var i = 0; i < 2; i++) {
      var item = result.next();
      if (item.getProperty("name").equals("parent")) {
        Assert.assertTrue(item.getProperty("children") instanceof Collection);
        Assert.assertEquals(1, ((Collection) item.getProperty("children")).size());
      }
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testLinkMapWithSubqueries() {
    var className = "testLinkMapWithSubqueries";
    var itemclassName = "testLinkMapWithSubqueriesTheItem";

    session.execute("CREATE CLASS " + className);
    session.execute("CREATE CLASS " + itemclassName);
    session.execute("CREATE PROPERTY " + className + ".mymap LINKMAP " + itemclassName);

    session.begin();
    session.execute("INSERT INTO " + itemclassName + " (name) VALUES ('test')");
    session.execute(
        "INSERT INTO "
            + className
            + " (mymap) VALUES ({'A-1': (SELECT FROM "
            + itemclassName
            + " WHERE name = 'test')})");
    session.commit();

    session.begin();
    var result = session.query("SELECT FROM " + className);

    var item = result.next();
    Map theMap = item.getProperty("mymap");
    Assert.assertEquals(1, theMap.size());
    Assert.assertNotNull(theMap.get("A-1"));

    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testQuotedCharactersInJson() {
    var className = "testQuotedCharactersInJson";

    session.execute("CREATE CLASS " + className);

    session.begin();
    session.execute(
        "INSERT INTO "
            + className
            + " CONTENT { name: \"jack\", memo: \"this is a \\n multi line text\" }");
    session.commit();

    session.begin();
    var result = session.query("SELECT FROM " + className);

    var item = result.next();
    String memo = item.getProperty("memo");
    Assert.assertEquals("this is a \n multi line text", memo);

    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }
}
