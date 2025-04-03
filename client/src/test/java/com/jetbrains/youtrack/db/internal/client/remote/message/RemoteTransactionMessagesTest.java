package com.jetbrains.youtrack.db.internal.client.remote.message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkFactory;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.LinkBagPointer;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.RidBagBucketPointer;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChanges;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Ignore;
import org.junit.Test;

public class RemoteTransactionMessagesTest extends DbTestBase {

  @Test
  public void testBeginTransactionEmptyWriteRead() throws IOException {
    var channel = new MockChannel();
    var request = new BeginTransaction38Request(session, 0, Collections.emptyList(),
        Collections.emptyList());
    request.write(session, channel, null);
    channel.close();
    var readRequest = new BeginTransaction38Request();
    readRequest.read(session, channel, 0, null);
  }

  @Test
  public void testBeginTransactionWriteRead() throws IOException {

    List<RecordOperation> operations = new ArrayList<>();
    operations.add(new RecordOperation(new EntityImpl(session), RecordOperation.CREATED));
    var channel = new MockChannel();
    var request =
        new BeginTransaction38Request(session, 0, operations, Collections.emptyList());
    request.write(session, channel, null);

    channel.close();

    var readRequest = new BeginTransaction38Request();
    readRequest.read(session, channel, 0, RecordSerializerNetworkFactory.current());
    assertEquals(1, readRequest.getOperations().size());
    assertEquals(0, readRequest.getTxId());
  }

  @Test
  public void testFullCommitTransactionWriteRead() throws IOException {
    List<RecordOperation> operations = new ArrayList<>();
    operations.add(new RecordOperation(new EntityImpl(session), RecordOperation.CREATED));
    Map<String, FrontendTransactionIndexChanges> changes = new HashMap<>();

    var channel = new MockChannel();
    var request = new Commit38Request(session, 0, operations, Collections.emptyList());
    request.write(session, channel, null);

    channel.close();

    var readRequest = new Commit38Request();
    readRequest.read(session, channel, 0, RecordSerializerNetworkFactory.current());
    assertEquals(1, readRequest.getOperations().size());
    assertEquals(0, readRequest.getTxId());
  }

  @Test
  public void testCommitResponseTransactionWriteRead() throws IOException {

    var channel = new MockChannel();

    Map<UUID, LinkBagPointer> changes = new HashMap<>();
    var val = UUID.randomUUID();
    changes.put(val, new LinkBagPointer(10, new RidBagBucketPointer(30, 40)));
    var updatedRids = new HashMap<RecordId, RecordId>();

    updatedRids.put(new RecordId(10, 20), new RecordId(10, 30));
    updatedRids.put(new RecordId(10, 21), new RecordId(10, 31));

    var response = new Commit37Response(0, updatedRids, changes, session);
    response.write(session, channel, 0, null);
    channel.close();

    var readResponse = new Commit37Response();
    readResponse.read(session, channel, null);

    assertEquals(2, readResponse.getOldToUpdatedRids().size());

    assertEquals(new RecordId(10, 30), readResponse.getOldToUpdatedRids().getFirst().getFirst());
    assertEquals(new RecordId(10, 20), readResponse.getOldToUpdatedRids().getFirst().getSecond());

    assertEquals(new RecordId(10, 31), readResponse.getOldToUpdatedRids().get(1).getFirst());
    assertEquals(new RecordId(10, 21), readResponse.getOldToUpdatedRids().get(1).getSecond());

    assertEquals(1, readResponse.getCollectionChanges().size());
    assertNotNull(readResponse.getCollectionChanges().get(val));
    assertEquals(10, readResponse.getCollectionChanges().get(val).getFileId());
    assertEquals(30, readResponse.getCollectionChanges().get(val).getLinkBagId().getPageIndex());
    assertEquals(40, readResponse.getCollectionChanges().get(val).getLinkBagId().getPageOffset());
  }

  @Test
  public void testEmptyCommitTransactionWriteRead() throws IOException {

    var channel = new MockChannel();
    var request = new Commit38Request(session, 0, Collections.emptyList(), Collections.emptyList());
    request.write(session, channel, null);

    channel.close();

    var readRequest = new Commit38Request();
    readRequest.read(session, channel, 0, RecordSerializerNetworkFactory.current());
    assertNull(readRequest.getOperations());
    assertEquals(0, readRequest.getTxId());
  }

