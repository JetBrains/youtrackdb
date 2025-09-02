package com.jetbrains.youtrackdb.internal.core.db.graph;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.StatefulEdge;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class TestGraphElementDelete {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSession session;

  @Before
  public void before() {
    youTrackDB =
        (YouTrackDBImpl) CreateDatabaseUtil.createDatabase("test",
            DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_MEMORY);
    session = youTrackDB.open("test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
  }

  @After
  public void after() {
    session.close();
    youTrackDB.close();
  }

  @Test
  public void testDeleteVertex() {
    var tx = session.begin();
    var vertex = tx.newVertex("V");
    var vertex1 = tx.newVertex("V");
    var edge = vertex.addStateFulEdge(vertex1, "E");
    tx.commit();

    tx = session.begin();
    tx.delete(tx.<Vertex>load(vertex));
    tx.commit();

    tx = session.begin();
    try {
      tx.load(edge.getIdentity());
      Assert.fail();
    } catch (RecordNotFoundException e) {
      // ignore
    }
    tx.commit();
  }

  @Test
  public void testDeleteEdge() {

    var tx = session.begin();
    var vertex = tx.newVertex("V");
    var vertex1 = tx.newVertex("V");
    var edge = vertex.addStateFulEdge(vertex1, "E");
    tx.commit();

    tx = session.begin();
    tx.delete(tx.<StatefulEdge>load(edge));
    tx.commit();

    tx = session.begin();
    assertFalse(tx.<Vertex>load(vertex).getEdges(Direction.OUT, "E").iterator().hasNext());
    tx.commit();
  }

  @Test
  public void testDeleteEdgeConcurrentModification() throws Exception {
    var tx = session.begin();
    var vertex = tx.newVertex("V");
    var vertex1 = tx.newVertex("V");
    var edge = vertex.addStateFulEdge(vertex1, "E");
    tx.commit();

    tx = session.begin();
    Entity instance = tx.load(edge.getIdentity());

    var th =
        new Thread(
            () -> {
              try (var database =
                  youTrackDB.open("test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD)) {
                var tx1 = database.begin();
                Entity element = tx1.load(edge.getIdentity());
                element.setProperty("one", "two");
                tx1.commit();
              }
            });
    th.start();
    th.join();

    try {
      tx.delete(instance);
      tx.commit();
      Assert.fail();
    } catch (ConcurrentModificationException e) {
    }

    tx = session.begin();
    assertNotNull(tx.load(edge.getIdentity()));
    assertNotNull(tx.load(vertex.getIdentity()));
    assertNotNull(tx.load(vertex1.getIdentity()));
    assertTrue(
        ((Vertex) tx.load(vertex.getIdentity()))
            .getEdges(Direction.OUT, "E")
            .iterator()
            .hasNext());
    assertTrue(
        ((Vertex) tx.load(vertex1.getIdentity()))
            .getEdges(Direction.IN, "E")
            .iterator()
            .hasNext());
    tx.commit();
  }
}
