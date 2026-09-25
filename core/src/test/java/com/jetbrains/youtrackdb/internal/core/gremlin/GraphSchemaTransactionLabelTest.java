package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.Test;
import org.mockito.Mockito;

public class GraphSchemaTransactionLabelTest extends GraphBaseTest {

  @Test
  public void vertexClassCreatedEarlierInSameTransactionIsReusedWithoutPublishing() {
    graph.tx().open();
    var txSession = ((YTDBTransaction) graph.tx()).getDatabaseSession();
    var schema = txSession.getSchema();
    schema.createClass("TxVertex", schema.getClass("V"));

    assertEquals("TxVertex", graph.addVertex("TxVertex").label());
    assertNotNull(txSession.getSchema().getClass("TxVertex"));
    assertInvisible("TxVertex");
    graph.tx().commit();
    assertVisible("TxVertex");
  }

  @Test
  public void nullSnapshotLookupUsesTransactionSchemaForExistingVertexClass() throws Exception {
    graph.tx().open();
    var txSession = ((YTDBTransaction) graph.tx()).getDatabaseSession();
    txSession.getSchema().createClass("NullSnapshotVertex", txSession.getSchema().getClass("V"));

    withNullSnapshotOnFirstRead(txSession,
        () -> assertEquals("NullSnapshotVertex", graph.addVertex("NullSnapshotVertex").label()));
    assertInvisible("NullSnapshotVertex");
    graph.tx().rollback();
  }

  @Test
  public void nullSnapshotVertexLookupRecreatesClassDroppedOnlyInTransaction() throws Exception {
    session.getSchema().createClass("CommittedVertex", session.getSchema().getClass("V"));
    graph.tx().open();
    var txSession = ((YTDBTransaction) graph.tx()).getDatabaseSession();
    txSession.getSchema().dropClass("CommittedVertex");
    assertNull(txSession.getSchema().getClass("CommittedVertex"));

    // The shared schema still has this class. Reading it would skip private-copy creation.
    withNullSnapshotOnFirstRead(txSession,
        () -> assertEquals("CommittedVertex", graph.addVertex("CommittedVertex").label()));
    assertNotNull(txSession.getSchema().getClass("CommittedVertex"));
    graph.tx().rollback();
    assertVisible("CommittedVertex");
  }

  @Test
  public void nullSnapshotEdgeLookupRecreatesClassDroppedOnlyInTransaction() throws Exception {
    session.getSchema().createClass("CommittedEdge", session.getSchema().getClass("E"));
    graph.tx().open();
    var txSession = ((YTDBTransaction) graph.tx()).getDatabaseSession();
    txSession.getSchema().dropClass("CommittedEdge");
    assertNull(txSession.getSchema().getClass("CommittedEdge"));
    var from = graph.addVertex();
    var to = graph.addVertex();

    // The committed edge class must not hide its absence in the transaction schema.
    withNullSnapshotOnFirstRead(txSession,
        () -> assertEquals("CommittedEdge", from.addEdge("CommittedEdge", to).label()));
    assertNotNull(txSession.getSchema().getClass("CommittedEdge"));
    graph.tx().rollback();
    assertVisible("CommittedEdge");
  }

  @Test
  public void edgeClassCreatedEarlierInSameTransactionIsReusedWithoutPublishing() {
    graph.tx().open();
    var txSession = ((YTDBTransaction) graph.tx()).getDatabaseSession();
    var schema = txSession.getSchema();
    schema.createClass("TxEdge", schema.getClass("E"));

    var from = graph.addVertex();
    var to = graph.addVertex();
    assertEquals("TxEdge", from.addEdge("TxEdge", to).label());
    assertInvisible("TxEdge");
    graph.tx().commit();
    assertVisible("TxEdge");
  }

  @Test
  public void missingVertexLabelInPrivateCopyPublishesOnlyAtCommit() {
    graph.tx().open();
    var txSession = ((YTDBTransaction) graph.tx()).getDatabaseSession();
    txSession.getSchema().createClass("SeedVertex", txSession.getSchema().getClass("V"));

    assertEquals("NewTxVertex", graph.addVertex("NewTxVertex").label());
    assertNotNull(txSession.getSchema().getClass("NewTxVertex"));
    assertInvisible("NewTxVertex");
    graph.tx().commit();
    assertVisible("NewTxVertex");
  }

