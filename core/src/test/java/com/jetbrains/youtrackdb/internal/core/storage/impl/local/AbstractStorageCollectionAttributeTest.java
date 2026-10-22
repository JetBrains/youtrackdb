package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.config.StoragePaginatedCollectionConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection.ATTRIBUTES;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Tests collection attribute dispatch and its persisted storage configuration. */
public class AbstractStorageCollectionAttributeTest {

  @Rule
  public TemporaryFolder directory = new TemporaryFolder();

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;
  private AbstractStorage storage;
  private int collectionId;

  @Before
  public void createStorage() {
    youTrackDB = openContext();
    youTrackDB.create("attributes", DatabaseType.DISK,
        "admin", DbTestBase.ADMIN_PASSWORD, "admin");
    openSession();
    collectionId = session.addCollection("attribute_test");
  }

  @After
  public void closeStorage() {
    if (session != null && !session.isClosed()) {
      session.close();
    }
    if (youTrackDB != null) {
      youTrackDB.close();
    }
  }

  /** Setting CONFLICTSTRATEGY updates the live collection and survives a fresh storage open. */
  @Test
  public void conflictStrategyUpdatesCollectionAndPersistsAcrossReopen() {
    assertNull(storage.getCollectionRecordConflictStrategy(collectionId));
    storage.setCollectionAttribute(collectionId, ATTRIBUTES.CONFLICTSTRATEGY, "content");
    assertStrategy("content");

    session.close();
    youTrackDB.close();
    youTrackDB = openContext();
    openSession();

    assertEquals("attribute_test", storage.getCollectionNameById(collectionId));
    assertStrategy("content");
  }

  /** A null attribute reports the switch failure through the atomic operation wrapper. */
  @Test
  public void nullAttributeReportsFailureWithoutChangingConflictStrategy() {
    storage.setCollectionAttribute(collectionId, ATTRIBUTES.CONFLICTSTRATEGY, "version");
    final var failure = assertThrows(StorageException.class,
        () -> storage.setCollectionAttribute(collectionId, null, "content"));
    assertEquals(NullPointerException.class, failure.getCause().getClass());
    assertStrategy("version");
  }

  private void assertStrategy(String expected) {
    assertEquals(expected, storage.getCollectionRecordConflictStrategy(collectionId));
    final var configuration = (StoragePaginatedCollectionConfiguration) storage.configuration
        .getCollections().get(collectionId);
    assertEquals(expected, configuration.conflictStrategy);
  }

  private YouTrackDBImpl openContext() {
    return (YouTrackDBImpl) YourTracks.instance(directory.getRoot().toPath());
  }

  private void openSession() {
    session = youTrackDB.open("attributes", "admin", DbTestBase.ADMIN_PASSWORD);
    storage = (AbstractStorage) session.getStorage();
  }
}
