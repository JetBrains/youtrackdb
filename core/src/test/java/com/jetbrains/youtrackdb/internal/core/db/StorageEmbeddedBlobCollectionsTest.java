package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Blob;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Test;

/**
 * Pins the storage-embedded blob-collection layout (Track 8 ruling R3): the {@code $blob<i>}
 * collections are created by {@link
 * com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage} inside the
 * storage-create WAL atomic operation (next to the {@code internal} collection), and
 * {@link SharedContext#create} only REGISTERS the storage's actual {@code $blob*} collections in
 * the schema by name — it never re-reads {@code STORAGE_BLOB_COLLECTIONS_COUNT} (single-read pin
 * CN50), so the count is frozen at storage birth.
 *
 * <p>Design test pin G.5 #7: the schema's blob registration equals the storage-created
 * {@code $blob*} collection ids, and a blob record round-trips, on both storage profiles.
 */
public class StorageEmbeddedBlobCollectionsTest {

  private static final String ADMIN_PASSWORD = "adminpwd";
  private static final Pattern BLOB_NAME = Pattern.compile("\\$blob\\d+");
  private static final byte[] PAYLOAD = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9};

  private YouTrackDBImpl youTrackDB;

  private YouTrackDBImpl createContext() {
    return (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(StorageEmbeddedBlobCollectionsTest.class));
  }

  @After
  public void tearDown() {
    if (youTrackDB != null) {
      youTrackDB.close();
      youTrackDB = null;
    }
  }

  /**
   * Resolves the ids of the storage's physical {@code $blob*} collections by name — the same
   * enumeration rule {@link SharedContext#create} uses for the schema registration.
   */
  private static Set<Integer> storageBlobCollectionIds(DatabaseSessionEmbedded session) {
    var ids = new HashSet<Integer>();
    for (var collectionName : session.getCollectionNames()) {
      if (BLOB_NAME.matcher(collectionName).matches()) {
        ids.add(session.getCollectionIdByName(collectionName));
      }
    }
    return ids;
  }

  /** Reads the schema's in-memory blob-collection registration as a set. */
  private static Set<Integer> registeredBlobCollectionIds(DatabaseSessionEmbedded session) {
    var ids = new HashSet<Integer>();
    for (var id : session.getBlobCollectionIds()) {
      ids.add(id);
    }
    return ids;
  }

  /** Reads the blob-collection set persisted on the schema root record. */
  private static Set<Integer> persistedBlobCollectionIds(DatabaseSessionEmbedded session) {
    var schemaShared = session.getSharedContext().getSchema();
    return session.computeInTx(tx -> {
      var root = session.<EntityImpl>load(schemaShared.getIdentity());
      Set<Integer> persisted = root.getEmbeddedSet("blobCollections");
      assertNotNull("the schema root must persist the blobCollections payload", persisted);
      return new HashSet<>(persisted);
    });
  }

  /**
   * The shared per-profile body: verifies the storage-birth layout ({@code internal} = 0,
   * {@code $blob0..N-1} = 1..N), the registration equality of pin G.5 #7, the persisted root
   * payload, and a blob record round-trip landing inside a storage-birth blob collection.
   */
  private void assertBlobLayoutAndRoundTrip(DatabaseType type) {
    var dbName = "blobLayout_" + type.name().toLowerCase();
    youTrackDB.create(dbName, type, "admin", ADMIN_PASSWORD, "admin");
    try (var session = youTrackDB.open(dbName, "admin", ADMIN_PASSWORD)) {
      var expectedCount =
          GlobalConfiguration.STORAGE_BLOB_COLLECTIONS_COUNT.getValueAsInteger();

      // Layout pinned by the design: internal = 0, $blob0..N-1 = 1..N (class collections
      // follow from N+1). The blob collections are storage-birth collections created right
      // after the internal collection inside the storage-create atomic operation.
      assertEquals("the internal collection keeps id 0",
          0, session.getCollectionIdByName(MetadataDefault.COLLECTION_INTERNAL_NAME));
      for (var i = 0; i < expectedCount; i++) {
        assertEquals("$blob" + i + " must occupy the storage-birth slot " + (i + 1),
            i + 1, session.getCollectionIdByName("$blob" + i));
      }

      var storageIds = storageBlobCollectionIds(session);
      assertEquals("exactly the configured number of $blob* collections must exist",
          expectedCount, storageIds.size());

      // Pin G.5 #7 (registration equality): the schema's blob registration equals the
      // storage-created $blob* collection ids, in memory and on the persisted root record.
      assertEquals("the schema blob registration must equal the storage-created $blob* ids",
          storageIds, registeredBlobCollectionIds(session));
      assertEquals("the persisted root payload must equal the storage-created $blob* ids",
          storageIds, persistedBlobCollectionIds(session));

      // Pin G.5 #7 (blob record round-trip): a blob lands in a storage-birth blob collection
      // and reads back byte-for-byte.
      session.begin();
      var blob = session.newBlob(PAYLOAD);
      session.commit();
      var rid = blob.getIdentity();
      assertTrue("the committed blob RID must be persistent", rid.isPersistent());
      assertTrue("the blob record must land in a storage-birth blob collection",
          storageIds.contains(rid.getCollectionId()));

      session.begin();
      var loaded = session.<Blob>load(rid);
      assertArrayEquals("the blob payload must round-trip byte-for-byte",
          PAYLOAD, loaded.toStream());
      session.rollback();

      // Force a fromStream re-parse of the root record: the registration survives it on every
      // profile (the memory profile keeps its SharedContext cached across session reopens, so
      // reload() is what makes the re-parse real here).
      session.getSharedContext().getSchema().reload(session);
      assertEquals("the blob registration must survive a schema root re-parse",
          storageIds, registeredBlobCollectionIds(session));
    } finally {
      youTrackDB.drop(dbName);
    }
  }

  /**
   * G.5 #7 on the memory profile: blob registration equals the storage-created {@code $blob*}
   * ids and a blob record round-trips.
   */
  @Test
  public void blobRegistrationEqualsStorageCreatedCollectionsOnMemoryProfile() {
    youTrackDB = createContext();
    assertBlobLayoutAndRoundTrip(DatabaseType.MEMORY);
  }

  /**
   * G.5 #7 on the disk profile: blob registration equals the storage-created {@code $blob*}
   * ids and a blob record round-trips.
   */
  @Test
  public void blobRegistrationEqualsStorageCreatedCollectionsOnDiskProfile() {
    youTrackDB = createContext();
    assertBlobLayoutAndRoundTrip(DatabaseType.DISK);
  }

  /**
   * CN50 (single config read at storage birth): a database created with a non-default
   * {@code STORAGE_BLOB_COLLECTIONS_COUNT} gets exactly that many {@code $blob*} collections at
   * ids 1..N, and the schema registration matches them — the process-global default (a different
   * value) is never consulted after create, because the register loop enumerates the storage's
   * actual collections by name instead of re-reading the configuration.
   */
  @Test
  public void blobCollectionsCountIsFrozenAtStorageBirth() {
    youTrackDB = createContext();
    var dbName = "blobCustomCount";
    var customCount = 3;
    // Guard the premise: the custom count must differ from the process default for this test to
    // prove the create-time configuration (not the global default) is what storage birth reads.
    assertTrue("test premise: custom count differs from the process-global default",
        customCount != GlobalConfiguration.STORAGE_BLOB_COLLECTIONS_COUNT.getValueAsInteger());
    var config = YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(
            GlobalConfiguration.STORAGE_BLOB_COLLECTIONS_COUNT, customCount)
        .build();
    youTrackDB.create(dbName, DatabaseType.MEMORY, config, "admin", ADMIN_PASSWORD, "admin");
    try (var session = youTrackDB.open(dbName, "admin", ADMIN_PASSWORD)) {
      var storageIds = storageBlobCollectionIds(session);
      assertEquals("exactly the create-time count of $blob* collections must exist",
          customCount, storageIds.size());
      for (var i = 0; i < customCount; i++) {
        assertEquals("$blob" + i + " must occupy the storage-birth slot " + (i + 1),
            i + 1, session.getCollectionIdByName("$blob" + i));
      }
      assertEquals("the schema blob registration must equal the storage-created $blob* ids",
          storageIds, registeredBlobCollectionIds(session));
      assertEquals("the persisted root payload must equal the storage-created $blob* ids",
          storageIds, persistedBlobCollectionIds(session));
    } finally {
      youTrackDB.drop(dbName);
    }
  }

  /**
   * The renumbered layout survives a full context close and disk reopen: a fresh
   * {@link SharedContext} loaded from disk shows the same registration and the previously
   * written blob record still reads back byte-for-byte.
   */
  @Test
  public void blobLayoutSurvivesDiskReopen() {
    youTrackDB = createContext();
    var dbName = "blobDiskReopen";
    youTrackDB.create(dbName, DatabaseType.DISK, "admin", ADMIN_PASSWORD, "admin");
    Set<Integer> storageIds;
    RID rid;
    try (var session = youTrackDB.open(dbName, "admin", ADMIN_PASSWORD)) {
      storageIds = storageBlobCollectionIds(session);
      session.begin();
      var blob = session.newBlob(PAYLOAD);
      session.commit();
      rid = blob.getIdentity();
    }
    // Full context close: the reopened context loads a fresh SharedContext from disk.
    youTrackDB.close();
    youTrackDB = createContext();
    try (var session = youTrackDB.open(dbName, "admin", ADMIN_PASSWORD)) {
      assertEquals("the blob registration must survive a disk reopen",
          storageIds, registeredBlobCollectionIds(session));
      session.begin();
      var loaded = session.<Blob>load(rid);
      assertArrayEquals("the blob payload must survive a disk reopen",
          PAYLOAD, loaded.toStream());
      session.rollback();
    } finally {
      youTrackDB.drop(dbName);
    }
  }
}
