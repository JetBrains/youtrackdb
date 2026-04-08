package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Tests that modifications to partially-deserialized properties (especially LinkBag)
 * are not lost when full deserialization is triggered by {@code setDirty()}.
 *
 * <p>Reproduces the bug where:
 * <ol>
 *   <li>An entity is loaded from storage with lazy (partial) deserialization</li>
 *   <li>A single property (e.g., a LinkBag) is accessed and deserialized</li>
 *   <li>The property is modified (e.g., an entry is added to the LinkBag)</li>
 *   <li>The modification fires {@code setDirty()}, which calls
 *       {@code checkForProperties()} to force full deserialization</li>
 *   <li>Full deserialization re-reads ALL properties from the original source bytes,
 *       overwriting the already-modified property with its pre-modification state</li>
 * </ol>
 *
 * <p>This was the root cause of the flaky {@code LocalPaginatedStorageRestoreFromWALIT}
 * failure where opposite link bag back-references were silently lost during commit-time
 * link consistency processing.
 */
public class EntityPartialDeserializationLinkBagTest extends DbTestBase {

  /**
   * Verifies that adding an entry to a LinkBag property on a committed entity
   * survives the full re-deserialization triggered by setDirty(). The entity
   * is loaded, only the linkMap property is accessed (partial deserialization),
   * the link bag is modified, and then the entity is saved. After re-loading,
   * the added entry must be present.
   */
  @Test
  public void testLinkBagAddSurvivesFullReDeserialization() {
    // Create schema: TestOne has a linkMap, TestTwo is a target
    var schema = session.getMetadata().getSchema();
    var classOne = schema.createClass("TestOne");
    classOne.createProperty("intProp", PropertyType.INTEGER);
    classOne.createProperty("stringProp", PropertyType.STRING);
    classOne.createProperty("linkMap", PropertyType.LINKMAP);

    var classTwo = schema.createClass("TestTwo");
    classTwo.createProperty("label", PropertyType.STRING);

    // Create target entities
    var targetRids = new ArrayList<RID>();
    session.executeInTx(tx -> {
      for (var i = 0; i < 3; i++) {
        var target = (EntityImpl) tx.newEntity(classTwo);
        target.setProperty("label", "target" + i);
        targetRids.add(target.getIdentity());
      }
    });

    // Create source entity with linkMap pointing to targets
    var sourceRids = new ArrayList<RID>();
    session.executeInTx(tx -> {
      var entity = (EntityImpl) tx.newEntity(classOne);
      entity.setProperty("intProp", 42);
      entity.setProperty("stringProp", "test");
      Map<String, RID> linkMap = new HashMap<>();
      for (var rid : targetRids) {
        linkMap.put(rid.toString(), rid);
      }
      entity.newLinkMap("linkMap", linkMap);
      sourceRids.add(entity.getIdentity());
    });

    // Create an additional target to add later
    var additionalTargetRids = new ArrayList<RID>();
    session.executeInTx(tx -> {
      var target = (EntityImpl) tx.newEntity(classTwo);
      target.setProperty("label", "additional");
      additionalTargetRids.add(target.getIdentity());
    });

    // Now: load the source entity, access ONLY the linkMap (partial deserialization),
    // modify it, and verify the modification survives
    session.executeInTx(tx -> {
      var entity = (EntityImpl) tx.loadEntity(sourceRids.get(0));

      // Access only linkMap — partial deserialization
      var linkMap =
          (Map<String, RID>) entity.getPropertyInternal("linkMap", false);
      assertNotNull("linkMap should be deserialized", linkMap);
      assertEquals("linkMap should have 3 entries", 3, linkMap.size());

      // Modify: add a new entry. This fires setDirty() which triggers
      // full deserialization from source bytes via checkForProperties().
      var additionalRid = additionalTargetRids.get(0);
      linkMap.put(additionalRid.toString(), additionalRid);

      // Verify the modification is still visible on the entity
      var linkMapAfter =
          (Map<String, RID>) entity.getPropertyInternal("linkMap", false);
      assertEquals("linkMap should now have 4 entries", 4, linkMapAfter.size());
      assertTrue("linkMap should contain the added entry",
          linkMapAfter.containsValue(additionalRid));
    });

    // Verify after commit and re-load
    session.executeInTx(tx -> {
      var entity = (EntityImpl) tx.loadEntity(sourceRids.get(0));
      var linkMap =
          (Map<String, RID>) entity.getPropertyInternal("linkMap", false);
      assertEquals("After re-load, linkMap should have 4 entries", 4, linkMap.size());
      assertTrue("After re-load, linkMap should contain the added entry",
          linkMap.containsValue(additionalTargetRids.get(0)));
    });
  }

