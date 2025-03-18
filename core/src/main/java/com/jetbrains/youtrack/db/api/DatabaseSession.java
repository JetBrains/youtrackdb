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
package com.jetbrains.youtrack.db.api;

import com.jetbrains.youtrack.db.api.config.ContextConfiguration;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.exception.CommandScriptException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.ModificationOperationProhibitedException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.exception.TransactionException;
import com.jetbrains.youtrack.db.api.query.LiveQueryMonitor;
import com.jetbrains.youtrack.db.api.query.LiveQueryResultListener;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.RecordHook;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.security.SecurityUser;
import com.jetbrains.youtrack.db.api.session.SessionListener;
import com.jetbrains.youtrack.db.api.session.Transaction;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Session for database operations with a specific user.
 */
public interface DatabaseSession extends AutoCloseable {
  enum STATUS {
    OPEN,
    CLOSED,
    IMPORTING
  }

  /**
   * Executes the passed in code in a transaction. Starts a transaction if not already started, in
   * this case the transaction is committed after the code is executed or rolled back if an
   * exception is thrown.
   *
   * @param runnable Code to execute in transaction
   */
  void executeInTx(Runnable runnable);

  /**
   * Splits data provided by iterator in batches and execute every batch in separate transaction.
   * Currently, YouTrackDB accumulates all changes in single batch in memory and then commits them
   * to the storage, that causes OutOfMemoryError in case of large data sets. This method allows to
   * avoid described problems.
   *
   * @param <T>       Type of data
   * @param iterator  Data to process
   * @param batchSize Size of batch
   * @param consumer  Consumer to process data
   */
  <T> void executeInTxBatches(
      Iterator<T> iterator, int batchSize, BiConsumer<DatabaseSession, T> consumer);

  /**
   * Splits data by batches, size of each batch is specified by parameter
   * {@link GlobalConfiguration#TX_BATCH_SIZE}.
   *
   * @param iterator Data to process
   * @param consumer Consumer to process data
   * @param <T>      Type of data
   * @see #executeInTxBatches(Iterator, int, BiConsumer)
   */
  <T> void executeInTxBatches(Iterator<T> iterator, BiConsumer<DatabaseSession, T> consumer);

  /**
   * Splits data provided by iterator in batches and execute every batch in separate transaction.
   * Currently, YouTrackDB accumulates all changes in single batch in memory and then commits them
   * to the storage, that causes OutOfMemoryError in case of large data sets. This method allows to
   * avoid described problems.
   *
   * @param <T>       Type of data
   * @param iterable  Data to process
   * @param batchSize Size of batch
   * @param consumer  Consumer to process data
   */
  <T> void executeInTxBatches(
      Iterable<T> iterable, int batchSize, BiConsumer<DatabaseSession, T> consumer);

  /**
   * Splits data by batches, size of each batch is specified by parameter
   * {@link GlobalConfiguration#TX_BATCH_SIZE}.
   *
   * @param iterable Data to process
   * @param consumer Consumer to process data
   * @param <T>      Type of data
   * @see #executeInTxBatches(Iterable, BiConsumer)
   */
  <T> void executeInTxBatches(Iterable<T> iterable, BiConsumer<DatabaseSession, T> consumer);

  /**
   * Splits data provided by stream in batches and execute every batch in separate transaction.
   * Currently, YouTrackDB accumulates all changes in single batch in memory and then commits them
   * to the storage, that causes OutOfMemoryError in case of large data sets. This method allows to
   * avoid described problems.
   * <p>
   * Stream is closed after processing.
   *
   * @param <T>       Type of data
   * @param stream    Data to process
   * @param batchSize Size of batch
   * @param consumer  Consumer to process data
   */
  <T> void executeInTxBatches(
      Stream<T> stream, int batchSize, BiConsumer<DatabaseSession, T> consumer);

  /**
   * Splits processing of  data by batches, size of each batch is specified by parameter
   * {@link GlobalConfiguration#TX_BATCH_SIZE}.
   * <p>
   * Stream is closed after processing.
   *
   * @param stream   Data to process
   * @param consumer Consumer to process data
   * @param <T>      Type of data
   * @see #executeInTxBatches(Stream, int, BiConsumer)
   */
  <T> void executeInTxBatches(Stream<T> stream, BiConsumer<DatabaseSession, T> consumer);

  /**
   * Executes the given code in a transaction. Starts a transaction if not already started, in this
   * case the transaction is committed after the code is executed or rolled back if an exception is
   * thrown.
   *
   * @param supplier Code to execute in transaction
   * @param <T>      the type of the returned result
   * @return the result of the code execution
   */
  <T> T computeInTx(Supplier<T> supplier);

