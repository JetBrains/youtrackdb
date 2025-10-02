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

package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.SessionListener;
import com.jetbrains.youtrackdb.api.common.query.LiveQueryMonitor;
import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.exception.SchemaException;
import com.jetbrains.youtrackdb.api.exception.TransactionException;
import com.jetbrains.youtrackdb.api.query.LiveQueryResultListener;
import com.jetbrains.youtrackdb.api.query.ResultSet;
import com.jetbrains.youtrackdb.api.record.Blob;
import com.jetbrains.youtrackdb.api.record.DBRecord;
import com.jetbrains.youtrackdb.api.record.Edge;
import com.jetbrains.youtrackdb.api.record.EmbeddedEntity;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.record.RecordHook;
import com.jetbrains.youtrackdb.api.record.RecordHook.TYPE;
import com.jetbrains.youtrackdb.api.record.StatefulEdge;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.transaction.Transaction;
import com.jetbrains.youtrackdb.api.transaction.TxBiConsumer;
import com.jetbrains.youtrackdb.api.transaction.TxConsumer;
import com.jetbrains.youtrackdb.api.transaction.TxFunction;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.TimeRate;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.cache.LocalRecordCache;
import com.jetbrains.youtrackdb.internal.core.conflict.RecordConflictStrategy;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorClass;
import com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorCollection;
import com.jetbrains.youtrackdb.internal.core.metadata.SessionMetadata;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EdgeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.StatefullEdgeEntityImpl;
import com.jetbrains.youtrackdb.internal.core.security.SecurityUser;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrackdb.internal.core.storage.RecordMetadata;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.StorageInfo;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManager;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface DatabaseSessionInternal extends DatabaseSession {

  /**
   * Returns the current user logged into the database.
   */
  SecurityUser getCurrentUser();

  /**
   * Creates a new element instance.
   *
   * @return The new instance.
   */
  EntityImpl newInstance(String className);

  EntityImpl newInstance();

  EntityImpl newInternalInstance();

  /**
   * Internal. Gets an instance of sb-tree collection manager for current database.
   */
  LinkCollectionsBTreeManager getBTreeCollectionManager();

  /**
   * @return the factory of binary serializers.
   */
  BinarySerializerFactory getSerializerFactory();

  /**
   * Returns the default record type for this kind of database.
   */
  byte getRecordType();

  /**
   * @return serializer which is used for entity serialization.
   */
  RecordSerializer getSerializer();

  @Override
  RecordHook registerHook(final @Nonnull RecordHook iHookImpl);

  /**
   * Retrieves all the registered hooks.
   *
   * @return A not-null unmodifiable map of RecordHook and position instances. If there are no hooks
   * registered, the Map is empty.
   */
  List<RecordHook> getHooks();

  /**
   * Activate current database instance on current thread.
   */
  void activateOnCurrentThread();

  /**
   * Returns true if the current database instance is active on current thread, otherwise false.
   */
  boolean isActiveOnCurrentThread();


  int begin(FrontendTransactionImpl tx);

  void setSerializer(RecordSerializer serializer);

  int assignAndCheckCollection(DBRecord record);

  void reloadUser();

  void afterReadOperations(final RecordAbstract identifiable);

  boolean beforeReadOperations(final RecordAbstract identifiable);

  void beforeUpdateOperations(final RecordAbstract recordAbstract, String collectionName);

  void afterUpdateOperations(final RecordAbstract recordAbstract);

  void beforeCreateOperations(final RecordAbstract recordAbstract, String collectionName);

  void afterCreateOperations(final RecordAbstract recordAbstract);

  void beforeDeleteOperations(final RecordAbstract recordAbstract, String collectionName);

  void afterDeleteOperations(final RecordAbstract recordAbstract);

  void callbackHooks(final TYPE type, final RecordAbstract record);

  @Nullable
  <RET extends RecordAbstract> RawPair<RET, RecordIdInternal> loadFirstRecordAndNextRidInCollection(
      int collectionId);

  @Nullable
  <RET extends RecordAbstract> RawPair<RET, RecordIdInternal> loadLastRecordAndPreviousRidInCollection(
      int collectionId);

  @Nullable
  <RET extends RecordAbstract> RawPair<RET, RecordIdInternal> loadRecordAndNextRidInCollection(
      @Nonnull final RecordIdInternal recordId);

  @Nullable
  <RET extends RecordAbstract> RawPair<RET, RecordIdInternal> loadRecordAndPreviousRidInCollection(
      @Nonnull final RecordIdInternal recordId);

  @Nullable
  LoadRecordResult executeReadRecord(@Nonnull final RecordIdInternal rid, boolean fetchPreviousRid,
      boolean fetchNextRid, boolean throwIfNotFound);

  boolean executeExists(@Nonnull RID rid);

  void setNoTxMode();

  DatabaseSessionInternal copy();

  boolean assertIfNotActive();

  void callOnOpenListeners();

  void callOnCloseListeners();

  DatabaseSession setCustom(final String name, final Object iValue);

  @Nullable
  default ResultSet getActiveQuery(String id) {
    throw new UnsupportedOperationException();
  }

  default Map<String, ResultSet> getActiveQueries() {
    throw new UnsupportedOperationException();
  }

  /**
   * Creates a new lightweight edge with the specified class name, starting vertex, and ending
   * vertex.
   *
   * @param iClassName the class name of the edge
   * @param from       the starting vertex of the edge
   * @param to         the ending vertex of the edge
   * @return the new lightweight edge
   */
  EdgeInternal newLightweightEdgeInternal(String iClassName, Vertex from, Vertex to);

  Edge newRegularEdge(String iClassName, Vertex from, Vertex to);

  default void realClose() {
    // Only implemented by pooled instances
    throw new UnsupportedOperationException();
  }

  default void reuse() {
    // Only implemented by pooled instances
    throw new UnsupportedOperationException();
  }

  /**
   * Executed the commit on the storage hiding away storage concepts from the transaction
   */
  void internalCommit(@Nonnull FrontendTransactionImpl transaction);

  void deleteInternal(@Nonnull RecordAbstract record);

  void internalClose(boolean recycle);

  String getCollectionName(@Nonnull final DBRecord record);

  boolean dropCollectionInternal(int collectionId);

  String getCollectionRecordConflictStrategy(int collectionId);

  int[] getCollectionsIds(@Nonnull Set<String> filterCollections);

  default void startExclusiveMetadataChange() {
  }

  default void endExclusiveMetadataChange() {
  }

  long truncateClass(String name, boolean polimorfic);

  long truncateCollectionInternal(String name);

  /**
   * Browses all the records of the specified collection.
   *
   * @param iCollectionName Collection name to iterate
   * @return Iterator of EntityImpl instances
   */
  <REC extends RecordAbstract> RecordIteratorCollection<REC> browseCollection(
      String iCollectionName);

  /**
   * Browses all the records of the specified class and also all the subclasses. If you've a class
   * Vehicle and Car that extends Vehicle then a db.browseClass("Vehicle", true) will return all the
   * instances of Vehicle and Car. The order of the returned instance starts from record id with
   * position 0 until the end. Base classes are worked at first.
   *
   * @param className Class name to iterate
   * @return Iterator of EntityImpl instances
   */
  RecordIteratorClass browseClass(@Nonnull String className);

  RecordIteratorClass browseClass(@Nonnull ImmutableSchemaClass clz);

  /**
   * Browses all the records of the specified class and if iPolymorphic is true also all the
   * subclasses. If you've a class Vehicle and Car that extends Vehicle then a
   * db.browseClass("Vehicle", true) will return all the instances of Vehicle and Car. The order of
   * the returned instance starts from record id with position 0 until the end. Base classes are
   * worked at first.
   *
   * @param className    Class name to iterate
   * @param iPolymorphic Consider also the instances of the subclasses or not
   * @return Iterator of EntityImpl instances
   */
  RecordIteratorClass browseClass(@Nonnull String className, boolean iPolymorphic);

  RecordIteratorClass browseClass(@Nonnull String className, boolean iPolymorphic,
      boolean forwardDirection);

  /**
   * Counts the entities contained in the specified class and sub classes (polymorphic).
   *
   * @param iClassName Class name
   * @return Total entities
   */
  long countClass(String iClassName);

  /**
   * Counts the entities contained in the specified class.
   *
   * @param iClassName   Class name
   * @param iPolymorphic True if consider also the sub classes, otherwise false
   * @return Total entities
   */
  long countClass(String iClassName, final boolean iPolymorphic);

  /**
   * Checks if the operation on a resource is allowed for the current user.
   *
   * @param iResource  Resource where to execute the operation
   * @param iOperation Operation to execute against the resource
   */
  @Deprecated
  void checkSecurity(String iResource, int iOperation);

  /**
   * Tells if validation of record is active. Default is true.
   *
   * @return true if it's active, otherwise false.
   */
  boolean isValidationEnabled();

  /**
   * Returns true if current configuration retains objects, otherwise false
   *
   * @see #setRetainRecords(boolean)
   */
  boolean isRetainRecords();

  /**
   * Specifies if retain handled objects in memory or not. Setting it to false can improve
   * performance on large inserts. Default is enabled.
   *
   * @param iValue True to enable, false to disable it.
   * @see #isRetainRecords()
   */
  DatabaseSession setRetainRecords(boolean iValue);

  /**
   * Checks if the operation on a resource is allowed for the current user. The check is made in two
   * steps:
   *
   * <ol>
   *   <li>Access to all the resource as *
   *   <li>Access to the specific target resource
   * </ol>
   *
   * @param iResourceGeneric  Resource where to execute the operation, i.e.: database.collections
   * @param iOperation        Operation to execute against the resource
   * @param iResourceSpecific Target resource, i.e.: "employee" to specify the collection name.
   */
  @Deprecated
  void checkSecurity(String iResourceGeneric, int iOperation, Object iResourceSpecific);

  /**
   * Checks if the operation against multiple resources is allowed for the current user. The check
   * is made in two steps:
   *
   * <ol>
   *   <li>Access to all the resource as *
   *   <li>Access to the specific target resources
   * </ol>
   *
   * @param iResourceGeneric   Resource where to execute the operation, i.e.: database.collections
   * @param iOperation         Operation to execute against the resource
   * @param iResourcesSpecific Target resources as an array of Objects, i.e.: ["employee", 2] to
   *                           specify collection name and id.
   */
  @Deprecated
  void checkSecurity(String iResourceGeneric, int iOperation, Object... iResourcesSpecific);

  /**
   * Checks if the operation on a resource is allowed for the current user.
   *
   * @param resourceGeneric Generic Resource where to execute the operation
   * @param iOperation      Operation to execute against the resource
   */
  void checkSecurity(
      Rule.ResourceGeneric resourceGeneric, String resourceSpecific, int iOperation);

  /**
   * Checks if the operation on a resource is allowed for the current user. The check is made in two
   * steps:
   *
   * <ol>
   *   <li>Access to all the resource as *
   *   <li>Access to the specific target resource
   * </ol>
   *
   * @param iResourceGeneric  Resource where to execute the operation, i.e.: database.collections
   * @param iOperation        Operation to execute against the resource
   * @param iResourceSpecific Target resource, i.e.: "employee" to specify the collection name.
   */
  void checkSecurity(
      Rule.ResourceGeneric iResourceGeneric, int iOperation, Object iResourceSpecific);

  void checkSecurity(final int operation, final Identifiable record, String collection);

  /**
   * Checks if the operation against multiple resources is allowed for the current user. The check
   * is made in two steps:
   *
   * <ol>
   *   <li>Access to all the resource as *
   *   <li>Access to the specific target resources
   * </ol>
   *
   * @param iResourceGeneric   Resource where to execute the operation, i.e.: database.collections
   * @param iOperation         Operation to execute against the resource
   * @param iResourcesSpecific Target resources as an array of Objects, i.e.: ["employee", 2] to
   *                           specify collection name and id.
   */
  void checkSecurity(
      Rule.ResourceGeneric iResourceGeneric, int iOperation, Object... iResourcesSpecific);

  /**
   * Return active transaction. Cannot be null. If no transaction is active, then a
   * FrontendTransactionNoTx instance is returned.
   *
   * @return FrontendTransaction implementation
   */
  FrontendTransaction getTransactionInternal();

  /**
   * Reloads the database information like the collection list.
   */
  void reload();

  /**
   * Returns the underlying storage implementation.
   *
   * @return The underlying storage implementation
   * @see Storage
   */
  AbstractStorage getStorage();

  StorageInfo getStorageInfo();

  /**
   * Set user for current database instance.
   */
  void setUser(SecurityUser user);

  /**
   * Internal method. Don't call it directly unless you're building an internal component.
   */
  void setInternal(ATTRIBUTES attribute, Object iValue);

  SharedContext getSharedContext();

  default DatabaseStats getStats() {
    return new DatabaseStats();
  }

  default void resetRecordLoadStats() {
  }

  /**
   * Returns the total size of records contained in the collection defined by its name.
   *
   * @param iCollectionName Collection name
   * @return Total size of records contained.
   */
  @Deprecated
  long getCollectionRecordSizeByName(String iCollectionName);

  /**
   * Returns the total size of records contained in the collection defined by its id.
   *
   * @param iCollectionId Collection id
   * @return The name of searched collection.
   */
  @Deprecated
  long getCollectionRecordSizeById(int iCollectionId);

  /**
   * Removes all data in the collection with given name. As result indexes for this class will be
   * rebuilt.
   *
   * @param collectionName Name of collection to be truncated.
   */
  void truncateCollection(String collectionName);

  /**
   * Counts all the entities in the specified collection id.
   *
   * @param iCurrentCollectionId Collection id
   * @return Total number of entities contained in the specified collection
   */
  long countCollectionElements(int iCurrentCollectionId);

  @Deprecated
  long countCollectionElements(int iCurrentCollectionId, boolean countTombstones);

  /**
   * Counts all the entities in the specified collection ids.
   *
   * @param iCollectionIds Array of collection ids Collection id
   * @return Total number of entities contained in the specified collections
   */
  long countCollectionElements(int[] iCollectionIds);

  @Deprecated
  long countCollectionElements(int[] iCollectionIds, boolean countTombstones);

  /**
   * Sets a property value
   *
   * @param iName  Property name
   * @param iValue new value to set
   * @return The previous value if any, otherwise null
   * @deprecated use <code>YouTrackDBConfig.builder().setConfig(propertyName,
   * propertyValue).build();
   * </code> instead if you use >=3.0 API.
   */
  @Deprecated
  Object setProperty(String iName, Object iValue);

  /**
   * Gets the property value.
   *
   * @param iName Property name
   * @return The previous value if any, otherwise null
   * @deprecated use {@link DatabaseSession#getConfiguration()} instead if you use >=3.0 API.
   */
  @Deprecated
  Object getProperty(String iName);

  /**
   * Returns an iterator of the property entries
   *
   * @deprecated use {@link DatabaseSession#getConfiguration()} instead if you use >=3.0 API.
   */
  @Deprecated
  Iterator<Entry<String, Object>> getProperties();

  /**
   * Registers a listener to the database events.
   *
   * @param iListener the listener to register
   */
  void registerListener(SessionListener iListener);

  /**
   * Unregisters a listener to the database events.
   *
   * @param iListener the listener to unregister
   */
  void unregisterListener(SessionListener iListener);

  @Deprecated
  RecordMetadata getRecordMetadata(final RID rid);


  /**
   * Returns if the Multi Version Concurrency Control is enabled or not. If enabled the version of
   * the record is checked before each update and delete against the records.
   *
   * @return true if enabled, otherwise false deprecated since 2.2
   */
  @Deprecated
  boolean isMVCC();

  /**
   * Enables or disables the Multi-Version Concurrency Control. If enabled the version of the record
   * is checked before each update and delete against the records.
   *
   * @return The Database instance itself giving a "fluent interface". Useful to call multiple
   * methods in chain. deprecated since 2.2
   */
  @Deprecated
  DatabaseSession setMVCC(boolean iValue);

  /**
   * Returns the current record conflict strategy.
   */
  @Deprecated
  RecordConflictStrategy getConflictStrategy();

  /**
   * Overrides record conflict strategy selecting the strategy by name.
   *
   * @param iStrategyName RecordConflictStrategy strategy name
   * @return The Database instance itself giving a "fluent interface". Useful to call multiple
   * methods in chain.
   */
  @Deprecated
  DatabaseSession setConflictStrategy(String iStrategyName);

  /**
   * Overrides record conflict strategy.
   *
   * @param iResolver RecordConflictStrategy implementation
   * @return The Database instance itself giving a "fluent interface". Useful to call multiple
   * methods in chain.
   */
  @Deprecated
  DatabaseSession setConflictStrategy(RecordConflictStrategy iResolver);

  /**
   * Returns the total size of the records in the database.
   */
  long getSize();

  /**
   * Returns the number of collections.
   *
   * @return Number of the collections
   */
  int getCollections();

  /**
   * Returns true if the collection exists, otherwise false.
   *
   * @param iCollectionName Collection name
   * @return true if the collection exists, otherwise false
   */
  boolean existsCollection(String iCollectionName);

  /**
   * Returns all the names of the collections.
   *
   * @return Collection of collection names.
   */
  Collection<String> getCollectionNames();

  /**
   * Returns the collection id by name.
   *
   * @param iCollectionName Collection name
   * @return The id of searched collection.
   */
  int getCollectionIdByName(String iCollectionName);

  /**
   * Returns the collection name by id.
   *
   * @param iCollectionId Collection id
   * @return The name of searched collection.
   */
  @Nullable
  String getCollectionNameById(int iCollectionId);

  /**
   * Counts all the entities in the specified collection name.
   *
   * @param iCollectionName Collection name
   * @return Total number of entities contained in the specified collection
   */
  long countCollectionElements(String iCollectionName);

  SessionMetadata getMetadata();

  void afterCommitOperations(boolean rootTx, Map<RID, RID> updatedRids);

  Object get(ATTRIBUTES_INTERNAL attribute);

  void set(ATTRIBUTES_INTERNAL attribute, Object value);

  void setValidationEnabled(boolean value);

  LocalRecordCache getLocalCache();

  default TransactionMeters transactionMeters() {
    return TransactionMeters.NOOP;
  }

  /**
   * Subscribe a query as a live query for future create/update event with the referred conditions
   *
   * @param query    live query
   * @param listener the listener that receive the query results
   * @param args     the live query args
   */
  LiveQueryMonitor live(String query, LiveQueryResultListener listener, Map<String, ?> args);

  /**
   * Subscribe a query as a live query for future create/update event with the referred conditions
   *
   * @param query    live query
   * @param listener the listener that receive the query results
   * @param args     the live query args
   */
  LiveQueryMonitor live(String query, LiveQueryResultListener listener, Object... args);


  @Nonnull
  RID refreshRid(@Nonnull RID rid);

  /**
   * Returns the number of active nested transactions.
   *
   * @return the number of active transactions, 0 means no active transactions are present.
   * @see #begin()
   * @see #commit()
   * @see #rollback()
   */
  @Override
  int activeTxCount();

  /**
   * Loads an element by its id, throws an exception if record is not an element or does not exist.
   *
   * @param id the id of the element to load
   * @return the loaded element
   * @throws DatabaseException       if the record is not an element
   * @throws RecordNotFoundException if the record does not exist
   */
  @Nonnull
  default Entity loadEntity(RID id) throws DatabaseException, RecordNotFoundException {
    var record = load(id);
    if (record instanceof Entity element) {
      return element;
    }

    throw new DatabaseException(getDatabaseName(),
        "Record with id " + id + " is not an entity, but a " + record.getClass().getSimpleName());
  }


  /**
   * Loads a vertex by its id, throws an exception if record is not a vertex or does not exist.
   *
   * @param id the id of the vertex to load
   * @return the loaded vertex
   * @throws DatabaseException       if the record is not a vertex
   * @throws RecordNotFoundException if the record does not exist
   */
  @Nonnull
  default Vertex loadVertex(RID id) throws DatabaseException, RecordNotFoundException {
    var record = load(id);
    if (record instanceof Vertex vertex) {
      return vertex;
    }

    throw new DatabaseException(getDatabaseName(),
        "Record with id " + id + " is not a vertex, but a " + record.getClass().getSimpleName());
  }

  /**
   * Loads an edge by its id, throws an exception if record is not an edge or does not exist.
   *
   * @param id the id of the edge to load
   * @return the loaded edge
   * @throws DatabaseException       if the record is not an edge
   * @throws RecordNotFoundException if the record does not exist
   */
  @Nonnull
  default StatefulEdge loadEdge(RID id) throws DatabaseException, RecordNotFoundException {
    var record = load(id);

    if (record instanceof StatefulEdge edge) {
      return edge;
    }

    throw new DatabaseException(getDatabaseName(),
        "Record with id " + id + " is not an edge, but a " + record.getClass().getSimpleName());
  }

  /**
   * Loads a blob by its id, throws an exception if record is not a blob or does not exist.
   *
   * @param id the id of the blob to load
   * @return the loaded blob
   * @throws DatabaseException       if the record is not a blob
   * @throws RecordNotFoundException if the record does not exist
   */
  @Nonnull
  default Blob loadBlob(RID id) throws DatabaseException, RecordNotFoundException {
    var record = load(id);

    if (record instanceof Blob blob) {
      return blob;
    }

    throw new DatabaseException(getDatabaseName(),
        "Record with id " + id + " is not a blob, but a " + record.getClass().getSimpleName());
  }

  /**
   * Create a new instance of a blob containing the given bytes.
   *
   * @param bytes content of the Blob
   * @return the Blob instance.
   */
  Blob newBlob(byte[] bytes);


  /**
   * Create a new empty instance of a blob.
   *
   * @return the Blob instance.
   */
  Blob newBlob();


  Entity newEntity(final String className);

  Entity newEntity(final ImmutableSchemaClass cls);

  Entity newEntity();

  EmbeddedEntity newEmbeddedEntity(ImmutableSchemaClass schemaClass);

  EmbeddedEntity newEmbeddedEntity(String schemaClass);

  EmbeddedEntity newEmbeddedEntity();

  <T extends DBRecord> T createOrLoadRecordFromJson(String json);

  Entity createOrLoadEntityFromJson(String json);

  /**
   * Creates a new Edge of type E
   *
   * @param from the starting point vertex
   * @param to   the endpoint vertex
   * @return the edge
   */
  default StatefulEdge newStatefulEdge(Vertex from, Vertex to) {
    return newStatefulEdge(from, to, "E");
  }

  /**
   * Creates a new Edge
   *
   * @param from the starting point vertex
   * @param to   the endpoint vertex
   * @param type the edge type
   * @return the edge
   */
  StatefulEdge newStatefulEdge(Vertex from, Vertex to, ImmutableSchemaClass type);

  /**
   * Creates a new Edge
   *
   * @param from the starting point vertex
   * @param to   the endpoint vertex
   * @param type the edge type
   * @return the edge
   */
  StatefulEdge newStatefulEdge(Vertex from, Vertex to, String type);

  StatefullEdgeEntityImpl newStatefulEdgeInternal(String className);

  /**
   * Creates a new lightweight edge of provided type (class). Provided class should be an abstract
   * class.
   *
   * @param from the starting point vertex
   * @param to   the endpoint vertex
   * @param type the edge type
   * @return the edge
   */
  Edge newLightweightEdge(Vertex from, Vertex to, @Nonnull ImmutableSchemaClass type);

  /**
   * Creates a new lightweight edge of provided type (class). Provided class should be an abstract
   * class.
   *
   * @param from the starting point vertex
   * @param to   the endpoint vertex
   * @param type the edge type
   * @return the edge
   */
  Edge newLightweightEdge(Vertex from, Vertex to, @Nonnull String type);

  /**
   * Creates a new Vertex of type V
   */
  default Vertex newVertex() {
    return newVertex("V");
  }

  /**
   * Creates a new Vertex
   *
   * @param type the vertex type
   */
  Vertex newVertex(ImmutableSchemaClass type);

  /**
   * Creates a new Vertex
   *
   * @param type the vertex type (class name)
   */
  Vertex newVertex(String type);

  /**
   * Loads the entity by the Record ID.
   *
   * @param recordId The unique record id of the entity to load.
   * @return The loaded entity
   * @throws RecordNotFoundException if record does not exist in database
   */
  @Nonnull
  <RET extends DBRecord> RET load(RID recordId);

  /**
   * Loads the entity by the Record ID, unlike {@link  #load(RID)} method does not throw exception
   * if record not found but returns <code>null</code> instead.
   *
   * @param recordId The unique record id of the entity to load.
   * @return The loaded entity or <code>null</code> if entity does not exist.
   */
  @Nullable
  default <RET extends DBRecord> RET loadOrNull(RID recordId) {
    try {
      return load(recordId);
    } catch (RecordNotFoundException e) {
      return null;
    }
  }

  /**
   * Checks if record exists in database. That happens in two cases:
   * <ol>
   *   <li>Record is already stored in database.</li>
   *   <li>Record is only added in current transaction.</li>
   * </ol>
   * <p/>
   *
   * @param rid Record id to check.
   * @return True if record exists, otherwise false.
   */
  boolean exists(RID rid);

  /**
   * Deletes an entity from the database in synchronous mode.
   *
   * @param record The entity to delete.
   */
  void delete(@Nonnull DBRecord record);

  /**
   * Commits the current transaction. The approach is all or nothing. All changes will be permanent
   * following the storage type. If the operation succeed all the entities changed inside the
   * transaction context will be effective. If the operation fails, all the changed entities will be
   * restored in the data store.
   *
   * @return Map of RIDs updated during transaction (oldRid, newRid) or <code>null</code> if that is
   * not the highest level commit and transaction is not committed yet.
   */
  @Nullable
  Map<RID, RID> commit() throws TransactionException;

  /**
   * Aborts the current running transaction. All the pending changed entities will be restored in
   * the data store.
   */
  void rollback() throws TransactionException;

  /**
   * Close all active queries. This method is called upon transaction commit/rollback.
   */
  void closeActiveQueries();

  /**
   * Executes an SQL query. The result set has to be closed after usage <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * ResultSet rs = db.query("SELECT FROM V where name = ?", "John"); while(rs.hasNext()){ Result
   * item = rs.next(); ... } rs.close(); </code>
   *
   * @param query the query string
   * @param args  query parameters (positional)
   * @return the query result set
   */
  ResultSet query(String query, Object... args)
      throws CommandSQLParsingException, CommandExecutionException;

  /**
   * Executes an SQL query (idempotent). The result set has to be closed after usage <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * Map&lt;String, Object&gt params = new HashMapMap&lt;&gt(); params.put("name", "John");
   * ResultSet rs = db.query("SELECT FROM V where name = :name", params); while(rs.hasNext()){
   * Result item = rs.next(); ... } rs.close();
   * </code>
   *
   * @param query the query string
   * @param args  query parameters (named)
   * @return the query result set
   */
  ResultSet query(String query, @SuppressWarnings("rawtypes") Map args)
      throws CommandSQLParsingException, CommandExecutionException;

  ResultSet query(String query, boolean syncTx, @SuppressWarnings("rawtypes") Map args)
      throws CommandSQLParsingException, CommandExecutionException;

  /**
   * Executes a generic (idempotent or non-idempotent) command. The result set has to be closed
   * after usage <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * ResultSet rs = db.execute("INSERT INTO Person SET name = ?", "John"); ... rs.close();
   * </code>
   *
   * @param args query arguments
   * @return the query result set
   */
  default ResultSet execute(String query, Object... args)
      throws CommandSQLParsingException, CommandExecutionException {
    throw new UnsupportedOperationException();
  }

  /**
   * Executes a generic (idempotent or non-idempotent) command. The result set has to be closed
   * after usage <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * ResultSet rs = db.execute("INSERT INTO Person SET name = :name", Map.of("name", "John")); ...
   * rs.close();
   * </code>
   */
  default ResultSet execute(String query, @SuppressWarnings("rawtypes") Map args)
      throws CommandSQLParsingException, CommandExecutionException {
    throw new UnsupportedOperationException();
  }

  /**
   * Executes a generic (non-idempotent) command, ignoring the produced result. Works in the same
   * way as {@link DatabaseSessionInternal#execute(String, Object...)}, but doesn't require closing
   * the result set after usage. <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * ResultSet rs = db.command("INSERT INTO Person SET name = ?", "John");
   * </code>
   *
   * @param args query arguments
   */
  default void command(String query, Object... args)
      throws CommandSQLParsingException, CommandExecutionException {
    execute(query, args).close();
  }

  /**
   * Executes a generic (non-idempotent) command, ignoring the produced result. Works in the same
   * way as {@link DatabaseSessionInternal#execute(String, Map)}, but doesn't require closing the
   * result set after usage. <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * ResultSet rs = db.command("INSERT INTO Person SET name = :name", Map.of("name", "John");
   * </code>
   *
   * @param args query arguments
   */
  default void command(String query, @SuppressWarnings("rawtypes") Map args)
      throws CommandSQLParsingException, CommandExecutionException {
    execute(query, args).close();
  }


  /**
   * creates a new vertex class (a class that extends V)
   *
   * @param className the class name
   * @return The object representing the class in the schema
   * @throws SchemaException if the class already exists or if V class is not defined (Eg. if it was
   *                         deleted from the schema)
   */
  default SchemaClass createVertexClass(String className) throws SchemaException {
    return createClass(className, SchemaClass.VERTEX_CLASS_NAME);
  }

  /**
   * Creates a non-abstract new edge class (a class that extends E)
   *
   * @param className the class name
   * @return The object representing the class in the schema
   * @throws SchemaException if the class already exists or if E class is not defined (Eg. if it was
   *                         deleted from the schema)
   */
  default SchemaClass createEdgeClass(String className) {
    var edgeClass = createClass(className, SchemaClass.EDGE_CLASS_NAME);

    edgeClass.createProperty(Edge.DIRECTION_IN, PropertyType.LINK);
    edgeClass.createProperty(Edge.DIRECTION_OUT, PropertyType.LINK);

    return edgeClass;
  }

  /**
   * Creates a new edge class for lightweight edge (an abstract class that extends E)
   *
   * @param className the class name
   * @return The object representing the class in the schema
   * @throws SchemaException if the class already exists or if E class is not defined (Eg. if it was
   *                         deleted from the schema)
   */
  default SchemaClass createLightweightEdgeClass(String className) {
    return createAbstractClass(className, SchemaClass.EDGE_CLASS_NAME);
  }

  /**
   * Creates a new class in the schema
   *
   * @param className    the class name
   * @param superclasses a list of superclasses for the class (can be empty)
   * @return the class with the given name
   * @throws SchemaException if a class with this name already exists or if one of the superclasses
   *                         does not exist.
   */
  default SchemaClass createClass(String className, String... superclasses) throws SchemaException {
    var schema = getMetadata().getSlowMutableSchema();
    SchemaClass[] superclassInstances = null;
    if (superclasses != null) {
      superclassInstances = new SchemaClass[superclasses.length];
      for (var i = 0; i < superclasses.length; i++) {
        var superclass = superclasses[i];
        var superclazz = schema.getClass(superclass);
        if (superclazz == null) {
          throw new SchemaException(getDatabaseName(), "Class " + superclass + " does not exist");
        }
        superclassInstances[i] = superclazz;
      }
    }
    var result = schema.getClass(className);
    if (result != null) {
      throw new SchemaException(getDatabaseName(), "Class " + className + " already exists");
    }
    if (superclassInstances == null) {
      return schema.createClass(className);
    } else {
      return schema.createClass(className, superclassInstances);
    }
  }

  /**
   * Creates a new abstract class in the schema
   *
   * @param className    the class name
   * @param superclasses a list of superclasses for the class (can be empty)
   * @return the class with the given name
   * @throws SchemaException if a class with this name already exists or if one of the superclasses
   *                         does not exist.
   */
  default SchemaClass createAbstractClass(String className, String... superclasses)
      throws SchemaException {
    var schema = getMetadata().getSlowMutableSchema();
    SchemaClass[] superclassInstances = null;
    if (superclasses != null) {
      superclassInstances = new SchemaClass[superclasses.length];
      for (var i = 0; i < superclasses.length; i++) {
        var superclass = superclasses[i];
        var superclazz = schema.getClass(superclass);
        if (superclazz == null) {
          throw new SchemaException(getDatabaseName(), "Class " + superclass + " does not exist");
        }
        superclassInstances[i] = superclazz;
      }
    }
    var result = schema.getClass(className);
    if (result != null) {
      throw new SchemaException(getDatabaseName(), "Class " + className + " already exists");
    }
    if (superclassInstances == null) {
      return schema.createAbstractClass(className);
    } else {
      return schema.createAbstractClass(className, superclassInstances);
    }
  }

  /**
   * If a class with given name already exists, it's just returned, otherwise the method creates a
   * new class and returns it.
   *
   * @param className    the class name
   * @param superclasses a list of superclasses for the class (can be empty)
   * @return the class with the given name
   * @throws SchemaException if one of the superclasses does not exist in the schema
   */
  default SchemaClass createClassIfNotExist(String className, String... superclasses)
      throws SchemaException {
    var schema = getMetadata().getSlowMutableSchema();

    var result = schema.getClass(className);
    if (result == null) {
      result = createClass(className, superclasses);
    }

    return result;
  }

  <X extends Exception> void executeInTxInternal(@Nonnull TxConsumer<FrontendTransaction, X> code)
      throws X;

  @Override
  default <X extends Exception> void executeInTx(@Nonnull TxConsumer<Transaction, X> code)
      throws X {
    executeInTxInternal(code::accept);
  }

  @Nullable
  <R, X extends Exception> R computeInTxInternal(TxFunction<FrontendTransaction, R, X> supplier)
      throws X;

  @Nullable
  @Override
  default <R, X extends Exception> R computeInTx(TxFunction<Transaction, R, X> supplier) throws X {
    return computeInTxInternal(supplier::apply);
  }

  <T, X extends Exception> void executeInTxBatchesInternal(Stream<T> stream,
      TxBiConsumer<FrontendTransaction, T, X> consumer) throws X;

  @Override
  default <T, X extends Exception> void executeInTxBatches(Stream<T> stream,
      TxBiConsumer<Transaction, T, X> consumer) throws X {
    executeInTxBatchesInternal(stream, consumer::accept);
  }

  <T, X extends Exception> void executeInTxBatchesInternal(
      Iterator<T> iterator, TxBiConsumer<FrontendTransaction, T, X> consumer) throws X;


  @Override
  default <T, X extends Exception> void executeInTxBatches(Iterator<T> iterator,
      TxBiConsumer<Transaction, T, X> consumer) throws X {
    executeInTxBatchesInternal(iterator, consumer::accept);
  }


  <T, X extends Exception> void executeInTxBatchesInternal(
      @Nonnull Iterator<T> iterator, int batchSize,
      TxBiConsumer<FrontendTransaction, T, X> consumer) throws X;

  @Override
  default <T, X extends Exception> void executeInTxBatches(@Nonnull Iterator<T> iterator,
      int batchSize,
      TxBiConsumer<Transaction, T, X> consumer) throws X {
    executeInTxBatchesInternal(iterator, batchSize, consumer::accept);
  }

  @Override
  @Nonnull
  FrontendTransaction getActiveTransaction();

  @Override
  FrontendTransaction begin();

  default Index getIndex(String indexName) {
    var indexManager = getSharedContext().getIndexManager();
    return indexManager.getIndex(indexName);
  }

  enum ATTRIBUTES_INTERNAL {
    VALIDATION
  }

  record TransactionMeters(
      TimeRate totalTransactions,
      TimeRate writeTransactions,
      TimeRate writeRollbackTransactions
  ) {

    public static TransactionMeters NOOP = new TransactionMeters(
        TimeRate.NOOP,
        TimeRate.NOOP,
        TimeRate.NOOP
    );
  }
}
