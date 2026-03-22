package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.PropertyLinkBagIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyLinkBagSecondaryIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for LINKBAG secondary indexes (BY VALUE), which index the secondaryRid
 * (opposite vertex RID) instead of the primaryRid (edge RID).
 *
 * <p>Tests use a manually-managed LINKBAG property with double-sided RidPair entries
 * (edgeRid, targetVertexRid) to verify both primary (BY KEY) and secondary (BY VALUE)
 * index maintenance through the full entity lifecycle.
 */
public class LinkBagSecondaryIndexTest extends BaseDBJUnit5Test {

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();

    var schema = session.getMetadata().getSchema();
    var testClass = schema.createClass("SecIdxTestClass");
    testClass.createProperty("ridBag", PropertyType.LINKBAG);

    // Secondary index (BY VALUE — indexes secondaryRid / opposite vertex RID)
    session
        .execute(
            "CREATE INDEX secIdxByValue ON SecIdxTestClass (ridBag by value) NOTUNIQUE")
        .close();

    // Primary index (default — indexes primaryRid / edge RID)
    session
        .execute(
            "CREATE INDEX secIdxByKey ON SecIdxTestClass (ridBag) NOTUNIQUE")
        .close();

    session.close();
  }

  @AfterAll
  @Override
  void afterAll() throws Exception {
    if (session.isClosed()) {
      session = acquireSession();
    }

    try {
      session.rollback();
    } catch (Exception ignored) {
      // no active transaction
    }

    session.getMetadata().getSchema().dropClass("SecIdxTestClass");
    session.close();
  }

  @AfterEach
  @Override
  void afterEach() {
    if (session.isClosed()) {
      session = createSessionInstance();
    }

    try {
      session.rollback();
    } catch (Exception ignored) {
      // no active transaction
    }

    session.begin();
    session.execute("DELETE FROM SecIdxTestClass").close();
    session.commit();
  }

  // --- Index definition type verification ---

  /**
   * Verifies that the BY VALUE index uses PropertyLinkBagSecondaryIndexDefinition
   * and the default index uses PropertyLinkBagIndexDefinition.
   */
  @Test
  @Order(1)
  void testIndexDefinitionTypes() {
    var secIdx = getIndex("secIdxByValue");
    var priIdx = getIndex("secIdxByKey");

    assertTrue(
        secIdx.getDefinition() instanceof PropertyLinkBagSecondaryIndexDefinition,
        "BY VALUE index should use PropertyLinkBagSecondaryIndexDefinition");
    assertTrue(
        priIdx.getDefinition() instanceof PropertyLinkBagIndexDefinition,
        "Default index should use PropertyLinkBagIndexDefinition");
  }

  // --- Dual index creation and verification ---

  /**
   * Creates an entity with a LINKBAG containing double-sided pairs (edgeRid, targetVertexRid).
   * Verifies that:
   * - Secondary index contains target vertex RIDs (secondaryRid)
   * - Primary index contains edge RIDs (primaryRid)
   */
  @Test
  @Order(2)
  void testDualIndexOnEntityCreation() {
    session.begin();
    var entity = (EntityImpl) session.newEntity("SecIdxTestClass");
    var edgeDoc1 = session.newEntity();
    var edgeDoc2 = session.newEntity();
    var target1 = session.newEntity();
    var target2 = session.newEntity();

    var ridBag = new LinkBag(session);
    ridBag.add(edgeDoc1.getIdentity(), target1.getIdentity());
    ridBag.add(edgeDoc2.getIdentity(), target2.getIdentity());
    entity.setProperty("ridBag", ridBag);
    session.commit();

    // Verify secondary index contains target RIDs
    var secIdx = getIndex("secIdxByValue");
    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    assertEquals(2, secIdx.size(session),
        "Secondary index should contain 2 entries (one per target RID)");

    var secKeys = collectKeys(secIdx, ato);
    assertTrue(secKeys.contains(target1.getIdentity()),
        "Secondary index should contain target1 RID");
    assertTrue(secKeys.contains(target2.getIdentity()),
        "Secondary index should contain target2 RID");
    assertFalse(secKeys.contains(edgeDoc1.getIdentity()),
        "Secondary index should NOT contain edgeDoc1 RID");

    // Verify primary index contains edge RIDs
    var priIdx = getIndex("secIdxByKey");
    assertEquals(2, priIdx.size(session),
        "Primary index should contain 2 entries (one per edge RID)");

    var priKeys = collectKeys(priIdx, ato);
    assertTrue(priKeys.contains(edgeDoc1.getIdentity()),
        "Primary index should contain edgeDoc1 RID");
    assertTrue(priKeys.contains(edgeDoc2.getIdentity()),
        "Primary index should contain edgeDoc2 RID");
    assertFalse(priKeys.contains(target1.getIdentity()),
        "Primary index should NOT contain target1 RID");
    session.commit();
  }

  // --- Entity removal with dual index maintenance ---

  /**
   * Creates an entity with two pairs, removes one entry via direct LinkBag manipulation,
   * verifies both indexes are updated correctly.
   */
  @Test
  @Order(3)
  void testDualIndexOnEntryRemoval() {
    session.begin();
    var entity = (EntityImpl) session.newEntity("SecIdxTestClass");
    var edgeDoc1 = session.newEntity();
    var edgeDoc2 = session.newEntity();
    var target1 = session.newEntity();
    var target2 = session.newEntity();

    var ridBag = new LinkBag(session);
    ridBag.add(edgeDoc1.getIdentity(), target1.getIdentity());
    ridBag.add(edgeDoc2.getIdentity(), target2.getIdentity());
    entity.setProperty("ridBag", ridBag);
    session.commit();

    // Remove one entry
    session.begin();
    EntityImpl loaded = session.load(entity.getIdentity());
    loaded.<LinkBag>getProperty("ridBag").remove(edgeDoc1.getIdentity());
    session.commit();

    // Verify secondary index has only target2
    var secIdx = getIndex("secIdxByValue");
    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    assertEquals(1, secIdx.size(session));

    var secKeys = collectKeys(secIdx, ato);
    assertTrue(secKeys.contains(target2.getIdentity()));
    assertFalse(secKeys.contains(target1.getIdentity()),
        "Removed entry's target should not be in secondary index");

    // Verify primary index has only edgeDoc2
    var priIdx = getIndex("secIdxByKey");
    assertEquals(1, priIdx.size(session));

    var priKeys = collectKeys(priIdx, ato);
    assertTrue(priKeys.contains(edgeDoc2.getIdentity()));
    assertFalse(priKeys.contains(edgeDoc1.getIdentity()),
        "Removed entry should not be in primary index");
    session.commit();
  }

  // --- Bulk operations: multiple entries with same target ---

  /**
   * Creates multiple entries pointing to different targets, then also to the
   * same target. Verifies the secondary index correctly handles both cases.
   */
  @Test
  @Order(4)
  void testMultipleEntriesWithMixedTargets() {
    session.begin();
    var entity = (EntityImpl) session.newEntity("SecIdxTestClass");
    var edgeDoc1 = session.newEntity();
    var edgeDoc2 = session.newEntity();
    var edgeDoc3 = session.newEntity();
    var target1 = session.newEntity();
    var target2 = session.newEntity();

    var ridBag = new LinkBag(session);
    ridBag.add(edgeDoc1.getIdentity(), target1.getIdentity());
    ridBag.add(edgeDoc2.getIdentity(), target2.getIdentity());
    ridBag.add(edgeDoc3.getIdentity(), target1.getIdentity());
    entity.setProperty("ridBag", ridBag);
    session.commit();

    // Primary index: 3 entries (3 distinct edge RIDs)
    var priIdx = getIndex("secIdxByKey");
    session.begin();
    assertEquals(3, priIdx.size(session),
        "Primary index should have 3 entries (one per edge RID)");

    // Secondary index: target1 is referenced by two edges (edgeDoc1 and edgeDoc3),
    // target2 by one. The NOTUNIQUE index deduplicates (key, docRID) pairs, so
    // duplicate (target1, entity) entries collapse to one. Result: 2 distinct entries.
    var secIdx = getIndex("secIdxByValue");
    assertEquals(2, secIdx.size(session),
        "Secondary index should have 2 entries (target1 + target2, deduplicated per entity)");

    var activeTx = session.getActiveTransaction();
    var ato = activeTx.getAtomicOperation();
    var secKeys = collectKeys(secIdx, ato);
    assertTrue(secKeys.contains(target1.getIdentity()));
    assertTrue(secKeys.contains(target2.getIdentity()));
    session.commit();
  }

  // --- Incremental add ---

  /**
   * Creates an entity with one pair, commits, then adds another pair in a second
   * transaction. Verifies both indexes are updated after the incremental add.
   */
  @Test
  @Order(5)
  void testIncrementalAddUpdatesSecondaryIndex() {
    session.begin();
    var entity = (EntityImpl) session.newEntity("SecIdxTestClass");
    var edgeDoc1 = session.newEntity();
    var edgeDoc2 = session.newEntity();
    var target1 = session.newEntity();
    var target2 = session.newEntity();

    var ridBag = new LinkBag(session);
    ridBag.add(edgeDoc1.getIdentity(), target1.getIdentity());
    entity.setProperty("ridBag", ridBag);
    session.commit();

    // Add second entry incrementally
    session.begin();
    EntityImpl loaded = session.load(entity.getIdentity());
    loaded.<LinkBag>getProperty("ridBag")
        .add(edgeDoc2.getIdentity(), target2.getIdentity());
    session.commit();

    // Verify both indexes have 2 entries
    var secIdx = getIndex("secIdxByValue");
    var priIdx = getIndex("secIdxByKey");
    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    assertEquals(2, secIdx.size(session));
    assertEquals(2, priIdx.size(session));

    var secKeys = collectKeys(secIdx, ato);
    assertTrue(secKeys.contains(target1.getIdentity()));
    assertTrue(secKeys.contains(target2.getIdentity()));

    var priKeys = collectKeys(priIdx, ato);
    assertTrue(priKeys.contains(edgeDoc1.getIdentity()));
    assertTrue(priKeys.contains(edgeDoc2.getIdentity()));
    session.commit();
  }

  // --- Entity deletion clears both indexes ---

  /**
   * Deletes an entity containing a LINKBAG. Verifies both primary and secondary
   * indexes are cleared.
   */
  @Test
  @Order(6)
  void testEntityDeletionClearsBothIndexes() {
    session.begin();
    var entity = (EntityImpl) session.newEntity("SecIdxTestClass");
    var edgeDoc1 = session.newEntity();
    var target1 = session.newEntity();

    var ridBag = new LinkBag(session);
    ridBag.add(edgeDoc1.getIdentity(), target1.getIdentity());
    entity.setProperty("ridBag", ridBag);
    session.commit();

    // Verify indexes have 1 entry each
    session.begin();
    assertEquals(1, getIndex("secIdxByValue").size(session));
    assertEquals(1, getIndex("secIdxByKey").size(session));
    session.commit();

    // Delete the entity
    session.begin();
    EntityImpl loaded = session.load(entity.getIdentity());
    loaded.delete();
    session.commit();

    // Both indexes should be empty
    session.begin();
    assertEquals(0, getIndex("secIdxByValue").size(session),
        "Secondary index should be empty after entity deletion");
    assertEquals(0, getIndex("secIdxByKey").size(session),
        "Primary index should be empty after entity deletion");
    session.commit();
  }

  // --- Persistence round-trip ---

  /**
   * Creates entries with dual indexes, closes and reopens the database, verifies
   * both indexes still contain the correct entries.
   */
  @Test
  @Order(7)
  void testPersistenceRoundTrip() {
    session.begin();
    var entity = (EntityImpl) session.newEntity("SecIdxTestClass");
    var edgeDoc1 = session.newEntity();
    var edgeDoc2 = session.newEntity();
    var target1 = session.newEntity();
    var target2 = session.newEntity();

    var ridBag = new LinkBag(session);
    ridBag.add(edgeDoc1.getIdentity(), target1.getIdentity());
    ridBag.add(edgeDoc2.getIdentity(), target2.getIdentity());
    entity.setProperty("ridBag", ridBag);
    session.commit();

    // Capture RIDs before close
    var target1Rid = target1.getIdentity();
    var target2Rid = target2.getIdentity();
    var edge1Rid = edgeDoc1.getIdentity();
    var edge2Rid = edgeDoc2.getIdentity();

    // Close and reopen
    session.close();
    session = createSessionInstance();

    // Verify secondary index
    var secIdx = getIndex("secIdxByValue");
    assertNotNull(secIdx, "Secondary index should exist after reopen");
    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    assertEquals(2, secIdx.size(session));

    var secKeys = collectKeys(secIdx, ato);
    assertTrue(secKeys.contains(target1Rid));
    assertTrue(secKeys.contains(target2Rid));

    // Verify primary index
    var priIdx = getIndex("secIdxByKey");
    assertEquals(2, priIdx.size(session));

    var priKeys = collectKeys(priIdx, ato);
    assertTrue(priKeys.contains(edge1Rid));
    assertTrue(priKeys.contains(edge2Rid));
    session.commit();
  }

  // --- Transaction rollback ---

  /**
   * Adds an entry in a transaction, rolls back, verifies neither index was modified.
   */
  @Test
  @Order(8)
  void testRollbackDoesNotModifyIndexes() {
    session.begin();
    var entity = (EntityImpl) session.newEntity("SecIdxTestClass");
    var edgeDoc1 = session.newEntity();
    var target1 = session.newEntity();

    var ridBag = new LinkBag(session);
    ridBag.add(edgeDoc1.getIdentity(), target1.getIdentity());
    entity.setProperty("ridBag", ridBag);
    session.commit();

    // Start a new transaction, add another entry, then rollback
    session.begin();
    EntityImpl loaded = session.load(entity.getIdentity());
    var edgeDoc2 = session.newEntity();
    var target2 = session.newEntity();
    loaded.<LinkBag>getProperty("ridBag")
        .add(edgeDoc2.getIdentity(), target2.getIdentity());
    session.rollback();

    // Both indexes should still have only 1 entry from the first commit
    session.begin();
    assertEquals(1, getIndex("secIdxByValue").size(session),
        "Rollback should not affect secondary index");
    assertEquals(1, getIndex("secIdxByKey").size(session),
        "Rollback should not affect primary index");
    session.commit();
  }

  /**
   * Collects all key RIDs from an index into a Set for assertion convenience.
   */
  private Set<Object> collectKeys(Index index, AtomicOperation ato) {
    var keys = new HashSet<>();
    try (var keyStream = index.keyStream(ato)) {
      var it = keyStream.iterator();
      while (it.hasNext()) {
        var key = (Identifiable) it.next();
        keys.add(key.getIdentity());
      }
    }
    return keys;
  }
}
