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
   * BG107 pin: an index created while the class temporarily held a MID-CHAIN name follows the
   * class to its final name. The deferred handle's definition names the class as it was at
   * creation time ("RenChainMid"), which is neither the pre-tx name nor the final name — only
   * event-time re-association (fixing the handle at each rename) keeps it attached.
   */
  @Test
  public void indexCreatedAtMidChainNameFollowsFinalName() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("RenChainStart");
    cls.createProperty("val", PropertyType.STRING);

    session.begin();
    session.getMetadata().getSchema().getClass("RenChainStart").setName("RenChainMid");
    session.getMetadata().getSchema().getClass("RenChainMid")
        .createIndex("RenChainIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "val");
    session.getMetadata().getSchema().getClass("RenChainMid").setName("RenChainEnd");
    session.commit();

    var indexManager = session.getSharedContext().getIndexManager();
    assertEquals("the mid-chain-created index must key under the FINAL class name",
        1, indexManager.getClassIndexes(session, "RenChainEnd").size());
    assertTrue("nothing may key under the mid-chain name",
        indexManager.getClassIndexes(session, "RenChainMid").isEmpty());
    assertTrue("nothing may key under the pre-tx name",
        indexManager.getClassIndexes(session, "RenChainStart").isEmpty());
    assertEquals("the definition must carry the final class name",
        "RenChainEnd", indexManager.getIndex("RenChainIdx").getDefinition().getClassName());
  }

  /**
   * BG108 pin (renamed-away name reused): after renaming committed class A away, a NEW class
   * created under the vacated name "A" and renamed again must NOT clobber the committed class's
   * rename entry — committed A's index follows A's class (now B), never the impostor's final
   * name (D).
   */
  @Test
  public void renamedAwayNameReusedByNewClassDoesNotClobber() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("RenReuseA");
    cls.createProperty("val", PropertyType.STRING);
    cls.createIndex("RenReuseIdx", SchemaClass.INDEX_TYPE.UNIQUE, "val");

    session.begin();
    session.getMetadata().getSchema().getClass("RenReuseA").setName("RenReuseB");
    // A brand-new class takes the vacated name, then moves on — it owns no committed indexes.
    session.getMetadata().getSchema().createClass("RenReuseA");
    session.getMetadata().getSchema().getClass("RenReuseA").setName("RenReuseD");
    session.commit();

    var indexManager = session.getSharedContext().getIndexManager();
    assertEquals("the committed index must follow ITS class to B",
        1, indexManager.getClassIndexes(session, "RenReuseB").size());
    assertTrue("the impostor's final name must own no indexes",
        indexManager.getClassIndexes(session, "RenReuseD").isEmpty());
    assertEquals("the definition must carry the committed class's final name",
        "RenReuseB", indexManager.getIndex("RenReuseIdx").getDefinition().getClassName());
  }

  /**
   * BG108 pin (swap-shaped rename): {X→Y, Z→X} in one transaction, plus an index created on the
   * post-swap "X" (which is committed Z's class). Every association must land exactly: X's
   * committed index under Y, Z's committed index under X, and the tx-created index under X —
   * with no iteration-order-dependent double-fixup shuffling them.
   */
  @Test
  public void swapShapedRenameKeepsAssociationsExact() {
    var schema = session.getMetadata().getSchema();
    var clsX = schema.createClass("RenSwapX");
    clsX.createProperty("val", PropertyType.STRING);
    clsX.createIndex("RenSwapXIdx", SchemaClass.INDEX_TYPE.UNIQUE, "val");
    var clsZ = schema.createClass("RenSwapZ");
    clsZ.createProperty("val", PropertyType.STRING);
    clsZ.createProperty("extra", PropertyType.STRING);
    clsZ.createIndex("RenSwapZIdx", SchemaClass.INDEX_TYPE.UNIQUE, "val");

    session.begin();
    session.getMetadata().getSchema().getClass("RenSwapX").setName("RenSwapY");
    session.getMetadata().getSchema().getClass("RenSwapZ").setName("RenSwapX");
    // Created on the post-swap holder of "RenSwapX" — committed Z's class.
    session.getMetadata().getSchema().getClass("RenSwapX")
        .createIndex("RenSwapNewIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "extra");
    session.commit();

    var indexManager = session.getSharedContext().getIndexManager();
    assertEquals("committed X's index must land under Y",
        "RenSwapY", indexManager.getIndex("RenSwapXIdx").getDefinition().getClassName());
    assertEquals("committed Z's index must land under X",
        "RenSwapX", indexManager.getIndex("RenSwapZIdx").getDefinition().getClassName());
    assertEquals("the tx-created index must land under X (committed Z's class)",
        "RenSwapX", indexManager.getIndex("RenSwapNewIdx").getDefinition().getClassName());
    assertEquals("Y must own exactly committed X's index",
        1, indexManager.getClassIndexes(session, "RenSwapY").size());
    assertEquals("X must own exactly committed Z's index plus the tx-created one",
        2, indexManager.getClassIndexes(session, "RenSwapX").size());
  }

  /**
   * BG108 pin (drop purges the rename entry): rename A→B, drop the class (now named B), then
   * rename committed C→B in the same transaction. The drop must purge A's rename entry, so B
   * ends up owning exactly C's index — not a nondeterministic mix that re-associates the dropped
   * class's index to B.
   */
  @Test
  public void dropOfRenameTargetPurgesTheRenameEntry() {
    var schema = session.getMetadata().getSchema();
    var clsA = schema.createClass("RenDropA");
    clsA.createProperty("val", PropertyType.STRING);
    clsA.createIndex("RenDropAIdx", SchemaClass.INDEX_TYPE.UNIQUE, "val");
    var clsC = schema.createClass("RenDropC");
    clsC.createProperty("val", PropertyType.STRING);
    clsC.createIndex("RenDropCIdx", SchemaClass.INDEX_TYPE.UNIQUE, "val");

    session.begin();
    session.getMetadata().getSchema().getClass("RenDropA").setName("RenDropB");
    session.getMetadata().getSchema().dropClass("RenDropB");
    session.getMetadata().getSchema().getClass("RenDropC").setName("RenDropB");
    session.commit();

    var indexManager = session.getSharedContext().getIndexManager();
    var underB = indexManager.getClassIndexes(session, "RenDropB");
    assertEquals("B must own exactly committed C's index", 1, underB.size());
    assertEquals("the index under B must be C's, not the dropped class's",
        "RenDropCIdx", underB.iterator().next().getName());
    assertEquals("C's definition must carry the new name",
        "RenDropB", indexManager.getIndex("RenDropCIdx").getDefinition().getClassName());
  }

  /**
   * BG109 pin: a rename composed with a same-transaction membership ripple (a subclass created
   * under the renamed parent) must keep BOTH durable: the re-associated class name AND the
   * membership addition. The membership persistence rewrites the parent index's record in the
   * same enroll phase, so ordered wrongly it would clobber the re-associated record with the
   * stale old class name — detectable only after a reload re-parses the durable record.
   */
  @Test
  public void renamePlusSubclassMembershipRippleSameTx() {
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("RenRippleP");
    parent.createProperty("val", PropertyType.STRING);
    parent.createIndex("RenRippleIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "val");

    session.begin();
    session.getMetadata().getSchema().getClass("RenRippleP").setName("RenRippleQ");
    // The subclass create ripples a membership add into the parent's index.
    session.getMetadata().getSchema()
        .createClass("RenRippleS", session.getMetadata().getSchema().getClass("RenRippleQ"));
    session.commit();

    var indexManager = session.getSharedContext().getIndexManager();
    assertEquals("the definition must carry the new class name after commit",
        "RenRippleQ", indexManager.getIndex("RenRippleIdx").getDefinition().getClassName());

    // Durability: the membership save and the re-association rewrite the SAME record; a wrong
    // enroll order lets one clobber the other, visible only after a re-parse.
    indexManager.reload(session);
    reOpen("admin", ADMIN_PASSWORD);
    var reloaded = session.getSharedContext().getIndexManager();
    assertEquals("the re-associated class name must survive the reload (not clobbered by the"
        + " membership save)",
        "RenRippleQ", reloaded.getIndex("RenRippleIdx").getDefinition().getClassName());
    var subclassCollections = session.getMetadata().getSchema().getClass("RenRippleS")
        .getCollectionIds();
    var parentIndexCollections = reloaded.getIndex("RenRippleIdx").getCollections();
    for (var collectionId : subclassCollections) {
      assertTrue("the membership addition must survive the reload too",
          parentIndexCollections.contains(session.getCollectionNameById(collectionId)));
    }
  }

  /**
   * TQ107 pin (chained rename): A→B→C in one transaction lands the committed index under the
   * FINAL name with nothing under the intermediate one.
   */
  @Test
  public void chainedRenameReassociatesToFinalName() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("RenHopA");
    cls.createProperty("val", PropertyType.STRING);
    cls.createIndex("RenHopIdx", SchemaClass.INDEX_TYPE.UNIQUE, "val");

    session.begin();
    session.getMetadata().getSchema().getClass("RenHopA").setName("RenHopB");
    session.getMetadata().getSchema().getClass("RenHopB").setName("RenHopC");
    session.commit();

    var indexManager = session.getSharedContext().getIndexManager();
    assertEquals("the committed index must land under the final chain name",
        "RenHopC", indexManager.getIndex("RenHopIdx").getDefinition().getClassName());
    assertTrue(indexManager.getClassIndexes(session, "RenHopA").isEmpty());
    assertTrue(indexManager.getClassIndexes(session, "RenHopB").isEmpty());
    assertEquals(1, indexManager.getClassIndexes(session, "RenHopC").size());
  }

  /**
   * TQ107 pin (rename-back nets out): A→B→A in one transaction is a no-op for index metadata —
   * the collapsed rename map drops the entry, and after commit everything still keys under A.
   */
  @Test
  public void renameBackNetsOut() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("RenBackA");
    cls.createProperty("val", PropertyType.STRING);
    cls.createIndex("RenBackIdx", SchemaClass.INDEX_TYPE.UNIQUE, "val");

    session.begin();
    session.getMetadata().getSchema().getClass("RenBackA").setName("RenBackB");
    session.getMetadata().getSchema().getClass("RenBackB").setName("RenBackA");
    session.commit();

    var indexManager = session.getSharedContext().getIndexManager();
    assertEquals("a netted-out rename must leave the association under the original name",
        1, indexManager.getClassIndexes(session, "RenBackA").size());
    assertTrue(indexManager.getClassIndexes(session, "RenBackB").isEmpty());
    assertEquals("RenBackA",
        indexManager.getIndex("RenBackIdx").getDefinition().getClassName());
  }

  /**
   * TQ107 pin (multi-class rename in one transaction): two committed classes renamed in the same
   * transaction each carry their own indexes to their own new names.
   */
  @Test
  public void multiClassRenameInOneTx() {
    var schema = session.getMetadata().getSchema();
    for (var name : List.of("RenMultiA", "RenMultiB")) {
      var cls = schema.createClass(name);
      cls.createProperty("val", PropertyType.STRING);
      cls.createIndex(name + ".val", SchemaClass.INDEX_TYPE.UNIQUE, "val");
    }

    session.begin();
    session.getMetadata().getSchema().getClass("RenMultiA").setName("RenMultiA2");
    session.getMetadata().getSchema().getClass("RenMultiB").setName("RenMultiB2");
    session.commit();

    var indexManager = session.getSharedContext().getIndexManager();
    assertEquals("RenMultiA2",
        indexManager.getIndex("RenMultiA.val").getDefinition().getClassName());
    assertEquals("RenMultiB2",
        indexManager.getIndex("RenMultiB.val").getDefinition().getClassName());
    assertEquals(1, indexManager.getClassIndexes(session, "RenMultiA2").size());
    assertEquals(1, indexManager.getClassIndexes(session, "RenMultiB2").size());
  }

  /**
   * TQ107 pin (case-only rename): the schema is case-sensitive, so "RenCase" → "RENCASE" is a
   * real rename and the re-association must follow it exactly like any other.
   */
  @Test
  public void caseOnlyRenameReassociates() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("RenCase");
    cls.createProperty("val", PropertyType.STRING);
    cls.createIndex("RenCase.val", SchemaClass.INDEX_TYPE.UNIQUE, "val");

    session.begin();
    session.getMetadata().getSchema().getClass("RenCase").setName("RENCASE");
    session.commit();

    var indexManager = session.getSharedContext().getIndexManager();
    assertEquals("RENCASE",
        indexManager.getIndex("RenCase.val").getDefinition().getClassName());
    assertEquals(1, indexManager.getClassIndexes(session, "RENCASE").size());
    assertTrue(indexManager.getClassIndexes(session, "RenCase").isEmpty());
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