  /**
   * Executes the given code for each element in the iterator in a transaction. Starts a transaction
   * if not already started, in this case the transaction is committed after the code is executed or
   * rolled back if an exception is thrown.
   *
   * @param iterator the iterator to iterate over
   * @param <T>      the type of the elements in the iterator
   */
  <T> void forEachInTx(Iterator<T> iterator, BiConsumer<DatabaseSession, T> consumer);

  /**
   * Executes the given code for each element in the iterable in a transaction. Starts a transaction
   * if not already started, in this case the transaction is committed after the code is executed or
   * rolled back if an exception is thrown.
   * <p>
   * The consumer function should return true if it wants to continue the iteration, false
   * otherwise.
   *
   * @param iterable the iterable to iterate over
   * @param consumer the code to execute for each element
   * @param <T>      the type of the elements in the iterable
   */
  <T> void forEachInTx(Iterable<T> iterable, BiConsumer<DatabaseSession, T> consumer);

  /**
   * Executes the given code for each element in the stream in a transaction. Starts a transaction
   * if not already started, in this case the transaction is committed after the code is executed or
   * rolled back if an exception is thrown.
   * <p>
   * The consumer function should return true if it wants to continue the iteration, false
   * otherwise.
   * <p>
   * The stream is closed after processing.
   *
   * @param stream   the stream to iterate over
   * @param consumer the code to execute for each element
   * @param <T>      the type of the elements in the stream
   */
  <T> void forEachInTx(Stream<T> stream, BiConsumer<DatabaseSession, T> consumer);

  /**
   * Executes the given code for each element in the iterator in a transaction. Starts a transaction
   * if not already started, in this case the transaction is committed after the code is executed or
   * rolled back if an exception is thrown.
   * <p>
   * The consumer function should return true if it wants to continue the iteration, false
   * otherwise.
   *
   * @param iterator the iterator to iterate over
   * @param <T>      the type of the elements in the iterator
   */
  <T> void forEachInTx(Iterator<T> iterator, BiFunction<DatabaseSession, T, Boolean> consumer);


  /**
   * Executes the given code for each element in the iterable in a transaction. Starts a transaction
   * if not already started, in this case the transaction is committed after the code is executed or
   * rolled back if an exception is thrown.
   * <p>
   * The consumer function should return true if it wants to continue the iteration, false
   * otherwise.
   *
   * @param iterable the iterable to iterate over
   * @param consumer the code to execute for each element
   * @param <T>      the type of the elements in the iterable
   */
  <T> void forEachInTx(Iterable<T> iterable, BiFunction<DatabaseSession, T, Boolean> consumer);

  /**
   * Executes the given code for each element in the stream in a transaction. Starts a transaction
   * if not already started, in this case the transaction is committed after the code is executed or
   * rolled back if an exception is thrown.
   * <p>
   * The consumer function should return true if it wants to continue the iteration, false
   * otherwise.
   * <p>
   * The stream is closed after processing.
   *
   * @param stream   the stream to iterate over
   * @param consumer the code to execute for each element
   * @param <T>      the type of the elements in the stream
   */
  <T> void forEachInTx(Stream<T> stream, BiFunction<DatabaseSession, T, Boolean> consumer);

  /**
   * Binds current record to the session. It is mandatory to call this method in case you use
   * records that are not created or loaded by the session. Method returns bounded instance of given
   * record, usage of passed in instance is prohibited.
   * <p>
   * Method throws {@link RecordNotFoundException} if record does not exist in database or if record
   * rid is temporary.
   * <p/>
   * You can verify if record already bound to the session by calling
   * {@link DBRecord#isNotBound(DatabaseSession)} method.
   * <p/>
   * <p>
   * Records with temporary RIDs are not allowed to be bound to the session and can not be accepted
   * from the outside of the transaction boundaries.
   *
   * @param identifiable Record or rid to bind to the session, passed in instance is
   *                     <b>prohibited</b> for further usage.
   * @param <T>          Type of record.
   * @return Bounded instance of given record.
   * @throws RecordNotFoundException if record does not exist in database
   * @throws DatabaseException       if the record rid is temporary
   * @see DBRecord#isNotBound(DatabaseSession)
   * @see Identifiable#getIdentity()
   * @see RID#isPersistent()
   */
  <T extends Identifiable> T bindToSession(T identifiable);

