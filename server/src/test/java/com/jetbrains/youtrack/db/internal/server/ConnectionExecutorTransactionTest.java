package com.jetbrains.youtrack.db.internal.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.client.remote.message.BeginTransaction38Request;
import com.jetbrains.youtrack.db.internal.client.remote.message.BeginTransactionResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.Commit37Response;
import com.jetbrains.youtrack.db.internal.client.remote.message.Commit38Request;
import com.jetbrains.youtrack.db.internal.client.remote.message.FetchTransaction38Request;
import com.jetbrains.youtrack.db.internal.client.remote.message.FetchTransaction38Response;
import com.jetbrains.youtrack.db.internal.client.remote.message.QueryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.QueryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.RebeginTransaction38Request;
import com.jetbrains.youtrack.db.internal.client.remote.message.RollbackTransactionRequest;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkFactory;
import com.jetbrains.youtrack.db.internal.server.network.protocol.NetworkProtocolData;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 *
 */
public class ConnectionExecutorTransactionTest {

  @Mock
  private YouTrackDBServer server;
  @Mock
  private ClientConnection connection;

  private YouTrackDB youTrackDb;
  private DatabaseSessionInternal db;

  @Before
  public void before() throws IOException {
    MockitoAnnotations.initMocks(this);
    youTrackDb = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig());
    youTrackDb.execute(
        "create database ? memory users (admin identified by 'admin' role admin)",
        ConnectionExecutorTransactionTest.class.getSimpleName());
    db =
        (DatabaseSessionInternal)
            youTrackDb.open(
                ConnectionExecutorTransactionTest.class.getSimpleName(), "admin", "admin");
    db.createClass("test");
    var protocolData = new NetworkProtocolData();
    protocolData.setSerializer(RecordSerializerNetworkFactory.current());
    Mockito.when(connection.getDatabaseSession()).thenReturn(db);
    Mockito.when(connection.getData()).thenReturn(protocolData);
  }

  @After
  public void after() {
    db.close();
    youTrackDb.drop(ConnectionExecutorTransactionTest.class.getSimpleName());
    youTrackDb.close();
  }

  @Test
  public void testExecutionBeginTransaction() {

    var executor = new ConnectionBinaryExecutor(connection, server);

    List<RecordOperation> operations = new ArrayList<>();
    var rec = new EntityImpl(db);
    final var iIdentity = new RecordId(3, -2);
    final var rec1 = (RecordAbstract) rec;
    rec1.setIdentity(iIdentity);
    operations.add(new RecordOperation(rec, RecordOperation.CREATED));
    assertFalse(db.getTransactionInternal().isActive());

    var request =
        new BeginTransaction38Request(db, 10, operations);
    var response = request.execute(executor);
    assertTrue(db.getTransactionInternal().isActive());
    assertTrue(response instanceof BeginTransactionResponse);
    // TODO:Define properly what is the txId
    // assertEquals(((BeginTransactionResponse) response).getTxId(), request.getTxId());
  }

  @Test
  public void testExecutionBeginCommitTransaction() {

    var executor = new ConnectionBinaryExecutor(connection, server);

    List<RecordOperation> operations = new ArrayList<>();
    var rec = new EntityImpl(db);
    final var iIdentity = new RecordId(3, -2);
    final var rec1 = (RecordAbstract) rec;
    rec1.setIdentity(iIdentity);
    operations.add(new RecordOperation(rec, RecordOperation.CREATED));

    assertFalse(db.getTransactionInternal().isActive());

    var request = new BeginTransaction38Request(db, 10, operations);
    var response = request.execute(executor);
    assertTrue(db.getTransactionInternal().isActive());
    assertTrue(response instanceof BeginTransactionResponse);

    var commit = new Commit38Request(db, 10, null);
    var commitResponse = commit.execute(executor);
    assertFalse(db.getTransactionInternal().isActive());
    assertTrue(commitResponse instanceof Commit37Response);

    assertEquals(1, ((Commit37Response) commitResponse).getOldToUpdatedRids().size());
  }

  @Test
  public void testExecutionReplaceCommitTransaction() {

    var executor = new ConnectionBinaryExecutor(connection, server);

    List<RecordOperation> operations = new ArrayList<>();
    var rec = new EntityImpl(db);
    final var iIdentity = new RecordId(3, -2);
    final var rec1 = (RecordAbstract) rec;
    rec1.setIdentity(iIdentity);
    rec.setInternalStatus(RecordElement.STATUS.LOADED);
    operations.add(new RecordOperation(rec, RecordOperation.CREATED));
    assertFalse(db.getTransactionInternal().isActive());

    var request =
        new BeginTransaction38Request(db, 10, operations);
    var response = request.execute(executor);
    assertTrue(db.getTransactionInternal().isActive());
    assertTrue(response instanceof BeginTransactionResponse);

    var record1 = new EntityImpl(db, new RecordId(3, -3));
    record1.setInternalStatus(RecordElement.STATUS.LOADED);
    operations.add(new RecordOperation(record1, RecordOperation.CREATED));

    var commit = new Commit38Request(db, 10, operations
    );
    var commitResponse = commit.execute(executor);
    assertFalse(db.getTransactionInternal().isActive());
    assertTrue(commitResponse instanceof Commit37Response);
    assertEquals(2, ((Commit37Response) commitResponse).getOldToUpdatedRids().size());
  }

  @Test
  public void testExecutionRebeginTransaction() {

    var executor = new ConnectionBinaryExecutor(connection, server);

    List<RecordOperation> operations = new ArrayList<>();
    var rec = new EntityImpl(db);
    final var iIdentity = new RecordId(3, -2);
    final var rec1 = (RecordAbstract) rec;
    rec1.setIdentity(iIdentity);
    rec.setInternalStatus(RecordElement.STATUS.LOADED);
    operations.add(new RecordOperation(rec, RecordOperation.CREATED));

    assertFalse(db.getTransactionInternal().isActive());

    var request =
        new BeginTransaction38Request(db, 10, operations);
    var response = request.execute(executor);
    assertTrue(db.getTransactionInternal().isActive());
    assertTrue(response instanceof BeginTransactionResponse);

    var record1 = new EntityImpl(db, new RecordId(3, -3));
    record1.setInternalStatus(RecordElement.STATUS.LOADED);
    operations.add(new RecordOperation(record1, RecordOperation.CREATED));

    var rebegin =
        new RebeginTransaction38Request(db, 10, operations);
    var rebeginResponse = rebegin.execute(executor);
    assertTrue(rebeginResponse instanceof BeginTransactionResponse);
    assertTrue(db.getTransactionInternal().isActive());
    assertEquals(2, db.getTransactionInternal().getEntryCount());
  }

  @Test
  public void testExecutionRebeginCommitTransaction() {

    var executor = new ConnectionBinaryExecutor(connection, server);

    List<RecordOperation> operations = new ArrayList<>();
    var rec = new EntityImpl(db);
    final var iIdentity = new RecordId(3, -2);
    final var rec1 = (RecordAbstract) rec;
    rec1.setIdentity(iIdentity);
    rec.setInternalStatus(RecordElement.STATUS.LOADED);
    operations.add(new RecordOperation(rec, RecordOperation.CREATED));

    assertFalse(db.getTransactionInternal().isActive());

    var request =
        new BeginTransaction38Request(db, 10, operations);
    var response = request.execute(executor);
    assertTrue(db.getTransactionInternal().isActive());
    assertTrue(response instanceof BeginTransactionResponse);

    var record1 = new EntityImpl(db, new RecordId(3, -3));
    record1.setInternalStatus(RecordElement.STATUS.LOADED);
    operations.add(new RecordOperation(record1, RecordOperation.CREATED));

    var rebegin =
        new RebeginTransaction38Request(db, 10, operations);
    var rebeginResponse = rebegin.execute(executor);
    assertTrue(rebeginResponse instanceof BeginTransactionResponse);
    assertTrue(db.getTransactionInternal().isActive());
    assertEquals(2, db.getTransactionInternal().getEntryCount());

    var record2 = new EntityImpl(db, new RecordId(3, -4));
    record2.setInternalStatus(RecordElement.STATUS.LOADED);
    operations.add(new RecordOperation(record2, RecordOperation.CREATED));

    var commit = new Commit38Request(db, 10, operations);
    var commitResponse = commit.execute(executor);
    assertFalse(db.getTransactionInternal().isActive());
    assertTrue(commitResponse instanceof Commit37Response);
    assertEquals(3, ((Commit37Response) commitResponse).getOldToUpdatedRids().size());
  }

  @Test
  public void testExecutionQueryChangesTracking() {

    var executor = new ConnectionBinaryExecutor(connection, server);

    List<RecordOperation> operations = new ArrayList<>();
    var rec = new EntityImpl(db, "test");
    operations.add(new RecordOperation(rec, RecordOperation.CREATED));
    assertFalse(db.getTransactionInternal().isActive());

    var request =
        new BeginTransaction38Request(db, 10, operations);
    var response = request.execute(executor);
    assertTrue(db.getTransactionInternal().isActive());
    assertTrue(response instanceof BeginTransactionResponse);

    var query =
        new QueryRequest(db,
            "sql",
            "update test set name='bla'",
            new HashMap<>(),
            QueryRequest.COMMAND,
            RecordSerializerNetworkFactory.current(), 20);
    var queryResponse = (QueryResponse) query.execute(executor);

    assertTrue(queryResponse.isTxChanges());
  }

  @Test
  public void testBeginChangeFetchTransaction() {

    db.begin();
    db.newEntity("test");
    db.commit();

    var executor = new ConnectionBinaryExecutor(connection, server);

    List<RecordOperation> operations = new ArrayList<>();
    var rec = new EntityImpl(db, "test");
    operations.add(new RecordOperation(rec, RecordOperation.CREATED));
    assertFalse(db.getTransactionInternal().isActive());

    var request =
        new BeginTransaction38Request(db, 10, operations);
    var response = request.execute(executor);
    assertTrue(db.getTransactionInternal().isActive());
    assertTrue(response instanceof BeginTransactionResponse);

    var query =
        new QueryRequest(db,
            "sql",
            "update test set name='bla'",
            new HashMap<>(),
            QueryRequest.COMMAND,
            RecordSerializerNetworkFactory.current(), 20);
    var queryResponse = (QueryResponse) query.execute(executor);

    assertTrue(queryResponse.isTxChanges());

    var fetchRequest = new FetchTransaction38Request(10);

    var response1 =
        (FetchTransaction38Response) fetchRequest.execute(executor);

    assertEquals(2, response1.getRecordOperations().size());
  }

  @Test
  public void testBeginRollbackTransaction() {
    var executor = new ConnectionBinaryExecutor(connection, server);

    List<RecordOperation> operations = new ArrayList<>();
    var rec = new EntityImpl(db, "test");
    operations.add(new RecordOperation(rec, RecordOperation.CREATED));
    assertFalse(db.getTransactionInternal().isActive());

    var request =
        new BeginTransaction38Request(db, 10, operations);
    var response = request.execute(executor);
    assertTrue(db.getTransactionInternal().isActive());
    assertTrue(response instanceof BeginTransactionResponse);

    var rollback = new RollbackTransactionRequest(10);
    var resposne = rollback.execute(executor);
    assertFalse(db.getTransactionInternal().isActive());
  }

  @Test
  public void testBeginSQLInsertCommitTransaction() {

    var executor = new ConnectionBinaryExecutor(connection, server);

    List<RecordOperation> operations = new ArrayList<>();

    var request =
        new BeginTransaction38Request(db, 10, operations);
    var response = request.execute(executor);

    assertTrue(db.getTransactionInternal().isActive());
    assertTrue(response instanceof BeginTransactionResponse);

    var results =
        db.execute("insert into test set name = 'update'").stream()
            .collect(Collectors.toList());

    assertEquals(1, results.size());

    assertEquals("update", results.getFirst().getProperty("name"));

    assertTrue(results.getFirst().asEntity().getIdentity().isTemporary());

    var commit = new Commit38Request(db, 10, null);
    var commitResponse = commit.execute(executor);
    assertFalse(db.getTransactionInternal().isActive());
    assertTrue(commitResponse instanceof Commit37Response);

    assertEquals(1, ((Commit37Response) commitResponse).getOldToUpdatedRids().size());

    assertTrue(
        ((Commit37Response) commitResponse).getOldToUpdatedRids().getFirst().getFirst()
            .isTemporary());

    assertEquals(1, db.countClass("test"));

    var query = db.query("select from test where name = 'update'");

    results = query.stream().toList();

    assertEquals(1, results.size());

    assertEquals("update", results.getFirst().getProperty("name"));

    query.close();
  }
}