  /**
   * Reproduces the exact scenario from the CI failure: entity with linkMap is committed,
   * then in a new transaction, the opposite entity's #linkMap (system property) is
   * modified via updateOppositeLinks during link consistency processing. The modification
   * must not be lost when setDirty() triggers full re-deserialization.
   *
   * This test creates entity A with linkMap -> {B}, commits. Then in a new TX,
   * creates entity C with linkMap -> {B} AND deletes entity A. During commit-time
   * callback processing, entity B's #linkMap is modified (C added, A removed).
   * The removal must find A in the link bag.
   */
  @Test
  public void testOppositeLinkBagConsistencyDuringDeleteAndCreate() {
    var schema = session.getMetadata().getSchema();
    var classOne = schema.createClass("SourceClass");
    classOne.createProperty("linkMap", PropertyType.LINKMAP);

    var classTwo = schema.createClass("TargetClass");
    classTwo.createProperty("label", PropertyType.STRING);

    // Create shared target
    var targetRids = new ArrayList<RID>();
    session.executeInTx(tx -> {
      var target = (EntityImpl) tx.newEntity(classTwo);
      target.setProperty("label", "shared-target");
      targetRids.add(target.getIdentity());
    });

    // Create entity A with linkMap -> {target}
    var entityARids = new ArrayList<RID>();
    session.executeInTx(tx -> {
      var entityA = (EntityImpl) tx.newEntity(classOne);
      Map<String, RID> linkMap = new HashMap<>();
      linkMap.put(targetRids.get(0).toString(), targetRids.get(0));
      entityA.newLinkMap("linkMap", linkMap);
      entityARids.add(entityA.getIdentity());
    });

    // Verify: target's #linkMap should contain entity A
    session.executeInTx(tx -> {
      var target = (EntityImpl) tx.loadEntity(targetRids.get(0));
      var oppositeBag = (LinkBag) target.getPropertyInternal("#linkMap", false);
      assertNotNull("Target should have #linkMap after entity A creation", oppositeBag);
      assertTrue("Target's #linkMap should contain entity A",
          oppositeBag.contains(entityARids.get(0)));
    });

    // Now: in a single TX, create entity C with linkMap -> {target} AND delete entity A.
    // This triggers the exact callback processing order that caused the bug:
    // 1. Entity C CREATED → adds C to target's #linkMap (partial deserialization + add)
    // 2. Entity A DELETED → removes A from target's #linkMap (must find A)
    session.executeInTx(tx -> {
      // Create entity C linking to the same target
      var entityC = (EntityImpl) tx.newEntity(classOne);
      Map<String, RID> linkMap = new HashMap<>();
      linkMap.put(targetRids.get(0).toString(), targetRids.get(0));
      entityC.newLinkMap("linkMap", linkMap);

      // Delete entity A
      var entityA = tx.loadEntity(entityARids.get(0));
      session.delete(entityA);
    });

    // Verify: target's #linkMap should NOT contain entity A, but SHOULD contain entity C
    session.executeInTx(tx -> {
      var target = (EntityImpl) tx.loadEntity(targetRids.get(0));
      var oppositeBag = (LinkBag) target.getPropertyInternal("#linkMap", false);
      assertNotNull("Target should still have #linkMap", oppositeBag);
      assertEquals("Target's #linkMap should have exactly 1 entry (entity C)",
          1, oppositeBag.size());
    });
  }

  /**
   * Stress test: multiple entities linking to the same target, then deleting some.
   * Ensures back-references survive through many iterations of partial
   * deserialization + modification cycles.
   */
  @Test
  public void testOppositeLinkBagConsistencyUnderRepeatedModification() {
    var schema = session.getMetadata().getSchema();
    var classOne = schema.createClass("SourceStress");
    classOne.createProperty("linkMap", PropertyType.LINKMAP);

    var classTwo = schema.createClass("TargetStress");
    classTwo.createProperty("label", PropertyType.STRING);

    // Create shared target
    var targetRids = new ArrayList<RID>();
    session.executeInTx(tx -> {
      var target = (EntityImpl) tx.newEntity(classTwo);
      target.setProperty("label", "stress-target");
      targetRids.add(target.getIdentity());
    });

    // Create 20 source entities, each linking to the target
    var sourceRids = new ArrayList<RID>();
    for (var i = 0; i < 20; i++) {
      session.executeInTx(tx -> {
        var entity = (EntityImpl) tx.newEntity(classOne);
        Map<String, RID> linkMap = new HashMap<>();
        linkMap.put(targetRids.get(0).toString(), targetRids.get(0));
        entity.newLinkMap("linkMap", linkMap);
        sourceRids.add(entity.getIdentity());
      });
    }

    // Verify all 20 back-references exist
    session.executeInTx(tx -> {
      var target = (EntityImpl) tx.loadEntity(targetRids.get(0));
      var oppositeBag = (LinkBag) target.getPropertyInternal("#linkMap", false);
      assertNotNull(oppositeBag);
      assertEquals(20, oppositeBag.size());
    });

    // Delete the first 10 entities, each in a TX that also creates a new entity
    // linking to the same target. This maximizes the chance of hitting the
    // partial deserialization + modification + re-deserialization race.
    Set<RID> deletedRids = new HashSet<>();
    for (var i = 0; i < 10; i++) {
      var ridToDelete = sourceRids.get(i);
      deletedRids.add(ridToDelete);
      session.executeInTx(tx -> {
        // Create a new entity linking to the same target
        var newEntity = (EntityImpl) tx.newEntity(classOne);
        Map<String, RID> linkMap = new HashMap<>();
        linkMap.put(targetRids.get(0).toString(), targetRids.get(0));
        newEntity.newLinkMap("linkMap", linkMap);
        sourceRids.add(newEntity.getIdentity());

        // Delete the old entity
        var oldEntity = tx.loadEntity(ridToDelete);
        session.delete(oldEntity);
      });
    }

    // Verify: target's #linkMap should have exactly 20 entries
    // (10 original remaining + 10 newly created)
    session.executeInTx(tx -> {
      var target = (EntityImpl) tx.loadEntity(targetRids.get(0));
      var oppositeBag = (LinkBag) target.getPropertyInternal("#linkMap", false);
      assertNotNull(oppositeBag);
      assertEquals("Should have 20 entries (10 surviving + 10 new)", 20,
          oppositeBag.size());

      // None of the deleted RIDs should be in the bag
      for (var deletedRid : deletedRids) {
        assertFalse("Deleted entity " + deletedRid + " should NOT be in #linkMap",
            oppositeBag.contains(deletedRid));
      }
    });
  }

