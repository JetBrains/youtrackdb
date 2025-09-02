package com.jetbrains.youtrackdb.internal.core.sql.select;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Test;

/**
 *
 */
public class TestNullFieldQuery extends DbTestBase {

  @Test
  public void testQueryNullValue() {
    session.getMetadata().getSchema().createClass("Test");
    session.begin();
    var doc = (EntityImpl) session.newEntity("Test");
    doc.setProperty("name", null);
    session.commit();

    session.begin();
    var res = session.query("select from Test where name= 'some' ");
    assertEquals(0, res.stream().count());
    session.commit();
  }

  @Test
  public void testQueryNullValueSchemaFull() {
    var clazz = session.getMetadata().getSchema().createClass("Test");
    clazz.createProperty("name", PropertyType.STRING);

    session.begin();
    var doc = (EntityImpl) session.newEntity("Test");
    doc.setProperty("name", null);
    session.commit();

    session.begin();
    var res = session.query("select from Test where name= 'some' ");
    assertEquals(0, res.stream().count());
    session.commit();
  }
}