  @Test
  public void testTransactionFetchResponseWriteRead() throws IOException {

    List<RecordOperation> operations = new ArrayList<>();
    operations.add(new RecordOperation(new EntityImpl(session), RecordOperation.CREATED));
    var docOne = new EntityImpl(session);
    final var iIdentity1 = new RecordId(10, 2);
    final var rec1 = (RecordAbstract) docOne;
    rec1.setIdentity(iIdentity1);

    var docTwo = new EntityImpl(session);
    final var iIdentity = new RecordId(10, 1);
    final var rec = (RecordAbstract) docTwo;
    rec.setIdentity(iIdentity);

    operations.add(
        new RecordOperation(docOne, RecordOperation.UPDATED));
    operations.add(
        new RecordOperation(docTwo, RecordOperation.DELETED));

    var channel = new MockChannel();
    var response =
        new FetchTransaction38Response(10, new HashMap<>(), operations, session);
    response.write(session, channel, 0, RecordSerializerNetworkV37.INSTANCE);

    channel.close();

    var readResponse = new FetchTransaction38Response();
    readResponse.read(session, channel, null);

    assertEquals(3, readResponse.getRecordOperations().size());
    assertEquals(RecordOperation.CREATED, readResponse.getRecordOperations().get(0).getType());
    assertNotNull(readResponse.getRecordOperations().get(0).getRecord());
    assertEquals(RecordOperation.UPDATED, readResponse.getRecordOperations().get(1).getType());
    assertNotNull(readResponse.getRecordOperations().get(1).getRecord());
    assertEquals(RecordOperation.DELETED, readResponse.getRecordOperations().get(2).getType());
    assertNotNull(readResponse.getRecordOperations().get(2).getRecord());
    assertEquals(10, readResponse.getTxId());
  }

  @Test
  @Ignore
  public void testTransactionFetchResponse38WriteRead() throws IOException {

    List<RecordOperation> operations = new ArrayList<>();
    operations.add(new RecordOperation(new EntityImpl(session), RecordOperation.CREATED));
    operations.add(
        new RecordOperation(new EntityImpl(session, new RecordId(10, 2)), RecordOperation.UPDATED));
    operations.add(
        new RecordOperation(new EntityImpl(session, new RecordId(10, 1)), RecordOperation.DELETED));
    Map<String, FrontendTransactionIndexChanges> changes = new HashMap<>();
    var channel = new MockChannel();
    var response =
        new FetchTransaction38Response(10, new HashMap<>(), operations, session);
    response.write(session, channel, 0, RecordSerializerNetworkV37.INSTANCE);

    channel.close();

    var readResponse = new FetchTransaction38Response();
    readResponse.read(session, channel, null);

    assertEquals(3, readResponse.getRecordOperations().size());
    assertEquals(RecordOperation.CREATED, readResponse.getRecordOperations().get(0).getType());
    assertNotNull(readResponse.getRecordOperations().getFirst().getRecord());
    assertEquals(RecordOperation.UPDATED, readResponse.getRecordOperations().get(1).getType());
    assertNotNull(readResponse.getRecordOperations().get(1).getRecord());
    assertEquals(RecordOperation.DELETED, readResponse.getRecordOperations().get(2).getType());
    assertNotNull(readResponse.getRecordOperations().get(2).getRecord());
    assertEquals(10, readResponse.getTxId());
  }

  @Test
  public void testTransactionClearIndexFetchResponseWriteRead() throws IOException {

    List<RecordOperation> operations = new ArrayList<>();

    var channel = new MockChannel();
    var response =
        new FetchTransaction38Response(10, new HashMap<>(), operations, session);
    response.write(session, channel, 0, RecordSerializerNetworkV37.INSTANCE);

    channel.close();

    var readResponse =
        new FetchTransaction38Response(10, new HashMap<>(), operations, session);
    readResponse.read(session, channel, null);

    assertEquals(10, readResponse.getTxId());
  }
}
