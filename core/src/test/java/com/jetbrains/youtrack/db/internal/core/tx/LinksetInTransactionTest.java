package com.jetbrains.youtrack.db.internal.core.tx;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import java.util.HashSet;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class LinksetInTransactionTest extends DbTestBase {

  @Test
  public void test() {

    session.createClass("WithLinks").createProperty("links", PropertyType.LINKSET);
    session.createClass("Linked");

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
    withLinks1 = session.bindToSession(withLinks1);
    link1 = session.bindToSession(link1);

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
    withLinks1 = session.bindToSession(withLinks1);
    links = withLinks1.getProperty("links");
    /* Initial record was removed */
    Assert.assertFalse(links.contains(link1));
    /* Fails: why is link2 still in the set? */
    Assert.assertFalse(links.contains(link2));
    session.commit();
  }
}
