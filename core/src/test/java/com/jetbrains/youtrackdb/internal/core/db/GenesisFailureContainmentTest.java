package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.exception.GenesisIncompleteException;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import org.junit.After;
import org.junit.Test;

/**
 * Pins the genesis-failure containment (Track 8 design §A1, CS34+CN48, gate-1 CS45+CN54; design
 * test pin G.5 #9) against the W-state enumeration:
 *
 * <ul>
 *   <li>(a) exception path — an injected phase-1 or phase-2 failure propagates AND cleans up:
 *       the storage is purged from the maps, the on-disk residue removed, {@code exists()} is
 *       false again, a create-retry succeeds, and {@code createIfNotExists} re-creates instead
 *       of silently no-op'ing on a corpse;</li>
 *   <li>(b) crash path — a marker-less database (the W6/W7 corpse shape) refuses both
 *       {@code open} and {@code openNoAuthenticate} loudly, while {@code drop()} discards it
 *       WITHOUT surfacing the refusal (CN54); the same marker-less state over a genuinely
 *       complete database is the W9a window, whose fail-closed FALSE refusal is accepted by
 *       design (CS45);</li>
 *   <li>(c) success path — the genesis-completion marker is present after every successful
 *       create, on both storage profiles.</li>
 * </ul>
 */
public class GenesisFailureContainmentTest {

  private static final String ADMIN_PASSWORD = "adminpwd";

  private YouTrackDBImpl youTrackDB;

  private YouTrackDBImpl createContext() {
    return (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(GenesisFailureContainmentTest.class));
  }

  @After
  public void tearDown() {
    if (youTrackDB != null) {
      youTrackDB.close();
      youTrackDB = null;
    }
  }

  /**
   * Throws once out of {@code onBeforeTxCommit} of the selected genesis commit: phase 1 (the
   * mutex-engaged schema commit) or phase 2 (the first record-carrying commit after phase 1) —
   * a genuine mid-genesis failure injection through a production seam (listener exceptions
   * abort the commit and propagate out of the creation).
   */
  private static final class PhaseFailureListener implements SessionListener {

    private final boolean failPhaseTwo;
    private boolean phaseOneSeen;
    private boolean injected;

    private PhaseFailureListener(boolean failPhaseTwo) {
      this.failPhaseTwo = failPhaseTwo;
    }

    @Override
    public void onBeforeTxCommit(final Transaction transaction) {
      if (injected) {
        return;
      }
      var session = transaction.getDatabaseSession();
      var mutexEngaged =
          session.getSharedContext().getMetadataWriteMutex().isEngagedBy(session);
      if (!failPhaseTwo && mutexEngaged) {
        injected = true;
        throw new IllegalStateException("injected phase-1 genesis failure");
      }
      if (failPhaseTwo) {
        if (mutexEngaged) {
          phaseOneSeen = true;
        } else if (phaseOneSeen
            && ((FrontendTransactionImpl) transaction).getRecordOperationsCount() > 0) {
          injected = true;
          throw new IllegalStateException("injected phase-2 genesis failure");
        }
      }
    }
  }

  /**
   * Pin G.5 #9(a), phase-1 arm on both profiles: the injected failure propagates out of the
   * create, no residue survives ({@code exists()} false, storage absent from the internal map),
   * and a retry with a clean configuration creates the database successfully.
   */
  @Test
  public void failedPhaseOneCleansUpAndRetrySucceeds() {
    assertInjectedFailureCleansUp(DatabaseType.MEMORY, false);
    tearDown();
    assertInjectedFailureCleansUp(DatabaseType.DISK, false);
  }

  /**
   * Pin G.5 #9(a), phase-2 arm on both profiles: same containment for a failure injected into
   * the merged roles+users data transaction.
   */
  @Test
  public void failedPhaseTwoCleansUpAndRetrySucceeds() {
    assertInjectedFailureCleansUp(DatabaseType.MEMORY, true);
    tearDown();
    assertInjectedFailureCleansUp(DatabaseType.DISK, true);
  }

