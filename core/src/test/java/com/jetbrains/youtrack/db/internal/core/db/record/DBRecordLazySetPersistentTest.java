package com.jetbrains.youtrack.db.internal.core.db.record;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Set;
import org.junit.Test;

public class DBRecordLazySetPersistentTest extends DbTestBase {

  @Test
  public void test1() {
    RID orid1;
    RID orid2;

    session.activateOnCurrentThread();
    session.begin();
    {
      var doc1 = (EntityImpl) session.newEntity();
      doc1.setProperty("linkset", session.newLinkSet());
      var linkset = doc1.getLinkSet("linkset");
      var doc2 = (EntityImpl) session.newEntity();

      orid2 = doc2.getIdentity();
      linkset.add(doc2);

      orid1 = doc1.getIdentity();
      assertNotNull(orid1);
    }
    session.commit();

    session.begin();
    {
      EntityImpl doc1 = session.load(orid1);
      assertNotNull(doc1);
      Set<Identifiable> linkset = doc1.getProperty("linkset");
      assertNotNull(linkset);
      assertEquals(1, linkset.size());

      EntityImpl doc2 = session.load(orid2);
      assertNotNull(doc2);

      assertEquals(orid2, linkset.iterator().next().getIdentity());
      assertEquals(orid2, doc2.getIdentity());

      linkset.remove(doc2);
      assertEquals(0, linkset.size()); // AssertionError: expected:<0> but was:<1>
    }
    session.commit();
  }

  @Test
  public void test2() {
    RID orid1;
    RID orid2;

    session.activateOnCurrentThread();
    session.begin();
    {
      var doc1 = (EntityImpl) session.newEntity();
      doc1.setProperty("linkset", session.newLinkSet());
      var linkset = doc1.getLinkSet("linkset");
      var doc2 = (EntityImpl) session.newEntity();

      orid2 = doc2.getIdentity();
      linkset.add(doc2);

      orid1 = doc1.getIdentity();
      assertNotNull(orid1);
    }
    session.commit();

    session.begin();
    {
      EntityImpl doc1 = session.load(orid1);
      assertNotNull(doc1);
      Set<Identifiable> linkset = doc1.getProperty("linkset");

      assertNotNull(linkset);
      assertEquals(1, linkset.size());

      EntityImpl doc2 = session.load(orid2);
      assertNotNull(doc2);

      assertEquals(orid2, linkset.iterator().next().getIdentity());
      assertEquals(orid2, doc2.getIdentity());

      linkset.remove(doc2);
      assertEquals(0, linkset.size()); // AssertionError: expected:<0> but was:<1>
    }
    session.commit();
  }
}