  /**
   * Stress test for concurrent opposite-link updates. Multiple threads
   * simultaneously create entities linking to the same target. After all
   * threads finish, every committed back-reference must be present in the
   * target's #linkMap.
   *
   * <p>If back-references are lost, this proves a serialization or
   * version-checking bug — not an inherent SI limitation — because under
   * correct SI, conflicting writes to the same entity are detected via
   * version checks and retried as CME.
   */
  @Test
  public void testOppositeLinkBagSurvivesConcurrentModification() throws Exception {
    var schema = session.getMetadata().getSchema();
    var sourceClass = schema.createClass("ConcSource");
    sourceClass.createProperty("linkMap", PropertyType.LINKMAP);
    var targetClass = schema.createClass("ConcTarget");
    targetClass.createProperty("label", PropertyType.STRING);

    // Create a shared target entity
    var targetRids = new ArrayList<RID>();
    session.executeInTx(tx -> {
      var target = (EntityImpl) tx.newEntity(targetClass);
      target.setProperty("label", "shared");
      targetRids.add(target.getIdentity());
    });

    var targetRid = targetRids.get(0);
    var numThreads = 4;
    var iterationsPerThread = 50;
    var allCommittedSourceRids = ConcurrentHashMap.<RID>newKeySet();
    var errors = new ConcurrentLinkedQueue<Throwable>();
    var startBarrier = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(numThreads);

    for (var t = 0; t < numThreads; t++) {
      new Thread(() -> {
        try {
          startBarrier.await();
          try (var s = youTrackDB.open(databaseName, "admin", "adminpwd")) {
            for (var i = 0; i < iterationsPerThread; i++) {
              try {
                // Capture the entity identity inside the lambda, but only
                // add it to the committed set AFTER executeInTx returns
                // successfully (i.e., after the TX has committed). Adding
                // inside the lambda would overcount: the lambda body runs
                // before commit, and a CME during commit would leave the
                // RID in the set even though the entity was rolled back.
                var committedRid = new AtomicReference<RID>();
                s.executeInTx(tx -> {
                  var entity = (EntityImpl) tx.newEntity("ConcSource");
                  Map<String, RID> linkMap = new HashMap<>();
                  linkMap.put(targetRid.toString(), targetRid);
                  entity.newLinkMap("linkMap", linkMap);
                  committedRid.set(entity.getIdentity());
                });
                // TX committed successfully — record the RID
                allCommittedSourceRids.add(committedRid.get());
              } catch (ConcurrentModificationException ignored) {
                // Expected under SI when two TXs modify the same
                // target entity's #linkMap concurrently — retry next
                // iteration.
              }
            }
          }
        } catch (Throwable e) {
          errors.add(e);
        } finally {
          doneLatch.countDown();
        }
      }).start();
    }

    startBarrier.countDown();
    doneLatch.await();

    if (!errors.isEmpty()) {
      var first = errors.poll();
      throw new RuntimeException(
          "Thread failed (" + errors.size() + " more)", first);
    }

    assertTrue("At least some TXs must have committed",
        allCommittedSourceRids.size() > 0);

    // Verify: every committed source entity's RID must be in
    // target's #linkMap. If any are missing, back-references were lost.
    session.executeInTx(tx -> {
      var target = (EntityImpl) tx.loadEntity(targetRid);
      var bag = (LinkBag) target.getPropertyInternal("#linkMap", false);
      assertNotNull("Target must have #linkMap", bag);

      var bagContents = new HashSet<RID>();
      for (var pair : bag) {
        bagContents.add(pair.primaryRid());
      }

      for (var sourceRid : allCommittedSourceRids) {
        assertTrue(
            "Target's #linkMap is missing committed back-ref " + sourceRid
                + ". Bag has " + bagContents.size() + " entries, expected "
                + allCommittedSourceRids.size(),
            bagContents.contains(sourceRid));
      }
      assertEquals("Bag size must match committed source count",
          allCommittedSourceRids.size(), bagContents.size());
    });
  }
}
