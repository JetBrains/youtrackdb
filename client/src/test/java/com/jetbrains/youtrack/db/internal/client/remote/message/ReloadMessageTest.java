package com.jetbrains.youtrack.db.internal.client.remote.message;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import java.io.IOException;
import org.junit.Test;

public class ReloadMessageTest extends DbTestBase {

  @Test
  public void testWriteReadResponse() throws IOException {
    var configuration =
        session.getStorage().getConfiguration();
    var responseWrite = new ReloadResponse37(configuration);
    var channel = new MockChannel();
    responseWrite.write(session, channel,
        ChannelBinaryProtocol.CURRENT_PROTOCOL_VERSION, null);
    channel.close();
    var responseRead = new ReloadResponse37();
    responseRead.read(session, channel, null);
    var payload = responseRead.getPayload();
    assertEquals(configuration.getProperties().size(), payload.getProperties().size());
    for (var i = 0; i < configuration.getProperties().size(); i++) {
      assertEquals(configuration.getProperties().get(i).name, payload.getProperties().get(i).name);
      assertEquals(
          configuration.getProperties().get(i).value, payload.getProperties().get(i).value);
    }
    assertEquals(configuration.getDateFormat(), payload.getDateFormat());
    assertEquals(configuration.getDateTimeFormat(), payload.getDateTimeFormat());
    assertEquals(configuration.getName(), payload.getName());
    assertEquals(configuration.getVersion(), payload.getVersion());
    assertEquals(configuration.getDirectory(), payload.getDirectory());
    assertEquals(configuration.getSchemaRecordId(), payload.getSchemaRecordId().toString());
    assertEquals(configuration.getIndexMgrRecordId(), payload.getIndexMgrRecordId().toString());
    assertEquals(configuration.getCollectionSelection(), payload.getCollectionSelection());
    assertEquals(configuration.getConflictStrategy(), payload.getConflictStrategy());
    assertEquals(configuration.isValidationEnabled(), payload.isValidationEnabled());
    assertEquals(configuration.getLocaleLanguage(), payload.getLocaleLanguage());
    assertEquals(configuration.getMinimumCollections(), payload.getMinimumCollections());
    assertEquals(configuration.isStrictSql(), payload.isStrictSql());
    assertEquals(configuration.getCharset(), payload.getCharset());
    assertEquals(configuration.getLocaleCountry(), payload.getLocaleCountry());
    assertEquals(configuration.getTimeZone(), payload.getTimeZone());
    assertEquals(configuration.getRecordSerializer(), payload.getRecordSerializer());
    assertEquals(configuration.getRecordSerializerVersion(), payload.getRecordSerializerVersion());
    assertEquals(configuration.getBinaryFormatVersion(), payload.getBinaryFormatVersion());

    assertEquals(configuration.getCollections().size(), payload.getCollections().size());
    for (var i = 0; i < configuration.getCollections().size(); i++) {
      assertEquals(
          configuration.getCollections().get(i).getId(), payload.getCollections().get(i).getId());
      assertEquals(
          configuration.getCollections().get(i).getName(), payload.getCollections().get(i).getName());
    }
  }
}
