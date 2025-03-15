package com.jetbrains.youtrack.db.auto;

import static org.testng.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 * @since 11.03.13 12:00
 */
@Test
public class DBRecordMetadataTest extends BaseDBTest {

  @Parameters(value = "remote")
  public DBRecordMetadataTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  private static void assetORIDEquals(RID actual, RID expected) {
    assertEquals(actual.getClusterId(), expected.getClusterId());
    assertEquals(actual.getClusterPosition(), expected.getClusterPosition());
  }

  public void testGetRecordMetadata() {

    var doc = ((EntityImpl) session.newEntity());
    for (var i = 0; i < 5; i++) {
      session.begin();
      if (!doc.getIdentity().isNew()) {
        doc = session.bindToSession(doc);
      }

      doc.setProperty("field", i);
      session.commit();

      final var metadata = session.getRecordMetadata(doc.getIdentity());
      assetORIDEquals(doc.getIdentity(), metadata.getRecordId());
      assertEquals(session.bindToSession(doc).getVersion(), metadata.getVersion());
    }
  }
}
