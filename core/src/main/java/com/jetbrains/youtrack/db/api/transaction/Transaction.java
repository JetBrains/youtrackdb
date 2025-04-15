package com.jetbrains.youtrack.db.api.transaction;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.exception.TransactionException;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.EmbeddedEntity;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.record.collection.embedded.EmbeddedList;
import com.jetbrains.youtrack.db.api.record.collection.embedded.EmbeddedMap;
import com.jetbrains.youtrack.db.api.record.collection.embedded.EmbeddedSet;
import com.jetbrains.youtrack.db.api.record.collection.links.LinkList;
import com.jetbrains.youtrack.db.api.record.collection.links.LinkMap;
import com.jetbrains.youtrack.db.api.record.collection.links.LinkSet;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@SuppressWarnings("unused")
public interface Transaction {

  boolean isActive();

  @Nonnull
  Stream<RecordOperation> getRecordOperations();

  int getRecordOperationsCount();

  int activeTxCount();

  @Nonnull
  DatabaseSession getDatabaseSession();


  /**
   * Loads an element by its id, throws an exception if record is not an element or does not exist.
   *
   * @param id the id of the element to load
   * @return the loaded element
   * @throws DatabaseException       if the record is not an element
   * @throws RecordNotFoundException if the record does not exist
   */
  @Nonnull
  Entity loadEntity(RID id) throws DatabaseException, RecordNotFoundException;

  @Nullable
  Entity loadEntityOrNull(RID id) throws DatabaseException;

  @Nonnull
  Entity loadEntity(Identifiable identifiable) throws DatabaseException, RecordNotFoundException;

  @Nullable
  Entity loadEntityOrNull(Identifiable identifiable) throws DatabaseException;

  /**
   * Loads a vertex by its id, throws an exception if record is not a vertex or does not exist.
   *
   * @param id the id of the vertex to load
   * @return the loaded vertex
   * @throws DatabaseException       if the record is not a vertex
   * @throws RecordNotFoundException if the record does not exist
   */
  @Nonnull
  Vertex loadVertex(RID id) throws DatabaseException, RecordNotFoundException;

  @Nullable
  Vertex loadVertexOrNull(RID id) throws RecordNotFoundException;

  @Nonnull
  Vertex loadVertex(Identifiable identifiable) throws DatabaseException, RecordNotFoundException;

  @Nullable
  Vertex loadVertexOrNull(Identifiable identifiable) throws RecordNotFoundException;

  /**
   * Loads an edge by its id, throws an exception if record is not an edge or does not exist.
   *
   * @param id the id of the edge to load
   * @return the loaded edge
   * @throws DatabaseException       if the record is not an edge
   * @throws RecordNotFoundException if the record does not exist
   */
  @Nonnull
  StatefulEdge loadEdge(@Nonnull RID id) throws DatabaseException, RecordNotFoundException;

  @Nullable
  StatefulEdge loadEdgeOrNull(@Nonnull RID id) throws DatabaseException;

  @Nonnull
  StatefulEdge loadEdge(@Nonnull Identifiable id) throws DatabaseException, RecordNotFoundException;

  @Nullable
  StatefulEdge loadEdgeOrNull(@Nonnull Identifiable id) throws DatabaseException;

  /**
   * Loads a blob by its id, throws an exception if record is not a blob or does not exist.
   *
   * @param id the id of the blob to load
   * @return the loaded blob
   * @throws DatabaseException       if the record is not a blob
   * @throws RecordNotFoundException if the record does not exist
   */
  @Nonnull
  Blob loadBlob(@Nonnull RID id) throws DatabaseException, RecordNotFoundException;

  @Nullable
  Blob loadBlobOrNull(@Nonnull RID id) throws DatabaseException, RecordNotFoundException;

  @Nonnull
  Blob loadBlob(@Nonnull Identifiable id) throws DatabaseException, RecordNotFoundException;

  @Nullable
  Blob loadBlobOrNull(@Nonnull Identifiable id) throws DatabaseException;

  /**
   * Create a new instance of a blob containing the given bytes.
   *
   * @param bytes content of the Blob
   * @return the Blob instance.
   */
  Blob newBlob(@Nonnull byte[] bytes);


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
   * Creates a new Edge of type E
   *
   * @param from the starting point vertex
   * @param to   the endpoint vertex
   * @return the edge
   */
  StatefulEdge newStatefulEdge(Vertex from, Vertex to);

  /**
   * Loads the record by the Record ID.
   *
   * @param recordId The unique record id of the entity to load.
   * @return The loaded entity
   * @throws RecordNotFoundException if record does not exist in database
   */
  @Nonnull
  <RET extends DBRecord> RET load(RID recordId);
  /**
   * Loads the record by the Record ID, unlike {@link  #load(RID)} method does not throw exception
   * if record not found but returns <code>null</code> instead.
   *
   * @param recordId The unique record id of the entity to load.
   * @return The loaded entity or <code>null</code> if entity does not exist.
   */
  @Nullable
  <RET extends DBRecord> RET loadOrNull(RID recordId);

  @Nonnull
  <RET extends DBRecord> RET load(Identifiable identifiable);

