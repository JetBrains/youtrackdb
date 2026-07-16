package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.List;
import org.junit.Test;

/**
 * D17 pins: a class rename re-associates the class's indexes as commit-only metadata — the
 * {@code classPropertyIndex} lookup map re-keys from the old to the new class name and every
 * affected {@code IndexDefinition.className} (recursing composites) is rewritten — so the indexes
 * keep accelerating queries under the new class name. The index's own NAME does not change (the
 * inert index-name rename and {@code ALTER INDEX … RENAME} are deferred to YTDB-1066), and no
 * engine storage file is touched (D16 keys them by {@code ie_<fileBaseId>} stems).
 */
public class ClassRenameIndexReassociationTest extends DbTestBase {

  /**
   * The load-bearing D17 flow: a committed class with a single-property index and a composite
   * index is renamed inside a transaction. Mid-transaction the tx-local view already serves the
   * indexes under the NEW class name and no longer under the old one. After commit the shared
   * {@code classPropertyIndex} serves the new name, every definition (including the composite's
   * sub-definitions) carries the new class name, index maintenance follows writes to the renamed
   * class, and the re-association is durable across an index-manager reload and a session reopen.
   */
  @Test
  public void inTxRenameReassociatesIndexesAtCommit() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("RenIdxOld");
    cls.createProperty("val", PropertyType.STRING);
    cls.createProperty("val2", PropertyType.STRING);
    cls.createIndex("RenIdxOld.val", SchemaClass.INDEX_TYPE.UNIQUE, "val");
    cls.createIndex("RenIdxOldComposite", SchemaClass.INDEX_TYPE.NOTUNIQUE, "val", "val2");
    session.executeInTx(tx -> {
      var row = tx.newEntity("RenIdxOld");
      row.setProperty("val", "pre-rename");
      row.setProperty("val2", "x");
    });

    var indexManager = session.getSharedContext().getIndexManager();

    session.begin();
    session.getMetadata().getSchema().getClass("RenIdxOld").setName("RenIdxNew");

    // Mid-transaction, the tx-local view serves the indexes under the NEW name only.
    assertEquals("mid-tx: both indexes must resolve under the new class name",
        2, indexManager.getClassIndexes(session, "RenIdxNew").size());
    assertTrue("mid-tx: the old class name must no longer resolve any index",
        indexManager.getClassIndexes(session, "RenIdxOld").isEmpty());
    assertFalse("mid-tx: the involved-indexes lookup must follow the rename",
        indexManager.getClassInvolvedIndexes(session, "RenIdxNew", "val").isEmpty());
    assertTrue("mid-tx: the fields must read as indexed under the new name",
        indexManager.areIndexed(session, "RenIdxNew", "val"));
    assertFalse("mid-tx: the fields must not read as indexed under the old name",
        indexManager.areIndexed(session, "RenIdxOld", "val"));
    session.commit();

    // Committed: the shared lookup map serves the new name; the old key is gone.
    assertEquals("commit must re-key classPropertyIndex to the new class name",
        2, indexManager.getClassIndexes(session, "RenIdxNew").size());
    assertTrue("commit must remove the old classPropertyIndex key",
        indexManager.getClassIndexes(session, "RenIdxOld").isEmpty());

    // Every affected definition carries the new class name — including the composite's
    // sub-definitions (the D17 "recursing composites" clause).
    var simple = indexManager.getIndex("RenIdxOld.val");
    assertNotNull(simple);
    assertEquals("the definition must carry the new class name",
        "RenIdxNew", simple.getDefinition().getClassName());
    var composite = indexManager.getIndex("RenIdxOldComposite");
    assertNotNull(composite);
    var compositeDefinition = (CompositeIndexDefinition) composite.getDefinition();
    assertEquals("the composite definition must carry the new class name",
        "RenIdxNew", compositeDefinition.getClassName());
    for (var subDefinition : compositeDefinition.getIndexDefinitions()) {
      assertEquals("every composite sub-definition must carry the new class name",
          "RenIdxNew", subDefinition.getClassName());
    }

    // The index still accelerates: the pre-rename row is served, and index maintenance follows
    // writes to the renamed class (proving ClassIndexManager resolves the index via the new key).
    assertEquals("the pre-rename row must be served through the re-associated index", 1,
        session.computeInTx(tx -> simple.getRids(session, "pre-rename").toList()).size());
    session.executeInTx(tx -> {
      var row = tx.newEntity("RenIdxNew");
      row.setProperty("val", "post-rename");
      row.setProperty("val2", "y");
    });
    assertEquals("a post-rename write must be tracked by the re-associated index", 1,
        session.computeInTx(tx -> simple.getRids(session, "post-rename").toList()).size());

