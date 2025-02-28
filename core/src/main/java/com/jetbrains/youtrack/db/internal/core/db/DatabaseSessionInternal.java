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
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.TransactionException;
import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.RecordHook;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.security.SecurityUser;
import com.jetbrains.youtrack.db.api.session.SessionListener;
import com.jetbrains.youtrack.db.internal.core.cache.LocalRecordCache;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
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
import com.jetbrains.youtrack.db.internal.core.query.Query;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EdgeInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.storage.RecordMetadata;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.StorageInfo;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BTreeCollectionManager;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BonsaiCollectionPointer;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionNoTx;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionOptimistic;
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

public interface DatabaseSessionInternal extends DatabaseSession {

  /**
   * Internal. Returns the factory that defines a set of components that current database should use
   * to be compatible to current version of storage. So if you open a database create with old
   * version of YouTrackDB it defines a components that should be used to provide backward
   * compatibility with that version of database.
   */
  CurrentStorageComponentsFactory getStorageVersions();

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
  BTreeCollectionManager getSbTreeCollectionManager();

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

  int begin(FrontendTransactionOptimistic tx);

  void setSerializer(RecordSerializer serializer);

  int assignAndCheckCluster(DBRecord record, String clusterName);

  void reloadUser();

  void afterReadOperations(final Identifiable identifiable);

  boolean beforeReadOperations(final Identifiable identifiable);

  void beforeCreateOperations(final Identifiable id, String iClusterName);

  void beforeUpdateOperations(final Identifiable id, String iClusterName);

  void beforeDeleteOperations(final Identifiable id, String iClusterName);

  void afterUpdateOperations(final Identifiable id);

  void afterCreateOperations(final Identifiable id);

  void afterDeleteOperations(final Identifiable id);

  RecordHook.RESULT callbackHooks(final RecordHook.TYPE type, final Identifiable id);

  @Nonnull
  <RET extends RecordAbstract> RET executeReadRecord(final RecordId rid);

  boolean executeExists(RID rid);

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

  DatabaseSessionInternal cleanOutRecord(RID rid, int version);

  default void realClose() {
    // Only implemented by pooled instances
    throw new UnsupportedOperationException();
  }

  default void reuse() {
    // Only implemented by pooled instances
    throw new UnsupportedOperationException();
  }

  /**
   * synchronizes current database instance with the rest of the cluster (if in distributed mode).
   *
   * @return true if the database was synchronized, false otherwise
   */
  default boolean sync(boolean forceDeployment, boolean tryWithDelta) {
    return false;
  }

  default Map<String, Object> getHaStatus(
      boolean servers, boolean db, boolean latency, boolean messages) {
    return null;
  }

  default boolean removeHaServer(String serverName) {
    return false;
  }

  /**
   * sends an execution plan to a remote node for a remote query execution
   *
   * @param nodeName        the node name
   * @param executionPlan   the execution plan
   * @param inputParameters the input parameters for execution
   * @return an ResultSet to fetch the results of the query execution
   */
  default ResultSet queryOnNode(
      String nodeName, ExecutionPlan executionPlan, Map<Object, Object> inputParameters) {
    throw new UnsupportedOperationException();
  }

  /**
   * Executed the commit on the storage hiding away storage concepts from the transaction
   */
  void internalCommit(@Nonnull FrontendTransactionOptimistic transaction);

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

  long[] getClusterDataRange(int currentClusterId);

  long getLastClusterPosition(int clusterId);

  String getClusterRecordConflictStrategy(int clusterId);

  int[] getClustersIds(@Nonnull Set<String> filterClusters);

  default void startExclusiveMetadataChange() {
  }

  default void endExclusiveMetadataChange() {
  }

  void truncateClass(String name);

  long truncateClass(String name, boolean polimorfic);

  long truncateClusterInternal(String name);

  FrontendTransactionNoTx.NonTxReadMode getNonTxReadMode();

