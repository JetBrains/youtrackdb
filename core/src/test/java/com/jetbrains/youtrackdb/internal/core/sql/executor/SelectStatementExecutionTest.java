package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionPlanPrintUtils.printExecutionPlan;
import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.common.query.BasicResult;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.DBRecord;
import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.api.record.Edge;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.record.StatefulEdge;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.api.schema.SchemaClass;
import com.jetbrains.youtrackdb.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunction;
import com.jetbrains.youtrackdb.internal.core.sql.parser.LocalResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLContainsCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGetInternalPropertyExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;


public class SelectStatementExecutionTest extends DbTestBase {

  @Test
  public void testSelectNoTarget() {
    session.begin();
    var result = session.query("select 1 as one, 2 as two, 2+3");
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals(1, item.<Object>getProperty("one"));
    Assert.assertEquals(2, item.<Object>getProperty("two"));
    Assert.assertEquals(5, item.<Object>getProperty("2 + 3"));
    printExecutionPlan(result);

    result.close();
    session.commit();
  }

  @Test
  public void testGroupByCount() {
    session.getMetadata().getSchema().createClass("InputTx");

    for (var i = 0; i < 100; i++) {
      session.begin();
      final var hash = UUID.randomUUID().toString();
      session.execute("insert into InputTx set address = '" + hash + "'");

      // CREATE RANDOM NUMBER OF COPIES final int random = new Random().nextInt(10);
      final var random = new Random().nextInt(10);
      for (var j = 0; j < random; j++) {
        session.execute("insert into InputTx set address = '" + hash + "'");
      }
      session.commit();
    }

    session.begin();
    final var result =
        session.query(
            "select address, count(*) as occurrencies from InputTx where address is not null group"
                + " by address limit 10");
    while (result.hasNext()) {
      final var row = result.next();
      Assert.assertNotNull(row.getProperty("address")); // <== FALSE!
      Assert.assertNotNull(row.getProperty("occurrencies"));
    }
    session.commit();
    result.close();
  }

  @Test
  public void testSelectNoTargetSkip() {
    var result = session.query("select 1 as one, 2 as two, 2+3 skip 1");
    Assert.assertFalse(result.hasNext());
    printExecutionPlan(result);

    result.close();
  }

  @Test
  public void testSelectNoTargetSkipZero() {
    var result = session.query("select 1 as one, 2 as two, 2+3 skip 0");
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals(1, item.<Object>getProperty("one"));
    Assert.assertEquals(2, item.<Object>getProperty("two"));
    Assert.assertEquals(5, item.<Object>getProperty("2 + 3"));
    printExecutionPlan(result);

    result.close();
  }

  @Test
  public void testSelectNoTargetLimit0() {
    var result = session.query("select 1 as one, 2 as two, 2+3 limit 0");
    Assert.assertFalse(result.hasNext());
    printExecutionPlan(result);

    result.close();
  }

  @Test
  public void testSelectNoTargetLimit1() {
    var result = session.query("select 1 as one, 2 as two, 2+3 limit 1");
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals(1, item.<Object>getProperty("one"));
    Assert.assertEquals(2, item.<Object>getProperty("two"));
    Assert.assertEquals(5, item.<Object>getProperty("2 + 3"));
    printExecutionPlan(result);

    result.close();
  }

  @Test
  public void testSelectNoTargetLimitx() {
    var result = session.query("select 1 as one, 2 as two, 2+3 skip 0 limit 0");
    printExecutionPlan(result);
    result.close();
  }