  private void assertInjectedFailureCleansUp(DatabaseType type, boolean failPhaseTwo) {
    youTrackDB = createContext();
    var internal = (YouTrackDBInternalEmbedded) youTrackDB.internal;
    var dbName = "containment_" + type.name().toLowerCase() + (failPhaseTwo ? "_p2" : "_p1");
    var failingConfig = YouTrackDBConfig.builder()
        .addSessionListener(new PhaseFailureListener(failPhaseTwo))
        .build();

    IllegalStateException injected = null;
    try {
      youTrackDB.create(dbName, type, failingConfig, "admin", ADMIN_PASSWORD, "admin");
    } catch (RuntimeException e) {
      for (Throwable t = e; t != null; t = t.getCause()) {
        if (t instanceof IllegalStateException ise
            && ise.getMessage() != null && ise.getMessage().startsWith("injected phase-")) {
          injected = ise;
          break;
        }
      }
      if (injected == null) {
        throw e;
      }
    }
    assertNotNull("the injected genesis failure must propagate out of the create", injected);

    // §A1 primary containment: no residue — exists() false, storage purged from the maps.
    assertFalse("a failed create must leave exists() == false", youTrackDB.exists(dbName));
    assertNull("a failed create must purge the storage from the internal map",
        internal.getStorage(dbName));

    // A retry with a clean configuration re-creates cleanly.
    youTrackDB.create(dbName, type, "admin", ADMIN_PASSWORD, "admin");
    try (var session = youTrackDB.open(dbName, "admin", ADMIN_PASSWORD)) {
      assertTrue(session.getMetadata().getSchema().existsClass("OUser"));
    } finally {
      youTrackDB.drop(dbName);
    }
  }

  /**
   * Pin G.5 #9(a), the {@code failIfExists=false} half: after an injected genesis failure,
   * {@code createIfNotExists} must RE-CREATE the database (returning {@code true}) instead of
   * silently no-op'ing over a condemned residue — the exact silent-adoption hazard the §A1
   * cleanup exists to prevent.
   */
  @Test
  public void createIfNotExistsRecreatesAfterFailedCreate() {
    youTrackDB = createContext();
    var dbName = "containmentIfNotExists";
    var failingConfig = YouTrackDBConfig.builder()
        .addSessionListener(new PhaseFailureListener(false))
        .build();
    var injectedSeen = false;
    try {
      youTrackDB.create(dbName, DatabaseType.MEMORY, failingConfig,
          "admin", ADMIN_PASSWORD, "admin");
      fail("the injected genesis failure must propagate");
    } catch (RuntimeException e) {
      // Verify the failure is OUR injection (review TQ17): an unrelated environmental failure
      // would make the re-create below pass trivially without exercising the cleaned-residue
      // path this test exists to pin.
      for (Throwable t = e; t != null; t = t.getCause()) {
        if (t instanceof IllegalStateException ise
            && ise.getMessage() != null && ise.getMessage().startsWith("injected phase-")) {
          injectedSeen = true;
          break;
        }
      }
      if (!injectedSeen) {
        throw e;
      }
    }
    assertTrue("the injected genesis failure must be the create's cause", injectedSeen);

    assertTrue("createIfNotExists must RE-CREATE after a cleaned-up failed create",
        youTrackDB.createIfNotExists(dbName, DatabaseType.MEMORY));
    var storage = ((YouTrackDBInternalEmbedded) youTrackDB.internal).getStorage(dbName);
    assertNotNull(storage);
    assertEquals("the re-created database must carry the genesis-completion marker",
        "true", storage.getProperty(SharedContext.GENESIS_COMPLETED_PROPERTY));
  }

  /**
   * Constructs the pre-marker crash-corpse state (W6/W7/W9a) on DISK and reopens it in a FRESH
   * context (review TQ18: the refusal pin exercises the reopened-config read of the marker,
   * not a live in-memory flip): the database is created completely, its marker flipped off,
   * and the whole context closed — the returned fresh context loads everything from disk.
   */
  private YouTrackDBInternalEmbedded createMarkerlessDiskCorpse(String dbName) {
    youTrackDB = createContext();
    var internal = (YouTrackDBInternalEmbedded) youTrackDB.internal;
    youTrackDB.create(dbName, DatabaseType.DISK, "admin", ADMIN_PASSWORD, "admin");
    internal.getStorage(dbName)
        .setProperty(SharedContext.GENESIS_COMPLETED_PROPERTY, "false");
    youTrackDB.close();
    youTrackDB = createContext();
    return (YouTrackDBInternalEmbedded) youTrackDB.internal;
  }

