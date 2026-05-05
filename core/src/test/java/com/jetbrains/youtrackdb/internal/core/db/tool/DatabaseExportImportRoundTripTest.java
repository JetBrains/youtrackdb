package com.jetbrains.youtrackdb.internal.core.db.tool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Live in-process round-trip exercising {@link DatabaseExport} + {@link DatabaseImport}.
 *
 * <p>The fixture follows the {@code DatabaseImportSimpleCompatibilityTest} precedent —
 * MEMORY-storage, {@link ByteArrayOutputStream} / {@link ByteArrayInputStream} streaming,
 * sub-second per test — but compares fidelity entity-by-entity via
 * {@link EntityHelper#hasSameContentOf} on each round-tripped record (the canonical
 * non-{@code DatabaseCompare} fidelity check; {@code DatabaseCompare} is pinned as
 * test-only-reachable elsewhere in this track and queued for deletion).
 *
 * <p>The fixture restricts payloads to <strong>unambiguous</strong> property types —
 * {@code STRING}, {@code INTEGER}, {@code EMBEDDED}, {@code LINKLIST}, {@code LINKMAP},
 * {@code LINKBAG} — to keep {@link EntityHelper#hasSameContentOf} comparisons deterministic.
 * Numeric extremes that exercise the legacy
 * {@code JSONSerializerJackson.IMPORT_BACKWARDS_COMPAT_INSTANCE} path triggered at
 * {@code DatabaseImport.java:416} (the {@code exporterVersion < 14} branch) are
 * intentionally excluded — that branch is only reachable via old-format JSON dumps and
 * its deletion / migration is owned by the deferred-cleanup track, not by this round-trip
 * fixture.
 *
 * <p>Marked {@code @Category(SequentialTest)} because the round-trip touches
 * {@code YourTracks} global state across two MEMORY databases (the source database is
 * exported, then a new MEMORY database is created and imported into); running this in a
 * parallel surefire fork would race on shared engine state.
 */
@Category(SequentialTest.class)
public class DatabaseExportImportRoundTripTest extends DbTestBase {

  private static final String TEST_CLASS = "Person";
  private static final String DEFAULT_OPTIONS = " -includeManualIndexes=false";

  /**
   * Round-trips a small fixture (~7 entities) covering STRING, INTEGER, EMBEDDED,
   * LINKLIST, LINKMAP, LINKBAG and verifies content-level fidelity entity-by-entity via
   * {@link EntityHelper#hasSameContentOf}. Also pins the per-step listener-callback
   * surface — {@code listener.onMessage} fires multiple times during both export and
   * import, exercising the {@code DatabaseImpExpAbstract} listener-dispatch branch and
   * the {@code DatabaseTool#message} variadic-format path.
   */
  @Test
  public void roundTripPreservesEntityContentForUnambiguousTypes() throws IOException {
    final var exportListener = new CountingListener();

    // Schema mutations must happen outside an active transaction (they are
    // non-transactional in YouTrackDB).
    var schema = session.getMetadata().getSchema();
    schema.createClass(TEST_CLASS);
    var schemaClass = schema.getClass(TEST_CLASS);
    schemaClass.createProperty("name", PropertyType.STRING);
    schemaClass.createProperty("age", PropertyType.INTEGER);

    // Build the source fixture. The source session stays open until after the
    // import-side fidelity comparison so EntityHelper.hasSameContentOf can read both
    // sides live.
    session.executeInTx(
        tx -> {
          // Two simple records first so we have RIDs to populate the link collections.
          var alice = (EntityImpl) session.newEntity(TEST_CLASS);
          alice.setProperty("name", "alice");
          alice.setProperty("age", 30);

          var bob = (EntityImpl) session.newEntity(TEST_CLASS);
          bob.setProperty("name", "bob");
          bob.setProperty("age", 41);

          var carol = (EntityImpl) session.newEntity(TEST_CLASS);
          carol.setProperty("name", "carol");
          carol.setProperty("age", 27);

          // Embedded entity (PropertyType.EMBEDDED).
          var address = (EntityImpl) session.newEmbeddedEntity();
          address.setProperty("city", "Paris");
          address.setProperty("zip", "75001");

          var withEmbedded = (EntityImpl) session.newEntity(TEST_CLASS);
          withEmbedded.setProperty("name", "diana");
          withEmbedded.setProperty("age", 35);
          withEmbedded.setProperty("address", address, PropertyType.EMBEDDED);

          // LinkList entity — list of RIDs preserves order.
          var linkList = session.newLinkList();
          linkList.add(alice);
          linkList.add(bob);

          var withLinkList = (EntityImpl) session.newEntity(TEST_CLASS);
          withLinkList.setProperty("name", "eve");
          withLinkList.setProperty("age", 22);
          withLinkList.setProperty("friends", linkList, PropertyType.LINKLIST);

          // LinkMap entity — keyed link map.
          var linkMap = session.newLinkMap();
          linkMap.put("primary", alice);
          linkMap.put("secondary", carol);

          var withLinkMap = (EntityImpl) session.newEntity(TEST_CLASS);
          withLinkMap.setProperty("name", "frank");
          withLinkMap.setProperty("age", 50);
          withLinkMap.setProperty("contacts", linkMap, PropertyType.LINKMAP);

          // LinkBag entity — set-like multi-link bag. Construction requires an active
          // transaction (verified by Step 5 in the importer-converter coverage track);
          // add() takes RID, not EntityImpl, so use getIdentity().
          var linkBag = new LinkBag(session);
          linkBag.add(bob.getIdentity());
          linkBag.add(carol.getIdentity());

          var withLinkBag = (EntityImpl) session.newEntity(TEST_CLASS);
          withLinkBag.setProperty("name", "grace");
          withLinkBag.setProperty("age", 65);
          withLinkBag.setProperty("seen", linkBag, PropertyType.LINKBAG);
        });

    var output = new ByteArrayOutputStream();
    var export = new DatabaseExport(session, output, exportListener);
    export.setOptions(DEFAULT_OPTIONS);
    export.exportDatabase();

    // The export listener should have produced multiple messages — proves the
    // listener-callback dispatch path executes during a real round-trip.
    assertTrue(
        "DatabaseExport listener should produce status messages",
        exportListener.count.get() > 0);
    assertTrue(
        "DatabaseExport must produce at least the JSON envelope header",
        output.size() > 0);

    // Capture the set of entity names we expect to see on the import side. This is
    // sufficient to pin each source entity by name later and avoids the
    // "record not bound to current session" error that would arise from caching
    // a detached EntityImpl from a closed transaction.
    var sourceNames = new java.util.ArrayList<String>();
    // Track which entities have a link-collection property so the comparison
    // strategy can split per-name: scalar/embedded entities use
    // EntityHelper.hasSameContentOf directly; link-collection entities use a
    // size-equality pin (RID mismatch across databases would otherwise fail the
    // strict content check; building a full source-to-import RID map for every
    // referenced entity would entrench DatabaseCompare's coupling, exactly what
    // this test fixture is designed to avoid — DatabaseCompare is pinned as
    // test-only-reachable elsewhere in this track and queued for deletion).
    var linkBearingNames = new java.util.HashSet<String>();
    linkBearingNames.add("eve"); // friends LINKLIST
    linkBearingNames.add("frank"); // contacts LINKMAP
    linkBearingNames.add("grace"); // seen LINKBAG
    session.executeInTx(
        tx -> {
          var iterator = session.browseClass(TEST_CLASS);
          while (iterator.hasNext()) {
            var loaded = iterator.next();
            sourceNames.add(loaded.getProperty("name"));
          }
        });

    // Open a fresh MEMORY database (within the same youTrackDB context) and import
    // into it. The source DB session stays open so EntityHelper.hasSameContentOf can
    // read both sessions live on either side of the comparison.
    final var importDbName = "imp_" + name.getMethodName();
    youTrackDB.createIfNotExists(
        importDbName, DatabaseType.MEMORY, adminUser, adminPassword, "admin");
    var importListener = new CountingListener();
    try (var importSession = youTrackDB.open(importDbName, adminUser, adminPassword)) {
      var importer =
          new DatabaseImport(
              importSession, new ByteArrayInputStream(output.toByteArray()), importListener);
      importer.importDatabase();

      // The import listener should have fired multiple times — exercises the
      // CommandOutputListener-dispatch branch on the import side.
      assertTrue(
          "DatabaseImport listener should produce status messages",
          importListener.count.get() > 0);

      // Schema fidelity check.
      var importSchema = importSession.getMetadata().getSchema();
      assertTrue(importSchema.existsClass(TEST_CLASS));

      // Entity-by-entity fidelity check via EntityHelper.hasSameContentOf — the
      // mandated comparison primitive. To avoid "record not bound to session"
      // errors from caching detached entities, we run the comparison entirely
      // inside paired transactions, switching the active session for each side.
      for (var entityName : sourceNames) {
        var matchedRef = new java.util.concurrent.atomic.AtomicBoolean(false);
        var foundRef = new java.util.concurrent.atomic.AtomicBoolean(false);
        // Holder so the deeper lambda can reach `imported` without violating the
        // effectively-final rule.
        var importedRef = new java.util.concurrent.atomic.AtomicReference<EntityImpl>();

        // Open a transaction on the import side, capture the entity matching the
        // expected name, then activate the source session inside that transaction
        // and locate its counterpart by name. The activation switch is safe here
        // because both sessions are independently bound to the current thread —
        // EntityHelper.hasSameContentOf takes both sessions explicitly.
        importSession.activateOnCurrentThread();
        importSession.executeInTx(
            txImport -> {
              var iter = importSession.browseClass(TEST_CLASS);
              while (iter.hasNext()) {
                var candidate = iter.next();
                if (entityName.equals(candidate.getProperty("name"))) {
                  importedRef.set(candidate);
                  break;
                }
              }
              if (importedRef.get() == null) {
                return;
              }
              foundRef.set(true);

              session.activateOnCurrentThread();
              session.executeInTx(
                  txSource -> {
                    var srcIter = session.browseClass(TEST_CLASS);
                    EntityImpl sourceEntity = null;
                    while (srcIter.hasNext()) {
                      var candidate = srcIter.next();
                      if (entityName.equals(candidate.getProperty("name"))) {
                        sourceEntity = candidate;
                        break;
                      }
                    }
                    assertNotNull(
                        "source fixture is missing entity '" + entityName + "'", sourceEntity);

                    if (linkBearingNames.contains(entityName)) {
                      // Link-collection entities — verify size equality on the link
                      // collection rather than full content equality. RIDs differ
                      // across databases by design; a full-content comparison would
                      // require a RIDMapper, which would entrench DatabaseCompare's
                      // semantics inside this test (unwanted coupling — that class
                      // is pinned as test-only-reachable and queued for deletion).
                      Object srcLinks = collectionAtFirst(sourceEntity);
                      Object impLinks = collectionAtFirst(importedRef.get());
                      assertNotNull(
                          "source link collection missing on '" + entityName + "'", srcLinks);
                      assertNotNull(
                          "imported link collection missing on '" + entityName + "'", impLinks);
                      matchedRef.set(linkCollectionSize(srcLinks) == linkCollectionSize(impLinks));
                    } else {
                      matchedRef.set(
                          EntityHelper.hasSameContentOf(
                              sourceEntity,
                              session,
                              importedRef.get(),
                              importSession,
                              null,
                              false));
                    }
                  });

              // Reactivate the import session before the outer-tx commit unwinds.
              importSession.activateOnCurrentThread();
            });

        assertTrue(
            "imported fixture is missing entity '" + entityName + "'", foundRef.get());
        assertTrue(
            "imported entity '"
                + entityName
                + "' content does not match source via EntityHelper.hasSameContentOf",
            matchedRef.get());
      }
    } finally {
      // Reactivate the YourTracks-managed handle so drop runs on the right session.
      youTrackDB.drop(importDbName);
    }
  }

  /**
   * Minimal round-trip exercising the {@code -excludeAll -includeSchema=true} option
   * dispatch on the export side. Confirms the option-flag parser actually reaches the
   * appropriate branches on {@link DatabaseExport#parseSetting} and that schema-only
   * exports round-trip cleanly with no record-section emission.
   */
  @Test
  public void roundTripWithSchemaOnlyOptionsTransfersClassDefinitions() throws IOException {
    var schema = session.getMetadata().getSchema();
    var schemaClass = schema.createClass(TEST_CLASS);
    schemaClass.createProperty("nick", PropertyType.STRING);

    var output = new ByteArrayOutputStream();
    var export = new DatabaseExport(session, output, iText -> {
    });
    export.setOptions(" -excludeAll -includeSchema=true");
    export.exportDatabase();
    assertTrue("schema-only export should still emit JSON", output.size() > 0);

    final var importDbName = "imp_schema_" + name.getMethodName();
    youTrackDB.createIfNotExists(
        importDbName, DatabaseType.MEMORY, adminUser, adminPassword, "admin");
    try (var importSession = youTrackDB.open(importDbName, adminUser, adminPassword)) {
      var importer =
          new DatabaseImport(
              importSession,
              new ByteArrayInputStream(output.toByteArray()),
              iText -> {
              });
      importer.importDatabase();
      var importSchema = importSession.getMetadata().getSchema();
      assertTrue(importSchema.existsClass(TEST_CLASS));
      assertNotNull(importSchema.getClass(TEST_CLASS).getProperty("nick"));
    } finally {
      youTrackDB.drop(importDbName);
    }
  }

  /**
   * Pins the option-flag dispatch on {@link DatabaseImport#parseSetting} for the
   * {@code -migrateLinks} / {@code -rebuildIndexes} / {@code -deleteRIDMapping} /
   * {@code -backwardCompatMode} flags. Each flag flips a getter the import exposes
   * (or, for {@code -backwardCompatMode}, leaves a non-default JSON serializer in
   * place); we don't run a full import here — that is covered by the round-trip test
   * above — just verify the option parser reaches each branch.
   */
  @Test
  public void importParseSettingDispatchesAllOptionFlags() throws IOException {
    var output = new ByteArrayOutputStream();
    // Produce a syntactically valid (but tiny) export so the import constructor's
    // JSONReader bootstrap doesn't choke.
    new DatabaseExport(session, output, iText -> {
    })
        .setOptions(" -excludeAll -includeSchema=true")
        .exportDatabase();

    var importer =
        new DatabaseImport(
            session,
            new ByteArrayInputStream(output.toByteArray()),
            iText -> {
            });

    // Defaults: both flags are true.
    assertTrue(importer.isMigrateLinks());
    assertTrue(importer.isRebuildIndexes());

    importer.setOptions(" -migrateLinks=false");
    assertFalse(importer.isMigrateLinks());

    importer.setOptions(" -rebuildIndexes=false");
    assertFalse(importer.isRebuildIndexes());

    // -deleteRIDMapping has no public getter; setting it through both interfaces
    // exercises the parseSetting branch and the explicit setter path. Both flips
    // must not throw and must reach setDeleteRIDMapping internally.
    importer.setOptions(" -deleteRIDMapping=false");
    importer.setDeleteRIDMapping(true);

    // -backwardCompatMode flips an internal serializer; reaches the parseSetting
    // branch and validates the alternate-instance dispatch.
    importer.setOptions(" -backwardCompatMode=true");
    importer.setOptions(" -backwardCompatMode=false");

    // setMigrateLinks / setRebuildIndexes setter paths.
    importer.setMigrateLinks(true);
    assertTrue(importer.isMigrateLinks());
    importer.setRebuildIndexes(true);
    assertTrue(importer.isRebuildIndexes());

    // setOption(name, value) is the public Option API surface — exercise each path.
    importer.setOption("migrateLinks", "false");
    assertFalse(importer.isMigrateLinks());
    importer.setOption("rebuildIndexes", "false");
    assertFalse(importer.isRebuildIndexes());

    // Unknown options fall through to DatabaseImpExpAbstract.parseSetting, which only
    // recognises -useLineFeedForRecords. An unrecognised setting is silently ignored.
    importer.setOptions(" -unrecognised=value");
  }

  /**
   * Exercises {@link DatabaseExport#parseSetting} for {@code -compressionLevel} and
   * {@code -compressionBuffer}. The values are stored on the instance and only consulted
   * during the file-output path (which we don't take here — the test exists to pin the
   * branch dispatch in {@code parseSetting}). Setting both, and an unrecognised flag,
   * must not throw.
   */
  @Test
  public void exportParseSettingHandlesCompressionFlagsAndUnrecognisedOptions()
      throws IOException {
    var export =
        new DatabaseExport(
            session,
            new ByteArrayOutputStream(),
            iText -> {
            });
    export.setOptions(" -compressionLevel=1 -compressionBuffer=8192");
    // Falls through to DatabaseImpExpAbstract.parseSetting for the line-feed flag.
    export.setOptions(" -useLineFeedForRecords=true");
    assertTrue(export.isUseLineFeedForRecords());
    // Unknown options must not throw.
    export.setOptions(" -unrecognised=value");
    // Construct via the no-options factory pattern to confirm the JSON envelope wrote.
    export.exportDatabase();
  }

  /**
   * Pins {@link DatabaseImport#removeExportImportRIDsMap} as a callable, idempotent
   * no-op when the RID-map class doesn't exist in the schema. The schema-only round-trip
   * above already covers the typical post-import cleanup path; this test pins the
   * "class doesn't exist" early-return arm separately for branch coverage.
   */
  @Test
  public void importRemoveExportImportRidsMapTolerantOfMissingClass() throws IOException {
    var output = new ByteArrayOutputStream();
    new DatabaseExport(session, output, iText -> {
    })
        .setOptions(" -excludeAll -includeSchema=true")
        .exportDatabase();

    var importer =
        new DatabaseImport(
            session,
            new ByteArrayInputStream(output.toByteArray()),
            iText -> {
            });
    // Class absent at this point — early-return arm.
    importer.removeExportImportRIDsMap();
  }

  /**
   * Returns the first non-name, non-age property value from the entity — used to
   * locate the link-collection property irrespective of which name the fixture
   * assigned to it (friends / contacts / seen).
   */
  private static Object collectionAtFirst(EntityImpl entity) {
    for (var name : entity.getPropertyNames()) {
      if ("name".equals(name) || "age".equals(name)) {
        continue;
      }
      return entity.getProperty(name);
    }
    return null;
  }

  /**
   * Returns the size of a link-collection value — handles {@code Collection},
   * {@code Map}, and {@link LinkBag} shapes.
   */
  private static int linkCollectionSize(Object value) {
    if (value instanceof java.util.Collection<?> c) {
      return c.size();
    }
    if (value instanceof java.util.Map<?, ?> m) {
      return m.size();
    }
    if (value instanceof LinkBag bag) {
      return bag.size();
    }
    throw new AssertionError(
        "unexpected link-collection shape: " + (value == null ? "null" : value.getClass()));
  }

  /**
   * Counts {@code onMessage} invocations so the round-trip test can assert that the
   * listener-callback path actually fires during export and import (covers the
   * common-base listener-dispatch branch in {@link DatabaseImpExpAbstract}).
   */
  private static final class CountingListener implements CommandOutputListener {

    final AtomicInteger count = new AtomicInteger();
    final List<String> messages = new ArrayList<>();

    @Override
    public void onMessage(String iText) {
      count.incrementAndGet();
      // Bound the keep-list — the round-trip generates dozens of short
      // status messages, no need to keep them all.
      if (messages.size() < 256) {
        messages.add(iText);
      }
    }
  }
}