    // Durable: reload the index manager from its records and reopen the session.
    indexManager.reload(session);
    reOpen("admin", ADMIN_PASSWORD);
    var reloaded = session.getSharedContext().getIndexManager();
    assertEquals("the re-association must survive a reload and reopen",
        2, reloaded.getClassIndexes(session, "RenIdxNew").size());
    assertTrue("no stale old-name key may survive a reload and reopen",
        reloaded.getClassIndexes(session, "RenIdxOld").isEmpty());
    assertEquals("the durable definition must carry the new class name",
        "RenIdxNew", reloaded.getIndex("RenIdxOld.val").getDefinition().getClassName());
  }

  /**
   * A rolled-back rename leaves every piece of index metadata untouched: the re-association is
   * overlay-only until commit, so the shared lookup map still serves the OLD class name, the
   * definitions still carry the old name, and nothing answers under the new name.
   */
  @Test
  public void renameRollbackLeavesIndexMetadataUntouched() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("RenRbOld");
    cls.createProperty("val", PropertyType.STRING);
    cls.createIndex("RenRbOld.val", SchemaClass.INDEX_TYPE.UNIQUE, "val");

    var indexManager = session.getSharedContext().getIndexManager();

    session.begin();
    session.getMetadata().getSchema().getClass("RenRbOld").setName("RenRbNew");
    session.rollback();

    assertEquals("a rolled-back rename must leave classPropertyIndex keyed by the old name",
        1, indexManager.getClassIndexes(session, "RenRbOld").size());
    assertTrue("a rolled-back rename must leave nothing under the new name",
        indexManager.getClassIndexes(session, "RenRbNew").isEmpty());
    assertEquals("a rolled-back rename must leave the definition's class name untouched",
        "RenRbOld", indexManager.getIndex("RenRbOld.val").getDefinition().getClassName());
  }

  /**
   * Rename composed with same-transaction index creation, both orders: an index created on the
   * NEW class name after the rename, and an index created on the OLD name before the rename
   * (whose deferred definition must be re-associated at commit). After commit all three indexes —
   * the pre-existing committed one, the created-before-rename one, and the created-after-rename
   * one — key under the new class name with matching definitions.
   */
  @Test
  public void renameComposesWithSameTxIndexCreation() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("RenMixOld");
    cls.createProperty("val", PropertyType.STRING);
    cls.createProperty("pre", PropertyType.STRING);
    cls.createProperty("post", PropertyType.STRING);
    cls.createIndex("RenMixOld.val", SchemaClass.INDEX_TYPE.UNIQUE, "val");

    var indexManager = session.getSharedContext().getIndexManager();

    session.begin();
    // Created BEFORE the rename: the deferred handle's definition names the old class and must be
    // re-associated at commit.
    session.getMetadata().getSchema().getClass("RenMixOld")
        .createIndex("RenMixPreIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "pre");
    session.getMetadata().getSchema().getClass("RenMixOld").setName("RenMixNew");
    // Created AFTER the rename: the definition names the new class from the start.
    session.getMetadata().getSchema().getClass("RenMixNew")
        .createIndex("RenMixPostIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "post");
    session.commit();

    assertEquals("all three indexes must key under the new class name after commit",
        3, indexManager.getClassIndexes(session, "RenMixNew").size());
    assertTrue("nothing may key under the old class name after commit",
        indexManager.getClassIndexes(session, "RenMixOld").isEmpty());
    for (var indexName : List.of("RenMixOld.val", "RenMixPreIdx", "RenMixPostIdx")) {
      assertEquals("definition of '" + indexName + "' must carry the new class name",
          "RenMixNew", indexManager.getIndex(indexName).getDefinition().getClassName());
    }
  }

  /**
   * YTDB-1066 deferral pin: the index's own NAME does not change on class rename. An auto-named
   * index keeps embedding the old class name ("RenNameOld.val" on class "RenNameNew") — known and
   * accepted; only the class association (lookup key + definition) is re-keyed. The inert
   * index-name rename and {@code ALTER INDEX … RENAME} land with YTDB-1066.
   */
  @Test
  public void indexNameDoesNotChangeOnClassRename() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("RenNameOld");
    cls.createProperty("val", PropertyType.STRING);
    cls.createIndex("RenNameOld.val", SchemaClass.INDEX_TYPE.UNIQUE, "val");

    session.executeInTx(
        tx -> session.getMetadata().getSchema().getClass("RenNameOld").setName("RenNameNew"));

    var indexManager = session.getSharedContext().getIndexManager();
    var index = indexManager.getIndex("RenNameOld.val");
    assertNotNull("the index must remain registered under its ORIGINAL name", index);
    assertEquals("the index name must keep embedding the old class name (YTDB-1066 deferred)",
        "RenNameOld.val", index.getName());
    assertEquals("while its class association follows the rename",
        "RenNameNew", index.getDefinition().getClassName());
  }

  /**
   * The non-transactional (top-level) rename path applies the same re-association eagerly: a
   * committed class renamed OUTSIDE any transaction re-keys {@code classPropertyIndex} and
   * rewrites the definitions immediately, mirroring how every other non-transactional DDL
   * self-applies. Durability is verified through a reload + reopen like the tx path.
   */
  @Test
  public void nonTxRenameReassociatesEagerly() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("RenEagerOld");
    cls.createProperty("val", PropertyType.STRING);
    cls.createIndex("RenEagerOld.val", SchemaClass.INDEX_TYPE.UNIQUE, "val");

    // No transaction: the legacy top-level path.
    session.getMetadata().getSchema().getClass("RenEagerOld").setName("RenEagerNew");

    var indexManager = session.getSharedContext().getIndexManager();
    assertEquals("a top-level rename must re-key classPropertyIndex eagerly",
        1, indexManager.getClassIndexes(session, "RenEagerNew").size());
    assertTrue("a top-level rename must remove the old classPropertyIndex key",
        indexManager.getClassIndexes(session, "RenEagerOld").isEmpty());
    assertEquals("a top-level rename must rewrite the definition's class name",
        "RenEagerNew", indexManager.getIndex("RenEagerOld.val").getDefinition().getClassName());

    indexManager.reload(session);
    reOpen("admin", ADMIN_PASSWORD);
    var reloaded = session.getSharedContext().getIndexManager();
    assertEquals("the eager re-association must survive a reload and reopen",
        1, reloaded.getClassIndexes(session, "RenEagerNew").size());
    assertEquals("the durable definition must carry the new class name",
        "RenEagerNew", reloaded.getIndex("RenEagerOld.val").getDefinition().getClassName());
  }
}
