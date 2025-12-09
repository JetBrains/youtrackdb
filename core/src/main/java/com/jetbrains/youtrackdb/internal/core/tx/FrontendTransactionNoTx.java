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
package com.jetbrains.youtrackdb.internal.core.tx;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Blob;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.EmbeddedEntity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.StatefulEdge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandScriptException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.NoTxRecordReadException;
import com.jetbrains.youtrackdb.internal.core.exception.TransactionException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.RecordSerializationContext;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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
  private DatabaseSessionEmbedded session;

  public FrontendTransactionNoTx(@Nonnull DatabaseSessionEmbedded session) {
    this.session = session;
  }

  @Override
  public int beginInternal() {
    throw new UnsupportedOperationException("Begin is not supported in no tx mode");
  }

  @Override
  public Map<RID, RID> commitInternal() {
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
  public @Nonnull Stream<com.jetbrains.youtrackdb.internal.core.tx.RecordOperation> getRecordOperations() {
    return Stream.empty();
  }

  @Override
  public int getRecordOperationsCount() {
    return 0;
  }

  @Override
  public int activeTxCount() {
    return 0;
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
  public StatefulEdge loadEdge(@Nonnull RID id) throws DatabaseException, RecordNotFoundException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nonnull
  @Override
  public Blob loadBlob(@Nonnull RID id) throws DatabaseException, RecordNotFoundException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public Blob newBlob(@Nonnull byte[] bytes) {
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
  public EmbeddedEntity newEmbeddedEntity(SchemaClass schemaClass) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public EmbeddedEntity newEmbeddedEntity(String schemaClass) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public EmbeddedEntity newEmbeddedEntity() {
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
  public <RET extends DBRecord> RET loadOrNull(RID recordId) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public Map<RID, RID> commitInternal(boolean force) {
    throw new UnsupportedOperationException("Commit is not supported in no tx mode");
  }

  @Override
  public void rollbackInternal() {
    throw new UnsupportedOperationException("Rollback is not supported in no tx mode");
  }

  @Override
  public @Nonnull RecordAbstract loadRecord(final RID rid) {
    throw new NoTxRecordReadException(session.getDatabaseName(), NON_TX_EXCEPTION_READ_MESSAGE);
  }

  @Override
  public boolean exists(@Nonnull RID rid) {
    throw new NoTxRecordReadException(session.getDatabaseName(), NON_TX_EXCEPTION_READ_MESSAGE);
  }

  @Override
  public void delete(@Nonnull DBRecord record) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public Map<RID, RID> commit() throws TransactionException {
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
  public ResultSet computeScript(String language, String script, Object... args)
      throws CommandExecutionException, CommandScriptException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public ResultSet computeScript(String language, String script, Map<String, ?> args)
      throws CommandExecutionException, CommandScriptException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public TXSTATUS getStatus() {
    return TXSTATUS.INVALID;
  }

  /**
   * Deletes the record.
   */
  @Override
  public void deleteRecord(final RecordAbstract iRecord) {
    throw new DatabaseException(session.getDatabaseName(), "Cannot delete record in no tx mode");
  }

  @Override
  public Collection<RecordOperation> getCurrentRecordEntries() {
    return Collections.emptyList();
  }

  @Override
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
  public void setSession(DatabaseSessionEmbedded session) {
    this.session = session;
  }

  @Override
  public void setMetadataHolder(FrontendTransacationMetadataHolder metadata) {
    throw new UnsupportedOperationException("SetMetadataHolder is not supported in no tx mode");
  }

  @Override
  public Iterator<byte[]> getSerializedOperations() {
    throw new UnsupportedOperationException(
        "getSerializedOperations is not supported in no tx mode");
  }

  @Override
  public void clearRecordEntries() {
    throw new UnsupportedOperationException("Operation not supported in no tx mode");
  }

  @Override
  public RecordAbstract getRecord(final RID rid) {
    throw new NoTxRecordReadException(session.getDatabaseName(), NON_TX_EXCEPTION_READ_MESSAGE);
  }

  @Override
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

  @Override
  public void addIndexEntry(
      final Index delegate,
      final String indexName,
      final OPERATION status,
      final Object key,
      final Identifiable value) {
    throw new UnsupportedOperationException("Operation not supported in no tx mode");
  }

  @Override
  public void clearIndexEntries() {
    throw new UnsupportedOperationException("Operation not supported in no tx mode");
  }

  @Override
  public void close() {
  }

  @Override
  public FrontendTransactionIndexChanges getIndexChanges(final String iName) {
    return null;
  }

  @Override
  public long getId() {
    return 0;
  }

  @Override
  public List<String> getInvolvedIndexes() {
    return Collections.emptyList();
  }

  @Override
  public boolean assertIdentityChangedAfterCommit(RecordIdInternal oldRid,
      RecordIdInternal newRid) {
    throw new UnsupportedOperationException("Operation not supported in no tx mode");
  }

  @Override
  public int amountOfNestedTxs() {
    return 0;
  }

  @Nonnull
  @Override
  public DatabaseSessionEmbedded getDatabaseSession() {
    return session;
  }

  @Override
  public RecordOperation addRecordOperation(RecordAbstract record, byte status) {
    throw new UnsupportedOperationException("Can not modify record outside transaction");
  }

  @Nullable
  @Override
  public RecordIdInternal getNextRidInCollection(
      @Nonnull RecordIdInternal rid,
      long upperBoundExclusive) {
    throw new NoTxRecordReadException(session.getDatabaseName(), NON_TX_EXCEPTION_READ_MESSAGE);
  }

  @Nullable
  @Override
  public RecordIdInternal getPreviousRidInCollection(
      @Nonnull RecordIdInternal rid,
      long lowerBoundInclusive) {
    throw new NoTxRecordReadException(session.getDatabaseName(), NON_TX_EXCEPTION_READ_MESSAGE);
  }

  @Override
  public boolean isDeletedInTx(@Nonnull RID rid) {
    return false;
  }

  @Override
  public boolean isScheduledForCallbackProcessing(RecordIdInternal rid) {
    return false;
  }

  @Override
  public @Nonnull RecordSerializationContext getRecordSerializationContext() {
    throw new UnsupportedOperationException("Operation is not supported in no tx mode");
  }

  @Override
  public FrontendTransactionIndexChanges getIndexChangesInternal(String indexName) {
    return null;
  }

  @Nullable
  @Override
  public Entity loadEntityOrNull(RID id) throws DatabaseException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nonnull
  @Override
  public Entity loadEntity(Identifiable identifiable)
      throws DatabaseException, RecordNotFoundException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nullable
  @Override
  public Entity loadEntityOrNull(Identifiable identifiable) throws DatabaseException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nullable
  @Override
  public Vertex loadVertexOrNull(RID id) throws RecordNotFoundException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nonnull
  @Override
  public Vertex loadVertex(Identifiable identifiable)
      throws DatabaseException, RecordNotFoundException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nullable
  @Override
  public Vertex loadVertexOrNull(Identifiable identifiable) throws RecordNotFoundException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nullable
  @Override
  public StatefulEdge loadEdgeOrNull(@Nonnull RID id) throws DatabaseException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nonnull
  @Override
  public StatefulEdge loadEdge(@Nonnull Identifiable id)
      throws DatabaseException, RecordNotFoundException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nonnull
  @Override
  public StatefulEdge loadEdgeOrNull(@Nonnull Identifiable id) throws DatabaseException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nullable
  @Override
  public Blob loadBlobOrNull(@Nonnull RID id) throws DatabaseException, RecordNotFoundException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nonnull
  @Override
  public Blob loadBlob(@Nonnull Identifiable id) throws DatabaseException, RecordNotFoundException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nonnull
  @Override
  public Blob loadBlobOrNull(@Nonnull Identifiable id) throws DatabaseException {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nonnull
  @Override
  public <RET extends DBRecord> RET load(Identifiable identifiable) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Nullable
  @Override
  public <RET extends DBRecord> RET loadOrNull(Identifiable identifiable) {
    throw new UnsupportedOperationException("not supported in no tx mode");
  }

  @Override
  public boolean isCallBackProcessingInProgress() {
    return false;
  }
}