  /**
   * Pin G.5 #9(b) + CS45: a disk database whose genesis-completion marker is absent — the
   * W6/W7 half-genesis corpse shape, indistinguishable by design from the accepted W9a window
   * (a genuinely COMPLETE database whose marker write was not yet durable, which is exactly
   * what this test constructs) — refuses both {@code open} and {@code openNoAuthenticate}
   * loudly with the discard-and-recreate refusal, read from the reopened configuration in a
   * fresh context. After the refusal the corpse's storage is closed and unregistered (reviews
   * BG14/CN55/CS55), so its file locks do not outlive the refusal; {@code drop()} still
   * discards it.
   */
  @Test
  public void markerlessDatabaseIsRefusedOnOpenAndOpenNoAuthenticate() {
    var dbName = "markerlessRefusal";
    var internal = createMarkerlessDiskCorpse(dbName);

    try {
      youTrackDB.open(dbName, "admin", ADMIN_PASSWORD);
      fail("open must refuse a marker-less database");
    } catch (RuntimeException e) {
      assertGenesisRefusal(e);
    }
    assertNull("the refused corpse's storage must be closed and unregistered (BG14)",
        internal.getStorage(dbName));

    try {
      internal.openNoAuthenticate(dbName, "admin");
      fail("openNoAuthenticate must refuse a marker-less database");
    } catch (RuntimeException e) {
      assertGenesisRefusal(e);
    }
    assertNull("the refused corpse's storage must be closed and unregistered (BG14)",
        internal.getStorage(dbName));

    // The corpse is still fully discardable after the refusals unregistered its storage.
    youTrackDB.drop(dbName);
    assertFalse(youTrackDB.exists(dbName));
  }

