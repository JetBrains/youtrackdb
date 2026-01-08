package com.jetbrains.youtrackdb.internal.core.db.tool;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.UserCredential;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageRecoverEventListener;
import java.util.Objects;
import org.junit.Assert;
import org.junit.Test;

public class GraphRecoveringTest {

  private class TestListener implements StorageRecoverEventListener {

    public long scannedEdges = 0;
    public long removedEdges = 0;
    public long scannedVertices = 0;
    public long scannedLinks = 0;
    public long removedLinks = 0;
    public long repairedVertices = 0;

    @Override
    public void onScannedEdge(EntityImpl edge) {
      scannedEdges++;
    }

    @Override
    public void onRemovedEdge(EntityImpl edge) {
      removedEdges++;
    }

    @Override
    public void onScannedVertex(EntityImpl vertex) {
      scannedVertices++;
    }

    @Override
    public void onScannedLink(Identifiable link) {
      scannedLinks++;
    }

    @Override
    public void onRemovedLink(Identifiable link) {
      removedLinks++;
    }

    @Override
    public void onRepairedVertex(EntityImpl vertex) {
      repairedVertices++;
    }
  }

  private static void init(DatabaseSessionEmbedded session) {
    session.getSchema().createVertexClass("V1");
    session.getSchema().createVertexClass("V2");
    session.getSchema().createEdgeClass("E1");
    session.getSchema().createEdgeClass("E2");

    var tx = session.begin();
    var v0 = tx.newVertex();
    v0.setProperty("key", 0);
    var v1 = tx.newVertex("V1");
    v1.setProperty("key", 1);
    var v2 = tx.newVertex("V2");
    v2.setProperty("key", 2);

    v0.addStateFulEdge(v1);
    v1.addEdge(v2, "E1");
    v2.addEdge(v0, "E2");

    tx.commit();
  }

  @Test
  public void testRecoverPerfectGraphNonLW() {
    try (var youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()))) {
      youTrackDB.create("testRecoverPerfectGraphNonLW", DatabaseType.MEMORY,
          new UserCredential("admin", "admin", PredefinedRole.ADMIN));
      try (var session = youTrackDB.open("testRecoverPerfectGraphNonLW",
          "admin", "admin")) {
        init(session);

        final var eventListener = new TestListener();

        new GraphRepair().setEventListener(eventListener).repair(session, null, null);

        Assert.assertEquals(3, eventListener.scannedEdges);
        Assert.assertEquals(0, eventListener.removedEdges);
        Assert.assertEquals(3, eventListener.scannedVertices);
        Assert.assertEquals(6, eventListener.scannedLinks);
        Assert.assertEquals(0, eventListener.removedLinks);
        Assert.assertEquals(0, eventListener.repairedVertices);
      }
    }
  }

  @Test
  public void testRecoverBrokenGraphAllEdges() {
    try (var youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()))) {
      youTrackDB.create("testRecoverBrokenGraphAllEdges", DatabaseType.MEMORY,
          new UserCredential("admin", "admin", PredefinedRole.ADMIN));
      try (var session = youTrackDB.open("testRecoverBrokenGraphAllEdges",
          "admin", "admin")) {
        init(session);

        var tx = session.begin();
        for (var e :
            tx.query("select from E").stream()
                .map(Result::asStatefulEdge)
                .toList()) {
          var transaction = session.getActiveTransaction();
          transaction.<EntityImpl>load(e).removePropertyInternal("out");
        }
        tx.commit();

        final var eventListener = new TestListener();

        new GraphRepair().setEventListener(eventListener).repair(session, null, null);

        Assert.assertEquals(3, eventListener.scannedEdges);
        Assert.assertEquals(3, eventListener.removedEdges);
        Assert.assertEquals(3, eventListener.scannedVertices);
        // This is 3 because 3 referred by the edge are cleaned by the edge delete
        Assert.assertEquals(3, eventListener.scannedLinks);
        // This is 3 because 3 referred by the edge are cleaned by the edge delete
        Assert.assertEquals(3, eventListener.removedLinks);
        Assert.assertEquals(3, eventListener.repairedVertices);
      }
    }
  }

  @Test
  public void testRecoverBrokenGraphLinksInVerticesNonLW() {
    try (var youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()))) {
      youTrackDB.create("testRecoverBrokenGraphLinksInVerticesNonLW", DatabaseType.MEMORY,
          new UserCredential("admin", "admin", PredefinedRole.ADMIN));
      try (var session =
          youTrackDB.open("testRecoverBrokenGraphLinksInVerticesNonLW",
              "admin", "admin")) {
        init(session);

        var tx = session.begin();
        for (var v :
            tx.query("select from V").stream()
                .map(Result::asEntityOrNull)
                .filter(Objects::nonNull)
                .map(Entity::asVertex)
                .toList()) {
          var transaction1 = session.getActiveTransaction();
          for (var f : transaction1.<EntityImpl>load(v).getPropertyNamesInternal(false,
              true)) {
            if (f.startsWith(Vertex.DIRECTION_OUT_PREFIX)) {
              var transaction = session.getActiveTransaction();
              transaction.<EntityImpl>load(v).removePropertyInternal(f);
            }
          }
        }
        tx.commit();

        final var eventListener = new TestListener();

        new GraphRepair().setEventListener(eventListener).repair(session, null, null);

        Assert.assertEquals(3, eventListener.scannedEdges);
        Assert.assertEquals(3, eventListener.removedEdges);
        Assert.assertEquals(3, eventListener.scannedVertices);
        // This is 0 because the delete edge does the cleanup
        Assert.assertEquals(0, eventListener.scannedLinks);
        // This is 0 because the delete edge does the cleanup
        Assert.assertEquals(0, eventListener.removedLinks);
        // This is 0 because the delete edge does the cleanup
        Assert.assertEquals(0, eventListener.repairedVertices);
      }
    }
  }
}
