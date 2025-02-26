package com.jetbrains.youtrack.db.internal.core.db.graph;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class TestGraphElementDelete {

  private YouTrackDB youTrackDB;
  private DatabaseSession session;

  @Before
  public void before() {
    youTrackDB =
        CreateDatabaseUtil.createDatabase("test", DbTestBase.embeddedDBUrl(getClass()),
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
    session.begin();
    var vertex = session.newVertex("V");
    var vertex1 = session.newVertex("V");
    var edge = vertex.addStateFulEdge(vertex1, "E");
    session.commit();

    session.begin();
    session.delete(session.bindToSession(vertex));
    session.commit();

    session.begin();
    try {
      session.load(edge.getIdentity());
      Assert.fail();
    } catch (RecordNotFoundException e) {
      // ignore
    }
    session.commit();
  }

  @Test
  public void testDeleteEdge() {

    session.begin();
    var vertex = session.newVertex("V");
    var vertex1 = session.newVertex("V");
    var edge = vertex.addStateFulEdge(vertex1, "E");
    session.commit();

    session.begin();
    session.delete(session.bindToSession(edge));
    session.commit();

    session.begin();
    assertFalse(session.bindToSession(vertex).getEdges(Direction.OUT, "E").iterator().hasNext());
    session.commit();
  }

  @Test
  public void testDeleteEdgeConcurrentModification() throws Exception {
    session.begin();
    var vertex = session.newVertex("V");
    var vertex1 = session.newVertex("V");
    var edge = vertex.addStateFulEdge(vertex1, "E");
    session.commit();

    session.begin();
    Entity instance = session.load(edge.getIdentity());

    var th =
        new Thread(
            () -> {
              try (var database =
                  youTrackDB.open("test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD)) {
                database.begin();
                Entity element = database.load(edge.getIdentity());
                element.setProperty("one", "two");
                database.commit();
              }
            });
    th.start();
    th.join();

    try {
      session.delete(instance);
      session.commit();
      Assert.fail();
    } catch (ConcurrentModificationException e) {
    }

    session.begin();
    assertNotNull(session.load(edge.getIdentity()));
    assertNotNull(session.load(vertex.getIdentity()));
    assertNotNull(session.load(vertex1.getIdentity()));
    assertTrue(
        ((Vertex) session.load(vertex.getIdentity()))
            .getEdges(Direction.OUT, "E")
            .iterator()
            .hasNext());
    assertTrue(
        ((Vertex) session.load(vertex1.getIdentity()))
            .getEdges(Direction.IN, "E")
            .iterator()
            .hasNext());
    session.commit();
  }
}