  private static void assertGenesisRefusal(RuntimeException failure) {
    GenesisIncompleteException refusal = null;
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof GenesisIncompleteException g) {
        refusal = g;
        break;
      }
    }
    assertNotNull("the refusal must be the genesis-completion check, saw: " + failure, refusal);
    assertTrue("the refusal must prescribe discard-and-recreate, saw: " + refusal.getMessage(),
        refusal.getMessage().contains("did not run to completion"));
  }

  /**
   * Pin G.5 #9(b), the CN54 drop-path exemption: {@code drop()} discards a marker-less disk
   * corpse in a fresh context WITHOUT surfacing the refusal — the completion check gates opens
   * FOR USE, never the discard it prescribes. The {@code onDrop} lifecycle listeners are
   * skipped (no usable session), an accepted consequence.
   */
  @Test
  public void dropDiscardsCorpseWithoutSurfacingRefusal() {
    var dbName = "corpseDrop";
    createMarkerlessDiskCorpse(dbName);

    // Must not throw: corpse deletion succeeds without surfacing the open refusal.
    youTrackDB.drop(dbName);
    assertFalse("the corpse must be gone after drop", youTrackDB.exists(dbName));
  }

  /**
   * Review BG14, the {@code failIfExists=false} crash-path half: a create that tolerates an
   * existing database (the {@code doCreate(failIfExists=false)} route, reached through the
   * user-credential {@code createIfNotExists} overloads and the internal create API) must be
   * LOUD over a genesis-incomplete corpse (a refusal naming the marker and both recovery
   * routes) instead of the pre-fix silent "already exists, nothing to do" no-op that left the
   * operator unaware the residue blocks the name. (The config-only boolean
   * {@code createIfNotExists} overload short-circuits on {@code exists()} without entering the
   * create path and keeps its false-means-exists contract; the very next open of the corpse
   * refuses loudly.) The corpse stays discardable afterwards.
   */
  @Test
  public void createIfNotExistsIsLoudOverGenesisIncompleteCorpse() {
    var dbName = "corpseIfNotExists";
    createMarkerlessDiskCorpse(dbName);

    try {
      youTrackDB.createIfNotExists(dbName, DatabaseType.DISK, "admin", ADMIN_PASSWORD, "admin");
      fail("createIfNotExists must refuse a genesis-incomplete corpse loudly");
    } catch (RuntimeException e) {
      GenesisIncompleteException refusal = null;
      for (Throwable t = e; t != null; t = t.getCause()) {
        if (t instanceof GenesisIncompleteException g) {
          refusal = g;
          break;
        }
      }
      assertNotNull("the loud no-op refusal must be the genesis check, saw: " + e, refusal);
      assertTrue("the refusal must name both recovery routes, saw: " + refusal.getMessage(),
          refusal.getMessage().contains("drop it and re-create")
              && refusal.getMessage().contains("older version"));
    }

    youTrackDB.drop(dbName);
    assertFalse(youTrackDB.exists(dbName));
  }

  /**
   * Review CS52 (Step 6 pin M.5 #10's prerequisite): the genesis-completion refusal fires
   * AFTER Track 2's schema-version gate, so a marker-less database whose schema root declares
   * an OLD format gets the version gate's export/reimport REDIRECT — never the
   * data-destroying discard-and-recreate advice. The old format is simulated by rewriting the
   * schema root's {@code schemaVersion} to the legacy value 5 on an otherwise complete disk
   * database whose marker is then removed (the closest constructible fidelity to a real v5
   * database: same marker-less config, same old-version root, read from disk by a fresh
   * context).
   */
  @Test
  public void oldFormatDatabaseGetsMigrationRedirectNotGenesisRefusal() {
    youTrackDB = createContext();
    var internal = (YouTrackDBInternalEmbedded) youTrackDB.internal;
    var dbName = "oldFormatRedirect";
    youTrackDB.create(dbName, DatabaseType.DISK, "admin", ADMIN_PASSWORD, "admin");
    try (var session = youTrackDB.open(dbName, "admin", ADMIN_PASSWORD)) {
      var schemaShared = session.getSharedContext().getSchema();
      session.executeInTx(transaction -> {
        EntityImpl root = session.load(schemaShared.getIdentity());
        root.setProperty("schemaVersion", 5);
      });
    }
    internal.getStorage(dbName)
        .setProperty(SharedContext.GENESIS_COMPLETED_PROPERTY, "false");
    youTrackDB.close();
    youTrackDB = createContext();

    try {
      youTrackDB.open(dbName, "admin", ADMIN_PASSWORD);
      fail("an old-format database must be rejected");
    } catch (RuntimeException e) {
      ConfigurationException redirect = null;
      for (Throwable t = e; t != null; t = t.getCause()) {
        assertFalse("the genesis refusal must NOT pre-empt the version gate (CS52), saw: " + e,
            t instanceof GenesisIncompleteException);
        if (t instanceof ConfigurationException c) {
          redirect = c;
        }
      }
      assertNotNull("the version gate's rejection must fire, saw: " + e, redirect);
      assertTrue("the rejection must carry the export/reimport redirect, saw: "
          + redirect.getMessage(),
          redirect.getMessage().contains("export"));
    }

    // The old-format corpse is still discardable (drop tolerates any load failure by deleting
    // in its finally; the version-gate rejection surfaces afterwards, as at HEAD).
    try {
      youTrackDB.drop(dbName);
    } catch (RuntimeException ignored) {
      // the version-gate rejection surfacing out of drop is today's contract for non-genesis
      // load failures; the deletion below is what matters
    }
    assertFalse(youTrackDB.exists(dbName));
  }

  // NOTE (review CS54): the never-opened-corpse discard (drop() of a W1/W2 corpse whose
  // storage open fails outright) is guarded in production by AbstractStorage.doDelete skipping
  // the dirty-flag write for a CLOSED storage. A drop-level suite test is NOT feasible: the
  // corpse's failed OPEN attempt (drop always opens first) trips a pre-existing resource leak
  // on the storage open-failure path (partially initialized WAL/disk-cache native buffers are
  // not released), which kills the test JVM at shutdown under the harness's direct-memory
  // tracking — an issue that predates this track and is recorded in the track file.

  /**
   * Pin G.5 #9(c): the genesis-completion marker is present after a successful create on BOTH
   * storage profiles, and the created database reopens normally.
   */
  @Test
  public void markerPresentAfterSuccessfulCreateOnBothProfiles() {
    youTrackDB = createContext();
    var internal = (YouTrackDBInternalEmbedded) youTrackDB.internal;
    for (var type : new DatabaseType[] {DatabaseType.MEMORY, DatabaseType.DISK}) {
      var dbName = "markerSuccess_" + type.name().toLowerCase();
      youTrackDB.create(dbName, type, "admin", ADMIN_PASSWORD, "admin");
      try {
        assertEquals("the marker must be present after a successful " + type + " create",
            "true",
            internal.getStorage(dbName).getProperty(SharedContext.GENESIS_COMPLETED_PROPERTY));
        try (var session = youTrackDB.open(dbName, "admin", ADMIN_PASSWORD)) {
          assertTrue(session.getMetadata().getSchema().existsClass("OUser"));
        }
      } finally {
        youTrackDB.drop(dbName);
      }
    }
  }
}
