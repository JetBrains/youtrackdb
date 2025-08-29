package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;

public class DefaultCollectionTest {

  @Test
  public void defaultCollectionTest() {
    final var context =
        (YouTrackDBImpl)CreateDatabaseUtil.createDatabase("test",
            DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_MEMORY);
    try (final var session =
        context.open("test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD)) {
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
