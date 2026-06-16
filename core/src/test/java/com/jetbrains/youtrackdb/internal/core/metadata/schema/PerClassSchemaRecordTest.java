package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * Persistence-level coverage for the per-class schema record format: the root schema record carries
 * a link set to one standalone record per class, each class binds its own record RID, the root keeps
 * the non-link payload, and the open-time version gate rejects both prior embedded-set formats.
 */
public class PerClassSchemaRecordTest extends DbTestBase {

  private SchemaShared schemaShared() {
    return session.getSharedContext().getSchema();
  }

  /**
   * Reads the RID set held by the root record's {@code "classes"} link set, the persisted
   * membership the per-class-record format relies on.
   */
  private Set<RID> rootClassLinks() {
    // Record reads must run inside a transaction; the schema record itself is loaded transactionally
    // everywhere it is read in production.
    return session.computeInTx(tx -> {
      var root = session.<EntityImpl>load(schemaShared().getIdentity());
      var links = root.getLinkSet("classes");
      var rids = new HashSet<RID>();
      if (links != null) {
        for (var link : links) {
          rids.add(link.getIdentity());
        }
      }
      return rids;
    });
  }

  /**
   * Reads the {@code collectionCounter} off the root record inside a transaction. The counter is
   * part of the root's non-link payload that must survive the per-class split.
   */
  private Integer readRootCollectionCounter() {
    return session.computeInTx(tx -> session.<EntityImpl>load(schemaShared().getIdentity())
        .getProperty("collectionCounter"));
  }

  /**
   * A created class is reachable from the root link set through its own standalone record, and a
   * reopen rebuilds the schema from those per-class records: the class survives, its record RID is
   * still bound, and the bound RID is one of the root's links. This is the round-trip a tx-local
   * schema copy seeded from a re-parse depends on.
   */
  @Test
  public void createdClassPersistsAsStandaloneLinkedRecord() {
    session.getMetadata().getSchema().createClass("RoundTripClass");

    var clsBefore = schemaShared().getClass("RoundTripClass");
    assertNotNull("class must exist after create", clsBefore);
    var ridBefore = clsBefore.getRecordId();
    assertNotNull("a persisted class must carry its own record RID", ridBefore);
    assertTrue("the bound record RID must be persistent after commit", ridBefore.isPersistent());
    assertTrue("the root link set must contain the class record RID",
        rootClassLinks().contains(ridBefore));

    // Force a fromStream re-parse of the on-disk per-class records on EVERY profile. reOpen reuses
    // the cached SchemaShared under the default MEMORY profile, so reload() (which always re-reads
    // the root record and rebuilds classes + bound RIDs) is what makes the round trip real locally.
    schemaShared().reload(session);
    reOpen("admin", ADMIN_PASSWORD);

    var clsAfter = schemaShared().getClass("RoundTripClass");
    assertNotNull("class must survive a fromStream re-parse", clsAfter);
    assertEquals("the per-class record RID must be re-bound from the link set at load",
        ridBefore, clsAfter.getRecordId());
    assertTrue("the reopened root link set must still contain the class record RID",
        rootClassLinks().contains(clsAfter.getRecordId()));
  }

  /**
   * Dropping a class deletes its standalone record and removes the link from the root, so neither
   * the in-memory class nor its record RID survives a reopen.
   */
  @Test
  public void droppedClassDeletesRecordAndUnlinks() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("ToDrop");
    var droppedRid = schemaShared().getClass("ToDrop").getRecordId();
    assertNotNull("a created class must carry a bound record RID before it is dropped", droppedRid);
    assertTrue("freshly created class record must be linked from the root",
        rootClassLinks().contains(droppedRid));

    schema.dropClass("ToDrop");

    assertNull("dropped class must not be present", schemaShared().getClass("ToDrop"));
    assertFalse("the dropped class record must be unlinked from the root",
        rootClassLinks().contains(droppedRid));

