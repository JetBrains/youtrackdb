package com.jetbrains.youtrack.db.internal.client.remote.message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;

/**
 *
 */
public class RemotePushMessagesTest extends DbTestBase {

  @Test
  public void testDistributedConfig() throws IOException {
    var channel = new MockChannel();
    List<String> hosts = new ArrayList<>();
    hosts.add("one");
    hosts.add("two");
    var request = new PushDistributedConfigurationRequest(hosts);
    request.write(null, channel);
    channel.close();

    var readRequest = new PushDistributedConfigurationRequest();
    readRequest.read(session, channel);
    assertEquals(2, readRequest.getHosts().size());
    assertEquals("one", readRequest.getHosts().get(0));
    assertEquals("two", readRequest.getHosts().get(1));
  }

  @Test
  public void testIndexManager() throws IOException {
    try (YouTrackDB youTrackDB = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig())) {
      youTrackDB.execute(
          "create database test memory users (admin identified by 'admin' role admin)");
      try (var session = (DatabaseSessionInternal) youTrackDB.open("test", "admin", "admin")) {
        session.begin();
        var channel = new MockChannel();

        var request = new PushIndexManagerRequest();
        request.write(null, channel);
        channel.close();
        session.commit();

        var readRequest = new PushIndexManagerRequest();
        readRequest.read(session, channel);
      }
    }
  }

  @Test
  public void testSchema() throws IOException {
    YouTrackDB youTrackDB = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig());
    youTrackDB.execute(
        "create database test memory users (admin identified by 'admin' role admin)");
    var session = (DatabaseSessionInternal) youTrackDB.open("test", "admin", "admin");


    var channel = new MockChannel();
    var request = new PushSchemaRequest();
    request.write(session, channel);
    channel.close();

    var readRequest = new PushSchemaRequest();
    readRequest.read(session, channel);
  }

  @Test
  public void testStorageConfiguration() throws IOException {
    YouTrackDB youTrackDB = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig());
    youTrackDB.execute(
        "create database test memory users (admin identified by 'admin' role admin)");
    var session = youTrackDB.open("test", "admin", "admin");
    var configuration =
        ((DatabaseSessionInternal) session).getStorage().getConfiguration();
    session.close();
    youTrackDB.close();
    var channel = new MockChannel();

    var request = new PushStorageConfigurationRequest(configuration);
    request.write(null, channel);
    channel.close();

    var readRequest = new PushStorageConfigurationRequest();
    readRequest.read(this.session, channel);
    var readPayload = readRequest.getPayload();
    var payload = request.getPayload();
    assertEquals(readPayload.getName(), payload.getName());
    assertEquals(readPayload.getDateFormat(), payload.getDateFormat());
    assertEquals(readPayload.getDateTimeFormat(), payload.getDateTimeFormat());
    assertEquals(readPayload.getVersion(), payload.getVersion());
    assertEquals(readPayload.getDirectory(), payload.getDirectory());
    for (var readProperty : readPayload.getProperties()) {
      var found = false;
      for (var property : payload.getProperties()) {
        if (readProperty.name.equals(property.name) && readProperty.value.equals(property.value)) {
          found = true;
          break;
        }
      }
      assertTrue(found);
    }
    assertEquals(readPayload.getSchemaRecordId(), payload.getSchemaRecordId());
    assertEquals(readPayload.getIndexMgrRecordId(), payload.getIndexMgrRecordId());
    assertEquals(readPayload.getCollectionSelection(), payload.getCollectionSelection());
    assertEquals(readPayload.getConflictStrategy(), payload.getConflictStrategy());
    assertEquals(readPayload.isValidationEnabled(), payload.isValidationEnabled());
    assertEquals(readPayload.getLocaleLanguage(), payload.getLocaleLanguage());
    assertEquals(readPayload.getMinimumCollections(), payload.getMinimumCollections());
    assertEquals(readPayload.isStrictSql(), payload.isStrictSql());
    assertEquals(readPayload.getCharset(), payload.getCharset());
    assertEquals(readPayload.getTimeZone(), payload.getTimeZone());
    assertEquals(readPayload.getLocaleCountry(), payload.getLocaleCountry());
    assertEquals(readPayload.getRecordSerializer(), payload.getRecordSerializer());
    assertEquals(readPayload.getRecordSerializerVersion(), payload.getRecordSerializerVersion());
    assertEquals(readPayload.getBinaryFormatVersion(), payload.getBinaryFormatVersion());
    for (var readCollection : readPayload.getCollections()) {
      var found = false;
      for (var collection : payload.getCollections()) {
        if (readCollection.getName().equals(collection.getName())
            && readCollection.getId() == collection.getId()) {
          found = true;
          break;
        }
      }
      assertTrue(found);
    }
  }

  @Test
  public void testSubscribeRequest() throws IOException {
    var channel = new MockChannel();

    var request =
        new SubscribeRequest(new SubscribeLiveQueryRequest("10", new HashMap<>()));
    request.write(null, channel, null);
    channel.close();

    var requestRead = new SubscribeRequest();
    requestRead.read(session, channel, 1, RecordSerializerNetworkV37.INSTANCE);

    assertEquals(request.getPushMessage(), requestRead.getPushMessage());
    assertTrue(requestRead.getPushRequest() instanceof SubscribeLiveQueryRequest);
  }

  @Test
  public void testSubscribeResponse() throws IOException {
    var channel = new MockChannel();

    var response = new SubscribeResponse(new SubscribeLiveQueryResponse(10));
    response.write(null, channel, 1, RecordSerializerNetworkV37.INSTANCE);
    channel.close();

    var responseRead = new SubscribeResponse(new SubscribeLiveQueryResponse());
    responseRead.read(session, channel, null);

    assertTrue(responseRead.getResponse() instanceof SubscribeLiveQueryResponse);
    assertEquals(10, ((SubscribeLiveQueryResponse) responseRead.getResponse()).getMonitorId());
  }

  @Test
  public void testUnsubscribeRequest() throws IOException {
    var channel = new MockChannel();
    var request = new UnsubscribeRequest(new UnsubscribeLiveQueryRequest(10));
    request.write(null, channel, null);
    channel.close();
    var readRequest = new UnsubscribeRequest();
    readRequest.read(session, channel, 0, null);
    assertEquals(
        10, ((UnsubscribeLiveQueryRequest) readRequest.getUnsubscribeRequest()).getMonitorId());
  }
}
