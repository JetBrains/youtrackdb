package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import java.util.Collection;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class SaveLinkedTypeAnyTest extends DbTestBase {

  @Test
  public void testRemoveLinkedType() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("TestRemoveLinkedType");
    classA.createProperty(session, "prop", PropertyType.EMBEDDEDLIST);

    session.begin();
    session.command("insert into TestRemoveLinkedType set prop = [4]").close();
    session.commit();

    session.begin();
    try (var result = session.query("select from TestRemoveLinkedType")) {
      Assert.assertTrue(result.hasNext());
      Collection coll = result.next().getEmbeddedList("prop");
      Assert.assertFalse(result.hasNext());
      Assert.assertEquals(coll.size(), 1);
      Assert.assertEquals(coll.iterator().next(), 4);
    }
    session.commit();
  }

  @Test
  public void testAlterRemoveLinkedType() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("TestRemoveLinkedType");
    classA.createProperty(session, "prop", PropertyType.EMBEDDEDLIST);

    session.command("alter property TestRemoveLinkedType.prop linkedtype null").close();

    session.begin();
    session.command("insert into TestRemoveLinkedType set prop = [4]").close();
    session.commit();

    session.begin();
    try (var result = session.query("select from TestRemoveLinkedType")) {
      Assert.assertTrue(result.hasNext());
      Collection coll = result.next().getProperty("prop");
      Assert.assertFalse(result.hasNext());
      Assert.assertEquals(coll.size(), 1);
      Assert.assertEquals(coll.iterator().next(), 4);
    }
    session.commit();
  }
}
