package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.Collection;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class SaveLinkedTypeAnyTest extends DbTestBase {

  @Test
  public void testRemoveLinkedType() {
    graph.autoExecuteInTx(g ->
        g.addSchemaClass("TestRemoveLinkedType")
            .addSchemaProperty("prop", PropertyType.EMBEDDEDLIST)
    );

    session.begin();
    session.execute("insert into TestRemoveLinkedType set prop = [4]").close();
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
    graph.autoExecuteInTx(g -> g.addSchemaClass("TestRemoveLinkedType")
        .addSchemaProperty("prop", PropertyType.EMBEDDEDLIST));

    session.begin();
    session.execute("insert into TestRemoveLinkedType set prop = [4]").close();
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
