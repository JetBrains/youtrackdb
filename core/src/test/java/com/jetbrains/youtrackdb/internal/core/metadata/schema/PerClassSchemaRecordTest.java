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

    // Reopen forces SchemaShared.fromStream to rebuild from the on-disk per-class records.
    reOpen("admin", ADMIN_PASSWORD);

    var clsAfter = schemaShared().getClass("RoundTripClass");
    assertNotNull("class must survive a reopen", clsAfter);
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
    assertNotNull(droppedRid);
    assertTrue("freshly created class record must be linked from the root",
        rootClassLinks().contains(droppedRid));

    schema.dropClass("ToDrop");

    assertNull("dropped class must not be present", schemaShared().getClass("ToDrop"));
    assertFalse("the dropped class record must be unlinked from the root",
        rootClassLinks().contains(droppedRid));

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

    var counterBefore = readRootCollectionCounter();
    assertNotNull("the collection counter is part of the root non-link payload", counterBefore);
    var globalsBefore = schemaShared().getGlobalProperties().size();

    reOpen("admin", ADMIN_PASSWORD);

    var counterAfter = readRootCollectionCounter();
    assertEquals("the collection counter must survive a reopen", counterBefore, counterAfter);
    assertEquals("the global-property table must survive a reopen",
        globalsBefore, schemaShared().getGlobalProperties().size());

    var clsAfter = schemaShared().getClass("PayloadClass");
    assertNotNull(clsAfter);
    assertNotNull("the class property must survive the round-trip",
        clsAfter.getProperty("amount"));
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
      assertTrue("rejection must redirect to export/import",
          expected.getMessage().contains("export"));
    }
  }
}
