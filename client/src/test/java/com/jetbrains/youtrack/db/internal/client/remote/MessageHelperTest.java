package com.jetbrains.youtrack.db.internal.client.remote;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.client.remote.message.MessageHelper;
import com.jetbrains.youtrack.db.internal.client.remote.message.MockChannel;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBAbstract;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class MessageHelperTest {

  @Test
  public void testIdentifiable() throws IOException {

    var youTrackDB = new YouTrackDBAbstract("embedded",
        YouTrackDBConfig.defaultConfig());

    youTrackDB.execute(
        "create database testIdentifiable memory users (admin identified by 'admin' role admin)");

    var db =
        (DatabaseSessionEmbedded) youTrackDB.open("testIdentifiable", "admin", "admin");
    try {
      db.createClass("Test");
      db.begin();
      var firstRecord = db.newEntity("Test");
      db.commit();

      var channel = new MockChannel();
      db.begin();
      var doc = ((EntityImpl) db.newEntity("Test"));
      var bags = new RidBag(db);
      bags.add(firstRecord.getIdentity());
      doc.setProperty("bag", bags);
      db.commit();

      MessageHelper.writeIdentifiable(db, channel, doc);
      channel.close();

      var rid = MessageHelper.readIdentifiable(db,
          channel, RecordSerializerNetworkFactory.current());
      assertThat(rid).isEqualTo(doc.getIdentity());
      Assert.assertTrue(
          db.getTransactionInternal().getRecordOperationsInternal()
              .isEmpty());
    } finally {
      db.close();
      youTrackDB.close();
    }
  }

  @Test
  public void testReadWriteTransactionEntry() {
    var request = new NetworkRecordOperation();

    request.setType(RecordOperation.UPDATED);
    request.setRecordType(RecordOperation.UPDATED);
    request.setId(new RecordId(25, 50));
    request.setDirtyCounter(456);
    request.setRecord(new byte[]{10, 20, 30});
    request.setVersion(100);
    request.setContentChanged(true);

    var outArray = new ByteArrayOutputStream();
    DataOutput out = new DataOutputStream(outArray);

    try {
      MessageHelper.writeTransactionEntry(out, request);
    } catch (Exception e) {
      e.printStackTrace();
      Assert.fail();
    }

    var in = new DataInputStream(new ByteArrayInputStream(outArray.toByteArray()));

    try {
      var result = MessageHelper.readTransactionEntry(in);
      Assert.assertEquals(request.getType(), result.getType());
      Assert.assertEquals(request.getRecordType(), result.getRecordType());
      Assert.assertEquals(request.getType(), result.getType());
      Assert.assertEquals(request.getId(), result.getId());
      Assert.assertArrayEquals(request.getRecord(), result.getRecord());
      Assert.assertEquals(request.getVersion(), result.getVersion());
      Assert.assertEquals(request.getDirtyCounter(), result.getDirtyCounter());
      Assert.assertEquals(request.isContentChanged(), result.isContentChanged());
    } catch (Exception e) {
      e.printStackTrace();
      Assert.fail();
    }
  }
}
