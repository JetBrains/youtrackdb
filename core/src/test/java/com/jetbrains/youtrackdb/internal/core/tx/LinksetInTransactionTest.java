package com.jetbrains.youtrackdb.internal.core.tx;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.HashSet;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class LinksetInTransactionTest extends DbTestBase {

  @Test
  public void test() {

    graph.autoExecuteInTx(
        g -> g.createSchemaClass("WithLinks").
            createSchemaProperty("links", PropertyType.LINKSET).
            createSchemaClass("Linked")
    );

    session.begin();
    /* A link must already be there */
    var withLinks1 = session.newInstance("WithLinks");
    var link1 = session.newInstance("Linked");

    Set set = new HashSet<>();
    set.add(link1);
    withLinks1.newLinkSet("links", set);

    session.commit();

    /* Only in transaction - without transaction all OK */
    session.begin();
    var activeTx2 = session.getActiveTransaction();
    withLinks1 = activeTx2.load(withLinks1);
    var activeTx1 = session.getActiveTransaction();
    link1 = activeTx1.load(link1);

    /* Add a new linked record */
    var link2 = session.newInstance("Linked");

    Set links = withLinks1.getProperty("links");
    links.add(link2);

    /* Remove all from LinkSet - if only link2 removed all OK */
    links = withLinks1.getProperty("links");
    links.remove(link1);
    links = withLinks1.getProperty("links");
    links.remove(link2);

    /* All seems OK before commit */
    links = withLinks1.getProperty("links");
    Assert.assertEquals(0, links.size());
    links = withLinks1.getProperty("links");
    Assert.assertEquals(0, links.size());
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    withLinks1 = activeTx.load(withLinks1);
    links = withLinks1.getProperty("links");
    /* Initial record was removed */
    Assert.assertFalse(links.contains(activeTx.load(link1)));
    /* Fails: why is link2 still in the set? */
    Assert.assertFalse(links.contains(activeTx.load(link2)));
    session.commit();
  }
}
