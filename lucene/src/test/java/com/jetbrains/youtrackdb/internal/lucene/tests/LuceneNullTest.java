package com.jetbrains.youtrackdb.internal.lucene.tests;

import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneNullTest extends LuceneBaseTest {

  @Before
  public void setUp() throws Exception {
    session.execute("create class Test extends V");

    session.execute("create property Test.names EMBEDDEDLIST STRING");

    session.execute("create index Test.names on Test(names) FULLTEXT ENGINE LUCENE");
  }

  @Test
  public void testNullChangeToNotNullWithLists() {

    session.begin();
    var doc = ((EntityImpl) session.newVertex("Test"));
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.newEmbeddedList("names", new String[]{"foo"});
    session.commit();

    var index = session.getSharedContext().getIndexManager().getIndex("Test.names");

    session.begin();
    Assert.assertEquals(1, index.size(session));
    session.commit();
  }

  @Test
  public void testNotNullChangeToNullWithLists() {

    session.begin();
    var doc = ((EntityImpl) session.newVertex("Test"));
    doc.newEmbeddedList("names", new String[]{"foo"});
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.removeProperty("names");
    session.commit();

    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex("Test.names");
    Assert.assertEquals(0, index.size(session));
    session.commit();
  }
}
