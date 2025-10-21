package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class EntityTest extends DbTestBase {

  @Test
  public void testGetSetProperty() {
    session.begin();
    var elem = session.newEntity();
    elem.setProperty("foo", "foo1");
    elem.setProperty("foo.bar", "foobar");

    var names = elem.getPropertyNames();
    Assert.assertTrue(names.contains("foo"));
    Assert.assertTrue(names.contains("foo.bar"));
    session.rollback();
  }

  @Test
  public void testLoadAndSave() {
    //noinspection unchecked
    graph.autoExecuteInTx(g -> g.schemaClass("TestLoadAndSave").fold().coalesce(
        __.unfold(),
        __.createSchemaClass("TestLoadAndSave")
    ));

    session.begin();
    var elem = session.newEntity("TestLoadAndSave");
    elem.setProperty("name", "foo");
    session.commit();

    session.begin();
    var result = session.query("select from TestLoadAndSave where name = 'foo'");
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("foo", result.next().getProperty("name"));
    result.close();
    session.commit();
  }
}
