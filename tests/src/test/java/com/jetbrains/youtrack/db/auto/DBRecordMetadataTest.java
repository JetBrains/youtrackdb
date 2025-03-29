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

    session.begin();
    var doc = ((EntityImpl) session.newEntity());
    session.commit();
    for (var i = 0; i < 5; i++) {
      session.begin();
      if (!doc.getIdentity().isNew()) {
        var activeTx = session.getActiveTransaction();
        doc = activeTx.load(doc);
      }

      doc.setProperty("field", i);
      session.commit();

      session.begin();
      final var metadata = session.getRecordMetadata(doc.getIdentity());
      assetORIDEquals(doc.getIdentity(), metadata.getRecordId());
      var activeTx = session.getActiveTransaction();
      assertEquals(activeTx.<EntityImpl>load(doc).getVersion(), metadata.getVersion());
      session.commit();
    }
  }
}
