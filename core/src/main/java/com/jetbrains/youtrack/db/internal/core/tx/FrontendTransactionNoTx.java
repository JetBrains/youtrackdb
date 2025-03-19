/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.internal.core.tx;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.NoTxRecordReadException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.exception.TransactionException;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.LoadRecordResult;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * No operation transaction.
 */
public class FrontendTransactionNoTx implements FrontendTransaction {

  private static final String NON_TX_EXCEPTION_READ_MESSAGE =
      "Read operation performed in no tx mode. "
          + "Such behavior can lead to inconsistent state of the database."
          + " Please start transaction";

  @Nonnull
  private DatabaseSessionInternal session;

  public FrontendTransactionNoTx(DatabaseSessionInternal session) {
    this.session = session;
  }

  public int beginInternal() {
    throw new UnsupportedOperationException("Begin is not supported in no tx mode");
  }

  public void commitInternal() {
    throw new UnsupportedOperationException("Commit is not supported in no tx mode");
  }

  @Override
  public int getEntryCount() {
    return 0;
  }

  @Override
  public boolean isActive() {
    return false;
  }

  @Override
  public DatabaseSession getSession() {
    return session;
  }

  @Override
  public <T extends Identifiable> T bindToSession(T identifiable) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nonnull
  @Override
  public Entity loadEntity(RID id) throws DatabaseException, RecordNotFoundException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nonnull
  @Override
  public Vertex loadVertex(RID id) throws DatabaseException, RecordNotFoundException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nonnull
  @Override
  public Edge loadEdge(RID id) throws DatabaseException, RecordNotFoundException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nonnull
  @Override
  public Blob loadBlob(RID id) throws DatabaseException, RecordNotFoundException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public Blob newBlob(byte[] bytes) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public Blob newBlob() {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public Entity newEntity(String className) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public Entity newEntity(SchemaClass cls) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public Entity newEntity() {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public Entity newEmbeddedEntity(SchemaClass schemaClass) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public Entity newEmbeddedEntity(String schemaClass) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public Entity newEmbeddedEntity() {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public <T extends DBRecord> T createOrLoadRecordFromJson(String json) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public Entity createOrLoadEntityFromJson(String json) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public StatefulEdge newStatefulEdge(Vertex from, Vertex to, SchemaClass type) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public StatefulEdge newStatefulEdge(Vertex from, Vertex to, String type) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public Edge newLightweightEdge(Vertex from, Vertex to, @Nonnull SchemaClass type) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public Edge newLightweightEdge(Vertex from, Vertex to, @Nonnull String type) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public Vertex newVertex(SchemaClass type) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public Vertex newVertex(String type) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public StatefulEdge newStatefulEdge(Vertex from, Vertex to) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nonnull
  @Override
  public <RET extends DBRecord> RET load(RID recordId) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nullable
  @Override
  public <RET extends DBRecord> RET loadSilently(RID recordId) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public void commitInternal(boolean force) {
    throw new UnsupportedOperationException("Commit is not supported in no tx mode");
  }

  public void rollbackInternal() {
    throw new UnsupportedOperationException("Rollback is not supported in no tx mode");
  }

  public @Nonnull LoadRecordResult loadRecord(final RID rid) {
    throw new NoTxRecordReadException(session.getDatabaseName(), NON_TX_EXCEPTION_READ_MESSAGE);
  }

  @Override
  public boolean exists(RID rid) {
    throw new NoTxRecordReadException(session.getDatabaseName(), NON_TX_EXCEPTION_READ_MESSAGE);
  }

  @Override
  public void delete(@Nonnull DBRecord record) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public boolean commit() throws TransactionException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public void rollback() throws TransactionException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public ResultSet query(String query, Object... args)
      throws CommandSQLParsingException, CommandExecutionException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public ResultSet query(String query, Map args)
      throws CommandSQLParsingException, CommandExecutionException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public ResultSet execute(String query, Object... args)
      throws CommandSQLParsingException, CommandExecutionException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public ResultSet execute(String query, Map args)
      throws CommandSQLParsingException, CommandExecutionException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public void command(String query, Object... args)
      throws CommandSQLParsingException, CommandExecutionException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public void command(String query, Map args)
      throws CommandSQLParsingException, CommandExecutionException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public TXSTATUS getStatus() {
    return TXSTATUS.INVALID;
  }

  /**
   * Deletes the record.
   */
  public void deleteRecord(final RecordAbstract iRecord) {
    throw new DatabaseException(session.getDatabaseName(), "Cannot delete record in no tx mode");
  }

  public Collection<RecordOperation> getCurrentRecordEntries() {
    return Collections.emptyList();
  }

  public Collection<RecordOperation> getRecordOperationsInternal() {
    return Collections.emptyList();
  }

  @Override
  public Map<String, FrontendTransactionIndexChanges> getIndexOperations() {
    throw new UnsupportedOperationException("GetIndexOperations is not supported in no tx mode");
  }

  @Override
  public void setStatus(TXSTATUS iStatus) {
    throw new UnsupportedOperationException("SetStatus is not supported in no tx mode");
  }

  @Override
  public void setSession(DatabaseSessionInternal session) {
    this.session = session;
  }

  @Override
  public void setMetadataHolder(FrontendTransacationMetadataHolder metadata) {
    throw new UnsupportedOperationException("SetMetadataHolder is not supported in no tx mode");
  }

  @Override
  public Iterator<byte[]> getSerializedOperations() {
    return null;
  }

  public void clearRecordEntries() {
    throw new UnsupportedOperationException("Operation not supported in no tx mode");
  }

  public RecordAbstract getRecord(final RID rid) {
    throw new NoTxRecordReadException(session.getDatabaseName(), NON_TX_EXCEPTION_READ_MESSAGE);
  }

  public RecordOperation getRecordEntry(final RID rid) {
    throw new UnsupportedOperationException("Operation not supported in no tx mode");
  }

  @Override
  public void setCustomData(String iName, Object iValue) {
    throw new UnsupportedOperationException("Operation not supported in no tx mode");
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public Object getCustomData(String iName) {
    throw new UnsupportedOperationException("Operation not supported in no tx mode");
  }

  public void addIndexEntry(
      final Index delegate,
      final String indexName,
      final OPERATION status,
      final Object key,
      final Identifiable value) {
    throw new UnsupportedOperationException("Operation not supported in no tx mode");
  }

  public void clearIndexEntries() {
    throw new UnsupportedOperationException("Operation not supported in no tx mode");
  }

  @Override
  public void close() {
  }

  public FrontendTransactionIndexChanges getIndexChanges(final String iName) {
    return null;
  }

  public long getId() {
    return 0;
  }

  public List<String> getInvolvedIndexes() {
    return Collections.emptyList();
  }

  public boolean assertIdentityChangedAfterCommit(RecordId oldRid, RecordId newRid) {
    throw new UnsupportedOperationException("Operation not supported in no tx mode");
  }

  @Override
  public int amountOfNestedTxs() {
    return 0;
  }

  @Override
  public void rollbackInternal(boolean force, int commitLevelDiff) {
    throw new UnsupportedOperationException("Rollback is not supported in no tx mode");
  }

  @Override
  public DatabaseSessionInternal getDatabaseSession() {
    return session;
  }

  @Override
  public RecordOperation addRecordOperation(RecordAbstract record, byte status) {
    throw new UnsupportedOperationException("Can not modify record outside transaction");
  }

  @Nullable
  @Override
  public RecordId getFirstRid(int clusterId) {
    throw new NoTxRecordReadException(session.getDatabaseName(), NON_TX_EXCEPTION_READ_MESSAGE);
  }

  @Nullable
  @Override
  public RecordId getLastRid(int clusterId) {
    throw new NoTxRecordReadException(session.getDatabaseName(), NON_TX_EXCEPTION_READ_MESSAGE);
  }

  @Nullable
  @Override
  public RecordId getNextRidInCluster(@Nonnull RecordId rid) {
    throw new NoTxRecordReadException(session.getDatabaseName(), NON_TX_EXCEPTION_READ_MESSAGE);
  }

  @Nullable
  @Override
  public RecordId getPreviousRidInCluster(@Nonnull RecordId rid) {
    throw new NoTxRecordReadException(session.getDatabaseName(), NON_TX_EXCEPTION_READ_MESSAGE);
  }

  @Override
  public boolean isDeletedInTx(@Nonnull RID rid) {
    return false;
  }

  @Override
  public void internalRollback() {
  }

  @Override
  public FrontendTransactionIndexChanges getIndexChangesInternal(String indexName) {
    return null;
  }
}
