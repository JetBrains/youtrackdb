package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.UserCredential;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;

public class DefaultCollectionTest {

  @Test
  public void defaultCollectionTest() {
    final var youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(getClass()));
    youTrackDB.create("test", DatabaseType.MEMORY,
        new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
    try (final var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD)) {
      var v =
          session.computeInTx(
              transaction -> {
                final var vertex = transaction.newVertex("V");
                vertex.setProperty("embedded", transaction.newEmbeddedEntity(),
                    PropertyType.EMBEDDED);
                return vertex;
              });

      var tx = session.begin();
      final EntityImpl embedded = tx.loadVertex(v).getProperty("embedded");
      Assert.assertFalse("Found: " + embedded.getIdentity(),
          embedded.getIdentity().isValidPosition());
      tx.commit();
    }
  }
}