  /**
   * Returns the schema of the database.
   *
   * @return the schema of the database
   */
  Schema getSchema();

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
   * Returns if the current session has an active transaction.
   *
   * @return <code>true</code> if the session has an active transaction, <code>false</code>
   * otherwise.
   */
  default boolean isTxActive() {
    return activeTxCount() > 0;
  }

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
  default Edge loadEdge(RID id) throws DatabaseException, RecordNotFoundException {
    var record = load(id);

    if (record instanceof Edge edge) {
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

  /**
   * @return <code>true</code> if database is obtained from the pool and <code>false</code>
   * otherwise.
   */
  boolean isPooled();

  Entity newEntity(final String className);

  Entity newEntity(final SchemaClass cls);

  Entity newEntity();

  Entity newEmbeddedEntity(SchemaClass schemaClass);

  Entity newEmbeddedEntity(String schemaClass);

  Entity newEmbeddedEntity();

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
   * creates a new vertex class (a class that extends V)
   *
   * @param className the class name
   * @return The object representing the class in the schema
   * @throws SchemaException if the class already exists or if V class is not defined (Eg. if it was
   *                         deleted from the schema)
   */
  default SchemaClass createVertexClass(String className) throws SchemaException {
    return createClass(className, "V");
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
    var edgeClass = createClass(className, "E");

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
    return createAbstractClass(className, "E");
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

  /**
   * Returns the database configuration settings. If defined, any database configuration overwrites
   * the global one.
   *
   * @return ContextConfiguration
   */
  ContextConfiguration getConfiguration();

  /**
   * Closes an opened database, if the database is already closed does nothing, if a transaction is
   * active will be rollback.
   */
  void close();

  /**
   * Returns the current status of database.
   */
  STATUS getStatus();

  /**
   * Returns the database name.
   *
   * @return Name of the database
   */
  String getDatabaseName();

  /**
   * Returns the database URL.
   *
   * @return URL of the database
   */
  String getURL();

  /**
   * Checks if the database is closed.
   *
   * @return true if is closed, otherwise false.
   */
  boolean isClosed();

  /**
   * Retrieve the set of defined blob cluster.
   *
   * @return the array of defined blob cluster ids.
   */
  int[] getBlobClusterIds();

  /**
   * Flush cached storage content to the disk.
   *
   * <p>After this call users can perform only idempotent calls like read records and
   * select/traverse queries. All write-related operations will queued till {@link #release()}
   * command will be called.
   *
   * <p>Given command waits till all on going modifications in indexes or DB will be finished.
   *
   * <p>IMPORTANT: This command is not reentrant.
   *
   * @see #release()
   */
  void freeze();

  /**
   * Allows to execute write-related commands on DB. Called after {@link #freeze()} command.
   *
   * @see #freeze()
   */
  void release();

  /**
   * Flush cached storage content to the disk.
   *
   * <p>After this call users can perform only select queries. All write-related commands will
   * queued till {@link #release()} command will be called or exception will be thrown on attempt to
   * modify DB data. Concrete behaviour depends on <code>throwException</code> parameter.
   *
   * <p>IMPORTANT: This command is not reentrant.
   *
   * @param throwException If <code>true</code> {@link ModificationOperationProhibitedException}
   *                       exception will be thrown in case of write command will be performed.
   */
  void freeze(boolean throwException);

  /**
   * Returns the current user logged into the database.
   */
  SecurityUser getCurrentUser();

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
  default <RET extends DBRecord> RET loadSilently(RID recordId) {
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
   * Begins a new transaction.If a previous transaction is running a nested call counter is
   * incremented. A transaction once begun has to be closed by calling the {@link #commit()} or
   * {@link #rollback()}.
   *
   * @return Amount of nested transaction calls. First call is 1.
   */
  int begin();

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
   * Executes a generic (idempotent or non idempotent) command. The result set has to be closed
   * after usage <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * ResultSet rs = db.command("INSERT INTO Person SET name = ?", "John"); ... rs.close();
   * </code>
   *
   * @param args query arguments
   * @return the query result set
   */
  default ResultSet command(String query, Object... args)
      throws CommandSQLParsingException, CommandExecutionException {
    throw new UnsupportedOperationException();
  }

  /**
   * Executes a generic (idempotent or non idempotent) command. The result set has to be closed
   * after usage <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * Map&lt;String, Object&gt params = new HashMapMap&lt;&gt(); params.put("name", "John");
   * ResultSet rs = db.query("INSERT INTO Person SET name = :name", params); ... rs.close();
   * </code>
   */
  default ResultSet command(String query, Map args)
      throws CommandSQLParsingException, CommandExecutionException {
    throw new UnsupportedOperationException();
  }

  /**
   * Execute a script in a specified query language. The result set has to be closed after usage
   * <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * String script = "INSERT INTO Person SET name = 'foo', surname = ?;"+ "INSERT INTO Person SET
   * name = 'bar', surname = ?;"+ "INSERT INTO Person SET name = 'baz', surname = ?;";
   * <p>
   * ResultSet rs = db.execute("sql", script, "Surname1", "Surname2", "Surname3"); ... rs.close();
   * </code>
   */
  default ResultSet execute(String language, String script, Object... args)
      throws CommandExecutionException, CommandScriptException {
    throw new UnsupportedOperationException();
  }

  /**
   * Execute a script of a specified query language The result set has to be closed after usage
   * <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * Map&lt;String, Object&gt params = new HashMapMap&lt;&gt(); params.put("surname1", "Jones");
   * params.put("surname2", "May"); params.put("surname3", "Ali");
   * <p>
   * String script = "INSERT INTO Person SET name = 'foo', surname = :surname1;"+ "INSERT INTO
   * Person SET name = 'bar', surname = :surname2;"+ "INSERT INTO Person SET name = 'baz', surname =
   * :surname3;";
   * <p>
   * ResultSet rs = db.execute("sql", script, params); ... rs.close(); </code>
   */
  default ResultSet execute(String language, String script, Map<String, ?> args)
      throws CommandExecutionException, CommandScriptException {
    throw new UnsupportedOperationException();
  }

  /**
   * Registers a hook to listen all events for Records.
   *
   * @param iHookImpl RecordHook implementation
   */
  void registerHook(RecordHook iHookImpl);

  /**
   * Unregisters a previously registered hook.
   *
   * @param iHookImpl RecordHook implementation
   */
  void unregisterHook(RecordHook iHookImpl);

  /**
   * Retrieves all the registered listeners.
   *
   * @return An iterable of SessionListener instances.
   */
  Iterable<SessionListener> getListeners();

  /**
   * Performs incremental backup of database content to the selected folder. This is thread safe
   * operation and can be done in normal operational mode.
   *
   * <p>If it will be first backup of data full content of database will be copied into folder
   * otherwise only changes after last backup in the same folder will be copied.
   *
   * @param path Path to backup folder.
   * @return File name of the backup
   */
  String incrementalBackup(Path path);

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


  /**
   * Returns a database attribute value
   *
   * @param iAttribute Attributes between #ATTRIBUTES enum
   * @return The attribute value
   */
  Object get(ATTRIBUTES iAttribute);

  /**
   * Sets a database attribute value
   *
   * @param iAttribute Attributes between #ATTRIBUTES enum
   * @param iValue     Value to set
   */
  void set(ATTRIBUTES iAttribute, Object iValue);

  @Nullable
  Transaction getActiveTransaction();

  <T> List<T> newEmbeddedList();

  <T> List<T> newEmbeddedList(int size);

  <T> List<T> newEmbeddedList(List<T> list);

  List<Byte> newEmbeddedList(byte[] source);

  List<Short> newEmbeddedList(short[] source);

  List<Integer> newEmbeddedList(int[] source);

  List<Long> newEmbeddedList(long[] source);

  List<Float> newEmbeddedList(float[] source);

  List<Double> newEmbeddedList(double[] source);

  List<Boolean> newEmbeddedList(boolean[] source);

  List<Identifiable> newLinkList();

  List<Identifiable> newLinkList(int size);

  List<Identifiable> newLinkList(List<Identifiable> source);

  <T> Set<T> newEmbeddedSet();

  <T> Set<T> newEmbeddedSet(int size);

  <T> Set<T> newEmbeddedSet(Set<T> set);

  Set<Identifiable> newLinkSet();

  Set<Identifiable> newLinkSet(int size);

  Set<Identifiable> newLinkSet(Set<Identifiable> source);

  <V> Map<String, V> newEmbeddedMap();

  <V> Map<String, V> newEmbeddedMap(int size);

  <V> Map<String, V> newEmbeddedMap(Map<String, V> map);

  Map<String, Identifiable> newLinkMap();

  Map<String, Identifiable> newLinkMap(int size);

  Map<String, Identifiable> newLinkMap(Map<String, Identifiable> source);

  enum ATTRIBUTES {
    DATEFORMAT,
    DATE_TIME_FORMAT,
    TIMEZONE,
    LOCALE_COUNTRY,
    LOCALE_LANGUAGE,
    CHARSET,
  }
}