  @Test
  public void testSelectFullScan1() {
    var className = "TestSelectFullScan1";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 100000; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className);
    for (var i = 0; i < 100000; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertTrue(("" + item.getProperty("name")).startsWith("name"));
    }
    Assert.assertFalse(result.hasNext());
    printExecutionPlan(result);
    result.close();
    session.commit();
  }

  @Test
  public void testSelectFullScanOrderByRidAsc() {
    var className = "testSelectFullScanOrderByRidAsc";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 100000; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className + " ORDER BY @rid ASC");
    printExecutionPlan(result);
    Identifiable lastItem = null;
    for (var i = 0; i < 100000; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertTrue(("" + item.getProperty("name")).startsWith("name"));
      if (lastItem != null) {
        Assert.assertTrue(
            lastItem.getIdentity().compareTo(item.asEntity().getIdentity()) < 0);
      }
      lastItem = item.asEntity();
    }
    Assert.assertFalse(result.hasNext());
    session.commit();

    result.close();
  }

  @Test
  public void testSelectFullScanOrderByRidDesc() {
    var className = "testSelectFullScanOrderByRidDesc";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 100000; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className + " ORDER BY @rid DESC");
    printExecutionPlan(result);
    Identifiable lastItem = null;
    for (var i = 0; i < 100000; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertTrue(("" + item.getProperty("name")).startsWith("name"));
      if (lastItem != null) {
        Assert.assertTrue(
            lastItem.getIdentity().compareTo(item.asEntity().getIdentity()) > 0);
      }
      lastItem = item.asEntity();
    }
    Assert.assertFalse(result.hasNext());
    session.commit();

    result.close();
  }

  @Test
  public void testSelectFullScanLimit1() {
    var className = "testSelectFullScanLimit1";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 300; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className + " limit 10");
    printExecutionPlan(result);

    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertTrue(("" + item.getProperty("name")).startsWith("name"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testSelectFullScanSkipLimit1() {
    var className = "testSelectFullScanSkipLimit1";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 300; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className + " skip 100 limit 10");
    printExecutionPlan(result);

    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertTrue(("" + item.getProperty("name")).startsWith("name"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testSelectOrderByDesc() {
    var className = "testSelectOrderByDesc";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 30; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className + " order by surname desc");
    printExecutionPlan(result);

    String lastSurname = null;
    for (var i = 0; i < 30; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      String thisSurname = item.getProperty("surname");
      if (lastSurname != null) {
        Assert.assertTrue(lastSurname.compareTo(thisSurname) >= 0);
      }
      lastSurname = thisSurname;
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testSelectOrderByAsc() {
    var className = "testSelectOrderByAsc";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 30; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className + " order by surname asc");
    printExecutionPlan(result);

    String lastSurname = null;
    for (var i = 0; i < 30; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      String thisSurname = item.getProperty("surname");
      if (lastSurname != null) {
        Assert.assertTrue(lastSurname.compareTo(thisSurname) <= 0);
      }
      lastSurname = thisSurname;
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testSelectOrderByMassiveAsc() {
    var className = "testSelectOrderByMassiveAsc";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 100000; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i % 100);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className + " order by surname asc limit 100");
    printExecutionPlan(result);

    for (var i = 0; i < 100; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("surname0", item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testSelectOrderWithProjections() {
    var className = "testSelectOrderWithProjections";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 100; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i % 10);
      doc.setProperty("surname", "surname" + i % 10);

      session.commit();
    }

    session.begin();
    var result = session.query("select name from " + className + " order by surname asc");
    printExecutionPlan(result);

    String lastName = null;
    for (var i = 0; i < 100; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      String name = item.getProperty("name");
      Assert.assertNotNull(name);
      if (i > 0) {
        Assert.assertTrue(name.compareTo(lastName) >= 0);
      }
      lastName = name;
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testSelectOrderWithProjections2() {
    var className = "testSelectOrderWithProjections2";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 100; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i % 10);
      doc.setProperty("surname", "surname" + i % 10);

      session.commit();
    }

    session.begin();
    var result =
        session.query("select name from " + className + " order by name asc, surname asc");
    printExecutionPlan(result);

    String lastName = null;
    for (var i = 0; i < 100; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      String name = item.getProperty("name");
      Assert.assertNotNull(name);
      if (i > 0) {
        Assert.assertTrue(name.compareTo(lastName) >= 0);
      }
      lastName = name;
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testSelectFullScanWithFilter1() {
    var className = "testSelectFullScanWithFilter1";
    session.getMetadata().getSchema().createClass(className);

    for (var i = 0; i < 300; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.query("select from " + className + " where name = 'name1' or name = 'name7' ");
    printExecutionPlan(result);

    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      var name = item.getProperty("name");
      Assert.assertTrue("name1".equals(name) || "name7".equals(name));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testSelectFullScanWithFilter2() {
    var className = "testSelectFullScanWithFilter2";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 300; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className + " where name <> 'name1' ");
    printExecutionPlan(result);

    for (var i = 0; i < 299; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      var name = item.getProperty("name");
      Assert.assertNotEquals("name1", name);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testProjections() {
    var className = "testProjections";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 300; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result = session.query("select name from " + className);
    printExecutionPlan(result);

    for (var i = 0; i < 300; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      String name = item.getProperty("name");
      String surname = item.getProperty("surname");
      Assert.assertNotNull(name);
      Assert.assertTrue(name.startsWith("name"));
      Assert.assertNull(surname);
      Assert.assertFalse(item.isEntity());
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testCountStar() {
    var className = "testCountStar";
    session.getMetadata().getSchema().createClass(className);

    for (var i = 0; i < 7; i++) {
      session.begin();
      session.newEntity(className);
      session.commit();
    }
    session.begin();
    var result = session.query("select count(*) from " + className);
    printExecutionPlan(result);
    Assert.assertNotNull(result);
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertNotNull(next);
    Assert.assertEquals(7L, (Object) next.getProperty("count(*)"));
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testCountStar2() {
    var className = "testCountStar2";
    session.getMetadata().getSchema().createClass(className);

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = (EntityImpl) session.newEntity(className);
      doc.setProperty("name", "name" + (i % 5));

      session.commit();
    }

    session.begin();
    var result = session.query("select count(*), name from " + className + " group by name");
    printExecutionPlan(result);
    Assert.assertNotNull(result);
    for (var i = 0; i < 5; i++) {
      Assert.assertTrue(result.hasNext());
      var next = result.next();
      Assert.assertNotNull(next);
      Assert.assertEquals(2L, (Object) next.getProperty("count(*)"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testCountStarEmptyNoIndex() {
    var className = "testCountStarEmptyNoIndex";
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var elem = session.newEntity(className);
    elem.setProperty("name", "bar");
    session.commit();

    session.begin();
    var result = session.query("select count(*) from " + className + " where name = 'foo'");
    printExecutionPlan(result);
    Assert.assertNotNull(result);
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertNotNull(next);
    Assert.assertEquals(0L, (Object) next.getProperty("count(*)"));
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testCountStarEmptyNoIndexWithAlias() {
    var className = "testCountStarEmptyNoIndexWithAlias";
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var elem = session.newEntity(className);
    elem.setProperty("name", "bar");
    session.commit();

    session.begin();
    var result =
        session.query("select count(*) as a from " + className + " where name = 'foo'");
    printExecutionPlan(result);
    Assert.assertNotNull(result);
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertNotNull(next);
    Assert.assertEquals(0L, (Object) next.getProperty("a"));
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testAggretateMixedWithNonAggregate() {
    var className = "testAggretateMixedWithNonAggregate";
    session.getMetadata().getSchema().createClass(className);

    try {
      session.executeInTx(transaction -> {
        session.query(
                "select max(a) + max(b) + pippo + pluto as foo, max(d) + max(e), f from " + className)
            .close();
        Assert.fail();
      });
    } catch (CommandExecutionException x) {
      //ignore
    } catch (Exception e) {
      Assert.fail();
    }
  }

  @Test
  public void testAggretateMixedWithNonAggregateInCollection() {
    var className = "testAggretateMixedWithNonAggregateInCollection";
    session.getMetadata().getSchema().createClass(className);

    try {
      session.executeInTx(transaction -> {
        session.query("select [max(a), max(b), foo] from " + className).close();
        Assert.fail();
      });
    } catch (CommandExecutionException x) {
      //ignore
    } catch (Exception e) {
      Assert.fail();
    }
  }

  @Test
  public void testAggretateInCollection() {
    var className = "testAggretateInCollection";
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var query = "select [max(a), max(b)] from " + className;
    var result = session.query(query);
    printExecutionPlan(query, result);
    result.close();
    session.commit();
  }

  @Test
  public void testAggretateMixedWithNonAggregateConstants() {
    var className = "testAggretateMixedWithNonAggregateConstants";
    session.getMetadata().getSchema().createClass(className);

    try {
      var result =
          session.query(
              "select max(a + b) + (max(b + c * 2) + 1 + 2) * 3 as foo, max(d) + max(e), f from "
                  + className);
      printExecutionPlan(result);
      result.close();
    } catch (Exception e) {
      e.printStackTrace();
      Assert.fail();
    }
  }

  @Test
  public void testAggregateSum() {
    var className = "testAggregateSum";
    session.getMetadata().getSchema().createClass(className);

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("val", i);

      session.commit();
    }

    session.begin();
    var result = session.query("select sum(val) from " + className);
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals(45, (Object) item.getProperty("sum(val)"));

    result.close();
    session.commit();
  }

  @Test
  public void testAggregateSumGroupBy() {
    var className = "testAggregateSumGroupBy";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("type", i % 2 == 0 ? "even" : "odd");
      doc.setProperty("val", i);

      session.commit();
    }
    session.begin();
    var result = session.query("select sum(val), type from " + className + " group by type");
    printExecutionPlan(result);
    var evenFound = false;
    var oddFound = false;
    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      if ("even".equals(item.getProperty("type"))) {
        Assert.assertEquals(20, item.<Object>getProperty("sum(val)"));
        evenFound = true;
      } else if ("odd".equals(item.getProperty("type"))) {
        Assert.assertEquals(25, item.<Object>getProperty("sum(val)"));
        oddFound = true;
      }
    }
    Assert.assertFalse(result.hasNext());
    Assert.assertTrue(evenFound);
    Assert.assertTrue(oddFound);
    result.close();
    session.commit();
  }

  @Test
  public void testAggregateSumMaxMinGroupBy() {
    var className = "testAggregateSumMaxMinGroupBy";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("type", i % 2 == 0 ? "even" : "odd");
      doc.setProperty("val", i);

      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select sum(val), max(val), min(val), type from " + className + " group by type");
    printExecutionPlan(result);
    var evenFound = false;
    var oddFound = false;
    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      if ("even".equals(item.getProperty("type"))) {
        Assert.assertEquals(20, item.<Object>getProperty("sum(val)"));
        Assert.assertEquals(8, item.<Object>getProperty("max(val)"));
        Assert.assertEquals(0, item.<Object>getProperty("min(val)"));
        evenFound = true;
      } else if ("odd".equals(item.getProperty("type"))) {
        Assert.assertEquals(25, item.<Object>getProperty("sum(val)"));
        Assert.assertEquals(9, item.<Object>getProperty("max(val)"));
        Assert.assertEquals(1, item.<Object>getProperty("min(val)"));
        oddFound = true;
      }
    }
    Assert.assertFalse(result.hasNext());
    Assert.assertTrue(evenFound);
    Assert.assertTrue(oddFound);
    result.close();
    session.commit();
  }

  @Test
  public void testAggregateSumNoGroupByInProjection() {
    var className = "testAggregateSumNoGroupByInProjection";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("type", i % 2 == 0 ? "even" : "odd");
      doc.setProperty("val", i);

      session.commit();
    }

    session.begin();
    var result = session.query("select sum(val) from " + className + " group by type");
    printExecutionPlan(result);
    var evenFound = false;
    var oddFound = false;
    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      var sum = item.getProperty("sum(val)");
      if (sum.equals(20)) {
        evenFound = true;
      } else if (sum.equals(25)) {
        oddFound = true;
      }
    }
    Assert.assertFalse(result.hasNext());
    Assert.assertTrue(evenFound);
    Assert.assertTrue(oddFound);
    result.close();
    session.commit();
  }

  @Test
  public void testAggregateSumNoGroupByInProjection2() {
    var className = "testAggregateSumNoGroupByInProjection2";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("type", i % 2 == 0 ? "dd1" : "dd2");
      doc.setProperty("val", i);

      session.commit();
    }

    session.begin();
    var result =
        session.query("select sum(val) from " + className + " group by type.substring(0,1)");
    printExecutionPlan(result);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      var sum = item.getProperty("sum(val)");
      Assert.assertEquals(45, sum);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromCollectionNumber() {
    var className = "testFetchFromCollectionNumber";
    Schema schema = session.getMetadata().getSchema();
    schema.createClass(className);

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("val", i);
      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className);
    printExecutionPlan(result);
    var sum = 0;
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Integer val = item.getProperty("val");
      Assert.assertNotNull(val);
      sum += val;
    }

    Assert.assertEquals(45, sum);
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassNumberOrderByRidDesc() {
    var className = "testFetchFromCollectionNumberOrderByRidDesc";
    Schema schema = session.getMetadata().getSchema();
    schema.createClass(className);

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("val", i);
      session.commit();
    }

    session.begin();
    var result =
        session.query("select from " + className + " order by @rid desc");
    printExecutionPlan(result);

    RID lastRid = null;
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      if (lastRid == null) {
        lastRid = item.getIdentity();
      } else {
        Assert.assertTrue(lastRid.compareTo(item.getIdentity()) > 0);
        lastRid = item.getIdentity();
      }
    }

    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassNumberOrderByRidAsc() {
    var className = "testFetchFromCollectionNumberOrderByRidAsc";
    Schema schema = session.getMetadata().getSchema();
    schema.createClass(className);

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("val", i);
      session.commit();
    }

    session.begin();
    var result = session.query(
        "select from " + className + " order by @rid asc");
    printExecutionPlan(result);

    RID lastRid = null;
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      if (lastRid == null) {
        lastRid = item.getIdentity();
      } else {
        Assert.assertTrue(lastRid.compareTo(item.getIdentity()) < 0);
        lastRid = item.getIdentity();
      }
    }

    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testQueryAsTarget() {
    var className = "testQueryAsTarget";
    Schema schema = session.getMetadata().getSchema();
    schema.createClass(className);

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("val", i);

      session.commit();
    }

    session.begin();
    var result =
        session.query("select from (select from " + className + " where val > 2)  where val < 8");
    printExecutionPlan(result);

    for (var i = 0; i < 5; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Integer val = item.getProperty("val");
      Assert.assertTrue(val > 2);
      Assert.assertTrue(val < 8);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testQuerySchema() {
    session.begin();
    var result = session.query("select from metadata:schema");
    printExecutionPlan(result);

    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item.getProperty("classesRefs"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testQueryMetadataIndexManager() {
    session.begin();
    var result = session.query("select from metadata:indexes");
    printExecutionPlan(result);
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testQueryMetadataDatabase() {
    session.begin();
    var result = session.query("select from metadata:database");
    printExecutionPlan(result);

    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertEquals("testQueryMetadataDatabase", item.getProperty("name"));
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testQueryMetadataStorage() {
    session.begin();
    var result = session.query("select from metadata:storage");
    printExecutionPlan(result);

    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertEquals("testQueryMetadataStorage", item.getProperty("name"));
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testNonExistingRids() {
    session.begin();
    var result = session.query("select from #0:100000000");
    printExecutionPlan(result);
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromSingleRid() {
    session.begin();
    var result = session.query("select from #0:1");
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    Assert.assertNotNull(result.next());
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromSingleRid2() {
    session.begin();
    var result = session.query("select from [#0:1]");
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    Assert.assertNotNull(result.next());
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromSingleRidParam() {
    session.begin();
    var result = session.query("select from ?", new RecordId(0, 1));
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    Assert.assertNotNull(result.next());
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromSingleRid3() {
    session.begin();
    var document = (EntityImpl) session.newEntity();

    session.commit();

    session.begin();
    var result = session.query("select from [#0:1, #0:2000]");
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    Assert.assertNotNull(result.next());

    Assert.assertFalse(result.hasNext());
    try {
      Assert.assertNotNull(result.next());
      Assert.fail();
    } catch (NoSuchElementException e) {
      //expected
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromSingleRid4() {
    session.begin();
    session.newEntity();
    session.commit();

    session.begin();
    var result = session.query("select from [#0:1, #0:20000, #0:100000]");
    printExecutionPlan(result);

    Assert.assertTrue(result.hasNext());
    Assert.assertNotNull(result.next());
    Assert.assertFalse(result.hasNext());
    try {
      Assert.assertNotNull(result.next());
      Assert.fail();
    } catch (NoSuchElementException e) {
      //expected
    }

    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassWithIndex() {
    var className = "testFetchFromClassWithIndex";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createIndex(className + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className + " where name = 'name2'");
    printExecutionPlan(result);

    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertNotNull(next);
    Assert.assertEquals("name2", next.getProperty("name"));

    Assert.assertFalse(result.hasNext());

    var p = result.getExecutionPlan();
    Assert.assertNotNull(p);
    Assert.assertTrue(p instanceof SelectExecutionPlan);
    var plan = (SelectExecutionPlan) p;
    Assert.assertEquals(FetchFromIndexStep.class, plan.getSteps().getFirst().getClass());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromIndexHierarchy() {
    var className = "testFetchFromIndexHierarchy";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createIndex(className + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    var classNameExt = "testFetchFromIndexHierarchyExt";
    var clazzExt = session.getMetadata().getSchema().createClass(classNameExt, clazz);
    clazzExt.createIndex(classNameExt + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    for (var i = 0; i < 5; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);

      session.commit();
    }

    for (var i = 5; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(classNameExt);
      doc.setProperty("name", "name" + i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + classNameExt + " where name = 'name6'");
    printExecutionPlan(result);

    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertNotNull(next);

    Assert.assertFalse(result.hasNext());

    var p = result.getExecutionPlan();
    Assert.assertNotNull(p);
    Assert.assertTrue(p instanceof SelectExecutionPlan);
    var plan = (SelectExecutionPlan) p;
    Assert.assertEquals(FetchFromIndexStep.class, plan.getSteps().getFirst().getClass());

    Assert.assertEquals(
        classNameExt + ".name", ((FetchFromIndexStep) plan.getSteps().getFirst()).getIndexName());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassWithIndexes() {
    var className = "testFetchFromClassWithIndexes";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createIndex(className + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    clazz.createIndex(className + ".surname", SchemaClass.INDEX_TYPE.NOTUNIQUE, "surname");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.query("select from " + className + " where name = 'name2' or surname = 'surname3'");
    printExecutionPlan(result);

    Assert.assertTrue(result.hasNext());
    for (var i = 0; i < 2; i++) {
      var next = result.next();
      Assert.assertNotNull(next);
      Assert.assertTrue(
          "name2".equals(next.getProperty("name"))
              || ("surname3".equals(next.getProperty("surname"))));
    }

    Assert.assertFalse(result.hasNext());

    var p = result.getExecutionPlan();
    Assert.assertNotNull(p);
    Assert.assertTrue(p instanceof SelectExecutionPlan);
    var plan = (SelectExecutionPlan) p;
    Assert.assertEquals(ParallelExecStep.class, plan.getSteps().getFirst().getClass());
    var parallel = (ParallelExecStep) plan.getSteps().getFirst();
    Assert.assertEquals(2, parallel.getSubExecutionPlans().size());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassWithIndexes2() {
    var className = "testFetchFromClassWithIndexes2";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createIndex(className + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    clazz.createIndex(className + ".surname", SchemaClass.INDEX_TYPE.NOTUNIQUE, "surname");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select from "
                + className
                + " where foo is not null and (name = 'name2' or surname = 'surname3')");
    printExecutionPlan(result);

    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassWithIndexes3() {
    var className = "testFetchFromClassWithIndexes3";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createIndex(className + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    clazz.createIndex(className + ".surname", SchemaClass.INDEX_TYPE.NOTUNIQUE, "surname");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);
      doc.setProperty("foo", i);

      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select from "
                + className
                + " where foo < 100 and (name = 'name2' or surname = 'surname3')");
    printExecutionPlan(result);

    Assert.assertTrue(result.hasNext());
    for (var i = 0; i < 2; i++) {
      var next = result.next();
      Assert.assertNotNull(next);
      Assert.assertTrue(
          "name2".equals(next.getProperty("name"))
              || ("surname3".equals(next.getProperty("surname"))));
    }

    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassWithIndexes4() {
    var className = "testFetchFromClassWithIndexes4";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createIndex(className + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    clazz.createIndex(className + ".surname", SchemaClass.INDEX_TYPE.NOTUNIQUE, "surname");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);
      doc.setProperty("foo", i);

      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select from "
                + className
                + " where foo < 100 and ((name = 'name2' and foo < 20) or surname = 'surname3') and"
                + " ( 4<5 and foo < 50)");
    printExecutionPlan(result);

    Assert.assertTrue(result.hasNext());
    for (var i = 0; i < 2; i++) {
      var next = result.next();
      Assert.assertNotNull(next);
      Assert.assertTrue(
          "name2".equals(next.getProperty("name"))
              || ("surname3".equals(next.getProperty("surname"))));
    }

    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassWithIndexes5() {
    var className = "testFetchFromClassWithIndexes5";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createIndex(className + ".name_surname", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "name",
        "surname");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);
      doc.setProperty("foo", i);

      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select from " + className + " where name = 'name3' and surname >= 'surname1'");
    printExecutionPlan(result);

    Assert.assertTrue(result.hasNext());
    for (var i = 0; i < 1; i++) {
      var next = result.next();
      Assert.assertNotNull(next);
      Assert.assertEquals("name3", next.getProperty("name"));
    }

    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassWithIndexes6() {
    var className = "testFetchFromClassWithIndexes6";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createIndex(className + ".name_surname", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "name",
        "surname");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);
      doc.setProperty("foo", i);

      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select from " + className + " where name = 'name3' and surname > 'surname3'");
    printExecutionPlan(result);

    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassWithIndexes7() {
    var className = "testFetchFromClassWithIndexes7";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createIndex(className + ".name_surname", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "name",
        "surname");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);
      doc.setProperty("foo", i);

      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select from " + className + " where name = 'name3' and surname >= 'surname3'");
    printExecutionPlan(result);
    for (var i = 0; i < 1; i++) {
      var next = result.next();
      Assert.assertNotNull(next);
      Assert.assertEquals("name3", next.getProperty("name"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassWithIndexes8() {
    var className = "testFetchFromClassWithIndexes8";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createIndex(className + ".name_surname", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "name",
        "surname");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);
      doc.setProperty("foo", i);

      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select from " + className + " where name = 'name3' and surname < 'surname3'");
    printExecutionPlan(result);

    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassWithIndexes9() {
    var className = "testFetchFromClassWithIndexes9";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createIndex(className + ".name_surname", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "name",
        "surname");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);
      doc.setProperty("foo", i);

      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select from " + className + " where name = 'name3' and surname <= 'surname3'");
    printExecutionPlan(result);
    for (var i = 0; i < 1; i++) {
      var next = result.next();
      Assert.assertNotNull(next);
      Assert.assertEquals("name3", next.getProperty("name"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassWithIndexes10() {
    var className = "testFetchFromClassWithIndexes10";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createIndex(className + ".name_surname", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "name",
        "surname");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);
      doc.setProperty("foo", i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className + " where name > 'name3' ");
    printExecutionPlan(result);
    for (var i = 0; i < 6; i++) {
      Assert.assertTrue(result.hasNext());
      var next = result.next();
      Assert.assertNotNull(next);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassWithIndexes11() {
    var className = "testFetchFromClassWithIndexes11";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createIndex(className + ".name_surname", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "name",
        "surname");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);
      doc.setProperty("foo", i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className + " where name >= 'name3' ");
    printExecutionPlan(result);
    for (var i = 0; i < 7; i++) {
      var next = result.next();
      Assert.assertNotNull(next);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassWithIndexes12() {
    var className = "testFetchFromClassWithIndexes12";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createIndex(className + ".name_surname", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "name",
        "surname");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);
      doc.setProperty("foo", i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className + " where name < 'name3' ");
    printExecutionPlan(result);
    for (var i = 0; i < 3; i++) {
      var next = result.next();
      Assert.assertNotNull(next);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassWithIndexes13() {
    var className = "testFetchFromClassWithIndexes13";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createIndex(className + ".name_surname", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "name",
        "surname");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);
      doc.setProperty("foo", i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className + " where name <= 'name3' ");
    printExecutionPlan(result);
    for (var i = 0; i < 4; i++) {
      var next = result.next();
      Assert.assertNotNull(next);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassWithIndexes14() {
    var className = "testFetchFromClassWithIndexes14";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createIndex(className + ".name_surname", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "name",
        "surname");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);
      doc.setProperty("foo", i);

      session.commit();
    }

    session.begin();
    var result =
        session.query("select from " + className + " where name > 'name3' and name < 'name5'");
    printExecutionPlan(result);
    for (var i = 0; i < 1; i++) {
      var next = result.next();
      Assert.assertNotNull(next);
    }
    Assert.assertFalse(result.hasNext());
    var plan = (SelectExecutionPlan) result.getExecutionPlan();
    Assert.assertEquals(
        1, plan.getSteps().stream().filter(step -> step instanceof FetchFromIndexStep).count());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassWithIndexes15() {
    var className = "testFetchFromClassWithIndexes15";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createIndex(className + ".name_surname", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "name",
        "surname");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);
      doc.setProperty("foo", i);

      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select from "
                + className
                + " where name > 'name6' and name = 'name3' and surname > 'surname2' and surname <"
                + " 'surname5' ");
    printExecutionPlan(result);
    Assert.assertFalse(result.hasNext());
    var plan = (SelectExecutionPlan) result.getExecutionPlan();
    Assert.assertEquals(
        1, plan.getSteps().stream().filter(step -> step instanceof FetchFromIndexStep).count());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassWithHashIndexes1() {
    var className = "testFetchFromClassWithHashIndexes1";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createIndex(
        className + ".name_surname", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name",
        "surname");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);
      doc.setProperty("foo", i);

      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select from " + className + " where name = 'name6' and surname = 'surname6' ");
    printExecutionPlan(result);

    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var next = result.next();
      Assert.assertNotNull(next);
    }
    Assert.assertFalse(result.hasNext());
    var plan = (SelectExecutionPlan) result.getExecutionPlan();
    Assert.assertEquals(
        1, plan.getSteps().stream().filter(step -> step instanceof FetchFromIndexStep).count());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromClassWithHashIndexes2() {
    var className = "testFetchFromClassWithHashIndexes2";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createIndex(
        className + ".name_surname", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name",
        "surname");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);
      doc.setProperty("foo", i);

      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select from " + className + " where name = 'name6' and surname >= 'surname6' ");
    printExecutionPlan(result);

    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var next = result.next();
      Assert.assertNotNull(next);
    }
    Assert.assertFalse(result.hasNext());
    var plan = (SelectExecutionPlan) result.getExecutionPlan();
    Assert.assertEquals(
        FetchFromIndexStep.class, plan.getSteps().getFirst().getClass()); // index not used
    result.close();
    session.commit();
  }

  @Test
  public void testExpand1() {
    var childClassName = "testExpand1_child";
    var parentClassName = "testExpand1_parent";
    var childClass = session.getMetadata().getSchema().createClass(childClassName);
    var parentClass = session.getMetadata().getSchema().createClass(parentClassName);

    var count = 10;
    for (var i = 0; i < count; i++) {
      session.begin();
      var doc = session.newInstance(childClassName);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);
      doc.setProperty("foo", i);

      var parent = (EntityImpl) session.newEntity(parentClassName);
      parent.setProperty("linked", doc);

      session.commit();
    }

    session.begin();
    var result = session.query("select expand(linked) from " + parentClassName);
    printExecutionPlan(result);

    for (var i = 0; i < count; i++) {
      Assert.assertTrue(result.hasNext());
      var next = result.next();
      Assert.assertNotNull(next);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testExpand2() {
    var childClassName = "testExpand2_child";
    var parentClassName = "testExpand2_parent";
    session.getMetadata().getSchema().createClass(childClassName);
    session.getMetadata().getSchema().createClass(parentClassName);

    session.begin();
    var count = 10;
    var collSize = 11;
    for (var i = 0; i < count; i++) {
      var coll = session.newLinkList();
      for (var j = 0; j < collSize; j++) {
        var doc = session.newInstance(childClassName);
        doc.setProperty("name", "name" + i);

        coll.add(doc);
      }

      var parent = (EntityImpl) session.newEntity(parentClassName);
      parent.setProperty("linked", coll);

    }
    session.commit();

    session.begin();
    var result = session.query("select expand(linked) from " + parentClassName);
    printExecutionPlan(result);

    for (var i = 0; i < count * collSize; i++) {
      Assert.assertTrue(result.hasNext());
      var next = result.next();
      Assert.assertNotNull(next);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testExpand3() {
    var childClassName = "testExpand3_child";
    var parentClassName = "testExpand3_parent";
    session.getMetadata().getSchema().createClass(childClassName);
    session.getMetadata().getSchema().createClass(parentClassName);

    session.begin();
    var count = 30;
    var collSize = 7;
    for (var i = 0; i < count; i++) {
      var coll = session.newLinkList();
      for (var j = 0; j < collSize; j++) {
        var doc = session.newInstance(childClassName);
        doc.setProperty("name", "name" + j);

        coll.add(doc);
      }

      var parent = (EntityImpl) session.newEntity(parentClassName);
      parent.setProperty("linked", coll);

    }
    session.commit();

    session.begin();
    var result =
        session.query("select expand(linked) from " + parentClassName + " order by name");
    printExecutionPlan(result);

    String last = null;
    for (var i = 0; i < count * collSize; i++) {
      Assert.assertTrue(result.hasNext());
      var next = result.next();
      if (i > 0) {
        Assert.assertTrue(last.compareTo(next.getProperty("name")) <= 0);
      }
      last = next.getProperty("name");
      Assert.assertNotNull(next);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testDistinct1() {
    var className = "testDistinct1";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);

    for (var i = 0; i < 30; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i % 10);
      doc.setProperty("surname", "surname" + i % 10);

      session.commit();
    }

    session.begin();
    var result = session.query("select distinct name, surname from " + className);
    printExecutionPlan(result);

    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var next = result.next();
      Assert.assertNotNull(next);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testDistinct2() {
    var className = "testDistinct2";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);

    for (var i = 0; i < 30; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i % 10);
      doc.setProperty("surname", "surname" + i % 10);

      session.commit();
    }

    session.begin();
    var result = session.query("select distinct(name) from " + className);
    printExecutionPlan(result);

    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var next = result.next();
      Assert.assertNotNull(next);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testLet1() {
    session.begin();
    var result = session.query("select $a as one, $b as two let $a = 1, $b = 1+1");
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals(1, item.<Object>getProperty("one"));
    Assert.assertEquals(2, item.<Object>getProperty("two"));
    printExecutionPlan(result);
    result.close();
    session.commit();
  }

  @Test
  public void testLet1Long() {
    session.begin();
    var result = session.query("select $a as one, $b as two let $a = 1L, $b = 1L+1");
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals(1L, item.<Object>getProperty("one"));
    Assert.assertEquals(2L, item.<Object>getProperty("two"));
    printExecutionPlan(result);
    result.close();
    session.commit();
  }

  @Test
  public void testLet2() {
    session.begin();
    var result = session.query("select $a as one let $a = (select 1 as a)");
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    var one = item.<BasicResult>getEmbeddedList("one");
    Assert.assertEquals(1, one.size());
    var x = one.getFirst();
    Assert.assertEquals(1, x.getInt("a").intValue());
    result.close();
  }

  @Test
  public void testLet3() {
    session.begin();
    var result = session.query("select $a[0].foo as one let $a = (select 1 as foo)");
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    var one = item.getProperty("one");
    Assert.assertEquals(1, one);
    result.close();
    session.commit();
  }

  @Test
  public void testLet4() {
    var className = "testLet4";
    session.getMetadata().getSchema().createClass(className);

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);
      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select name, surname, $nameAndSurname as fullname from "
                + className
                + " let $nameAndSurname = name + ' ' + surname");
    printExecutionPlan(result);

    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);

      Assert.assertEquals(
          item.getProperty("fullname"),
          item.getProperty("name") + " " + item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testLet5() {
    var className = "testLet5";
    session.getMetadata().getSchema().createClass(className);

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select from "
                + className
                + " where name in (select name from "
                + className
                + " where name = 'name1')");
    printExecutionPlan(result);
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
  public void testLet6() {
    var className = "testLet6";
    session.getMetadata().getSchema().createClass(className);

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select $foo as name from "
                + className
                + " let $foo = (select name from "
                + className
                + " where name = $parent.$current.name)");
    printExecutionPlan(result);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("name"));
      Assert.assertTrue(item.getProperty("name") instanceof Collection);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testLet7() {
    var className = "testLet7";
    session.getMetadata().getSchema().createClass(className);

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select $bar as name from "
                + className
                + " "
                + "let $foo = (select name from "
                + className
                + " where name = $parent.$current.name),"
                + "$bar = $foo[0].name");
    printExecutionPlan(result);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("name"));
      Assert.assertTrue(item.getProperty("name") instanceof String);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testLetWithTraverseFunction() {
    var vertexClassName = "testLetWithTraverseFunction";
    var edgeClassName = "testLetWithTraverseFunctioEdge";

    var vertexClass = session.createVertexClass(vertexClassName);

    session.begin();
    var doc1 = session.newVertex(vertexClass);
    doc1.setProperty("name", "A");

    var doc2 = session.newVertex(vertexClass);
    doc2.setProperty("name", "B");
    session.commit();

    var doc2Id = doc2.getIdentity();

    var edgeClass = session.createEdgeClass(edgeClassName);

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    doc1 = activeTx1.load(doc1);
    var activeTx = session.getActiveTransaction();
    doc2 = activeTx.load(doc2);

    session.newStatefulEdge(doc1, doc2, edgeClass);
    session.commit();

    session.begin();
    var queryString =
        "SELECT $x, name FROM " + vertexClassName + " let $x = out(\"" + edgeClassName + "\")";
    var resultSet = session.query(queryString);
    var counter = 0;
    while (resultSet.hasNext()) {
      var result = resultSet.next();
      Iterable<Identifiable> edge = result.getProperty("$x");
      for (var identifiable : edge) {
        Vertex toVertex = session.load(identifiable.getIdentity());
        if (doc2Id.equals(toVertex.getIdentity())) {
          ++counter;
        }
      }
    }
    Assert.assertEquals(1, counter);
    resultSet.close();
    session.commit();
  }

  @Test
  public void testLetVariableSubqueryProjectionFetchFromClassTarget_9695() {
    var className = "testLetVariableSubqueryProjectionFetchFromClassTarget_9695";
    session.getMetadata().getSchema().createClass(className);

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setInt("i", i);
      doc.newEmbeddedList("iSeq", new int[]{i, 2 * i, 4 * i});

      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select $current.*, $b.*, $b.@class from (select 1 as sqa, @class as sqc from "
                + className
                + " LIMIT 2)\n"
                + "let $b = $current");
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    var currentProperty = item.getProperty("$current.*");
    Assert.assertTrue(currentProperty instanceof BasicResult);
    final var currentResult = (Result) currentProperty;
    Assert.assertTrue(currentResult.isProjection());
    Assert.assertEquals(Integer.valueOf(1), currentResult.<Integer>getProperty("sqa"));
    Assert.assertEquals(className, currentResult.getProperty("sqc"));
    var bProperty = item.getProperty("$b.*");
    Assert.assertTrue(bProperty instanceof BasicResult);
    final var bResult = (Result) bProperty;
    Assert.assertTrue(bResult.isProjection());
    Assert.assertEquals(Integer.valueOf(1), bResult.<Integer>getProperty("sqa"));
    Assert.assertEquals(className, bResult.getProperty("sqc"));
    result.close();
    session.commit();
  }

  @Test
  public void testUnwind1() {
    var className = "testUnwind1";
    session.getMetadata().getSchema().createClass(className);

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("i", i);
      doc.newEmbeddedList("iSeq", new int[]{i, 2 * i, 4 * i});

      session.commit();
    }

    session.begin();
    var result = session.query("select i, iSeq from " + className + " unwind iSeq");
    printExecutionPlan(result);
    for (var i = 0; i < 30; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("i"));
      Assert.assertNotNull(item.getProperty("iSeq"));
      Integer first = item.getProperty("i");
      Integer second = item.getProperty("iSeq");
      Assert.assertTrue(first + second == 0 || second % first == 0);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testUnwind2() {
    var className = "testUnwind2";
    session.getMetadata().getSchema().createClass(className);

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("i", i);
      List<Integer> iSeq = new ArrayList<>();
      iSeq.add(i);
      iSeq.add(i << 1);
      iSeq.add(i << 2);
      doc.newEmbeddedList("iSeq", iSeq);

      session.commit();
    }

    session.begin();
    var result = session.query("select i, iSeq from " + className + " unwind iSeq");
    printExecutionPlan(result);
    for (var i = 0; i < 30; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("i"));
      Assert.assertNotNull(item.getProperty("iSeq"));
      Integer first = item.getProperty("i");
      Integer second = item.getProperty("iSeq");
      Assert.assertTrue(first + second == 0 || second % first == 0);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromSubclassIndexes1() {
    var parent = "testFetchFromSubclassIndexes1_parent";
    var child1 = "testFetchFromSubclassIndexes1_child1";
    var child2 = "testFetchFromSubclassIndexes1_child2";
    var parentClass = session.getMetadata().getSchema().createClass(parent);
    var childClass1 = session.getMetadata().getSchema().createClass(child1, parentClass);
    var childClass2 = session.getMetadata().getSchema().createClass(child2, parentClass);

    parentClass.createProperty("name", PropertyType.STRING);
    childClass1.createIndex(child1 + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    childClass2.createIndex(child2 + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(child1);
      doc.setProperty("name", "name" + i);

      session.commit();
    }

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(child2);
      doc.setProperty("name", "name" + i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + parent + " where name = 'name1'");
    printExecutionPlan(result);
    var plan = (InternalExecutionPlan) result.getExecutionPlan();
    Assert.assertTrue(plan.getSteps().getFirst() instanceof ParallelExecStep);
    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromSubclassIndexes2() {
    var parent = "testFetchFromSubclassIndexes2_parent";
    var child1 = "testFetchFromSubclassIndexes2_child1";
    var child2 = "testFetchFromSubclassIndexes2_child2";
    var parentClass = session.getMetadata().getSchema().createClass(parent);
    var childClass1 = session.getMetadata().getSchema().createClass(child1, parentClass);
    var childClass2 = session.getMetadata().getSchema().createClass(child2, parentClass);

    parentClass.createProperty("name", PropertyType.STRING);
    childClass1.createIndex(child1 + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    childClass2.createIndex(child2 + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(child1);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(child2);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.query("select from " + parent + " where name = 'name1' and surname = 'surname1'");
    printExecutionPlan(result);
    var plan = (InternalExecutionPlan) result.getExecutionPlan();
    Assert.assertTrue(plan.getSteps().getFirst() instanceof ParallelExecStep);
    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromSubclassIndexes3() {
    var parent = "testFetchFromSubclassIndexes3_parent";
    var child1 = "testFetchFromSubclassIndexes3_child1";
    var child2 = "testFetchFromSubclassIndexes3_child2";
    var parentClass = session.getMetadata().getSchema().createClass(parent);
    var childClass1 = session.getMetadata().getSchema().createClass(child1, parentClass);
    var childClass2 = session.getMetadata().getSchema().createClass(child2, parentClass);

    parentClass.createProperty("name", PropertyType.STRING);
    childClass1.createIndex(child1 + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(child1);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(child2);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.query("select from " + parent + " where name = 'name1' and surname = 'surname1'");
    printExecutionPlan(result);
    var plan = (InternalExecutionPlan) result.getExecutionPlan();
    Assert.assertTrue(
        plan.getSteps().getFirst() instanceof FetchFromClassExecutionStep); // no index used
    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromSubclassIndexes4() {
    var parent = "testFetchFromSubclassIndexes4_parent";
    var child1 = "testFetchFromSubclassIndexes4_child1";
    var child2 = "testFetchFromSubclassIndexes4_child2";
    var parentClass = session.getMetadata().getSchema().createClass(parent);
    var childClass1 = session.getMetadata().getSchema().createClass(child1, parentClass);
    var childClass2 = session.getMetadata().getSchema().createClass(child2, parentClass);

    parentClass.createProperty("name", PropertyType.STRING);
    childClass1.createIndex(child1 + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    childClass2.createIndex(child2 + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    session.begin();
    var parentdoc = session.newInstance(parent);
    parentdoc.setProperty("name", "foo");

    session.commit();

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(child1);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(child2);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.query("select from " + parent + " where name = 'name1' and surname = 'surname1'");
    printExecutionPlan(result);
    var plan = (InternalExecutionPlan) result.getExecutionPlan();
    Assert.assertTrue(
        plan.getSteps().getFirst()
            instanceof
            FetchFromClassExecutionStep); // no index, because the superclass is not empty
    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromSubSubclassIndexes() {
    var parent = "testFetchFromSubSubclassIndexes_parent";
    var child1 = "testFetchFromSubSubclassIndexes_child1";
    var child2 = "testFetchFromSubSubclassIndexes_child2";
    var child2_1 = "testFetchFromSubSubclassIndexes_child2_1";
    var child2_2 = "testFetchFromSubSubclassIndexes_child2_2";
    var parentClass = session.getMetadata().getSchema().createClass(parent);
    var childClass1 = session.getMetadata().getSchema().createClass(child1, parentClass);
    var childClass2 = session.getMetadata().getSchema().createClass(child2, parentClass);
    var childClass2_1 = session.getMetadata().getSchema().createClass(child2_1, childClass2);
    var childClass2_2 = session.getMetadata().getSchema().createClass(child2_2, childClass2);

    parentClass.createProperty("name", PropertyType.STRING);
    childClass1.createIndex(child1 + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    childClass2_1.createIndex(child2_1 + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "name");
    childClass2_2.createIndex(child2_2 + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "name");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(child1);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(child2_1);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(child2_2);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.query("select from " + parent + " where name = 'name1' and surname = 'surname1'");
    printExecutionPlan(result);
    var plan = (InternalExecutionPlan) result.getExecutionPlan();
    Assert.assertTrue(plan.getSteps().getFirst() instanceof ParallelExecStep);
    for (var i = 0; i < 3; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testFetchFromSubSubclassIndexesWithDiamond() {
    var parent = "testFetchFromSubSubclassIndexesWithDiamond_parent";
    var child1 = "testFetchFromSubSubclassIndexesWithDiamond_child1";
    var child2 = "testFetchFromSubSubclassIndexesWithDiamond_child2";
    var child12 = "testFetchFromSubSubclassIndexesWithDiamond_child12";

    var parentClass = session.getMetadata().getSchema().createClass(parent);
    var childClass1 = session.getMetadata().getSchema().createClass(child1, parentClass);
    var childClass2 = session.getMetadata().getSchema().createClass(child2, parentClass);
    var childClass12 =
        session.getMetadata().getSchema().createClass(child12, childClass1, childClass2);

    parentClass.createProperty("name", PropertyType.STRING);
    childClass1.createIndex(child1 + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    childClass2.createIndex(child2 + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(child1);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(child2);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(child12);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.query("select from " + parent + " where name = 'name1' and surname = 'surname1'");
    printExecutionPlan(result);
    var plan = (InternalExecutionPlan) result.getExecutionPlan();
    Assert.assertTrue(plan.getSteps().getFirst() instanceof FetchFromClassExecutionStep);
    for (var i = 0; i < 3; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testIndexPlusSort1() {
    var className = "testIndexPlusSort1";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    session.execute(
            "create index "
                + className
                + ".name_surname on "
                + className
                + " (name, surname) NOTUNIQUE")
        .close();

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i % 3);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.query("select from " + className + " where name = 'name1' order by surname ASC");
    printExecutionPlan(result);
    String lastSurname = null;
    for (var i = 0; i < 3; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("surname"));

      String surname = item.getProperty("surname");
      if (i > 0) {
        Assert.assertTrue(surname.compareTo(lastSurname) > 0);
      }
      lastSurname = surname;
    }
    Assert.assertFalse(result.hasNext());
    var plan = result.getExecutionPlan();
    Assert.assertEquals(
        1, plan.getSteps().stream().filter(step -> step instanceof FetchFromIndexStep).count());
    Assert.assertEquals(
        0, plan.getSteps().stream().filter(step -> step instanceof OrderByStep).count());
    result.close();
    session.commit();
  }

  @Test
  public void testIndexPlusSort2() {
    var className = "testIndexPlusSort2";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    session.execute(
            "create index "
                + className
                + ".name_surname on "
                + className
                + " (name, surname) NOTUNIQUE")
        .close();

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i % 3);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.query("select from " + className + " where name = 'name1' order by surname DESC");
    printExecutionPlan(result);
    String lastSurname = null;
    for (var i = 0; i < 3; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("surname"));

      String surname = item.getProperty("surname");
      if (i > 0) {
        Assert.assertTrue(surname.compareTo(lastSurname) < 0);
      }
      lastSurname = surname;
    }
    Assert.assertFalse(result.hasNext());
    var plan = result.getExecutionPlan();
    Assert.assertEquals(
        1, plan.getSteps().stream().filter(step -> step instanceof FetchFromIndexStep).count());
    Assert.assertEquals(
        0, plan.getSteps().stream().filter(step -> step instanceof OrderByStep).count());
    result.close();
    session.commit();
  }

  @Test
  public void testIndexPlusSort3() {
    var className = "testIndexPlusSort3";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    session.execute(
            "create index "
                + className
                + ".name_surname on "
                + className
                + " (name, surname) NOTUNIQUE")
        .close();

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i % 3);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select from " + className + " where name = 'name1' order by name DESC, surname DESC");
    printExecutionPlan(result);
    String lastSurname = null;
    for (var i = 0; i < 3; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("surname"));

      String surname = item.getProperty("surname");
      if (i > 0) {
        Assert.assertTrue(((String) item.getProperty("surname")).compareTo(lastSurname) < 0);
      }
      lastSurname = surname;
    }
    Assert.assertFalse(result.hasNext());
    var plan = result.getExecutionPlan();
    Assert.assertEquals(
        1, plan.getSteps().stream().filter(step -> step instanceof FetchFromIndexStep).count());
    Assert.assertEquals(
        0, plan.getSteps().stream().filter(step -> step instanceof OrderByStep).count());
    result.close();
    session.commit();
  }

  @Test
  public void testIndexPlusSort4() {
    var className = "testIndexPlusSort4";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    session.execute(
            "create index "
                + className
                + ".name_surname on "
                + className
                + " (name, surname) NOTUNIQUE")
        .close();

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i % 3);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select from " + className + " where name = 'name1' order by name ASC, surname ASC");
    printExecutionPlan(result);
    String lastSurname = null;
    for (var i = 0; i < 3; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("surname"));

      String surname = item.getProperty("surname");
      if (i > 0) {
        Assert.assertTrue(surname.compareTo(lastSurname) > 0);
      }
      lastSurname = surname;
    }
    Assert.assertFalse(result.hasNext());
    var plan = result.getExecutionPlan();
    Assert.assertEquals(
        1, plan.getSteps().stream().filter(step -> step instanceof FetchFromIndexStep).count());
    Assert.assertEquals(
        0, plan.getSteps().stream().filter(step -> step instanceof OrderByStep).count());
    result.close();
    session.commit();
  }

  @Test
  public void testIndexPlusSort5() {
    var className = "testIndexPlusSort5";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createProperty("address", PropertyType.STRING);
    session.execute(
            "create index "
                + className
                + ".name_surname on "
                + className
                + " (name, surname, address) NOTUNIQUE")
        .close();

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i % 3);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.query("select from " + className + " where name = 'name1' order by surname ASC");
    printExecutionPlan(result);
    String lastSurname = null;
    for (var i = 0; i < 3; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("surname"));
      String surname = item.getProperty("surname");
      if (i > 0) {
        Assert.assertTrue(surname.compareTo(lastSurname) > 0);
      }
      lastSurname = surname;
    }
    Assert.assertFalse(result.hasNext());
    var plan = result.getExecutionPlan();
    Assert.assertEquals(
        1, plan.getSteps().stream().filter(step -> step instanceof FetchFromIndexStep).count());
    Assert.assertEquals(
        0, plan.getSteps().stream().filter(step -> step instanceof OrderByStep).count());
    result.close();
    session.commit();
  }

  @Test
  public void testIndexPlusSort6() {
    var className = "testIndexPlusSort6";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createProperty("address", PropertyType.STRING);
    session.execute(
            "create index "
                + className
                + ".name_surname on "
                + className
                + " (name, surname, address) NOTUNIQUE")
        .close();

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i % 3);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.query("select from " + className + " where name = 'name1' order by surname DESC");
    printExecutionPlan(result);
    String lastSurname = null;
    for (var i = 0; i < 3; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("surname"));
      String surname = item.getProperty("surname");
      if (i > 0) {
        Assert.assertTrue(surname.compareTo(lastSurname) < 0);
      }
      lastSurname = surname;
    }
    Assert.assertFalse(result.hasNext());
    var plan = result.getExecutionPlan();
    Assert.assertEquals(
        1, plan.getSteps().stream().filter(step -> step instanceof FetchFromIndexStep).count());
    Assert.assertEquals(
        0, plan.getSteps().stream().filter(step -> step instanceof OrderByStep).count());
    result.close();
    session.commit();
  }

  @Test
  public void testIndexPlusSort7() {
    var className = "testIndexPlusSort7";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    clazz.createProperty("address", PropertyType.STRING);
    session.execute(
            "create index "
                + className
                + ".name_surname on "
                + className
                + " (name, surname, address) NOTUNIQUE")
        .close();

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i % 3);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.query("select from " + className + " where name = 'name1' order by address DESC");
    printExecutionPlan(result);

    for (var i = 0; i < 3; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    var orderStepFound = false;
    for (var step : result.getExecutionPlan().getSteps()) {
      if (step instanceof OrderByStep) {
        orderStepFound = true;
        break;
      }
    }
    Assert.assertTrue(orderStepFound);
    result.close();
    session.commit();
  }

  @SuppressWarnings("ConstantValue")
  @Test
  public void testIndexPlusSort8() {
    var className = "testIndexPlusSort8";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    session.execute(
            "create index "
                + className
                + ".name_surname on "
                + className
                + " (name, surname) NOTUNIQUE")
        .close();

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i % 3);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select from " + className + " where name = 'name1' order by name ASC, surname DESC");
    printExecutionPlan(result);
    for (var i = 0; i < 3; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    Assert.assertFalse(result.hasNext());
    var orderStepFound = false;
    for (var step : result.getExecutionPlan().getSteps()) {
      if (step instanceof OrderByStep) {
        orderStepFound = true;
        break;
      }
    }
    Assert.assertTrue(orderStepFound);
    result.close();
    session.commit();
  }

  @SuppressWarnings("ConstantValue")
  @Test
  public void testIndexPlusSort9() {
    var className = "testIndexPlusSort9";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    session.execute(
            "create index "
                + className
                + ".name_surname on "
                + className
                + " (name, surname) NOTUNIQUE")
        .close();

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i % 3);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className + " order by name , surname ASC");
    printExecutionPlan(result);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    Assert.assertFalse(result.hasNext());
    var orderStepFound = false;
    for (var step : result.getExecutionPlan().getSteps()) {
      if (step instanceof OrderByStep) {
        orderStepFound = true;
        break;
      }
    }
    Assert.assertFalse(orderStepFound);
    result.close();
    session.commit();
  }

  @SuppressWarnings("ConstantValue")
  @Test
  public void testIndexPlusSort10() {
    var className = "testIndexPlusSort10";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    session.execute(
            "create index "
                + className
                + ".name_surname on "
                + className
                + " (name, surname) NOTUNIQUE")
        .close();

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i % 3);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className + " order by name desc, surname desc");
    printExecutionPlan(result);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    Assert.assertFalse(result.hasNext());
    var orderStepFound = false;
    for (var step : result.getExecutionPlan().getSteps()) {
      if (step instanceof OrderByStep) {
        orderStepFound = true;
        break;
      }
    }
    Assert.assertFalse(orderStepFound);
    result.close();
    session.commit();
  }

  @SuppressWarnings("ConstantValue")
  @Test
  public void testIndexPlusSort11() {
    var className = "testIndexPlusSort11";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    session.execute(
            "create index "
                + className
                + ".name_surname on "
                + className
                + " (name, surname) NOTUNIQUE")
        .close();

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i % 3);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className + " order by name asc, surname desc");
    printExecutionPlan(result);
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    Assert.assertFalse(result.hasNext());
    var orderStepFound = false;
    for (var step : result.getExecutionPlan().getSteps()) {
      if (step instanceof OrderByStep) {
        orderStepFound = true;
        break;
      }
    }
    Assert.assertTrue(orderStepFound);
    result.close();
    session.commit();
  }

  @SuppressWarnings("ConstantValue")
  @Test
  public void testIndexPlusSort12() {
    var className = "testIndexPlusSort12";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("surname", PropertyType.STRING);
    session.execute(
            "create index "
                + className
                + ".name_surname on "
                + className
                + " (name, surname) NOTUNIQUE")
        .close();

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i % 3);
      doc.setProperty("surname", "surname" + i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className + " order by name");
    printExecutionPlan(result);
    String last = null;
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("name"));
      String name = item.getProperty("name");
      if (i > 0) {
        Assert.assertTrue(name.compareTo(last) >= 0);
      }
      last = name;
    }
    Assert.assertFalse(result.hasNext());
    Assert.assertFalse(result.hasNext());
    var orderStepFound = false;
    for (var step : result.getExecutionPlan().getSteps()) {
      if (step instanceof OrderByStep) {
        orderStepFound = true;
        break;
      }
    }
    Assert.assertFalse(orderStepFound);
    result.close();
    session.commit();
  }

  @Test
  public void testSelectFromStringParam() {
    var className = "testSelectFromStringParam";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from ?", className);
    printExecutionPlan(result);

    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertTrue(("" + item.getProperty("name")).startsWith("name"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testSelectFromStringNamedParam() {
    var className = "testSelectFromStringNamedParam";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);

      session.commit();
    }
    Map<Object, Object> params = new HashMap<>();
    params.put("target", className);

    session.begin();
    var result = session.query("select from :target", params);
    printExecutionPlan(result);

    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertTrue(("" + item.getProperty("name")).startsWith("name"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testMatches() {
    var className = "testMatches";
    session.getMetadata().getSchema().createClass(className);

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);

      session.commit();
    }

    session.begin();
    var result = session.query("select from " + className + " where name matches 'name1'");
    printExecutionPlan(result);

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
  public void testRange() {
    var className = "testRange";
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var doc = session.newInstance(className);
    doc.newEmbeddedList("name", new String[]{"a", "b", "c", "d"});

    session.commit();

    session.begin();
    var result = session.query("select name[0..3] as names from " + className);
    printExecutionPlan(result);

    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      var names = item.<String>getEmbeddedList("names");
      if (names == null) {
        Assert.fail();
      }

      Assert.assertEquals(3, names.size());
      var iter = names.iterator();
      Assert.assertEquals("a", iter.next());
      Assert.assertEquals("b", iter.next());
      Assert.assertEquals("c", iter.next());
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testRangeParams1() {
    var className = "testRangeParams1";
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var doc = session.newInstance(className);
    doc.newEmbeddedList("name", new String[]{"a", "b", "c", "d"});

    session.commit();

    session.begin();
    var result = session.query("select name[?..?] as names from " + className, 0, 3);
    printExecutionPlan(result);

    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      var names = item.<String>getEmbeddedList("names");
      if (names == null) {
        Assert.fail();
      }
      Assert.assertEquals(3, names.size());
      var iter = names.iterator();
      Assert.assertEquals("a", iter.next());
      Assert.assertEquals("b", iter.next());
      Assert.assertEquals("c", iter.next());
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testRangeParams2() {
    var className = "testRangeParams2";
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var doc = session.newInstance(className);
    doc.newEmbeddedList("name", new String[]{"a", "b", "c", "d"});

    session.commit();

    Map<String, Object> params = new HashMap<>();
    params.put("a", 0);
    params.put("b", 3);

    session.begin();
    var result = session.query("select name[:a..:b] as names from " + className, params);
    printExecutionPlan(result);

    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      var names = item.<String>getEmbeddedList("names");
      if (names == null) {
        Assert.fail();
      }
      Assert.assertEquals(3, (names).size());
      var iter = names.iterator();
      Assert.assertEquals("a", iter.next());
      Assert.assertEquals("b", iter.next());
      Assert.assertEquals("c", iter.next());
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testEllipsis() {
    var className = "testEllipsis";
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var doc = session.newInstance(className);
    doc.newEmbeddedList("name", new String[]{"a", "b", "c", "d"});

    session.commit();

    session.begin();
    var result = session.query("select name[0...2] as names from " + className);
    printExecutionPlan(result);

    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      var names = item.<String>getEmbeddedList("names");
      if (names == null) {
        Assert.fail();
      }
      Assert.assertEquals(3, names.size());
      var iter = names.iterator();
      Assert.assertEquals("a", iter.next());
      Assert.assertEquals("b", iter.next());
      Assert.assertEquals("c", iter.next());
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testNewRid() {
    session.begin();
    var result = session.query("select {\"@rid\":\"#12:0\"} as theRid ");
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    var rid = item.getProperty("theRid");
    Assert.assertTrue(rid instanceof Identifiable);
    var id = (Identifiable) rid;
    Assert.assertEquals(12, id.getIdentity().getCollectionId());
    Assert.assertEquals(0L, id.getIdentity().getCollectionPosition());
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testNestedProjections1() {
    var className = "testNestedProjections1";
    session.execute("create class " + className).close();

    session.begin();
    var elem1 = session.newEntity(className);
    elem1.setProperty("name", "a");

    var elem2 = session.newEntity(className);
    elem2.setProperty("name", "b");
    elem2.setProperty("surname", "lkj");

    var elem3 = session.newEntity(className);
    elem3.setProperty("name", "c");

    var elem4 = session.newEntity(className);
    elem4.setProperty("name", "d");
    elem4.setProperty("elem1", elem1);
    elem4.setProperty("elem2", elem2);
    elem4.setProperty("elem3", elem3);

    session.commit();

    session.begin();
    var result =
        session.query(
            "select name, elem1:{*}, elem2:{!surname} from " + className + " where name = 'd'");
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    // TODO refine this!
    Assert.assertTrue(item.getProperty("elem1") instanceof BasicResult);
    Assert.assertEquals("a", ((BasicResult) item.getProperty("elem1")).getProperty("name"));
    printExecutionPlan(result);

    result.close();
    session.commit();
  }

  @Test
  public void testSimpleCollectionFiltering() {
    var className = "testSimpleCollectionFiltering";
    session.execute("create class " + className).close();

    session.begin();
    var elem1 = session.newEntity(className);
    List<String> coll = new ArrayList<>();
    coll.add("foo");
    coll.add("bar");
    coll.add("baz");
    elem1.newEmbeddedList("coll", coll);
    session.commit();

    session.begin();
    var result = session.query("select coll[='foo'] as filtered from " + className);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    var res = item.<String>getEmbeddedList("filtered");
    Assert.assertEquals(1, res.size());
    Assert.assertEquals("foo", res.getFirst());
    result.close();

    result = session.query("select coll[<'ccc'] as filtered from " + className);
    Assert.assertTrue(result.hasNext());
    item = result.next();
    res = item.getProperty("filtered");
    Assert.assertEquals(2, res.size());
    result.close();

    result = session.query("select coll[LIKE 'ba%'] as filtered from " + className);
    Assert.assertTrue(result.hasNext());
    item = result.next();
    res = item.getProperty("filtered");
    Assert.assertEquals(2, res.size());
    result.close();

    result = session.query("select coll[in ['bar']] as filtered from " + className);
    Assert.assertTrue(result.hasNext());
    item = result.next();
    res = item.getProperty("filtered");
    Assert.assertEquals(1, res.size());
    Assert.assertEquals("bar", res.getFirst());
    result.close();
    session.commit();
  }

  @Test
  public void testContaninsWithConversion() {
    var className = "testContaninsWithConversion";
    session.execute("create class " + className).close();

    session.begin();
    var elem1 = session.newEntity(className);
    List<Long> coll = new ArrayList<>();
    coll.add(1L);
    coll.add(3L);
    coll.add(5L);
    elem1.newEmbeddedList("coll", coll);

    var elem2 = session.newEntity(className);
    coll = new ArrayList<>();
    coll.add(2L);
    coll.add(4L);
    coll.add(6L);
    elem2.newEmbeddedList("coll", coll);
    session.commit();

    session.begin();
    var result = session.query("select from " + className + " where coll contains 1");
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();

    result = session.query("select from " + className + " where coll contains 1L");
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();

    result = session.query("select from " + className + " where coll contains 12L");
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testIndexPrefixUsage() {
    // issue #7636
    var className = "testIndexPrefixUsage";
    session.execute("create class " + className).close();
    session.execute("create property " + className + ".id LONG").close();
    session.execute("create property " + className + ".name STRING").close();
    session.execute("create index " + className + ".id_name on " + className + "(id, name) UNIQUE")
        .close();

    session.begin();
    session.execute("insert into " + className + " set id = 1 , name = 'Bar'").close();
    session.commit();

    session.begin();
    var result = session.query("select from " + className + " where name = 'Bar'");
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testNamedParams() {
    var className = "testNamedParams";
    session.execute("create class " + className).close();

    session.begin();
    session.execute("insert into " + className + " set name = 'Foo', surname = 'Fox'").close();
    session.execute("insert into " + className + " set name = 'Bar', surname = 'Bax'").close();
    session.commit();

    Map<String, Object> params = new HashMap<>();
    params.put("p1", "Foo");
    params.put("p2", "Fox");
    session.begin();
    var result =
        session.query("select from " + className + " where name = :p1 and surname = :p2", params);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testNamedParamsWithIndex() {
    var className = "testNamedParamsWithIndex";
    session.execute("create class " + className).close();
    session.execute("create property " + className + ".name STRING").close();
    session.execute("create index " + className + ".name ON " + className + " (name) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("insert into " + className + " set name = 'Foo'").close();
    session.execute("insert into " + className + " set name = 'Bar'").close();
    session.commit();

    Map<String, Object> params = new HashMap<>();
    params.put("p1", "Foo");
    session.begin();
    var result = session.query("select from " + className + " where name = :p1", params);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testIsDefined() {
    var className = "testIsDefined";
    session.execute("create class " + className).close();

    session.begin();
    session.execute("insert into " + className + " set name = 'Foo'").close();
    session.execute("insert into " + className + " set sur = 'Bar'").close();
    session.execute("insert into " + className + " set sur = 'Barz'").close();
    session.commit();

    session.begin();
    var result = session.query("select from " + className + " where name is defined");
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testIsNotDefined() {
    var className = "testIsNotDefined";
    session.execute("create class " + className).close();

    session.begin();
    session.execute("insert into " + className + " set name = 'Foo'").close();
    session.execute("insert into " + className + " set name = null, sur = 'Bar'").close();
    session.execute("insert into " + className + " set sur = 'Barz'").close();
    session.commit();

    session.begin();
    var result = session.query("select from " + className + " where name is not defined");
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testContainsWithSubquery() {
    var className = "testContainsWithSubquery";
    session.createClassIfNotExist(className + 1);
    var clazz2 = session.createClassIfNotExist(className + 2);
    clazz2.createProperty("tags", PropertyType.EMBEDDEDLIST);

    session.begin();
    session.execute("insert into " + className + 1 + "  set name = 'foo'");

    session.execute("insert into " + className + 2 + "  set tags = ['foo', 'bar']");
    session.execute("insert into " + className + 2 + "  set tags = ['baz', 'bar']");
    session.execute("insert into " + className + 2 + "  set tags = ['foo']");
    session.commit();

    session.begin();
    try (var result =
        session.query(
            "select from "
                + className
                + 2
                + " where tags contains (select from "
                + className
                + 1
                + " where name = 'foo')")) {

      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
    }
    session.commit();
  }

  @Test
  public void testInWithSubquery() {
    var className = "testInWithSubquery";
    session.createClassIfNotExist(className + 1);
    var clazz2 = session.createClassIfNotExist(className + 2);
    clazz2.createProperty("tags", PropertyType.EMBEDDEDLIST);

    session.begin();
    session.execute("insert into " + className + 1 + "  set name = 'foo'");

    session.execute("insert into " + className + 2 + "  set tags = ['foo', 'bar']");
    session.execute("insert into " + className + 2 + "  set tags = ['baz', 'bar']");
    session.execute("insert into " + className + 2 + "  set tags = ['foo']");
    session.commit();

    session.begin();
    try (var result =
        session.query(
            "select from "
                + className
                + 2
                + " where (select from "
                + className
                + 1
                + " where name = 'foo') in tags")) {

      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
      session.commit();
    }
  }

  @Test
  public void testContainsAny() {
    var className = "testContainsAny";
    var clazz = session.createClassIfNotExist(className);
    clazz.createProperty("tags", PropertyType.EMBEDDEDLIST, PropertyType.STRING);

    session.begin();
    session.execute("insert into " + className + "  set tags = ['foo', 'bar']");
    session.execute("insert into " + className + "  set tags = ['bbb', 'FFF']");
    session.commit();

    session.begin();
    try (var result =
        session.query("select from " + className + " where tags containsany ['foo','baz']")) {
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
    }

    try (var result =
        session.query("select from " + className + " where tags containsany ['foo','bar']")) {
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
    }

    try (var result =
        session.query("select from " + className + " where tags containsany ['foo','bbb']")) {
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
    }

    try (var result =
        session.query("select from " + className + " where tags containsany ['xx','baz']")) {
      Assert.assertFalse(result.hasNext());
    }

    try (var result = session.query("select from " + className + " where tags containsany []")) {
      Assert.assertFalse(result.hasNext());
    }
    session.commit();
  }

  @Test
  public void testContainsAnyWithIndex() {
    var className = "testContainsAnyWithIndex";
    var clazz = session.createClassIfNotExist(className);
    var prop = clazz.createProperty("tags", PropertyType.EMBEDDEDLIST,
        PropertyType.STRING);
    prop.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    session.begin();
    session.execute("insert into " + className + "  set tags = ['foo', 'bar']");
    session.execute("insert into " + className + "  set tags = ['bbb', 'FFF']");
    session.commit();

    session.begin();
    try (var result =
        session.query("select from " + className + " where tags containsany ['foo','baz']")) {
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
      Assert.assertTrue(
          result.getExecutionPlan().getSteps().stream()
              .anyMatch(x -> x instanceof FetchFromIndexStep));
    }

    try (var result =
        session.query("select from " + className + " where tags containsany ['foo','bar']")) {
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
      Assert.assertTrue(
          result.getExecutionPlan().getSteps().stream()
              .anyMatch(x -> x instanceof FetchFromIndexStep));
    }

    try (var result =
        session.query("select from " + className + " where tags containsany ['foo','bbb']")) {
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
      Assert.assertTrue(
          result.getExecutionPlan().getSteps().stream()
              .anyMatch(x -> x instanceof FetchFromIndexStep));
    }

    try (var result =
        session.query("select from " + className + " where tags containsany ['xx','baz']")) {
      Assert.assertFalse(result.hasNext());
      Assert.assertTrue(
          result.getExecutionPlan().getSteps().stream()
              .anyMatch(x -> x instanceof FetchFromIndexStep));
    }

    try (var result = session.query("select from " + className + " where tags containsany []")) {
      Assert.assertFalse(result.hasNext());
      Assert.assertTrue(
          result.getExecutionPlan().getSteps().stream()
              .anyMatch(x -> x instanceof FetchFromIndexStep));
    }
    session.commit();
  }

  @Test
  public void testContainsAll() {
    var className = "testContainsAll";
    var clazz = session.createClassIfNotExist(className);
    clazz.createProperty("tags", PropertyType.EMBEDDEDLIST, PropertyType.STRING);

    session.begin();
    session.execute("insert into " + className + "  set tags = ['foo', 'bar']");
    session.execute("insert into " + className + "  set tags = ['foo', 'FFF']");
    session.commit();

    session.begin();
    try (var result =
        session.query("select from " + className + " where tags containsall ['foo','bar']")) {
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
    }

    try (var result =
        session.query("select from " + className + " where tags containsall ['foo']")) {
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
    }
    session.commit();
  }

  @Test
  public void testBetween() {
    var className = "testBetween";
    session.createClassIfNotExist(className);

    session.begin();
    session.execute("insert into " + className + "  set name = 'foo1', val = 1");
    session.execute("insert into " + className + "  set name = 'foo2', val = 2");
    session.execute("insert into " + className + "  set name = 'foo3', val = 3");
    session.execute("insert into " + className + "  set name = 'foo4', val = 4");
    session.commit();

    session.begin();
    try (var result = session.query("select from " + className + " where val between 2 and 3")) {
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
    }
    session.commit();
  }

  @Test
  public void testInWithIndex() {
    var className = "testInWithIndex";
    var clazz = session.createClassIfNotExist(className);
    var prop = clazz.createProperty("tag", PropertyType.STRING);
    prop.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    session.begin();
    session.execute("insert into " + className + "  set tag = 'foo'");
    session.execute("insert into " + className + "  set tag = 'bar'");
    session.commit();

    session.begin();
    try (var result = session.query(
        "select from " + className + " where tag in ['foo','baz']")) {
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
      Assert.assertTrue(
          result.getExecutionPlan().getSteps().stream()
              .anyMatch(x -> x instanceof FetchFromIndexStep));
    }

    try (var result = session.query(
        "select from " + className + " where tag in ['foo','bar']")) {
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
      Assert.assertTrue(
          result.getExecutionPlan().getSteps().stream()
              .anyMatch(x -> x instanceof FetchFromIndexStep));
    }

    try (var result = session.query("select from " + className + " where tag in []")) {
      Assert.assertFalse(result.hasNext());
      Assert.assertTrue(
          result.getExecutionPlan().getSteps().stream()
              .anyMatch(x -> x instanceof FetchFromIndexStep));
    }

    List<String> params = new ArrayList<>();
    params.add("foo");
    params.add("bar");
    try (var result = session.query("select from " + className + " where tag in (?)", params)) {
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
      Assert.assertTrue(
          result.getExecutionPlan().getSteps().stream()
              .anyMatch(x -> x instanceof FetchFromIndexStep));
    }
    session.commit();
  }

  @Test
  public void testIndexChain() {
    var className1 = "testIndexChain1";
    var className2 = "testIndexChain2";
    var className3 = "testIndexChain3";

    var clazz3 = session.createClassIfNotExist(className3);
    var prop = clazz3.createProperty("name", PropertyType.STRING);
    prop.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    var clazz2 = session.createClassIfNotExist(className2);
    prop = clazz2.createProperty("next", PropertyType.LINK, clazz3);
    prop.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    var clazz1 = session.createClassIfNotExist(className1);
    prop = clazz1.createProperty("next", PropertyType.LINK, clazz2);
    prop.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    session.begin();
    var elem3 = session.newEntity(className3);
    elem3.setProperty("name", "John");

    var elem2 = session.newEntity(className2);
    elem2.setProperty("next", elem3);

    var elem1 = session.newEntity(className1);
    elem1.setProperty("next", elem2);
    elem1.setProperty("name", "right");

    elem1 = session.newEntity(className1);
    elem1.setProperty("name", "wrong");
    session.commit();

    session.begin();
    try (var result =
        session.query("select from " + className1 + " where next.next.name = ?", "John")) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertEquals("right", item.getProperty("name"));
      Assert.assertFalse(result.hasNext());
      Assert.assertTrue(
          result.getExecutionPlan().getSteps().stream()
              .anyMatch(x -> x instanceof FetchFromIndexStep));
    }
    session.commit();
  }

  @Test
  public void testIndexChainWithContainsAny() {
    var className1 = "testIndexChainWithContainsAny1";
    var className2 = "testIndexChainWithContainsAny2";
    var className3 = "testIndexChainWithContainsAny3";

    var clazz3 = session.createClassIfNotExist(className3);
    var prop = clazz3.createProperty("name", PropertyType.STRING);
    prop.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    var clazz2 = session.createClassIfNotExist(className2);
    prop = clazz2.createProperty("next", PropertyType.LINKSET, clazz3);
    prop.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    var clazz1 = session.createClassIfNotExist(className1);
    prop = clazz1.createProperty("next", PropertyType.LINKSET, clazz2);
    prop.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    session.begin();
    var elem3 = session.newEntity(className3);
    elem3.setProperty("name", "John");

    var elemFoo = session.newEntity(className3);
    elemFoo.setProperty("foo", "bar");

    var elem2 = session.newEntity(className2);
    var elems3 = session.newLinkList();

    elems3.add(elem3);
    elems3.add(elemFoo);
    elem2.setProperty("next", elems3);

    var elem1 = session.newEntity(className1);
    var elems2 = session.newLinkList();
    elems2.add(elem2);

    elem1.setProperty("next", elems2);
    elem1.setProperty("name", "right");

    elem1 = session.newEntity(className1);
    elem1.setProperty("name", "wrong");

    session.commit();

    session.begin();
    try (var result =
        session.query("select from " + className1 + " where next.next.name CONTAINSANY ['John']")) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertEquals("right", item.getProperty("name"));
      Assert.assertFalse(result.hasNext());
      Assert.assertTrue(
          result.getExecutionPlan().getSteps().stream()
              .anyMatch(x -> x instanceof FetchFromIndexStep));
    }
    session.commit();
  }

  @Test
  public void testMapByKeyIndex() {
    var className = "testMapByKeyIndex";

    var clazz1 = session.createClassIfNotExist(className);
    clazz1.createProperty("themap", PropertyType.EMBEDDEDMAP);

    session.execute(
        "CREATE INDEX " + className + ".themap ON " + className + "(themap by key) NOTUNIQUE");

    for (var i = 0; i < 100; i++) {
      session.begin();
      var theMap = session.newEmbeddedMap();
      theMap.put("key" + i, "val" + i);

      var elem1 = session.newEntity(className);
      elem1.setProperty("themap", theMap);

      session.commit();
    }

    session.begin();
    try (var result =
        session.query("select from " + className + " where themap CONTAINSKEY ?", "key10")) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Map<String, Object> map = item.getProperty("themap");
      Assert.assertEquals("key10", map.keySet().iterator().next());
      Assert.assertFalse(result.hasNext());
      Assert.assertTrue(
          result.getExecutionPlan().getSteps().stream()
              .anyMatch(x -> x instanceof FetchFromIndexStep));
    }
    session.commit();
  }

  @Test
  public void testMapByKeyIndexMultiple() {
    var className = "testMapBjyKeyIndexMultiple";

    var clazz1 = session.createClassIfNotExist(className);
    clazz1.createProperty("themap", PropertyType.EMBEDDEDMAP);
    clazz1.createProperty("thestring", PropertyType.STRING);

    session.execute(
        "CREATE INDEX "
            + className
            + ".themap_thestring ON "
            + className
            + "(themap by key, thestring) NOTUNIQUE");

    for (var i = 0; i < 100; i++) {
      session.begin();
      var theMap = session.newEmbeddedMap();

      theMap.put("key" + i, "val" + i);

      var elem1 = session.newEntity(className);
      elem1.setProperty("themap", theMap);
      elem1.setProperty("thestring", "thestring" + i);

      session.commit();
    }

    session.begin();
    try (var result =
        session.query(
            "select from " + className + " where themap CONTAINSKEY ? AND thestring = ?",
            "key10",
            "thestring10")) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Map<String, Object> map = item.getProperty("themap");
      Assert.assertEquals("key10", map.keySet().iterator().next());
      Assert.assertFalse(result.hasNext());
      Assert.assertTrue(
          result.getExecutionPlan().getSteps().stream()
              .anyMatch(x -> x instanceof FetchFromIndexStep));
    }
    session.commit();
  }

  @Test
  public void testMapByValueIndex() {
    var className = "testMapByValueIndex";

    var clazz1 = session.createClassIfNotExist(className);
    var prop = clazz1.createProperty("themap", PropertyType.EMBEDDEDMAP,
        PropertyType.STRING);

    session.execute(
        "CREATE INDEX " + className + ".themap ON " + className + "(themap by value) NOTUNIQUE");

    for (var i = 0; i < 100; i++) {
      session.begin();
      Map<String, Object> theMap = new HashMap<>();
      theMap.put("key" + i, "val" + i);
      var elem1 = session.newEntity(className);
      elem1.newEmbeddedMap("themap", theMap);
      session.commit();
    }

    session.begin();
    try (var result =
        session.query("select from " + className + " where themap CONTAINSVALUE ?", "val10")) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Map<String, Object> map = item.getProperty("themap");
      Assert.assertEquals("key10", map.keySet().iterator().next());
      Assert.assertFalse(result.hasNext());
      Assert.assertTrue(
          result.getExecutionPlan().getSteps().stream()
              .anyMatch(x -> x instanceof FetchFromIndexStep));
    }
    session.commit();
  }

  @Test
  public void testListOfMapsContains() {
    var className = "testListOfMapsContains";

    var clazz1 = session.createClassIfNotExist(className);
    clazz1.createProperty("thelist", PropertyType.EMBEDDEDLIST, PropertyType.EMBEDDEDMAP);

    session.begin();
    session.execute("INSERT INTO " + className + " SET thelist = [{name:\"Jack\"}]").close();
    session.execute("INSERT INTO " + className + " SET thelist = [{name:\"Joe\"}]").close();
    session.commit();

    session.begin();
    try (var result =
        session.query("select from " + className + " where thelist CONTAINS ( name = ?)", "Jack")) {
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
    }
    session.commit();
  }

  @Test
  public void testOrderByWithCollate() {
    var className = "testOrderByWithCollate";

    session.createClassIfNotExist(className);

    session.begin();
    session.execute("INSERT INTO " + className + " SET name = 'A', idx = 0").close();
    session.execute("INSERT INTO " + className + " SET name = 'C', idx = 2").close();
    session.execute("INSERT INTO " + className + " SET name = 'E', idx = 4").close();
    session.execute("INSERT INTO " + className + " SET name = 'b', idx = 1").close();
    session.execute("INSERT INTO " + className + " SET name = 'd', idx = 3").close();
    session.commit();

    session.begin();
    try (var result =
        session.query("select from " + className + " order by name asc collate ci")) {
      for (var i = 0; i < 5; i++) {
        Assert.assertTrue(result.hasNext());
        var item = result.next();
        int val = item.getProperty("idx");
        Assert.assertEquals(i, val);
      }
      Assert.assertFalse(result.hasNext());
    }
    session.commit();
  }

  @Test
  public void testContainsEmptyCollection() {
    var className = "testContainsEmptyCollection";

    session.createClassIfNotExist(className);

    session.begin();
    session.execute("INSERT INTO " + className + " content {\"name\": \"jack\", \"age\": 22}")
        .close();
    session.execute(
            "INSERT INTO "
                + className
                + " content {\"name\": \"rose\", \"age\": 22, \"test\": [[]]}")
        .close();
    session.execute(
            "INSERT INTO "
                + className
                + " content {\"name\": \"rose\", \"age\": 22, \"test\": [[1]]}")
        .close();
    session.execute(
            "INSERT INTO "
                + className
                + " content {\"name\": \"pete\", \"age\": 22, \"test\": [{}]}")
        .close();
    session.execute(
            "INSERT INTO "
                + className
                + " content {\"name\": \"david\", \"age\": 22, \"test\": [\"hello\"]}")
        .close();
    session.commit();

    session.begin();
    try (var result = session.query("select from " + className + " where test contains []")) {
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
    }
    session.commit();
  }

  @Test
  public void testContainsCollection() {
    var className = "testContainsCollection";

    session.createClassIfNotExist(className);

    session.begin();
    session.execute("INSERT INTO " + className + " content {\"name\": \"jack\", \"age\": 22}")
        .close();
    session.execute(
            "INSERT INTO "
                + className
                + " content {\"name\": \"rose\", \"age\": 22, \"test\": [[]]}")
        .close();
    session.execute(
            "INSERT INTO "
                + className
                + " content {\"name\": \"rose\", \"age\": 22, \"test\": [[1]]}")
        .close();
    session.execute(
            "INSERT INTO "
                + className
                + " content {\"name\": \"pete\", \"age\": 22, \"test\": [{}]}")
        .close();
    session.execute(
            "INSERT INTO "
                + className
                + " content {\"name\": \"david\", \"age\": 22, \"test\": [\"hello\"]}")
        .close();
    session.commit();

    session.begin();
    try (var result = session.query("select from " + className + " where test contains [1]")) {
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
    }
    session.commit();
  }

  @Test
  public void testHeapLimitForOrderBy() {
    Long oldValue = GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getValueAsLong();
    try {
      GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.setValue(3);

      var className = "testHeapLimitForOrderBy";

      session.createClassIfNotExist(className);

      session.begin();
      session.execute("INSERT INTO " + className + " set name = 'a'").close();
      session.execute("INSERT INTO " + className + " set name = 'b'").close();
      session.execute("INSERT INTO " + className + " set name = 'c'").close();
      session.execute("INSERT INTO " + className + " set name = 'd'").close();
      session.commit();

      session.begin();
      try {
        try (var result = session.query("select from " + className + " ORDER BY name")) {
          result.forEachRemaining(x -> x.getProperty("name"));
        }
        Assert.fail();
      } catch (CommandExecutionException ex) {
        session.rollback();
        //ignore
      }
    } finally {
      GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.setValue(oldValue);
    }
  }

  @Test
  public void testXor() {
    session.begin();
    try (var result = session.query("select 15 ^ 4 as foo")) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertEquals(11, (int) item.getProperty("foo"));
      Assert.assertFalse(result.hasNext());
    }
    session.commit();
  }

  @Test
  public void testLike() {
    var className = "testLike";

    session.createClassIfNotExist(className);

    session.begin();
    session.execute("INSERT INTO " + className + " content {\"name\": \"foobarbaz\"}").close();
    session.execute("INSERT INTO " + className + " content {\"name\": \"test[]{}()|*^.test\"}")
        .close();
    session.commit();

    session.begin();
    try (var result = session.query("select from " + className + " where name LIKE 'foo%'")) {
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
    }
    try (var result =
        session.query("select from " + className + " where name LIKE '%foo%baz%'")) {
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
    }
    try (var result = session.query("select from " + className + " where name LIKE '%bar%'")) {
      Assert.assertTrue(result.hasNext());
      result.next();
      Assert.assertFalse(result.hasNext());
    }

    try (var result = session.query("select from " + className + " where name LIKE 'bar%'")) {
      Assert.assertFalse(result.hasNext());
    }

    try (var result = session.query("select from " + className + " where name LIKE '%bar'")) {
      Assert.assertFalse(result.hasNext());
    }

    var specialChars = "[]{}()|*^.";
    for (var c : specialChars.toCharArray()) {
      try (var result =
          session.query("select from " + className + " where name LIKE '%" + c + "%'")) {
        Assert.assertTrue(result.hasNext());
        result.next();
        Assert.assertFalse(result.hasNext());
      }
    }
    session.commit();
  }

  @Test
  public void testCountGroupBy() {
    // issue #9288
    var className = "testCountGroupBy";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("type", i % 2 == 0 ? "even" : "odd");
      doc.setProperty("val", i);

      session.commit();
    }

    session.begin();
    var result = session.query("select count(val) as count from " + className + " limit 3");
    printExecutionPlan(result);

    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertEquals(10L, (long) item.getProperty("count"));
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  @Ignore
  public void testTimeout() {
    var className = "testTimeout";
    final var funcitonName = getClass().getSimpleName() + "_sleep";
    session.getMetadata().getSchema().createClass(className);

    SQLEngine
        .registerFunction(
            funcitonName,
            new SQLFunction() {

              @Override
              public Object execute(
                  Object iThis,
                  Result iCurrentRecord,
                  Object iCurrentResult,
                  Object[] iParams,
                  CommandContext iContext) {
                try {
                  Thread.sleep(5);
                } catch (InterruptedException e) {
                  //ignore
                }
                return null;
              }

              @Override
              public void config(Object[] configuredParameters) {
              }

              @Override
              public boolean aggregateResults() {
                return false;
              }

              @Override
              public boolean filterResult() {
                return false;
              }

              @Override
              public String getName(DatabaseSession session) {
                return funcitonName;
              }

              @Override
              public int getMinParams() {
                return 0;
              }

              @Override
              public int getMaxParams(DatabaseSession session) {
                return 0;
              }

              @Override
              public String getSyntax(DatabaseSession session) {
                return "";
              }

              @Override
              public Object getResult() {
                return null;
              }

              @Override
              public void setResult(Object iResult) {
              }
            });
    for (var i = 0; i < 3; i++) {
      var doc = session.newInstance(className);
      doc.setProperty("type", i % 2 == 0 ? "even" : "odd");
      doc.setProperty("val", i);

    }
    try (var result =
        session.query("select " + funcitonName + "(), * from " + className + " timeout 1")) {
      while (result.hasNext()) {
        result.next();
      }
      Assert.fail();
    } catch (TimeoutException ex) {
      //ignore
    }

    try (var result =
        session.query("select " + funcitonName + "(), * from " + className + " timeout 1000")) {
      while (result.hasNext()) {
        result.next();
      }
    } catch (TimeoutException ex) {
      Assert.fail();
    }
  }

  @Test
  public void testSimpleRangeQueryWithIndexGTE() {
    final var className = "testSimpleRangeQueryWithIndexGTE";
    final var clazz = session.getMetadata().getSchema().getOrCreateClass(className);
    final var prop = clazz.createProperty("name", PropertyType.STRING);
    prop.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    for (var i = 0; i < 10; i++) {
      session.begin();
      final var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);

      session.commit();
    }
    session.begin();
    final var result = session.query("select from " + className + " WHERE name >= 'name5'");
    printExecutionPlan(result);

    for (var i = 0; i < 5; i++) {
      Assert.assertTrue(result.hasNext());
      result.next();
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testSimpleRangeQueryWithIndexLTE() {
    final var className = "testSimpleRangeQueryWithIndexLTE";
    final var clazz = session.getMetadata().getSchema().getOrCreateClass(className);
    final var prop = clazz.createProperty("name", PropertyType.STRING);
    prop.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    for (var i = 0; i < 10; i++) {
      session.begin();
      final var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);

      session.commit();
    }

    session.begin();
    final var result = session.query("select from " + className + " WHERE name <= 'name5'");
    printExecutionPlan(result);

    for (var i = 0; i < 6; i++) {
      Assert.assertTrue(result.hasNext());
      result.next();
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testSimpleRangeQueryWithOutIndex() {
    final var className = "testSimpleRangeQueryWithOutIndex";
    final var clazz = session.getMetadata().getSchema().getOrCreateClass(className);
    final var prop = clazz.createProperty("name", PropertyType.STRING);
    // Hash Index skipped for range query
    prop.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    for (var i = 0; i < 10; i++) {
      session.begin();
      final var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);

      session.commit();
    }

    session.begin();
    final var result = session.query("select from " + className + " WHERE name >= 'name5'");
    printExecutionPlan(result);

    for (var i = 0; i < 5; i++) {
      Assert.assertTrue(result.hasNext());
      result.next();
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testComplexIndexChain() {

    // A -b-> B -c-> C -d-> D.name
    //               C.name

    var classNamePrefix = "testComplexIndexChain_";
    var a = session.getMetadata().getSchema().createClass(classNamePrefix + "A");
    var b = session.getMetadata().getSchema().createClass(classNamePrefix + "B");
    var c = session.getMetadata().getSchema().createClass(classNamePrefix + "C");
    var d = session.getMetadata().getSchema().createClass(classNamePrefix + "D");

    a.createProperty("b", PropertyType.LINK, b)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    b.createProperty("c", PropertyType.LINK, c)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    c.createProperty("d", PropertyType.LINK, d)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    c.createProperty("name", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    d.createProperty("name", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    session.begin();
    var dDoc = session.newEntity(d.getName());
    dDoc.setProperty("name", "foo");

    var cDoc = session.newEntity(c.getName());
    cDoc.setProperty("name", "foo");
    cDoc.setProperty("d", dDoc);

    var bDoc = session.newEntity(b.getName());
    bDoc.setProperty("c", cDoc);

    var aDoc = session.newEntity(a.getName());
    aDoc.setProperty("b", bDoc);

    session.commit();

    session.begin();
    try (var rs =
        session.query(
            "SELECT FROM "
                + classNamePrefix
                + "A WHERE b.c.name IN ['foo'] AND b.c.d.name IN ['foo']")) {
      Assert.assertTrue(rs.hasNext());
    }

    try (var rs =
        session.query(
            "SELECT FROM " + classNamePrefix + "A WHERE b.c.name = 'foo' AND b.c.d.name = 'foo'")) {
      Assert.assertTrue(rs.hasNext());
      Assert.assertTrue(
          rs.getExecutionPlan().getSteps().stream()
              .anyMatch(x -> x instanceof FetchFromIndexStep));
    }
    session.commit();
  }

  @Test
  public void testIndexWithSubquery() {
    var classNamePrefix = "testIndexWithSubquery_";
    session.execute("create class " + classNamePrefix + "Ownership extends V abstract;").close();
    session.execute("create class " + classNamePrefix + "User extends V;").close();
    session.execute("create property " + classNamePrefix + "User.id String;").close();
    session.execute(
            "create index "
                + classNamePrefix
                + "User.id ON "
                + classNamePrefix
                + "User(id) unique;")
        .close();
    session.execute(
            "create class " + classNamePrefix + "Report extends " + classNamePrefix + "Ownership;")
        .close();
    session.execute("create property " + classNamePrefix + "Report.id String;").close();
    session.execute("create property " + classNamePrefix + "Report.label String;").close();
    session.execute("create property " + classNamePrefix + "Report.format String;").close();
    session.execute("create property " + classNamePrefix + "Report.source String;").close();
    session.execute("create class " + classNamePrefix + "hasOwnership extends E;").close();

    session.begin();
    session.execute("insert into " + classNamePrefix + "User content {id:\"admin\"};");
    session.execute(
            "insert into "
                + classNamePrefix
                + "Report content {format:\"PDF\", id:\"rep1\", label:\"Report 1\","
                + " source:\"Report1.src\"};")
        .close();
    session.execute(
            "insert into "
                + classNamePrefix
                + "Report content {format:\"CSV\", id:\"rep2\", label:\"Report 2\","
                + " source:\"Report2.src\"};")
        .close();
    session.execute(
            "create edge "
                + classNamePrefix
                + "hasOwnership from (select from "
                + classNamePrefix
                + "User) to (select from "
                + classNamePrefix
                + "Report);")
        .close();
    session.commit();

    session.begin();
    try (var rs =
        session.query(
            "select from "
                + classNamePrefix
                + "Report where id in (select out('"
                + classNamePrefix
                + "hasOwnership').id from "
                + classNamePrefix
                + "User where id = 'admin');")) {
      Assert.assertTrue(rs.hasNext());
      rs.next();
      Assert.assertTrue(rs.hasNext());
      rs.next();
      Assert.assertFalse(rs.hasNext());
    }
    session.commit();

    session.execute(
            "create index "
                + classNamePrefix
                + "Report.id ON "
                + classNamePrefix
                + "Report(id) unique;")
        .close();

    session.begin();
    try (var rs =
        session.query(
            "select from "
                + classNamePrefix
                + "Report where id in (select out('"
                + classNamePrefix
                + "hasOwnership').id from "
                + classNamePrefix
                + "User where id = 'admin');")) {
      Assert.assertTrue(rs.hasNext());
      rs.next();
      Assert.assertTrue(rs.hasNext());
      rs.next();
      Assert.assertFalse(rs.hasNext());
    }
    session.commit();
  }

  /// Checks that composite index is used if we use both inV and outV functions to select the edge
  /// needed.
  @Test
  public void testCompositeIndexWithInVOutVFunctions() {
    var schema = session.getSchema();

    var vertexClass = schema.createVertexClass("TestIndexWithEdgesFunctionsVertex");
    var edgeClass = schema.createEdgeClass("TestIndexWithEdgesFunctionsEdge");

    edgeClass.createIndex("EdgeIndex", INDEX_TYPE.NOTUNIQUE, Edge.DIRECTION_IN, Edge.DIRECTION_OUT);

    var rids = session.computeInTx(transaction -> {
      var v1 = transaction.newVertex(vertexClass);
      var v2 = transaction.newVertex(vertexClass);

      var edge = v1.addStateFulEdge(v2, edgeClass);

      return new RID[]{v1.getIdentity(), v2.getIdentity(), edge.getIdentity()};
    });

    session.executeInTx(transaction -> {
      try (var rs = transaction.query(
          "select from TestIndexWithEdgesFunctionsEdge where inV() = :inV and outV() = :outV",
          Map.of("outV", rids[0], "inV", rids[1]))) {

        var resList = rs.toStatefulEdgeList();
        Assert.assertEquals(1, resList.size());
        Assert.assertEquals(rids[2], resList.getFirst().getIdentity());

        var executionPlan = rs.getExecutionPlan();
        var steps = executionPlan.getSteps();
        Assert.assertFalse(steps.isEmpty());

        var fetchFromIndex = steps.getFirst();
        Assert.assertTrue(fetchFromIndex instanceof FetchFromIndexStep);

        var keyCondition = ((FetchFromIndexStep) fetchFromIndex).getDesc().getKeyCondition();
        Assert.assertTrue(keyCondition instanceof SQLAndBlock);
        var sqlAndBlock = (SQLAndBlock) keyCondition;
        Assert.assertEquals(2, sqlAndBlock.getSubBlocks().size());
        var firstExpression = sqlAndBlock.getSubBlocks().getFirst();
        Assert.assertTrue(firstExpression instanceof SQLBinaryCondition);

        var firstBinaryCondition = (SQLBinaryCondition) firstExpression;
        Assert.assertTrue(
            firstBinaryCondition.getLeft() instanceof SQLGetInternalPropertyExpression);
        Assert.assertEquals("in", firstBinaryCondition.getLeft().toString());
        Assert.assertEquals(":inV", firstBinaryCondition.getRight().toString());
        Assert.assertTrue(firstBinaryCondition.getOperator() instanceof SQLEqualsOperator);

        var secondExpression = sqlAndBlock.getSubBlocks().getLast();
        Assert.assertTrue(secondExpression instanceof SQLBinaryCondition);

        var secondBinaryCondition = (SQLBinaryCondition) secondExpression;
        Assert.assertTrue(
            secondBinaryCondition.getLeft() instanceof SQLGetInternalPropertyExpression);
        Assert.assertEquals("out", secondBinaryCondition.getLeft().toString());
        Assert.assertEquals(":outV", secondBinaryCondition.getRight().toString());
        Assert.assertTrue(secondBinaryCondition.getOperator() instanceof SQLEqualsOperator);
      }
    });
  }

  @Test
  public void testInVOutVFunctionsWithoutIndex() {
    var schema = session.getSchema();

    var vertexClass = schema.createVertexClass("TestInVOutVFunctionsWithoutIndexVertex");
    var edgeClass = schema.createEdgeClass("TestInVOutVFunctionsWithoutIndexEdge");

    var rids = session.computeInTx(transaction -> {
      var v1 = transaction.newVertex(vertexClass);
      var v2 = transaction.newVertex(vertexClass);

      var edge = v1.addStateFulEdge(v2, edgeClass);

      return new RID[]{v1.getIdentity(), v2.getIdentity(), edge.getIdentity()};
    });

    session.executeInTx(transaction -> {
      try (var rs = transaction.query(
          "select from TestInVOutVFunctionsWithoutIndexEdge where inV() = :inV and outV() = :outV",
          Map.of("outV", rids[0], "inV", rids[1]))) {

        var resList = rs.toStatefulEdgeList();
        Assert.assertEquals(1, resList.size());
        Assert.assertEquals(rids[2], resList.getFirst().getIdentity());
      }
    });
  }

  @Test
  public void testOutEStateFullEdgesIndexUsageInGraph() {
    var schema = session.getSchema();

    var vertexClass = schema.createVertexClass("TestOutEStateFullIndexUsageInGraphVertex");
    var edgeClass = schema.createEdgeClass("TestOutEStateFullIndexUsageInGraphEdge");

    var edgeClassName = edgeClass.getName();
    var propertyName = Vertex.getEdgeLinkFieldName(Direction.OUT, edgeClassName);
    var oudEdgesProperty = vertexClass.createProperty(propertyName,
        PropertyType.LINKBAG);

    var vertexClassName = vertexClass.getName();

    var indexName = "TestOutEStateFullIndexUsageInGraphIndex";
    vertexClass.createIndex(indexName, INDEX_TYPE.NOTUNIQUE, oudEdgesProperty.getName());

    var rids = session.computeInTx(transaction -> {
      Vertex v1 = null;
      Vertex v2 = null;

      StatefulEdge edge = null;

      for (var i = 0; i < 10; i++) {
        v1 = transaction.newVertex(vertexClass);
        v2 = transaction.newVertex(vertexClass);

        edge = v1.addStateFulEdge(v2, edgeClass);
      }

      return new RID[]{v1.getIdentity(), v2.getIdentity(), edge.getIdentity()};
    });

    session.executeInTx(transaction -> {
      try (var rs = transaction.query("select from " + vertexClassName + " where "
          + "outE('" + edgeClassName + "') contains :outE", Map.of("outE", rids[2]))) {
        var resList = rs.toVertexList();

        Assert.assertEquals(1, resList.size());
        Assert.assertEquals(rids[0], resList.getFirst().getIdentity());

        var executionPlan = rs.getExecutionPlan();
        var steps = executionPlan.getSteps();
        Assert.assertFalse(steps.isEmpty());

        var sourceStep = steps.getFirst();
        Assert.assertTrue(sourceStep instanceof FetchFromIndexStep);
        var fetchFromIndexStep = (FetchFromIndexStep) sourceStep;
        Assert.assertEquals(indexName, fetchFromIndexStep.getDesc().getIndex().getName());

        var keyCondition = fetchFromIndexStep.getDesc().getKeyCondition();
        Assert.assertTrue(keyCondition instanceof SQLAndBlock);
        var sqlAndBlock = (SQLAndBlock) keyCondition;
        Assert.assertEquals(1, sqlAndBlock.getSubBlocks().size());

        var expression = sqlAndBlock.getSubBlocks().getFirst();
        Assert.assertTrue(expression instanceof SQLContainsCondition);

        var containsCondition = (SQLContainsCondition) expression;
        Assert.assertTrue(
            containsCondition.getLeft() instanceof SQLGetInternalPropertyExpression);
        Assert.assertEquals(propertyName, containsCondition.getLeft().toString());
        Assert.assertEquals(":outE", containsCondition.getRight().toString());
      }
    });
  }

  @Test
  public void testOutEStateFullEdgesWithoutIndexUsageInGraph() {
    var schema = session.getSchema();

    var vertexClass = schema.createVertexClass("TestOutEStateFullWithoutIndexUsageInGraphVertex");
    var edgeClass = schema.createEdgeClass("TestOutEStateFullWithoutIndexUsageInGraphEdge");

    var edgeClassName = edgeClass.getName();
    var vertexClassName = vertexClass.getName();
    var rids = session.computeInTx(transaction -> {
      Vertex v1 = null;
      Vertex v2 = null;

      StatefulEdge edge = null;

      for (var i = 0; i < 10; i++) {
        v1 = transaction.newVertex(vertexClass);
        v2 = transaction.newVertex(vertexClass);

        edge = v1.addStateFulEdge(v2, edgeClass);
      }

      return new RID[]{v1.getIdentity(), v2.getIdentity(), edge.getIdentity()};
    });

    session.executeInTx(transaction -> {
      try (var rs = transaction.query("select from " + vertexClassName + " where "
          + "outE('" + edgeClassName + "') contains :outE", Map.of("outE", rids[2]))) {
        var resList = rs.toVertexList();

        Assert.assertEquals(1, resList.size());
        Assert.assertEquals(rids[0], resList.getFirst().getIdentity());
      }
    });
  }

  @Test
  public void testInEStateFullEdgesIndexUsageInGraph() {
    var schema = session.getSchema();

    var vertexClass = schema.createVertexClass("TestInEStateFullIndexUsageInGraphVertex");
    var edgeClass = schema.createEdgeClass("TestInEStateFullIndexUsageInGraphEdge");

    var edgeClassName = edgeClass.getName();
    var inEdgesProperty = vertexClass.createProperty(
        Vertex.getEdgeLinkFieldName(Direction.IN, edgeClassName),
        PropertyType.LINKBAG);

    var propertyName = inEdgesProperty.getName();
    var vertexClassName = vertexClass.getName();

    var indexName = "TestInEStateFullIndexUsageInGraphIndex";
    vertexClass.createIndex(indexName, INDEX_TYPE.NOTUNIQUE, inEdgesProperty.getName());

    var rids = session.computeInTx(transaction -> {
      Vertex v1 = null;
      Vertex v2 = null;

      StatefulEdge edge = null;

      for (var i = 0; i < 10; i++) {
        v1 = transaction.newVertex(vertexClass);
        v2 = transaction.newVertex(vertexClass);

        edge = v1.addStateFulEdge(v2, edgeClass);
      }

      return new RID[]{v1.getIdentity(), v2.getIdentity(), edge.getIdentity()};
    });

    session.executeInTx(transaction -> {
      try (var rs = transaction.query("select from " + vertexClassName + " where "
          + "inE('" + edgeClassName + "') contains :inE", Map.of("inE", rids[2]))) {
        var resList = rs.toVertexList();

        Assert.assertEquals(1, resList.size());
        Assert.assertEquals(rids[1], resList.getFirst().getIdentity());

        var executionPlan = rs.getExecutionPlan();
        var steps = executionPlan.getSteps();
        Assert.assertFalse(steps.isEmpty());

        var sourceStep = steps.getFirst();
        Assert.assertTrue(sourceStep instanceof FetchFromIndexStep);
        var fetchFromIndexStep = (FetchFromIndexStep) sourceStep;
        Assert.assertEquals(indexName, fetchFromIndexStep.getDesc().getIndex().getName());

        var keyCondition = fetchFromIndexStep.getDesc().getKeyCondition();
        Assert.assertTrue(keyCondition instanceof SQLAndBlock);
        var sqlAndBlock = (SQLAndBlock) keyCondition;
        Assert.assertEquals(1, sqlAndBlock.getSubBlocks().size());

        var expression = sqlAndBlock.getSubBlocks().getFirst();
        Assert.assertTrue(expression instanceof SQLContainsCondition);

        var containsCondition = (SQLContainsCondition) expression;
        Assert.assertTrue(
            containsCondition.getLeft() instanceof SQLGetInternalPropertyExpression);
        Assert.assertEquals(propertyName, containsCondition.getLeft().toString());
        Assert.assertEquals(":inE", containsCondition.getRight().toString());
      }
    });
  }

  @Test
  public void testBothEStateFullEdgesWithoutIndex() {
    var schema = session.getSchema();

    var vertexClass = schema.createVertexClass("TestBothEStateFullVertex");
    var edgeClass = schema.createEdgeClass("TestBothEStateFullEdge");

    var edgeClassName = edgeClass.getName();
    var vertexClassName = vertexClass.getName();

    var rids = session.computeInTx(transaction -> {
      Vertex v1 = null;
      Vertex v2 = null;

      StatefulEdge edge = null;

      for (var i = 0; i < 10; i++) {
        v1 = transaction.newVertex(vertexClass);
        v2 = transaction.newVertex(vertexClass);

        edge = v1.addStateFulEdge(v2, edgeClass);
      }

      return new RID[]{v1.getIdentity(), v2.getIdentity(), edge.getIdentity()};
    });

    session.executeInTx(transaction -> {
      try (var rs = transaction.query("select from " + vertexClassName + " where "
          + "bothE('" + edgeClassName + "') contains :bothE", Map.of("bothE", rids[2]))) {
        var resList = rs.toVertexList();

        Assert.assertEquals(2, resList.size());
        Assert.assertEquals(Set.of(rids[0], rids[1]), new HashSet<>(resList));
      }
    });
  }

  @Test
  public void testBothEStateFullEdgesIndexUsageInGraphOneIndexIn() {
    var schema = session.getSchema();

    var vertexClass = schema.createVertexClass("TestBothEStateFullIndexUsageInGraphVertex");
    var edgeClass = schema.createEdgeClass("TestBothEStateFullIndexUsageInGraphEdge");

    var edgeClassName = edgeClass.getName();
    var oudEdgesProperty = vertexClass.createProperty(
        Vertex.getEdgeLinkFieldName(Direction.IN, edgeClassName),
        PropertyType.LINKBAG);

    var vertexClassName = vertexClass.getName();

    var indexName = "TestBothEStateFullIndexUsageInGraphIndex";
    vertexClass.createIndex(indexName, INDEX_TYPE.NOTUNIQUE, oudEdgesProperty.getName());

    var rids = session.computeInTx(transaction -> {
      Vertex v1 = null;
      Vertex v2 = null;

      StatefulEdge edge = null;

      for (var i = 0; i < 10; i++) {
        v1 = transaction.newVertex(vertexClass);
        v2 = transaction.newVertex(vertexClass);

        edge = v1.addStateFulEdge(v2, edgeClass);
      }

      return new RID[]{v1.getIdentity(), v2.getIdentity(), edge.getIdentity()};
    });

    session.executeInTx(transaction -> {
      try (var rs = transaction.query("select from " + vertexClassName + " where "
          + "bothE('" + edgeClassName + "') contains :bothE", Map.of("bothE", rids[2]))) {
        var resList = rs.toVertexList();

        Assert.assertEquals(2, resList.size());
        Assert.assertEquals(Set.of(rids[0], rids[1]), new HashSet<>(resList));
      }
    });
  }

  @Test
  public void testBothEStateFullEdgesIndexUsageInGraphOneIndexOut() {
    var schema = session.getSchema();

    var vertexClass = schema.createVertexClass("TestBothEStateFullIndexUsageInGraphVertex");
    var edgeClass = schema.createEdgeClass("TestBothEStateFullIndexUsageInGraphEdge");

    var edgeClassName = edgeClass.getName();
    var oudEdgesProperty = vertexClass.createProperty(
        Vertex.getEdgeLinkFieldName(Direction.OUT, edgeClassName),
        PropertyType.LINKBAG);

    var vertexClassName = vertexClass.getName();

    var indexName = "TestBothEStateFullIndexUsageInGraphIndex";
    vertexClass.createIndex(indexName, INDEX_TYPE.NOTUNIQUE, oudEdgesProperty.getName());

    var rids = session.computeInTx(transaction -> {
      Vertex v1 = null;
      Vertex v2 = null;

      StatefulEdge edge = null;

      for (var i = 0; i < 10; i++) {
        v1 = transaction.newVertex(vertexClass);
        v2 = transaction.newVertex(vertexClass);

        edge = v1.addStateFulEdge(v2, edgeClass);
      }

      return new RID[]{v1.getIdentity(), v2.getIdentity(), edge.getIdentity()};
    });

    session.executeInTx(transaction -> {
      try (var rs = transaction.query("select from " + vertexClassName + " where "
          + "bothE('" + edgeClassName + "') contains :bothE", Map.of("bothE", rids[2]))) {
        var resList = rs.toVertexList();

        Assert.assertEquals(2, resList.size());
        Assert.assertEquals(Set.of(rids[0], rids[1]), new HashSet<>(resList));
      }
    });
  }

  @Test
  public void testBothEStateFullEdgesIndexUsageInGraphTwoIndexes() {
    var schema = session.getSchema();

    var vertexClass = schema.createVertexClass("TestBothEStateFullIndexUsageInGraphVertex");
    var edgeClass = schema.createEdgeClass("TestBothEStateFullIndexUsageInGraphEdge");

    var edgeClassName = edgeClass.getName();

    var outEdgesProperty = vertexClass.createProperty(
        Vertex.getEdgeLinkFieldName(Direction.OUT, edgeClassName),
        PropertyType.LINKBAG);
    var inEdgesProperty = vertexClass.createProperty(
        Vertex.getEdgeLinkFieldName(Direction.IN, edgeClassName),
        PropertyType.LINKBAG);

    var vertexClassName = vertexClass.getName();

    var outIndexName = "TestBothEStateFullIndexUsageInGraphIndexOutIndex";
    vertexClass.createIndex(outIndexName, INDEX_TYPE.NOTUNIQUE, outEdgesProperty.getName());

    var inIndexName = "TestBothEStateFullIndexUsageInGraphIndexInIndex";
    vertexClass.createIndex(inIndexName, INDEX_TYPE.NOTUNIQUE, inEdgesProperty.getName());

    var rids = session.computeInTx(transaction -> {
      Vertex v1 = null;
      Vertex v2 = null;

      StatefulEdge edge = null;

      for (var i = 0; i < 10; i++) {
        v1 = transaction.newVertex(vertexClass);
        v2 = transaction.newVertex(vertexClass);

        edge = v1.addStateFulEdge(v2, edgeClass);
      }

      return new RID[]{v1.getIdentity(), v2.getIdentity(), edge.getIdentity()};
    });

    session.executeInTx(transaction -> {
      try (var rs = transaction.query("select from " + vertexClassName + " where "
          + "bothE('" + edgeClassName + "') contains :bothE", Map.of("bothE", rids[2]))) {
        var resList = rs.toVertexList();

        Assert.assertEquals(2, resList.size());
        Assert.assertEquals(Set.of(rids[0], rids[1]), new HashSet<>(resList));

        var executionPlan = rs.getExecutionPlan();
        var steps = executionPlan.getSteps();
        Assert.assertFalse(steps.isEmpty());
        var first = steps.getFirst();
        Assert.assertTrue(first instanceof ParallelExecStep);

        var parallelExecStep = (ParallelExecStep) first;
        var parallelSteps = parallelExecStep.getSubExecutionPlans();

        Assert.assertEquals(2, parallelSteps.size());

        var firstParallelStep = parallelSteps.getFirst().getSteps().getFirst();
        Assert.assertTrue(firstParallelStep instanceof FetchFromIndexStep);
        var firstParallelIndexStep = (FetchFromIndexStep) firstParallelStep;

        var secondParallelStep = parallelSteps.getLast().getSteps().getFirst();
        Assert.assertTrue(secondParallelStep instanceof FetchFromIndexStep);
        var secondParallelIndexStep = (FetchFromIndexStep) secondParallelStep;

        var indexNames = Set.of(firstParallelIndexStep.getDesc().getIndex().getName(),
            secondParallelIndexStep.getDesc().getIndex().getName());

        Assert.assertEquals(Set.of(outIndexName, inIndexName), indexNames);
      }
    });
  }


  @Test
  public void testExclude() {
    var className = "TestExclude";
    session.getMetadata().getSchema().createClass(className);
    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("name", "foo");
    doc.setProperty("surname", "bar");

    session.commit();

    session.begin();
    var result = session.query("select *, !surname from " + className);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertNotNull(item);
    Assert.assertEquals("foo", item.getProperty("name"));
    Assert.assertNull(item.getProperty("surname"));

    printExecutionPlan(result);
    result.close();
    session.commit();
  }

  @Test
  public void testOrderByLet() {
    var className = "testOrderByLet";
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("name", "abbb");

    doc = session.newInstance(className);
    doc.setProperty("name", "baaa");

    session.commit();

    session.begin();
    try (var result =
        session.query(
            "select from "
                + className
                + " LET $order = name.substring(1) ORDER BY $order ASC LIMIT 1")) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("baaa", item.getProperty("name"));
    }
    try (var result =
        session.query(
            "select from "
                + className
                + " LET $order = name.substring(1) ORDER BY $order DESC LIMIT 1")) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("abbb", item.getProperty("name"));
    }
    session.commit();
  }

  @Test
  public void testMapToJson() {
    var className = "testMapToJson";
    session.execute("create class " + className).close();
    session.execute("create property " + className + ".themap embeddedmap").close();

    session.begin();
    session.execute(
            "insert into "
                + className
                + " set name = 'foo', themap = {\"foo bar\":\"baz\", \"riz\":\"faz\"}")
        .close();
    session.commit();

    session.begin();
    try (var rs = session.query("select themap.tojson() as x from " + className)) {
      Assert.assertTrue(rs.hasNext());
      var item = rs.next();
      Assert.assertTrue(((String) item.getProperty("x")).contains("foo bar"));
    }
    session.commit();
  }

  @Test
  public void testOptimizedCountQuery() {
    var className = "testOptimizedCountQuery";
    session.execute("create class " + className).close();
    session.execute("create property " + className + ".field boolean").close();
    session.execute("create index " + className + ".field on " + className + "(field) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("insert into " + className + " set field=true").close();
    session.commit();

    session.begin();
    try (var rs =
        session.query("select count(*) as count from " + className + " where field=true")) {
      Assert.assertTrue(rs.hasNext());
      var item = rs.next();
      Assert.assertEquals(1L, (long) item.getProperty("count"));
      Assert.assertFalse(rs.hasNext());
    }
    session.commit();
  }

  @Test
  public void testAsSetKeepsOrderWithExpand() {
    // init classes
    session.activateOnCurrentThread();

    var car = session.createVertexClass("Car");
    var engine = session.createVertexClass("Engine");
    var body = session.createVertexClass("BodyType");
    var eng = session.createEdgeClass("eng");
    var bt = session.createEdgeClass("bt");
    session.begin();

    var diesel = session.newVertex(engine);
    diesel.setProperty("name", "diesel");
    var gasoline = session.newVertex(engine);
    gasoline.setProperty("name", "gasoline");
    var microwave = session.newVertex(engine);
    microwave.setProperty("name", "EV");

    var coupe = session.newVertex(body);
    coupe.setProperty("name", "coupe");
    var suv = session.newVertex(body);
    suv.setProperty("name", "suv");
    session.commit();
    session.begin();
    // fill data
    var coupe1 = session.newVertex(car);
    var activeTx8 = session.getActiveTransaction();
    gasoline = activeTx8.load(gasoline);
    var activeTx7 = session.getActiveTransaction();
    coupe = activeTx7.load(coupe);

    coupe1.setProperty("name", "car1");
    coupe1.addEdge(gasoline, eng);
    coupe1.addEdge(coupe, bt);

    var coupe2 = session.newVertex(car);
    var activeTx6 = session.getActiveTransaction();
    diesel = activeTx6.load(diesel);
    var activeTx5 = session.getActiveTransaction();
    coupe = activeTx5.load(coupe);

    coupe2.setProperty("name", "car2");
    coupe2.addEdge(diesel, eng);
    coupe2.addEdge(coupe, bt);

    var mw1 = session.newVertex(car);

    var activeTx4 = session.getActiveTransaction();
    microwave = activeTx4.load(microwave);
    var activeTx3 = session.getActiveTransaction();
    suv = activeTx3.load(suv);
    mw1.setProperty("name", "microwave1");
    mw1.addEdge(microwave, eng);
    mw1.addEdge(suv, bt);

    var mw2 = session.newVertex(car);
    mw2.setProperty("name", "microwave2");
    mw2.addEdge(microwave, eng);
    mw2.addEdge(suv, bt);

    var hatch1 = session.newVertex(car);
    hatch1.setProperty("name", "hatch1");
    hatch1.addEdge(diesel, eng);
    hatch1.addEdge(suv, bt);
    session.commit();

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    gasoline = activeTx2.load(gasoline);
    var activeTx1 = session.getActiveTransaction();
    diesel = activeTx1.load(diesel);
    var activeTx = session.getActiveTransaction();
    microwave = activeTx.load(microwave);

    var identities =
        String.join(
            ",",
            Stream.of(coupe1, coupe2, mw1, mw2, hatch1)
                .map(DBRecord::getIdentity)
                .map(Object::toString)
                .toList());
    var unionAllEnginesQuery =
        "SELECT expand(unionAll($a, $a).asSet()) LET $a=(SELECT expand(out('eng')) FROM ["
            + identities
            + "])";

    var engineNames =
        session.query(unionAllEnginesQuery)
            .vertexStream()
            .map(oVertex -> oVertex.getProperty("name"))
            .collect(Collectors.toSet());

    var names = Arrays.asList(
        gasoline.getProperty("name"),
        diesel.getProperty("name"),
        microwave.getProperty("name"));
    Assert.assertEquals(names.size(), engineNames.size());
    Assert.assertEquals(new HashSet<>(names), engineNames);
    session.commit();
  }

  @Test
  public void testSelectIntersectWithExpandAndLet() {
    var schema = session.getSchema();

    schema.createVertexClass("V1");
    schema.createVertexClass("V2");
    schema.createVertexClass("V3");

    schema.createEdgeClass("link1");
    schema.createEdgeClass("link2");

    //V1 -> V2
    //V2 -> V3
    var rids = session.computeInTx(transaction -> {
      var v1 = transaction.newVertex("V1");
      var v2 = transaction.newVertex("V2");

      var v3 = transaction.newVertex("V3");

      v1.addEdge(v2, "link1");
      v2.addEdge(v3, "link2");

      var v1rid = v1.getIdentity();
      var v2rid = v2.getIdentity();
      var v3rid = v3.getIdentity();

      for (var i = 0; i < 10; i++) {
        v2 = transaction.newVertex("V2");
        v1.addEdge(v2, "link1");
      }

      for (var i = 0; i < 10; i++) {
        v2 = transaction.newVertex("V2");
        v2.addEdge(v3, "link2");
      }

      return new RID[]{v1rid, v3rid, v2rid};
    });


    final var query = "SELECT expand(intersect($a0, $b0)) "
        + "LET $a0=(SELECT expand(out('link1')) FROM :targetIds1), "
        + "$b0=(SELECT expand(in('link2')) FROM :targetIds2)";
    final Map<Object, Object> params =
        Map.of("targetIds1", rids[0], "targetIds2", rids[1]);

    session.executeInTx(transaction -> {
      final var result = transaction.query(query, params).toList();
      Assert.assertEquals(1, result.size());
      Assert.assertEquals(rids[2], result.getFirst().getIdentity());
    });

    session.executeInTx(tx -> {

      var statement = (SQLSelectStatement) SQLEngine.parse(query, session);
      var ctx = new BasicCommandContext();
      ctx.setDatabaseSession(session);
      ctx.setInputParameters(params);

      final var plan = statement.createExecutionPlan(ctx);
      new LocalResultSet(session, plan).toList();

      final var a = ctx.getVariable("$a0");
      final var b = ctx.getVariable("$b0");

      // checking that the variables have not been converted to collections, killing
      // the performance of "intersect" function.
      assertThat(a).isInstanceOf(LocalResultSet.class);
      assertThat(b).isInstanceOf(LocalResultSet.class);
    });
  }
}