  /**
   * Browses all the records of the specified cluster.
   *
   * @param iClusterName Cluster name to iterate
   * @return Iterator of EntityImpl instances
   */
  <REC extends DBRecord> RecordIteratorCluster<REC> browseCluster(String iClusterName);

  <REC extends DBRecord> RecordIteratorCluster<REC> browseCluster(
      String iClusterName,
      long startClusterPosition,
      long endClusterPosition,
      boolean loadTombstones);

  /**
   * Browses all the records of the specified cluster of the passed record type.
   *
   * @param iClusterName Cluster name to iterate
   * @param iRecordClass The record class expected
   * @return Iterator of EntityImpl instances
   */
  @Deprecated
  <REC extends DBRecord> RecordIteratorCluster<REC> browseCluster(
      String iClusterName, Class<REC> iRecordClass);

  @Deprecated
  <REC extends DBRecord> RecordIteratorCluster<REC> browseCluster(
      String iClusterName,
      Class<REC> iRecordClass,
      long startClusterPosition,
      long endClusterPosition);

  @Deprecated
  <REC extends DBRecord> RecordIteratorCluster<REC> browseCluster(
      String iClusterName,
      Class<REC> iRecordClass,
      long startClusterPosition,
      long endClusterPosition,
      boolean loadTombstones);

  /**
   * Browses all the records of the specified class and also all the subclasses. If you've a class
   * Vehicle and Car that extends Vehicle then a db.browseClass("Vehicle", true) will return all the
   * instances of Vehicle and Car. The order of the returned instance starts from record id with
   * position 0 until the end. Base classes are worked at first.
   *
   * @param className Class name to iterate
   * @return Iterator of EntityImpl instances
   */
  RecordIteratorClass<EntityImpl> browseClass(@Nonnull String className);

  /**
   * Browses all the records of the specified class and if iPolymorphic is true also all the
   * subclasses. If you've a class Vehicle and Car that extends Vehicle then a
   * db.browseClass("Vehicle", true) will return all the instances of Vehicle and Car. The order of
   * the returned instance starts from record id with position 0 until the end. Base classes are
   * worked at first.
   *
   * @param className   Class name to iterate
   * @param iPolymorphic Consider also the instances of the subclasses or not
   * @return Iterator of EntityImpl instances
   */
  RecordIteratorClass<EntityImpl> browseClass(@Nonnull String className, boolean iPolymorphic);

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
  FrontendTransaction getTransaction();

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
   * Execute a query against the database. If the Storage used is remote (OStorageRemote) then the
   * command will be executed remotely and the result returned back to the calling client.
   *
   * @param iCommand Query command
   * @param iArgs    Optional parameters to bind to the query
   * @return List of POJOs
   * @deprecated use {@link #query(String, Map)} or {@link #query(String, Object...)} instead
   */
  @Deprecated
  <RET extends List<?>> RET query(final Query<?> iCommand, final Object... iArgs);

  /**
   * Creates a command request to run a command against the database (you have to invoke
   * .execute(parameters) to actually execute it). A command can be a SQL statement or a Procedure.
   * If the Storage used is remote (OStorageRemote) then the command will be executed remotely and
   * the result returned back to the calling client.
   *
   * @param iCommand Command request to execute.
   * @return The same Command request received as parameter.
   * @deprecated use {@link #command(String, Map)}, {@link #command(String, Object...)},
   * {@link #execute(String, String, Map)}, {@link #execute(String, String, Object...)} instead
   */
  @Deprecated
  <RET extends CommandRequest> RET command(CommandRequest iCommand);

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
   * @param iValue
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

  @Nonnull
  RID refreshRid(@Nonnull RID rid);

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

  default Index getIndex(String indexName) {
    var metadata = getMetadata();
    var indexManager = metadata.getIndexManagerInternal();
    return indexManager.getIndex(this, indexName);
  }

  enum ATTRIBUTES_INTERNAL {
    VALIDATION
  }

}
