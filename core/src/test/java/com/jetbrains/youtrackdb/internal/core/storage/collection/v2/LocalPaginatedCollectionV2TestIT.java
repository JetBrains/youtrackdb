package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.collection.LocalPaginatedCollectionAbstract;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
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

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);
    youTrackDB.create(dbName, DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
    databaseDocumentTx = youTrackDB.open(dbName, "admin", "admin");

    storage = (AbstractStorage) databaseDocumentTx.getStorage();

    paginatedCollection = new PaginatedCollectionV2("paginatedCollectionTest", storage);
    paginatedCollection.configure(42, "paginatedCollectionTest");
    storage
        .getAtomicOperationsManager()
        .executeInsideAtomicOperation(
            atomicOperation -> paginatedCollection.create(atomicOperation));
  }
}