  @Test
  public void missingEdgeLabelInPrivateCopyPublishesOnlyAtCommit() {
    graph.tx().open();
    var txSession = ((YTDBTransaction) graph.tx()).getDatabaseSession();
    txSession.getSchema().createClass("SeedEdge", txSession.getSchema().getClass("E"));

    var from = graph.addVertex();
    var to = graph.addVertex();
    assertEquals("NewTxEdge", from.addEdge("NewTxEdge", to).label());
    assertNotNull(txSession.getSchema().getClass("NewTxEdge"));
    assertInvisible("NewTxEdge");
    graph.tx().commit();
    assertVisible("NewTxEdge");
  }

  @Test
  public void missingVertexLabelInPrivateCopyDisappearsOnRollback() {
    graph.tx().open();
    var txSession = ((YTDBTransaction) graph.tx()).getDatabaseSession();
    txSession.getSchema().createClass("SeedRollbackVertex", txSession.getSchema().getClass("V"));
    graph.addVertex("RolledBackVertex");

    assertNotNull(txSession.getSchema().getClass("RolledBackVertex"));
    assertInvisible("RolledBackVertex");
    graph.tx().rollback();
    assertInvisible("RolledBackVertex");
  }

  @Test
  public void missingEdgeLabelInPrivateCopyDisappearsOnRollback() {
    graph.tx().open();
    var txSession = ((YTDBTransaction) graph.tx()).getDatabaseSession();
    txSession.getSchema().createClass("SeedRollbackEdge", txSession.getSchema().getClass("E"));
    var from = graph.addVertex();
    var to = graph.addVertex();
    from.addEdge("RolledBackEdge", to);

    assertNotNull(txSession.getSchema().getClass("RolledBackEdge"));
    assertInvisible("RolledBackEdge");
    graph.tx().rollback();
    assertInvisible("RolledBackEdge");
  }

  @Test
  public void indexOnlyPrivateCopyKeepsNewVertexLabelPrivate() {
    createIndexedBase();
    graph.tx().open();
    var txSession = ((YTDBTransaction) graph.tx()).getDatabaseSession();
    txSession.getSchema().getClass("IndexedBase")
        .createIndex("IndexedBase.key", SchemaClass.INDEX_TYPE.NOTUNIQUE, "key");
    assertNotNull(txSession.getTxSchemaState());

    graph.addVertex("IndexTxVertex");
    assertInvisible("IndexTxVertex");
    graph.tx().rollback();
    assertInvisible("IndexTxVertex");
  }

  @Test
  public void indexOnlyPrivateCopyKeepsNewEdgeLabelPrivate() {
    createIndexedBase();
    graph.tx().open();
    var txSession = ((YTDBTransaction) graph.tx()).getDatabaseSession();
    txSession.getSchema().getClass("IndexedBase")
        .createIndex("IndexedBase.key", SchemaClass.INDEX_TYPE.NOTUNIQUE, "key");
    assertNotNull(txSession.getTxSchemaState());

    var from = graph.addVertex();
    from.addEdge("IndexTxEdge", graph.addVertex());
    assertInvisible("IndexTxEdge");
    graph.tx().rollback();
    assertInvisible("IndexTxEdge");
  }

  @Test
  public void indexOnlyPrivateCopyPublishesNewVertexLabelAtCommit() {
    createIndexedBase();
    graph.tx().open();
    var txSession = ((YTDBTransaction) graph.tx()).getDatabaseSession();
    txSession.getSchema().getClass("IndexedBase")
        .createIndex("IndexedBase.key", SchemaClass.INDEX_TYPE.NOTUNIQUE, "key");
    assertNotNull(txSession.getTxSchemaState());

    assertEquals("IndexCommitVertex", graph.addVertex("IndexCommitVertex").label());
    assertInvisible("IndexCommitVertex");
    graph.tx().commit();
    assertVisible("IndexCommitVertex");
  }

