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
  private DatabaseSessionInternal session;

  @Before
  public void before() throws IOException {
    MockitoAnnotations.initMocks(this);
    youTrackDb = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig());
    youTrackDb.execute(
        "create database ? memory users (admin identified by 'admin' role admin)",
        ConnectionExecutorTransactionTest.class.getSimpleName());
    session =
        (DatabaseSessionInternal)
            youTrackDb.open(
                ConnectionExecutorTransactionTest.class.getSimpleName(), "admin", "admin");
    session.createClass("test");
    var protocolData = new NetworkProtocolData();
    protocolData.setSerializer(RecordSerializerNetworkFactory.current());
    Mockito.when(connection.getDatabaseSession()).thenReturn(session);
    Mockito.when(connection.getData()).thenReturn(protocolData);
  }

  @After
  public void after() {
    session.close();
    youTrackDb.drop(ConnectionExecutorTransactionTest.class.getSimpleName());
    youTrackDb.close();
  }

  @Test
  public void testExecutionBeginTransaction() {
    var executor = new ConnectionBinaryExecutor(connection, server);

    List<RecordOperation> operations = new ArrayList<>();
    session.begin();
    var entity = session.newEntity();
    var recordOperation = new RecordOperation((RecordAbstract) entity, RecordOperation.CREATED);
    operations.add(recordOperation);
    var request =
        new BeginTransaction38Request(session, 10, operations);
    session.rollback();

    assertFalse(session.getTransactionInternal().isActive());

    var response = request.execute(executor);

    assertTrue(session.getTransactionInternal().isActive());
    assertTrue(response instanceof BeginTransactionResponse);
    session.rollback();
  }

  @Test
  public void testExecutionBeginCommitTransaction() {
    var executor = new ConnectionBinaryExecutor(connection, server);

    List<RecordOperation> operations = new ArrayList<>();
    var rec = new EntityImpl(session);
    final var iIdentity = new RecordId(3, -2);
    final var rec1 = (RecordAbstract) rec;
    rec1.setIdentity(iIdentity);
    operations.add(new RecordOperation(rec, RecordOperation.CREATED));
    var request = new BeginTransaction38Request(session, 10, operations);

    assertFalse(session.getTransactionInternal().isActive());

    var response = request.execute(executor);
    assertTrue(session.getTransactionInternal().isActive());
    assertTrue(response instanceof BeginTransactionResponse);

    var commit = new Commit38Request(session, 10, null);
    var commitResponse = commit.execute(executor);
    assertFalse(session.getTransactionInternal().isActive());
    assertTrue(commitResponse instanceof Commit37Response);

    assertEquals(1, ((Commit37Response) commitResponse).getOldToUpdatedRids().size());
  }

  @Test
  public void testExecutionReplaceCommitTransaction() {

    var executor = new ConnectionBinaryExecutor(connection, server);

    List<RecordOperation> operations = new ArrayList<>();
    session.newEntity();
    operations.add(
        session.getTransactionInternal().getRecordOperationsInternal().iterator().next());
    assertFalse(session.getTransactionInternal().isActive());

    var request =
        new BeginTransaction38Request(session, 10, operations);
    var response = request.execute(executor);
    assertTrue(session.getTransactionInternal().isActive());
    assertTrue(response instanceof BeginTransactionResponse);

    var record1 = new EntityImpl(session, new RecordId(3, -3));
    record1.setInternalStatus(RecordElement.STATUS.LOADED);
    operations.add(new RecordOperation(record1, RecordOperation.CREATED));

    var commit = new Commit38Request(session, 10, operations
    );
    var commitResponse = commit.execute(executor);
    assertFalse(session.getTransactionInternal().isActive());
    assertTrue(commitResponse instanceof Commit37Response);
    assertEquals(2, ((Commit37Response) commitResponse).getOldToUpdatedRids().size());
  }

  @Test
  public void testExecutionRebeginTransaction() {

    var executor = new ConnectionBinaryExecutor(connection, server);

    List<RecordOperation> operations = new ArrayList<>();
    var rec = new EntityImpl(session);
    final var iIdentity = new RecordId(3, -2);
    final var rec1 = (RecordAbstract) rec;
    rec1.setIdentity(iIdentity);
    rec.setInternalStatus(RecordElement.STATUS.LOADED);
    operations.add(new RecordOperation(rec, RecordOperation.CREATED));

    assertFalse(session.getTransactionInternal().isActive());

    var request =
        new BeginTransaction38Request(session, 10, operations);
    var response = request.execute(executor);
    assertTrue(session.getTransactionInternal().isActive());
    assertTrue(response instanceof BeginTransactionResponse);

    var record1 = new EntityImpl(session, new RecordId(3, -3));
    record1.setInternalStatus(RecordElement.STATUS.LOADED);
    operations.add(new RecordOperation(record1, RecordOperation.CREATED));

    var rebegin =
        new RebeginTransaction38Request(session, 10, operations);
    var rebeginResponse = rebegin.execute(executor);
    assertTrue(rebeginResponse instanceof BeginTransactionResponse);
    assertTrue(session.getTransactionInternal().isActive());
    assertEquals(2, session.getTransactionInternal().getEntryCount());
  }

  @Test
  public void testExecutionRebeginCommitTransaction() {

    var executor = new ConnectionBinaryExecutor(connection, server);

    List<RecordOperation> operations = new ArrayList<>();
    var rec = new EntityImpl(session);
    final var iIdentity = new RecordId(3, -2);
    final var rec1 = (RecordAbstract) rec;
    rec1.setIdentity(iIdentity);
    rec.setInternalStatus(RecordElement.STATUS.LOADED);
    operations.add(new RecordOperation(rec, RecordOperation.CREATED));

    assertFalse(session.getTransactionInternal().isActive());

    var request =
        new BeginTransaction38Request(session, 10, operations);
    var response = request.execute(executor);
    assertTrue(session.getTransactionInternal().isActive());
    assertTrue(response instanceof BeginTransactionResponse);

    var record1 = new EntityImpl(session, new RecordId(3, -3));
    record1.setInternalStatus(RecordElement.STATUS.LOADED);
    operations.add(new RecordOperation(record1, RecordOperation.CREATED));

    var rebegin =
        new RebeginTransaction38Request(session, 10, operations);
    var rebeginResponse = rebegin.execute(executor);
    assertTrue(rebeginResponse instanceof BeginTransactionResponse);
    assertTrue(session.getTransactionInternal().isActive());
    assertEquals(2, session.getTransactionInternal().getEntryCount());

    var record2 = new EntityImpl(session, new RecordId(3, -4));
    record2.setInternalStatus(RecordElement.STATUS.LOADED);
    operations.add(new RecordOperation(record2, RecordOperation.CREATED));

    var commit = new Commit38Request(session, 10, operations);
    var commitResponse = commit.execute(executor);
    assertFalse(session.getTransactionInternal().isActive());
    assertTrue(commitResponse instanceof Commit37Response);
    assertEquals(3, ((Commit37Response) commitResponse).getOldToUpdatedRids().size());
  }

  @Test
  public void testExecutionQueryChangesTracking() {

    var executor = new ConnectionBinaryExecutor(connection, server);

    List<RecordOperation> operations = new ArrayList<>();
    var rec = new EntityImpl(session, "test");
    operations.add(new RecordOperation(rec, RecordOperation.CREATED));
    assertFalse(session.getTransactionInternal().isActive());

    var request =
        new BeginTransaction38Request(session, 10, operations);
    var response = request.execute(executor);
    assertTrue(session.getTransactionInternal().isActive());
    assertTrue(response instanceof BeginTransactionResponse);

    var query =
        new QueryRequest(session,
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

    session.begin();
    session.newEntity("test");
    session.commit();

    var executor = new ConnectionBinaryExecutor(connection, server);

    List<RecordOperation> operations = new ArrayList<>();
    var rec = new EntityImpl(session, "test");
    operations.add(new RecordOperation(rec, RecordOperation.CREATED));
    assertFalse(session.getTransactionInternal().isActive());

    var request =
        new BeginTransaction38Request(session, 10, operations);
    var response = request.execute(executor);
    assertTrue(session.getTransactionInternal().isActive());
    assertTrue(response instanceof BeginTransactionResponse);

    var query =
        new QueryRequest(session,
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
    var rec = new EntityImpl(session, "test");
    operations.add(new RecordOperation(rec, RecordOperation.CREATED));
    assertFalse(session.getTransactionInternal().isActive());

    var request =
        new BeginTransaction38Request(session, 10, operations);
    var response = request.execute(executor);
    assertTrue(session.getTransactionInternal().isActive());
    assertTrue(response instanceof BeginTransactionResponse);

    var rollback = new RollbackTransactionRequest(10);
    var resposne = rollback.execute(executor);
    assertFalse(session.getTransactionInternal().isActive());
  }

  @Test
  public void testBeginSQLInsertCommitTransaction() {

    var executor = new ConnectionBinaryExecutor(connection, server);

    List<RecordOperation> operations = new ArrayList<>();

    var request =
        new BeginTransaction38Request(session, 10, operations);
    var response = request.execute(executor);

    assertTrue(session.getTransactionInternal().isActive());
    assertTrue(response instanceof BeginTransactionResponse);

    var results =
        session.execute("insert into test set name = 'update'").stream()
            .collect(Collectors.toList());

    assertEquals(1, results.size());

    assertEquals("update", results.getFirst().getProperty("name"));

    assertTrue(results.getFirst().asEntity().getIdentity().isTemporary());

    var commit = new Commit38Request(session, 10, null);
    var commitResponse = commit.execute(executor);
    assertFalse(session.getTransactionInternal().isActive());
    assertTrue(commitResponse instanceof Commit37Response);

    assertEquals(1, ((Commit37Response) commitResponse).getOldToUpdatedRids().size());

    assertTrue(
        ((Commit37Response) commitResponse).getOldToUpdatedRids().getFirst().getFirst()
            .isTemporary());

    assertEquals(1, session.countClass("test"));

    var query = session.query("select from test where name = 'update'");

    results = query.stream().toList();

    assertEquals(1, results.size());

    assertEquals("update", results.getFirst().getProperty("name"));

    query.close();
  }
}