    // Force a fromStream re-parse on every profile (see createdClassPersistsAsStandaloneLinkedRecord).
    schemaShared().reload(session);
    reOpen("admin", ADMIN_PASSWORD);
    assertNull("dropped class must not reappear after a reopen",
        schemaShared().getClass("ToDrop"));
    assertFalse("the dropped class record must stay unlinked after a reopen",
        rootClassLinks().contains(droppedRid));
  }

  /**
   * The root's non-link payload — the global-property table, the collection counter, and the
   * blob-collections set — survives a reopen. The global-property table is exercised through a
   * class property, which allocates a global slot; the counter is read straight off the root.
   */
  @Test
  public void rootNonLinkPayloadSurvivesReopen() {
    session.getMetadata().getSchema().createClass("PayloadClass");
    schemaShared().getClass("PayloadClass").createProperty(session, "amount", PropertyType.INTEGER);
    // Register a blob collection so the third documented payload element is actually exercised.
    int blobCollId = session.addCollection("payloadBlobColl");
    schemaShared().addBlobCollection(session, blobCollId);

    var counterBefore = readRootCollectionCounter();
    assertNotNull("the collection counter is part of the root non-link payload", counterBefore);
    var globalsBefore = schemaShared().getGlobalProperties().size();
    assertTrue("the blob-collections set must hold the registered collection before a reopen",
        schemaShared().getBlobCollections().contains(blobCollId));

    // Force a fromStream re-parse on every profile (see createdClassPersistsAsStandaloneLinkedRecord).
    schemaShared().reload(session);
    reOpen("admin", ADMIN_PASSWORD);

    var counterAfter = readRootCollectionCounter();
    assertEquals("the collection counter must survive a reopen", counterBefore, counterAfter);
    assertEquals("the global-property table must survive a reopen",
        globalsBefore, schemaShared().getGlobalProperties().size());
    assertTrue("the blob-collections set must survive a reopen",
        schemaShared().getBlobCollections().contains(blobCollId));

    var clsAfter = schemaShared().getClass("PayloadClass");
    assertNotNull(clsAfter);
    assertNotNull("the class property must survive the round-trip",
        clsAfter.getProperty("amount"));
  }

  /**
   * A failed schema save can roll back after its inner transaction bound a temporary record id
   * onto the shared class instance but before that id became persistent, leaving the class with a
   * non-persistent record id. The next save must self-heal by allocating a fresh standalone record
   * rather than loading against the record that never persisted. This pins that recovery: a class
   * carrying a simulated post-rollback temp id is re-saved, ends up with a persistent record id,
   * and the whole schema still survives a fromStream re-parse.
   */
  @Test
  public void nonPersistentRecordIdIsReallocatedOnNextSave() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("WedgedClass");
    var goodRid = schemaShared().getClass("WedgedClass").getRecordId();
    assertNotNull("the class must carry a persistent record RID after a clean create", goodRid);
    assertTrue("the freshly created record RID must be persistent", goodRid.isPersistent());

    // Simulate the post-failed-save state: a non-persistent temporary id left bound on the shared
    // class instance, exactly what a rolled-back inner save leaves behind.
    var staleTempRid = new ChangeableRecordId();
    assertFalse("the simulated stale RID must be non-persistent", staleTempRid.isPersistent());
    schemaShared().getClass("WedgedClass").setRecordId(staleTempRid);

    // Trigger a schema save that re-streams every class. The wedged class must not load against the
    // dead temp id; the save must succeed and rebind a persistent record RID.
    schema.createClass("TriggerSave");

    var healedRid = schemaShared().getClass("WedgedClass").getRecordId();
    assertNotNull("the re-saved class must carry a record RID again", healedRid);
    assertTrue("the re-saved class record RID must be persistent after self-heal",
        healedRid.isPersistent());
    assertTrue("the healed record RID must be linked from the root",
        rootClassLinks().contains(healedRid));

    // The schema must survive a fromStream re-parse with the wedged class intact.
    schemaShared().reload(session);
    reOpen("admin", ADMIN_PASSWORD);
    var reparsed = schemaShared().getClass("WedgedClass");
    assertNotNull("the self-healed class must survive a fromStream re-parse", reparsed);
    assertTrue("the reopened root link set must contain the healed record RID",
        rootClassLinks().contains(reparsed.getRecordId()));
  }

  /**
   * Forces the on-disk schema record's version field to the prior embedded-set version 4 and
   * confirms the tightened open-time gate rejects it with the export/import redirect rather than
   * parsing version-4 bytes as the new link-set format.
   */
  @Test
  public void versionFourDatabaseIsRejectedAndRedirected() {
    assertVersionRejected(SchemaShared.VERSION_NUMBER_V4);
  }

  /**
   * Same gate check for the legacy version 5 (the 2.0-M1/M2 marker). The pre-bump gate accepted
   * version 5; the tightened gate must reject it too, so a legacy database does not silently
   * mis-parse as the per-class link-set format.
   */
  @Test
  public void legacyVersionFiveDatabaseIsRejectedAndRedirected() {
    assertVersionRejected(SchemaShared.VERSION_NUMBER_V5);
  }

  /**
   * Stamps {@code schemaVersion} onto the on-disk root record, then reloads the schema and asserts
   * {@code fromStream} throws {@link ConfigurationException}. Both arms of the dropped accept-set
   * (4 and the legacy 5) run through this helper.
   */
  private void assertVersionRejected(int rejectedVersion) {
    var rootRid = schemaShared().getIdentity();
    session.executeInTx(tx -> {
      var root = session.<EntityImpl>load(rootRid);
      root.setProperty("schemaVersion", rejectedVersion);
    });

    try {
      schemaShared().reload(session);
      fail("reload must reject schema version " + rejectedVersion
          + " and redirect to export/import");
    } catch (ConfigurationException expected) {
      // The ConfigurationException type is the load-bearing signal; the message is operator-facing
      // prose that steers toward export/import and is intentionally not pinned to a substring.
      assertNotNull("rejection must carry a diagnostic message", expected.getMessage());
    }
  }
}
