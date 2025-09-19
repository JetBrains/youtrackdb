package com.jetbrains.youtrackdb.internal.core.sql.fetch;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.fetch.FetchContext;
import com.jetbrains.youtrackdb.internal.core.fetch.FetchHelper;
import com.jetbrains.youtrackdb.internal.core.fetch.remote.RemoteFetchContext;
import com.jetbrains.youtrackdb.internal.core.fetch.remote.RemoteFetchListener;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Test;

public class DepthFetchPlanTest extends DbTestBase {

  @Test
  public void testFetchPlanDepth() {
    session.getMetadata().getSlowMutableSchema().createClass("Test");

    session.begin();
    var doc = ((EntityImpl) session.newEntity("Test"));
    var doc1 = ((EntityImpl) session.newEntity("Test"));
    var doc2 = ((EntityImpl) session.newEntity("Test"));
    doc.setProperty("name", "name");
    session.commit();

    session.begin();
    var activeTx4 = session.getActiveTransaction();
    doc = activeTx4.load(doc);
    var activeTx3 = session.getActiveTransaction();
    doc1 = activeTx3.load(doc1);

    doc1.setProperty("name", "name1");
    doc1.setProperty("ref", doc);
    session.commit();

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    doc1 = activeTx2.load(doc1);
    var activeTx1 = session.getActiveTransaction();
    doc2 = activeTx1.load(doc2);

    doc2.setProperty("name", "name2");
    doc2.setProperty("ref", doc1);
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc2 = activeTx.load(doc2);
    FetchContext context = new RemoteFetchContext();
    var listener = new CountFetchListener();
    FetchHelper.fetch(session,
        doc2, doc2, FetchHelper.buildFetchPlan("ref:1 *:-2"), listener, context, "");

    assertEquals(1, listener.count);
    session.commit();
  }

  @Test
  public void testFullDepthFetchPlan() {
    session.getMetadata().getSlowMutableSchema().createClass("Test");

    session.begin();
    var doc = ((EntityImpl) session.newEntity("Test"));
    var doc1 = ((EntityImpl) session.newEntity("Test"));
    var doc2 = ((EntityImpl) session.newEntity("Test"));
    var doc3 = ((EntityImpl) session.newEntity("Test"));
    doc.setProperty("name", "name");
    session.commit();

    session.begin();
    var activeTx6 = session.getActiveTransaction();
    doc = activeTx6.load(doc);
    var activeTx5 = session.getActiveTransaction();
    doc1 = activeTx5.load(doc1);

    doc1.setProperty("name", "name1");
    doc1.setProperty("ref", doc);
    session.commit();

    session.begin();
    var activeTx4 = session.getActiveTransaction();
    doc1 = activeTx4.load(doc1);
    var activeTx3 = session.getActiveTransaction();
    doc2 = activeTx3.load(doc2);

    doc2.setProperty("name", "name2");
    doc2.setProperty("ref", doc1);
    session.commit();

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    doc2 = activeTx2.load(doc2);
    var activeTx1 = session.getActiveTransaction();
    doc3 = activeTx1.load(doc3);

    doc3.setProperty("name", "name2");
    doc3.setProperty("ref", doc2);
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc3 = activeTx.load(doc3);
    FetchContext context = new RemoteFetchContext();
    var listener = new CountFetchListener();
    FetchHelper.fetch(session, doc3, doc3, FetchHelper.buildFetchPlan("[*]ref:-1"), listener,
        context,
        "");
    assertEquals(3, listener.count);
    session.commit();
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
