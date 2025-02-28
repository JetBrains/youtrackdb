package com.jetbrains.youtrack.db.internal.core.sql.select;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import static org.junit.Assert.assertEquals;
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
    doc.field("name", null);
    session.commit();

    session.begin();
    var res = session.query("select from Test where name= 'some' ");
    assertEquals(0, res.stream().count());
    session.commit();
  }

  @Test
  public void testQueryNullValueSchemaFull() {
    var clazz = session.getMetadata().getSchema().createClass("Test");
    clazz.createProperty(session, "name", PropertyType.STRING);

    session.begin();
    var doc = (EntityImpl) session.newEntity("Test");
    doc.field("name", null);
    session.commit();

    session.begin();
    var res = session.query("select from Test where name= 'some' ");
    assertEquals(0, res.stream().count());
    session.commit();
  }
}