  @Nullable
  <RET extends DBRecord> RET loadOrNull(Identifiable identifiable);

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
  boolean exists(@Nonnull RID rid);

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
  @SuppressWarnings("rawtypes")
  ResultSet query(String query, Map args)
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
  ResultSet execute(String query, Object... args)
      throws CommandSQLParsingException, CommandExecutionException;

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
  @SuppressWarnings("rawtypes")
  ResultSet execute(String query, Map args)
      throws CommandSQLParsingException, CommandExecutionException;

  /**
   * Executes a generic (non-idempotent) command, ignoring the produced result. Works in the same
   * way as {@link Transaction#execute(String, Object...)}, but doesn't require closing the result
   * set after usage. <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * ResultSet rs = db.command("INSERT INTO Person SET name = ?", "John");
   * </code>
   *
   * @param args query arguments
   */
  void command(String query, Object... args)
      throws CommandSQLParsingException, CommandExecutionException;

  /**
   * Executes a generic (non-idempotent) command, ignoring the produced result. Works in the same
   * way as {@link Transaction#execute(String, Map)}, but doesn't require closing the result set
   * after usage. <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * ResultSet rs = db.command("INSERT INTO Person SET name = :name", Map.of("name", "John");
   * </code>
   *
   * @param args query arguments
   */
  @SuppressWarnings("rawtypes")
  void command(String query, Map args)
      throws CommandSQLParsingException, CommandExecutionException;

  default <T> EmbeddedList<T> newEmbeddedList() {
    return getDatabaseSession().newEmbeddedList();
  }

  default <T> EmbeddedList<T> newEmbeddedList(int size) {
    return getDatabaseSession().newEmbeddedList(size);
  }

  default <T> EmbeddedList<T> newEmbeddedList(Collection<T> list) {
    return getDatabaseSession().newEmbeddedList(list);
  }

  default EmbeddedList<String> newEmbeddedList(String[] source) {
    return getDatabaseSession().newEmbeddedList(source);
  }

  default EmbeddedList<Date> newEmbeddedList(Date[] source) {
    return getDatabaseSession().newEmbeddedList(source);
  }

  default EmbeddedList<Byte> newEmbeddedList(byte[] source) {
    return getDatabaseSession().newEmbeddedList(source);
  }

  default EmbeddedList<Short> newEmbeddedList(short[] source) {
    return getDatabaseSession().newEmbeddedList(source);
  }

  default EmbeddedList<Integer> newEmbeddedList(int[] source) {
    return getDatabaseSession().newEmbeddedList(source);
  }

  default EmbeddedList<Long> newEmbeddedList(long[] source) {
    return getDatabaseSession().newEmbeddedList(source);
  }

  default EmbeddedList<Float> newEmbeddedList(float[] source) {
    return getDatabaseSession().newEmbeddedList(source);
  }

  default EmbeddedList<Double> newEmbeddedList(double[] source) {
    return getDatabaseSession().newEmbeddedList(source);
  }

  default EmbeddedList<Boolean> newEmbeddedList(boolean[] source) {
    return getDatabaseSession().newEmbeddedList(source);
  }

  default LinkList newLinkList() {
    return getDatabaseSession().newLinkList();
  }

  default LinkList newLinkList(int size) {
    return getDatabaseSession().newLinkList(size);
  }

  default LinkList newLinkList(Collection<? extends Identifiable> source) {
    return getDatabaseSession().newLinkList(source);
  }

  default <T> EmbeddedSet<T> newEmbeddedSet() {
    return getDatabaseSession().newEmbeddedSet();
  }

  default <T> EmbeddedSet<T> newEmbeddedSet(int size) {
    return getDatabaseSession().newEmbeddedSet(size);
  }

  default <T> EmbeddedSet<T> newEmbeddedSet(Collection<T> set) {
    return getDatabaseSession().newEmbeddedSet(set);
  }

  default LinkSet newLinkSet() {
    return getDatabaseSession().newLinkSet();
  }

  default LinkSet newLinkSet(Collection<? extends Identifiable> source) {
    return getDatabaseSession().newLinkSet(source);
  }

  default <V> EmbeddedMap<V> newEmbeddedMap() {
    return getDatabaseSession().newEmbeddedMap();
  }

  default <V> EmbeddedMap<V> newEmbeddedMap(int size) {
    return getDatabaseSession().newEmbeddedMap(size);
  }

  default <V> EmbeddedMap<V> newEmbeddedMap(Map<String, V> map) {
    return getDatabaseSession().newEmbeddedMap(map);
  }

  default LinkMap newLinkMap() {
    return getDatabaseSession().newLinkMap();
  }

  default LinkMap newLinkMap(int size) {
    return getDatabaseSession().newLinkMap(size);
  }

  default LinkMap newLinkMap(Map<String, ? extends Identifiable> source) {
    return getDatabaseSession().newLinkMap(source);
  }

  /**
   * Returns the schema of the database.
   *
   * @return the schema of the database
   */
  default Schema getSchema() {
    return getDatabaseSession().getSchema();
  }

}