  @Test
  public void indexOnlyPrivateCopyPublishesNewEdgeLabelAtCommit() {
    createIndexedBase();
    graph.tx().open();
    var txSession = ((YTDBTransaction) graph.tx()).getDatabaseSession();
    txSession.getSchema().getClass("IndexedBase")
        .createIndex("IndexedBase.key", SchemaClass.INDEX_TYPE.NOTUNIQUE, "key");
    assertNotNull(txSession.getTxSchemaState());

    var from = graph.addVertex();
    assertEquals("IndexCommitEdge", from.addEdge("IndexCommitEdge", graph.addVertex()).label());
    assertInvisible("IndexCommitEdge");
    graph.tx().commit();
    assertVisible("IndexCommitEdge");
  }

  @Test
  public void ordinaryVertexLabelSurvivesRollbackWithoutPrivateCopy() {
    graph.tx().open();
    assertNull(((YTDBTransaction) graph.tx()).getDatabaseSession().getTxSchemaState());
    graph.addVertex("OrdinaryVertex");
    assertVisible("OrdinaryVertex");
    graph.tx().rollback();
    assertVisible("OrdinaryVertex");
  }

  @Test
  public void ordinaryEdgeLabelSurvivesRollbackWithoutPrivateCopy() {
    graph.tx().open();
    var from = graph.addVertex();
    from.addEdge("OrdinaryEdge", graph.addVertex());
    assertNull(((YTDBTransaction) graph.tx()).getDatabaseSession().getTxSchemaState());
    assertVisible("OrdinaryEdge");
    graph.tx().rollback();
    assertVisible("OrdinaryEdge");
  }

  @Test
  public void ordinaryVertexLabelRemainsAfterLaterSchemaChangeAndCommit() {
    graph.tx().open();
    graph.addVertex("BeforeSeedVertex");
    assertVisible("BeforeSeedVertex");
    var txSession = ((YTDBTransaction) graph.tx()).getDatabaseSession();
    txSession.getSchema().createClass("LaterVertex", txSession.getSchema().getClass("V"));
    assertNotNull(txSession.getSchema().getClass("BeforeSeedVertex"));
    graph.tx().commit();
    assertVisible("BeforeSeedVertex");
    assertVisible("LaterVertex");
  }

  @Test
  public void wrongKindVertexClassInPrivateCopyIsRejectedWithoutPublishing() {
    graph.tx().open();
    var txSession = ((YTDBTransaction) graph.tx()).getDatabaseSession();
    txSession.getSchema().createClass("NotAVertex", txSession.getSchema().getClass("E"));
    var error = assertThrows(IllegalArgumentException.class,
        () -> graph.addVertex("NotAVertex"));
    assertEquals("Class NotAVertex is not a vertex type", error.getMessage());
    assertInvisible("NotAVertex");
    graph.tx().rollback();
  }

  private void withNullSnapshotOnFirstRead(DatabaseSessionEmbedded txSession, Runnable action)
      throws Exception {
    // Only the label lookup sees null. Vertex or edge creation still reads the real snapshot.
    var metadataField = DatabaseSessionEmbedded.class.getDeclaredField("metadata");
    metadataField.setAccessible(true);
    var original = (MetadataDefault) metadataField.get(txSession);
    var metadata = Mockito.spy(original);
    doReturn(null).doCallRealMethod().when(metadata).getImmutableSchemaSnapshot();
    metadataField.set(txSession, metadata);
    try {
      action.run();
    } finally {
      metadataField.set(txSession, original);
    }
  }

  private void createIndexedBase() {
    var cls = session.getSchema().createClass("IndexedBase", session.getSchema().getClass("V"));
    cls.createProperty("key", PropertyType.STRING);
  }

  private void assertInvisible(String name) {
    try (var other = openDatabase()) {
      assertFalse(name + " must be invisible in another session",
          other.getSchema().existsClass(name));
    }
  }

  private void assertVisible(String name) {
    try (var other = openDatabase()) {
      assertTrue(name + " must be visible in another session", other.getSchema().existsClass(name));
    }
  }
}
