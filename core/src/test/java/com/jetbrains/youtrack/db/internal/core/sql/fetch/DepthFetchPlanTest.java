package com.jetbrains.youtrack.db.internal.core.sql.fetch;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.fetch.FetchContext;
import com.jetbrains.youtrack.db.internal.core.fetch.FetchHelper;
import com.jetbrains.youtrack.db.internal.core.fetch.remote.RemoteFetchContext;
import com.jetbrains.youtrack.db.internal.core.fetch.remote.RemoteFetchListener;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.junit.Test;

public class DepthFetchPlanTest extends DbTestBase {

  @Test
  public void testFetchPlanDepth() {
    session.getMetadata().getSchema().createClass("Test");

    session.begin();
    var doc = ((EntityImpl) session.newEntity("Test"));
    var doc1 = ((EntityImpl) session.newEntity("Test"));
    var doc2 = ((EntityImpl) session.newEntity("Test"));
    doc.field("name", "name");
    session.commit();

    session.begin();
    doc = session.bindToSession(doc);
    doc1 = session.bindToSession(doc1);

    doc1.field("name", "name1");
    doc1.field("ref", doc);
    session.commit();

    session.begin();
    doc1 = session.bindToSession(doc1);
    doc2 = session.bindToSession(doc2);

    doc2.field("name", "name2");
    doc2.field("ref", doc1);
    session.commit();

    doc2 = session.bindToSession(doc2);
    FetchContext context = new RemoteFetchContext();
    var listener = new CountFetchListener();
    FetchHelper.fetch(session,
        doc2, doc2, FetchHelper.buildFetchPlan("ref:1 *:-2"), listener, context, "");

    assertEquals(1, listener.count);
  }

  @Test
  public void testFullDepthFetchPlan() {
    session.getMetadata().getSchema().createClass("Test");

    session.begin();
    var doc = ((EntityImpl) session.newEntity("Test"));
    var doc1 = ((EntityImpl) session.newEntity("Test"));
    var doc2 = ((EntityImpl) session.newEntity("Test"));
    var doc3 = ((EntityImpl) session.newEntity("Test"));
    doc.field("name", "name");
    session.commit();

    session.begin();
    doc = session.bindToSession(doc);
    doc1 = session.bindToSession(doc1);

    doc1.field("name", "name1");
    doc1.field("ref", doc);
    session.commit();

    session.begin();
    doc1 = session.bindToSession(doc1);
    doc2 = session.bindToSession(doc2);

    doc2.field("name", "name2");
    doc2.field("ref", doc1);
    session.commit();

    session.begin();
    doc2 = session.bindToSession(doc2);
    doc3 = session.bindToSession(doc3);

    doc3.field("name", "name2");
    doc3.field("ref", doc2);
    session.commit();

    doc3 = session.bindToSession(doc3);
    FetchContext context = new RemoteFetchContext();
    var listener = new CountFetchListener();
    FetchHelper.fetch(session, doc3, doc3, FetchHelper.buildFetchPlan("[*]ref:-1"), listener,
        context,
        "");
    assertEquals(3, listener.count);
  }

  private final class CountFetchListener extends RemoteFetchListener {

    public int count;

    @Override
    public boolean requireFieldProcessing() {
      return true;
    }

    @Override
    protected void sendRecord(RecordAbstract iLinked) {
      count++;
    }
  }
}
