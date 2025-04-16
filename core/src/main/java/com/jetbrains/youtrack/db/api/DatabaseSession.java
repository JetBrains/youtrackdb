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

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandScriptException;
import com.jetbrains.youtrack.db.api.exception.ModificationOperationProhibitedException;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RecordHook;
import com.jetbrains.youtrack.db.api.record.collection.embedded.EmbeddedList;
import com.jetbrains.youtrack.db.api.record.collection.embedded.EmbeddedMap;
import com.jetbrains.youtrack.db.api.record.collection.embedded.EmbeddedSet;
import com.jetbrains.youtrack.db.api.record.collection.links.LinkList;
import com.jetbrains.youtrack.db.api.record.collection.links.LinkMap;
import com.jetbrains.youtrack.db.api.record.collection.links.LinkSet;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.transaction.Transaction;
import com.jetbrains.youtrack.db.api.transaction.TxBiConsumer;
import com.jetbrains.youtrack.db.api.transaction.TxBiFunction;
import com.jetbrains.youtrack.db.api.transaction.TxConsumer;
import com.jetbrains.youtrack.db.api.transaction.TxFunction;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;
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
   * @param code Code to execute in transaction
   */
  <X extends Exception> void executeInTx(@Nonnull TxConsumer<Transaction, X> code) throws X;


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
  <T, X extends Exception> void executeInTxBatches(
      @Nonnull Iterator<T> iterator, int batchSize, TxBiConsumer<Transaction, T, X> consumer)
      throws X;

  /**
   * Splits data by batches, size of each batch is specified by parameter
   * {@link GlobalConfiguration#TX_BATCH_SIZE}.
   *
   * @param iterator Data to process
   * @param consumer Consumer to process data
   * @param <T>      Type of data
   * @see #executeInTxBatches(Iterator, int, TxBiConsumer)
   */
  <T, X extends Exception> void executeInTxBatches(Iterator<T> iterator,
      TxBiConsumer<Transaction, T, X> consumer) throws X;

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
  <T, X extends Exception> void executeInTxBatches(
      Iterable<T> iterable, int batchSize, TxBiConsumer<Transaction, T, X> consumer) throws X;

  /**
   * Splits data by batches, size of each batch is specified by parameter
   * {@link GlobalConfiguration#TX_BATCH_SIZE}.
   *
   * @param iterable Data to process
   * @param consumer Consumer to process data
   * @param <T>      Type of data
   * @see #executeInTxBatches(Iterable, TxBiConsumer)
   */
  <T, X extends Exception> void executeInTxBatches(Iterable<T> iterable,
      TxBiConsumer<Transaction, T, X> consumer) throws X;


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
  <T, X extends Exception> void executeInTxBatches(
      Stream<T> stream, int batchSize, TxBiConsumer<Transaction, T, X> consumer) throws X;

  /**
   * Splits processing of  data by batches, size of each batch is specified by parameter
   * {@link GlobalConfiguration#TX_BATCH_SIZE}.
   * <p>
   * Stream is closed after processing.
   *
   * @param stream   Data to process
   * @param consumer Consumer to process data
   * @param <T>      Type of data
   * @see #executeInTxBatches(Stream, int, TxBiConsumer)
   */
  <T, X extends Exception> void executeInTxBatches(Stream<T> stream,
      TxBiConsumer<Transaction, T, X> consumer) throws X;

  /**
   * Executes the given code in a transaction. Starts a transaction if not already started, in this
   * case the transaction is committed after the code is executed or rolled back if an exception is
   * thrown.
   *
   * @param supplier Code to execute in transaction
   * @param <R>      the type of the returned result
   * @return the result of the code execution
   */
  @Nullable
  <R, X extends Exception> R computeInTx(TxFunction<Transaction, R, X> supplier) throws X;

  /**
   * Executes the given code in a transaction. Starts a transaction if not already started, in this
   * case the transaction is committed after the code is executed or rolled back if an exception is
   * thrown.
   * <p>
   * This method is very similar to {@link DatabaseSession#computeInTx(TxFunction)} and exists
   * primarily for Kotlin compatibility, allowing callers to avoid specifying generic exception
   * types.
   *
   * @param action Code to execute in transaction
   * @param <R>    the type of the returned result
   * @return the result of the code execution
   */
  @Nullable
  default <R> R transaction(Function<Transaction, R> action) throws Exception {
    return computeInTx(action::apply);
  }

  /**
   * Executes the given code for each element in the iterator in a transaction. Starts a transaction
   * if not already started, in this case the transaction is committed after the code is executed or
   * rolled back if an exception is thrown.
   *
   * @param iterator the iterator to iterate over
   * @param <T>      the type of the elements in the iterator
   */
  <T, X extends Exception> void forEachInTx(Iterator<T> iterator,
      TxBiConsumer<Transaction, T, X> consumer) throws X;

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
  <T, X extends Exception> void forEachInTx(Iterable<T> iterable,
      TxBiConsumer<Transaction, T, X> consumer) throws X;

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
  <T, X extends Exception> void forEachInTx(Stream<T> stream,
      TxBiConsumer<Transaction, T, X> consumer) throws X;

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
  <T, X extends Exception> void forEachInTx(Iterator<T> iterator,
      TxBiFunction<Transaction, T, Boolean, X> consumer) throws X;

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
  <T, X extends Exception> void forEachInTx(Iterable<T> iterable,
      TxBiFunction<Transaction, T, Boolean, X> consumer) throws X;

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
  <T, X extends Exception> void forEachInTx(Stream<T> stream,
      TxBiFunction<Transaction, T, Boolean, X> consumer) throws X;


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
   * @see Transaction#commit()
   * @see Transaction#rollback()
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
   * @return <code>true</code> if database is obtained from the pool and <code>false</code>
   * otherwise.
   */
  boolean isPooled();

  /**
   * Returns the database configuration settings. If defined, any database configuration overwrites
   * the global one.
   *
   * @return ContextConfiguration
   */
  @Nullable
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

  @Nullable
  String getCurrentUserName();

  /**
   * Begins a new transaction.If a previous transaction is running a nested call counter is
   * incremented. A transaction once begun has to be closed by calling the
   * {@link Transaction#commit()} or {@link Transaction#rollback()}.
   *
   * @return Amount of nested transaction calls. First call is 1.
   */
  Transaction begin();


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
   * ResultSet rs = db.runScript("sql", script, "Surname1", "Surname2", "Surname3"); ...
   * rs.close();
   * </code>
   */
  default ResultSet runScript(String language, String script, Object... args)
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
   * ResultSet rs = db.runScript("sql", script, params); ... rs.close(); </code>
   */
  default ResultSet runScript(String language, String script, Map<String, ?> args)
      throws CommandExecutionException, CommandScriptException {
    throw new UnsupportedOperationException();
  }

  /**
   * Registers a hook to listen all events for Records.
   *
   * @param iHookImpl RecordHook implementation
   */
  RecordHook registerHook(@Nonnull RecordHook iHookImpl);

  /**
   * Unregisters a previously registered hook.
   *
   * @param iHookImpl RecordHook implementation
   */
  void unregisterHook(@Nonnull RecordHook iHookImpl);

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

  @Nonnull
  Transaction getActiveTransaction();

  @Nullable
  Transaction getActiveTransactionOrNull();

  <T> EmbeddedList<T> newEmbeddedList();

  <T> EmbeddedList<T> newEmbeddedList(int size);

  <T> EmbeddedList<T> newEmbeddedList(Collection<T> list);

  EmbeddedList<String> newEmbeddedList(String[] source);

  EmbeddedList<Date> newEmbeddedList(Date[] source);

  EmbeddedList<Byte> newEmbeddedList(byte[] source);

  EmbeddedList<Short> newEmbeddedList(short[] source);

  EmbeddedList<Integer> newEmbeddedList(int[] source);

  EmbeddedList<Long> newEmbeddedList(long[] source);

  EmbeddedList<Float> newEmbeddedList(float[] source);

  EmbeddedList<Double> newEmbeddedList(double[] source);

  EmbeddedList<Boolean> newEmbeddedList(boolean[] source);

  LinkList newLinkList();

  LinkList newLinkList(int size);

  LinkList newLinkList(Collection<? extends Identifiable> source);

  <T> EmbeddedSet<T> newEmbeddedSet();

  <T> EmbeddedSet<T> newEmbeddedSet(int size);

  <T> EmbeddedSet<T> newEmbeddedSet(Collection<T> set);

  LinkSet newLinkSet();

  LinkSet newLinkSet(Collection<? extends Identifiable> source);

  <V> EmbeddedMap<V> newEmbeddedMap();

  <V> EmbeddedMap<V> newEmbeddedMap(int size);

  <V> EmbeddedMap<V> newEmbeddedMap(Map<String, V> map);

  LinkMap newLinkMap();

  LinkMap newLinkMap(int size);

  LinkMap newLinkMap(Map<String, ? extends Identifiable> source);

  enum ATTRIBUTES {
    DATEFORMAT,
    DATE_TIME_FORMAT,
    TIMEZONE,
    LOCALE_COUNTRY,
    LOCALE_LANGUAGE,
    CHARSET,
  }
}
