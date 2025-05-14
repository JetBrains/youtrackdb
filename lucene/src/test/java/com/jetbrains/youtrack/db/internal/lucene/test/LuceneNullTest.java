package com.jetbrains.youtrack.db.internal.lucene.test;

import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class LuceneNullTest extends BaseLuceneTest {

  @Test
  public void testNullChangeToNotNullWithLists() {

    session.execute("create class Test extends V").close();

    session.execute("create property Test.names EMBEDDEDLIST STRING").close();

    session.execute("create index Test.names on Test (names) fulltext engine lucene").close();

    session.begin();
    var doc = ((EntityImpl) session.newVertex("Test"));
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.newEmbeddedList("names", new String[]{"foo"});
    session.commit();

    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex("Test.names");
    Assert.assertEquals(1, index.size(session));
    session.commit();
  }

  @Test
  public void testNotNullChangeToNullWithLists() {

    session.execute("create class Test extends V").close();
    session.execute("create property Test.names EMBEDDEDLIST STRING").close();
    session.execute("create index Test.names on Test (names) fulltext engine lucene").close();

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
    Assert.assertEquals(index.size(session), 0);
    session.commit();
  }
}
