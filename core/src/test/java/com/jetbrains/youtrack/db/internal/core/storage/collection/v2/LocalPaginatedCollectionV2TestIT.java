package com.jetbrains.youtrack.db.internal.core.storage.collection.v2;

import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.storage.collection.LocalPaginatedCollectionAbstract;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractStorage;
import java.io.File;
import java.io.IOException;
import org.junit.BeforeClass;

public class LocalPaginatedCollectionV2TestIT extends LocalPaginatedCollectionAbstract {

  @BeforeClass
  public static void beforeClass() throws IOException {
    buildDirectory = System.getProperty("buildDirectory");
    if (buildDirectory == null || buildDirectory.isEmpty()) {
      buildDirectory = ".";
    }

    buildDirectory += File.separator + LocalPaginatedCollectionV2TestIT.class.getSimpleName();
    FileUtils.deleteRecursively(new File(buildDirectory));

    dbName = "collectionTest";

    final var config = YouTrackDBConfig.defaultConfig();
    youTrackDB = YourTracks.embedded(buildDirectory, config);
    youTrackDB.execute(
        "create database " + dbName + " disk users ( admin identified by 'admin' role admin)");

    databaseDocumentTx = (DatabaseSessionInternal) youTrackDB.open(dbName, "admin", "admin");

    storage = (AbstractStorage) databaseDocumentTx.getStorage();

    paginatedCollection = new PaginatedCollectionV2("paginatedCollectionTest", storage);
    paginatedCollection.configure(42, "paginatedCollectionTest");
    storage
        .getAtomicOperationsManager()
        .executeInsideAtomicOperation(
            atomicOperation -> paginatedCollection.create(atomicOperation));
  }
}
