package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.index.IndexManagerEmbedded;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Pins the Track 8 G1 enabler (design G2.a / FM-G1): {@code SchemaShared.create} persists a
 * BOOTSTRAP-VALID empty-schema root — the {@code toStream} shape for an empty schema
 * ({@code schemaVersion} = current, empty {@code globalProperties}, {@code collectionCounter} 0,
 * empty {@code blobCollections}) — instead of an empty entity, so {@code copyForTx}'s
 * bootstrapped-schema precondition holds from the instant the root exists and any schema
 * transaction opened against a virgin database seeds cleanly.
 *
 * <p>The virgin state (a schema root that exists but predates every genesis DDL write) is only
 * reachable mid-genesis in production, so these tests reconstruct it at the unit level: a fresh
 * {@link SchemaEmbedded} instance runs {@code create} against the test session, allocating a new
 * root record exactly as genesis does before any class exists.
 */
public class GenesisSchemaBootstrapTest extends DbTestBase {

  /**
   * Design test pin G.5 #1 (red-first): a schema transaction opened immediately after the schema
   * root is created — the virgin-DB state the restructured genesis's phase-1 transaction will see
   * — seeds its tx-local copy cleanly, and the seeded copy is empty. RED at HEAD before the G2.a
   * fix: the root is an empty entity, so {@code copyForTx}'s bootstrapped-schema assert
   * ({@code globalProperties} present) trips in test builds.
   */
  @Test
  public void virginDbSchemaTransactionSeedsCleanly() {
    var virginSchema = new SchemaEmbedded();
    virginSchema.create(session);

    session.begin();
    try {
      var txCopy = virginSchema.copyForTx(session);
      assertNotNull("the tx-local seed from a virgin root must succeed", txCopy);
      assertTrue("the tx-local copy seeded from a virgin root must hold no classes",
          txCopy.getClasses(session).isEmpty());
      assertTrue("the tx-local copy seeded from a virgin root must hold no global properties",
          txCopy.getGlobalProperties().isEmpty());
    } finally {
      session.rollback();
    }
  }

  /**
   * Design test pin G.5 #2(a) (unit-level): the root record persisted by
   * {@code SchemaShared.create} parses as the bootstrap-valid empty-schema shape BEFORE any
   * genesis DDL runs — {@code schemaVersion} = {@link SchemaShared#CURRENT_VERSION_NUMBER},
   * {@code globalProperties} present and empty, {@code collectionCounter} 0,
   * {@code blobCollections} present and empty, and no class links (the {@code toStream} shape
   * for an empty schema leaves the {@code classes} link set unallocated).
   */
  @Test
  public void createdRootPersistsAsBootstrapValidEmptySchema() {
    var virginSchema = new SchemaEmbedded();
    virginSchema.create(session);

    session.executeInTx(transaction -> {
      EntityImpl root = session.load(virginSchema.getIdentity());

      Integer schemaVersion = root.getProperty("schemaVersion");
      assertEquals("the virgin root must carry the current schema version",
          Integer.valueOf(SchemaShared.CURRENT_VERSION_NUMBER), schemaVersion);

      List<EntityImpl> globalProperties = root.getProperty("globalProperties");
      assertNotNull("the virgin root must carry the (empty) global-property table —"
          + " copyForTx's bootstrapped-schema precondition", globalProperties);
      assertTrue("a virgin root's global-property table must be empty",
          globalProperties.isEmpty());

      Integer collectionCounter = root.getProperty("collectionCounter");
      assertEquals("the virgin root must persist a zero collection counter",
          Integer.valueOf(0), collectionCounter);

      Set<Integer> blobCollections = root.getEmbeddedSet("blobCollections");
      assertNotNull("the virgin root must carry the (empty) blob-collections set",
          blobCollections);
      assertTrue("a virgin root's blob-collections set must be empty", blobCollections.isEmpty());

      assertNull("an empty schema links no class records (the toStream empty shape)",
          root.getLinkSet("classes"));
    });
  }

  /**
   * Q-G3 verify-first (empirical arm): the empty {@code IndexManagerEmbedded} root shell does NOT
   * choke the reopen load the way the empty schema root chokes {@code copyForTx}. A fresh
   * index-manager instance loading the just-created empty shell tolerates it (the load's
   * {@code getLinkSet(CONFIG_INDEXES)} read is null-guarded) and yields zero indexes. The commit
   * arm (the genesis commit's index reconciliation) writes the root through
   * {@code getOrCreateLinkSet}, which is empty-shell-tolerant by construction — verified by code
   * trace, exercised end-to-end by Step 3's genesis transaction. Verdict recorded in track-8.md:
   * NO symmetric fix needed.
   */
  @Test
  public void emptyIndexManagerRootShellToleratesReopenLoad() {
    var storage = session.getSharedContext().getStorage();

    // Create the empty IM root shell exactly as genesis does (this repoints the test database's
    // index-manager record id — contained: the database is per-test and dropped afterwards).
    var virginIndexManager = new IndexManagerEmbedded(storage);
    virginIndexManager.create(session);

    // A second, fresh instance replays the reopen load against the empty shell.
    var reopenedIndexManager = new IndexManagerEmbedded(storage);
    reopenedIndexManager.load(session);

    assertTrue("the reopen load of an empty index-manager root shell must yield no indexes",
        reopenedIndexManager.getIndexes(session).isEmpty());
  }
}
