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

package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.SessionListener;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.exception.TransactionException;
import com.jetbrains.youtrack.db.api.query.LiveQueryMonitor;
import com.jetbrains.youtrack.db.api.query.LiveQueryResultListener;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.EmbeddedEntity;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.RecordHook;
import com.jetbrains.youtrack.db.api.record.RecordHook.TYPE;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.TimeRate;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.cache.LocalRecordCache;
import com.jetbrains.youtrack.db.internal.core.conflict.RecordConflictStrategy;
import com.jetbrains.youtrack.db.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorClass;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorCluster;
import com.jetbrains.youtrack.db.internal.core.metadata.MetadataInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Token;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EdgeInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.security.SecurityUser;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.storage.RecordMetadata;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.StorageInfo;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BTreeCollectionManager;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BonsaiCollectionPointer;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionImpl;
import com.jetbrains.youtrack.db.internal.enterprise.EnterpriseEndpoint;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TimerTask;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface DatabaseSessionInternal extends DatabaseSession {

  /**
   * Internal. Returns the factory that defines a set of components that current database should use
   * to be compatible to current version of storage. So if you open a database create with old
   * version of YouTrackDB it defines a components that should be used to provide backward
   * compatibility with that version of database.
   */
  CurrentStorageComponentsFactory getStorageVersions();

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
  BTreeCollectionManager getBTreeCollectionManager();

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

  void registerHook(final @Nonnull RecordHook iHookImpl);

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

  /**
   * Adds a new cluster for store blobs.
   *
   * @param iClusterName Cluster name
   * @param iParameters  Additional parameters to pass to the factories
   * @return Cluster id
   */
  int addBlobCluster(String iClusterName, Object... iParameters);

  int begin(FrontendTransactionImpl tx);

  void setSerializer(RecordSerializer serializer);

  int assignAndCheckCluster(DBRecord record);

  void reloadUser();

  void afterReadOperations(final RecordAbstract identifiable);

  boolean beforeReadOperations(final RecordAbstract identifiable);

  void afterUpdateOperations(final RecordAbstract id, java.lang.String clusterName);

  void afterCreateOperations(final RecordAbstract id, String clusterName);

  void afterDeleteOperations(final RecordAbstract id, java.lang.String clusterName);

  void callbackHooks(final TYPE type, final RecordAbstract record);

  @Nullable
  <RET extends RecordAbstract> RawPair<RET, RecordId> loadFirstRecordAndNextRidInCluster(
      int clusterId);

  @Nullable
  <RET extends RecordAbstract> RawPair<RET, RecordId> loadLastRecordAndPreviousRidInCluster(
      int clusterId);

  @Nullable
  <RET extends RecordAbstract> RawPair<RET, RecordId> loadRecordAndNextRidInCluster(
      @Nonnull final RecordId recordId);

  @Nullable
  <RET extends RecordAbstract> RawPair<RET, RecordId> loadRecordAndPreviousRidInCluster(
      @Nonnull final RecordId recordId);

  @Nullable
  LoadRecordResult executeReadRecord(@Nonnull final RecordId rid, boolean fetchPreviousRid,
      boolean fetchNextRid, boolean throwIfNotFound);

  boolean executeExists(@Nonnull RID rid);

  void setDefaultTransactionMode();

  DatabaseSessionInternal copy();

  void recycle(DBRecord record);

  boolean assertIfNotActive();

  void callOnOpenListeners();

  void callOnCloseListeners();

  DatabaseSession setCustom(final String name, final Object iValue);

  void setPrefetchRecords(boolean prefetchRecords);

  boolean isPrefetchRecords();

  void checkForClusterPermissions(String name);

  @Nullable
  default ResultSet getActiveQuery(String id) {
    throw new UnsupportedOperationException();
  }

  default Map<String, QueryDatabaseState> getActiveQueries() {
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

  boolean isClusterVertex(int cluster);

  boolean isClusterEdge(int cluster);

  void deleteInternal(@Nonnull DBRecord record);

  void internalClose(boolean recycle);

  String getClusterName(@Nonnull final DBRecord record);

  default boolean isRemote() {
    return false;
  }

  Map<UUID, BonsaiCollectionPointer> getCollectionsChanges();

  boolean dropClusterInternal(int clusterId);

  String getClusterRecordConflictStrategy(int clusterId);

  int[] getClustersIds(@Nonnull Set<String> filterClusters);

  default void startExclusiveMetadataChange() {
  }

  default void endExclusiveMetadataChange() {
  }

  void truncateClass(String name);

  long truncateClass(String name, boolean polimorfic);

  long truncateClusterInternal(String name);

  /**
   * Browses all the records of the specified cluster.
   *
   * @param iClusterName Cluster name to iterate
   * @return Iterator of EntityImpl instances
   */
  <REC extends RecordAbstract> RecordIteratorCluster<REC> browseCluster(String iClusterName);

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

  RecordIteratorClass browseClass(@Nonnull SchemaClass clz);

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
   * @param iResourceGeneric  Resource where to execute the operation, i.e.: database.clusters
   * @param iOperation        Operation to execute against the resource
   * @param iResourceSpecific Target resource, i.e.: "employee" to specify the cluster name.
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
   * @param iResourceGeneric   Resource where to execute the operation, i.e.: database.clusters
   * @param iOperation         Operation to execute against the resource
   * @param iResourcesSpecific Target resources as an array of Objects, i.e.: ["employee", 2] to
   *                           specify cluster name and id.
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
   * @param iResourceGeneric  Resource where to execute the operation, i.e.: database.clusters
   * @param iOperation        Operation to execute against the resource
   * @param iResourceSpecific Target resource, i.e.: "employee" to specify the cluster name.
   */
  void checkSecurity(
      Rule.ResourceGeneric iResourceGeneric, int iOperation, Object iResourceSpecific);

  void checkSecurity(final int operation, final Identifiable record, String cluster);

  /**
   * Checks if the operation against multiple resources is allowed for the current user. The check
   * is made in two steps:
   *
   * <ol>
   *   <li>Access to all the resource as *
   *   <li>Access to the specific target resources
   * </ol>
   *
   * @param iResourceGeneric   Resource where to execute the operation, i.e.: database.clusters
   * @param iOperation         Operation to execute against the resource
   * @param iResourcesSpecific Target resources as an array of Objects, i.e.: ["employee", 2] to
   *                           specify cluster name and id.
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
   * Reloads the database information like the cluster list.
   */
  void reload();

  /**
   * Returns the underlying storage implementation.
   *
   * @return The underlying storage implementation
   * @see Storage
   */
  Storage getStorage();

  StorageInfo getStorageInfo();

  /**
   * Set user for current database instance.
   */
  void setUser(SecurityUser user);

  void resetInitialization();

  /**
   * Returns the database owner. Used in wrapped instances to know the up level ODatabase instance.
   *
   * @return Returns the database owner.
   */
  DatabaseSessionInternal getDatabaseOwner();

  /**
   * Internal. Sets the database owner.
   */
  DatabaseSessionInternal setDatabaseOwner(DatabaseSessionInternal iOwner);

  /**
   * Return the underlying database. Used in wrapper instances to know the down level ODatabase
   * instance.
   *
   * @return The underlying ODatabase implementation.
   */
  DatabaseSession getUnderlying();

  /**
   * Internal method. Don't call it directly unless you're building an internal component.
   */
  void setInternal(ATTRIBUTES attribute, Object iValue);

  /**
   * Opens a database using an authentication token received as an argument.
   *
   * @param iToken Authentication token
   * @return The Database instance itself giving a "fluent interface". Useful to call multiple
   * methods in chain.
   */
  @Deprecated
  DatabaseSession open(final Token iToken);

  SharedContext getSharedContext();

  /**
   * @return an endpoint for Enterprise features. Null in Community Edition
   */
  @Nullable
  default EnterpriseEndpoint getEnterpriseEndpoint() {
    return null;
  }

  default DatabaseStats getStats() {
    return new DatabaseStats();
  }

  default void resetRecordLoadStats() {
  }

  default void addRidbagPrefetchStats(long execTimeMs) {
  }

  /**
   * creates an interrupt timer task for this db instance (without scheduling it!)
   *
   * @return the timer task. Null if this operation is not supported for current db impl.
   */
  @Nullable
  default TimerTask createInterruptTimerTask() {
    return null;
  }

  /**
   * Opens a database using the user and password received as arguments.
   *
   * @param iUserName     Username to login
   * @param iUserPassword Password associated to the user
   * @return The Database instance itself giving a "fluent interface". Useful to call multiple
   * methods in chain.
   */
  @Deprecated
  DatabaseSession open(final String iUserName, final String iUserPassword);

  /**
   * Creates a new database.
   *
   * @return The Database instance itself giving a "fluent interface". Useful to call multiple
   * methods in chain.
   */
  @Deprecated
  DatabaseSession create();

  /**
   * Creates new database from database backup. Only incremental backups are supported.
   *
   * @param incrementalBackupPath Path to incremental backup
   * @return he Database instance itself giving a "fluent interface". Useful to call multiple
   * methods in chain.
   */
  @Deprecated
  DatabaseSession create(String incrementalBackupPath);

  /**
   * Creates a new database passing initial settings.
   *
   * @return The Database instance itself giving a "fluent interface". Useful to call multiple
   * methods in chain.
   */
  @Deprecated
  DatabaseSession create(Map<GlobalConfiguration, Object> iInitialSettings);

  /**
   * Drops a database.
   *
   * @throws DatabaseException if database is closed. @Deprecated use instead
   *                           {@link YouTrackDB#drop}
   */
  @Deprecated
  void drop();

  /**
   * Checks if the database exists.
   *
   * @return True if already exists, otherwise false.
   */
  @Deprecated
  boolean exists();

  /**
   * Set the current status of database. deprecated since 2.2
   */
  @Deprecated
  DatabaseSession setStatus(STATUS iStatus);

  /**
   * Returns the total size of records contained in the cluster defined by its name.
   *
   * @param iClusterName Cluster name
   * @return Total size of records contained.
   */
  @Deprecated
  long getClusterRecordSizeByName(String iClusterName);

  /**
   * Returns the total size of records contained in the cluster defined by its id.
   *
   * @param iClusterId Cluster id
   * @return The name of searched cluster.
   */
  @Deprecated
  long getClusterRecordSizeById(int iClusterId);

  /**
   * Removes all data in the cluster with given name. As result indexes for this class will be
   * rebuilt.
   *
   * @param clusterName Name of cluster to be truncated.
   */
  void truncateCluster(String clusterName);

  /**
   * Counts all the entities in the specified cluster id.
   *
   * @param iCurrentClusterId Cluster id
   * @return Total number of entities contained in the specified cluster
   */
  long countClusterElements(int iCurrentClusterId);

  @Deprecated
  long countClusterElements(int iCurrentClusterId, boolean countTombstones);

  /**
   * Counts all the entities in the specified cluster ids.
   *
   * @param iClusterIds Array of cluster ids Cluster id
   * @return Total number of entities contained in the specified clusters
   */
  long countClusterElements(int[] iClusterIds);

  @Deprecated
  long countClusterElements(int[] iClusterIds, boolean countTombstones);

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

  void rollback(boolean force) throws TransactionException;

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
   * Returns the number of clusters.
   *
   * @return Number of the clusters
   */
  int getClusters();

  /**
   * Returns true if the cluster exists, otherwise false.
   *
   * @param iClusterName Cluster name
   * @return true if the cluster exists, otherwise false
   */
  boolean existsCluster(String iClusterName);

  /**
   * Returns all the names of the clusters.
   *
   * @return Collection of cluster names.
   */
  Collection<String> getClusterNames();

  /**
   * Returns the cluster id by name.
   *
   * @param iClusterName Cluster name
   * @return The id of searched cluster.
   */
  int getClusterIdByName(String iClusterName);

  /**
   * Returns the cluster name by id.
   *
   * @param iClusterId Cluster id
   * @return The name of searched cluster.
   */
  @Nullable
  String getClusterNameById(int iClusterId);

  /**
   * Counts all the entities in the specified cluster name.
   *
   * @param iClusterName Cluster name
   * @return Total number of entities contained in the specified cluster
   */
  long countClusterElements(String iClusterName);

  /**
   * Adds a new cluster.
   *
   * @param iClusterName Cluster name
   * @param iParameters  Additional parameters to pass to the factories
   * @return Cluster id
   */
  int addCluster(String iClusterName, Object... iParameters);

  boolean dropCluster(final String iClusterName);

  boolean dropCluster(final int clusterId);

  /**
   * Adds a new cluster.
   *
   * @param iClusterName Cluster name
   * @param iRequestedId requested id of the cluster
   * @return Cluster id
   */
  int addCluster(String iClusterName, int iRequestedId);

  MetadataInternal getMetadata();

  void afterCommitOperations();

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

  Entity newEntity(final SchemaClass cls);

  Entity newEntity();

  EmbeddedEntity newEmbeddedEntity(SchemaClass schemaClass);

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
  StatefulEdge newStatefulEdge(Vertex from, Vertex to, SchemaClass type);

  /**
   * Creates a new Edge
   *
   * @param from the starting point vertex
   * @param to   the endpoint vertex
   * @param type the edge type
   * @return the edge
   */
  StatefulEdge newStatefulEdge(Vertex from, Vertex to, String type);

  /**
   * Creates a new lightweight edge of provided type (class). Provided class should be an abstract
   * class.
   *
   * @param from the starting point vertex
   * @param to   the endpoint vertex
   * @param type the edge type
   * @return the edge
   */
  Edge newLightweightEdge(Vertex from, Vertex to, @Nonnull SchemaClass type);

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
  Vertex newVertex(SchemaClass type);

  /**
   * Creates a new Vertex
   *
   * @param type the vertex type (class name)
   */
  Vertex newVertex(String type);

  /**
   * Retrieve the set of defined blob cluster.
   *
   * @return the array of defined blob cluster ids.
   */
  int[] getBlobClusterIds();

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
   * @return true if the transaction is the last nested transaction and thus cmd can be committed,
   * otherwise false. If false is returned, then there are still nested transaction that have to be
   * committed.
   */
  boolean commit() throws TransactionException;

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
  default ResultSet query(String query, Object... args)
      throws CommandSQLParsingException, CommandExecutionException {
    throw new UnsupportedOperationException();
  }

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
  default ResultSet query(String query, Map args)
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
  default ResultSet execute(String query, Map args)
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
  default void command(String query, Map args)
      throws CommandSQLParsingException, CommandExecutionException {
    execute(query, args).close();
  }

  /**
   * retrieves a class from the schema
   *
   * @param className The class name
   * @return The object representing the class in the schema. Null if the class does not exist.
   */
  default SchemaClassInternal getClassInternal(String className) {
    var schema = getMetadata().getSchemaInternal();
    return schema.getClassInternal(className);
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
   * retrieves a class from the schema
   *
   * @param className The class name
   * @return The object representing the class in the schema. Null if the class does not exist.
   */
  default SchemaClass getClass(String className) {
    var schema = getSchema();
    return schema.getClass(className);
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
    var schema = getSchema();
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
    var schema = getSchema();
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
    var schema = getSchema();

    var result = schema.getClass(className);
    if (result == null) {
      result = createClass(className, superclasses);
    }

    return result;
  }


  default Index getIndex(String indexName) {
    var metadata = getMetadata();
    var indexManager = metadata.getIndexManagerInternal();
    return indexManager.getIndex(this, indexName);
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
