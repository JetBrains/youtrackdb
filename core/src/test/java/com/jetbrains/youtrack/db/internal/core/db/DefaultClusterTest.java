package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;

public class DefaultClusterTest {

  @Test
  public void defaultClusterTest() {
    final YouTrackDB context =
        CreateDatabaseUtil.createDatabase("test",
            DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_MEMORY);
    try (final var session =
        context.open("test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD)) {
      var v =
          session.computeInTx(
              () -> {
                final var vertex = session.newVertex("V");
                vertex.setProperty("embedded", session.newEmbeddedEntity(), PropertyType.EMBEDDED);
                return vertex;
              });

      session.begin();
      final EntityImpl embedded = session.bindToSession(v).getProperty("embedded");
      Assert.assertFalse("Found: " + embedded.getIdentity(), embedded.getIdentity().isValid());
      session.commit();
    }
  }
}
