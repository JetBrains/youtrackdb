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
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.exception.SecurityAccessException;
import com.jetbrains.youtrack.db.api.exception.SecurityException;
import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.RecordHook;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.Stopwatch;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.conflict.RecordConflictStrategy;
import com.jetbrains.youtrack.db.internal.core.db.record.ClassTrigger;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkList;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkMap;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkSet;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorCluster;
import com.jetbrains.youtrack.db.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrack.db.internal.core.metadata.function.FunctionLibraryImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.metadata.security.ImmutableUser;
import com.jetbrains.youtrack.db.internal.core.metadata.security.PropertyAccess;
import com.jetbrains.youtrack.db.internal.core.metadata.security.PropertyEncryptionNone;
import com.jetbrains.youtrack.db.internal.core.metadata.security.RestrictedAccessHook;
import com.jetbrains.youtrack.db.internal.core.metadata.security.RestrictedOperation;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityShared;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Token;
import com.jetbrains.youtrack.db.internal.core.metadata.security.auth.AuthenticationInfo;
import com.jetbrains.youtrack.db.internal.core.metadata.sequence.SequenceLibraryImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.sequence.SequenceLibraryProxy;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryHook;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryHookV2;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EdgeInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.VertexEntityImpl;
import com.jetbrains.youtrack.db.internal.core.schedule.ScheduledEvent;
import com.jetbrains.youtrack.db.internal.core.schedule.SchedulerImpl;
import com.jetbrains.youtrack.db.internal.core.security.SecurityUser;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializerFactory;
import com.jetbrains.youtrack.db.internal.core.sql.SQLEngine;
import com.jetbrains.youtrack.db.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrack.db.internal.core.sql.executor.InternalResultSet;
import com.jetbrains.youtrack.db.internal.core.sql.parser.LocalResultSet;
import com.jetbrains.youtrack.db.internal.core.sql.parser.LocalResultSetLifecycleDecorator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrack.db.internal.core.storage.RecordMetadata;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.StorageInfo;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.FreezableStorageComponent;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BTreeCollectionManager;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionImpl;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 *
 */
public class DatabaseSessionEmbedded extends DatabaseSessionAbstract
    implements QueryLifecycleListener {

  private YouTrackDBConfigImpl config;
  private final Storage storage; // todo: make this final when "removeStorage" is removed


  private final Stopwatch freezeDurationMetric;
  private final Stopwatch releaseDurationMetric;

  private final TransactionMeters transactionMeters;

  public DatabaseSessionEmbedded(final Storage storage) {
    activateOnCurrentThread();

    try {
      status = STATUS.CLOSED;
      // OVERWRITE THE URL
      url = storage.getURL();
      this.storage = storage;
      this.componentsFactory = storage.getComponentsFactory();

      init();

      databaseOwner = this;

      final var metrics = YouTrackDBEnginesManager.instance().getMetricsRegistry();
      freezeDurationMetric =
          metrics.databaseMetric(CoreMetrics.DATABASE_FREEZE_DURATION, getDatabaseName());
      releaseDurationMetric =
          metrics.databaseMetric(CoreMetrics.DATABASE_RELEASE_DURATION, getDatabaseName());

      this.transactionMeters = new TransactionMeters(
          metrics.databaseMetric(CoreMetrics.TRANSACTION_RATE, getDatabaseName()),
          metrics.databaseMetric(CoreMetrics.TRANSACTION_WRITE_RATE, getDatabaseName()),
          metrics.databaseMetric(CoreMetrics.TRANSACTION_WRITE_ROLLBACK_RATE, getDatabaseName())
      );

    } catch (Exception t) {
      activeSession.remove();
      throw BaseException.wrapException(
          new DatabaseException(
              getDatabaseName(), "Error on opening database "), t, getDatabaseName());
    }
  }

  public DatabaseSession open(final String iUserName, final String iUserPassword) {
    throw new UnsupportedOperationException("Use YouTrackDB");
  }

  public void init(YouTrackDBConfigImpl config, SharedContext sharedContext) {
    this.sharedContext = sharedContext;
    activateOnCurrentThread();
    this.config = config;
    applyAttributes(config);
    applyListeners(config);
    try {

      status = STATUS.OPEN;
      if (initialized) {
        return;
      }

      var serializerFactory = RecordSerializerFactory.instance();
      var serializeName = getStorageInfo().getConfiguration().getRecordSerializer();
      if (serializeName == null) {
        throw new DatabaseException(getDatabaseName(),
            "Impossible to open database from version before 2.x use export import instead");
      }
      serializer = serializerFactory.getFormat(serializeName);
      if (serializer == null) {
        throw new DatabaseException(getDatabaseName(),
            "RecordSerializer with name '" + serializeName + "' not found ");
      }
      if (getStorageInfo().getConfiguration().getRecordSerializerVersion()
          > serializer.getMinSupportedVersion()) {
        throw new DatabaseException(getDatabaseName(),
            "Persistent record serializer version is not support by the current implementation");
      }

      localCache.startup(this);

      loadMetadata();

      installHooksEmbedded();

      user = null;

      initialized = true;
    } catch (BaseException e) {
      activeSession.remove();
      throw e;
    } catch (Exception e) {
      activeSession.remove();
      throw BaseException.wrapException(
          new DatabaseException(getDatabaseName(), "Cannot open database url=" + getURL()), e,
          getDatabaseName());
    }
  }

  public void internalOpen(final AuthenticationInfo authenticationInfo) {
    try {
      var security = sharedContext.getSecurity();

      if (user == null || user.getVersion() != security.getVersion(this)) {
        final SecurityUser usr;

        usr = security.securityAuthenticate(this, authenticationInfo);
        if (usr != null) {
          user = new ImmutableUser(this, security.getVersion(this), usr);
        } else {
          user = null;
        }

        checkSecurity(Rule.ResourceGeneric.DATABASE, Role.PERMISSION_READ);
      }

    } catch (BaseException e) {
      activeSession.remove();
      throw e;
    } catch (Exception e) {
      activeSession.remove();
      throw BaseException.wrapException(
          new DatabaseException(getDatabaseName(), "Cannot open database url=" + getURL()), e,
          getDatabaseName());
    }
  }

  public void internalOpen(final String iUserName, final String iUserPassword) {
    internalOpen(iUserName, iUserPassword, true);
  }

  public void internalOpen(
      final String iUserName, final String iUserPassword, boolean checkPassword) {
    executeInTx(
        transaction -> {
          try {
            var security = sharedContext.getSecurity();

            if (user == null
                || user.getVersion() != security.getVersion(this)
                || !user.getName(this).equalsIgnoreCase(iUserName)) {
              final SecurityUser usr;

              if (checkPassword) {
                usr = security.securityAuthenticate(this, iUserName, iUserPassword);
              } else {
                usr = security.getUser(this, iUserName);
              }
              if (usr != null) {
                user = new ImmutableUser(this, security.getVersion(this), usr);
              } else {
                user = null;
              }

              checkSecurity(Rule.ResourceGeneric.DATABASE, Role.PERMISSION_READ);
            }
          } catch (BaseException e) {
            activeSession.remove();
            throw e;
          } catch (Exception e) {
            activeSession.remove();
            throw BaseException.wrapException(
                new DatabaseException(getDatabaseName(), "Cannot open database url=" + getURL()), e,
                getDatabaseName());
          }
        });
  }

  private void applyListeners(YouTrackDBConfigImpl config) {
    if (config != null) {
      for (var listener : config.getListeners()) {
        registerListener(listener);
      }
    }
  }

  /**
   * Opens a database using an authentication token received as an argument.
   *
   * @param iToken Authentication token
   * @return The Database instance itself giving a "fluent interface". Useful to call multiple
   * methods in chain.
   */
  @Deprecated
  public DatabaseSession open(final Token iToken) {
    throw new UnsupportedOperationException("Deprecated Method");
  }

  @Override
  public DatabaseSession create() {
    throw new UnsupportedOperationException("Deprecated Method");
  }

  /**
   * {@inheritDoc}
   */
  public void internalCreate(YouTrackDBConfigImpl config, SharedContext ctx) {
    var serializer = RecordSerializerFactory.instance().getDefaultRecordSerializer();
    if (serializer.toString().equals("ORecordDocument2csv")) {
      throw new DatabaseException(getDatabaseName(),
          "Impossible to create the database with ORecordDocument2csv serializer");
    }
    storage.setRecordSerializer(serializer.toString(), serializer.getCurrentVersion());
    storage.setProperty(SQLStatement.CUSTOM_STRICT_SQL, "true");

    this.setSerializer(serializer);

    this.sharedContext = ctx;
    this.status = STATUS.OPEN;
    // THIS IF SHOULDN'T BE NEEDED, CREATE HAPPEN ONLY IN EMBEDDED
    applyAttributes(config);
    applyListeners(config);
    metadata = new MetadataDefault(this);
    installHooksEmbedded();
    createMetadata(ctx);
  }

  public void callOnCreateListeners() {
    // WAKE UP DB LIFECYCLE LISTENER
    assert assertIfNotActive();
    for (var it = YouTrackDBEnginesManager.instance()
        .getDbLifecycleListeners();
        it.hasNext(); ) {
      it.next().onCreate(getDatabaseOwner());
    }
  }

  protected void createMetadata(SharedContext shared) {
    assert assertIfNotActive();
    metadata.init(shared);
    ((SharedContextEmbedded) shared).create(this);
  }

  @Override
  protected void loadMetadata() {
    assert assertIfNotActive();
    executeInTx(
        transaction -> {
          metadata = new MetadataDefault(this);
          metadata.init(sharedContext);
          sharedContext.load(this);
        });
  }

  private void applyAttributes(YouTrackDBConfigImpl config) {
    if (config != null) {
      for (var attrs : config.getAttributes().entrySet()) {
        this.set(attrs.getKey(), attrs.getValue());
      }
    }
  }

  @Override
  public void set(final ATTRIBUTES iAttribute, final Object iValue) {
    assert assertIfNotActive();

    if (iAttribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    final var stringValue = IOUtils.getStringContent(iValue != null ? iValue.toString() : null);
    final var storage = this.storage;
    switch (iAttribute) {
      case DATEFORMAT:
        if (stringValue == null) {
          throw new IllegalArgumentException("date format is null");
        }

        // CHECK FORMAT
        new SimpleDateFormat(stringValue).format(new Date());

        storage.setDateFormat(stringValue);
        break;

      case DATE_TIME_FORMAT:
        if (stringValue == null) {
          throw new IllegalArgumentException("date format is null");
        }

        // CHECK FORMAT
        new SimpleDateFormat(stringValue).format(new Date());

        storage.setDateTimeFormat(stringValue);
        break;

      case TIMEZONE:
        if (stringValue == null) {
          throw new IllegalArgumentException("Timezone can't be null");
        }

        // for backward compatibility, until 2.1.13 YouTrackDB accepted timezones in lowercase as well
        var timeZoneValue = TimeZone.getTimeZone(stringValue.toUpperCase(Locale.ENGLISH));
        if (timeZoneValue.equals(TimeZone.getTimeZone("GMT"))) {
          timeZoneValue = TimeZone.getTimeZone(stringValue);
        }

        storage.setTimeZone(timeZoneValue);
        break;

      case LOCALE_COUNTRY:
        storage.setLocaleCountry(stringValue);
        break;

      case LOCALE_LANGUAGE:
        storage.setLocaleLanguage(stringValue);
        break;

      case CHARSET:
        storage.setCharset(stringValue);
        break;
      default:
        throw new IllegalArgumentException(
            "Option '" + iAttribute + "' not supported on alter database");
    }
  }

  private void clearCustomInternal() {
    storage.clearProperties();
  }

  private void removeCustomInternal(final String iName) {
    setCustomInternal(iName, null);
  }

  private void setCustomInternal(final String iName, final String iValue) {
    final var storage = this.storage;
    if (iValue == null || "null".equalsIgnoreCase(iValue))
    // REMOVE
    {
      storage.removeProperty(iName);
    } else
    // SET
    {
      storage.setProperty(iName, iValue);
    }
  }

  public DatabaseSession setCustom(final String name, final Object iValue) {
    assert assertIfNotActive();

    if ("clear".equalsIgnoreCase(name) && iValue == null) {
      clearCustomInternal();
    } else {
      var customValue = iValue == null ? null : "" + iValue;
      if (name == null || customValue.isEmpty()) {
        removeCustomInternal(name);
      } else {
        setCustomInternal(name, customValue);
      }
    }

    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DatabaseSession create(String incrementalBackupPath) {
    throw new UnsupportedOperationException("use YouTrackDB");
  }

  @Override
  public DatabaseSession create(final Map<GlobalConfiguration, Object> iInitialSettings) {
    throw new UnsupportedOperationException("use YouTrackDB");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void drop() {
    throw new UnsupportedOperationException("use YouTrackDB");
  }

  /**
   * Returns a copy of current database if it's open. The returned instance can be used by another
   * thread without affecting current instance. The database copy is not set in thread local.
   */
  public DatabaseSessionInternal copy() {
    assertIfNotActive();
    var storage = (Storage) getSharedContext().getStorage();
    storage.open(this, null, null, config.getConfiguration());
    String user;
    if (getCurrentUser() != null) {
      user = getCurrentUser().getName(this);
    } else {
      user = null;
    }

    var database = new DatabaseSessionEmbedded(storage);
    database.init(config, this.sharedContext);
    database.internalOpen(user, null, false);
    database.callOnOpenListeners();

    database.activateOnCurrentThread();
    return database;
  }

  @Override
  public boolean exists() {
    throw new UnsupportedOperationException("use YouTrackDB");
  }

  @Override
  public boolean isClosed() {
    return status == STATUS.CLOSED || storage.isClosed(this);
  }

  public void rebuildIndexes() {
    assert assertIfNotActive();
    if (metadata.getIndexManagerInternal().autoRecreateIndexesAfterCrash(this)) {
      metadata.getIndexManagerInternal().recreateIndexes(this);
    }
  }

  protected void installHooksEmbedded() {
    assert assertIfNotActive();
    hooks.clear();
  }

  @Override
  public Storage getStorage() {
    return storage;
  }

  @Override
  public StorageInfo getStorageInfo() {
    return storage;
  }


  @Override
  public ResultSet query(String query, Object[] args) {
    checkOpenness();
    assert assertIfNotActive();
    beginReadOnly();
    currentTx.preProcessRecordsAndExecuteCallCallbacks();
    getSharedContext().getYouTrackDB().startCommand(Optional.empty());
    preQueryStart();
    try {
      var statement = SQLEngine.parse(query, this);
      if (!statement.isIdempotent()) {
        throw new CommandExecutionException(getDatabaseName(),
            "Cannot execute query on non idempotent statement: " + query);
      }
      var original = statement.execute(this, args, true);
      var result = new LocalResultSetLifecycleDecorator(original);
      queryStarted(result);
      return result;
    } finally {
      cleanQueryState();
      getSharedContext().getYouTrackDB().endCommand();
    }
  }

  @Override
  public ResultSet query(String query, Map args) {
    checkOpenness();
    assert assertIfNotActive();
    beginReadOnly();
    currentTx.preProcessRecordsAndExecuteCallCallbacks();
    getSharedContext().getYouTrackDB().startCommand(Optional.empty());
    preQueryStart();
    try {
      var statement = SQLEngine.parse(query, this);
      if (!statement.isIdempotent()) {
        throw new CommandExecutionException(getDatabaseName(),
            "Cannot execute query on non idempotent statement: " + query);
      }
      var original = statement.execute(this, args, true);
      var result = new LocalResultSetLifecycleDecorator(original);
      queryStarted(result);
      return result;
    } finally {
      cleanQueryState();
      getSharedContext().getYouTrackDB().endCommand();
    }
  }

  @Override
  public ResultSet execute(String query, Object[] args) {
    checkOpenness();
    assert assertIfNotActive();

    currentTx.preProcessRecordsAndExecuteCallCallbacks();
    getSharedContext().getYouTrackDB().startCommand(Optional.empty());
    preQueryStart();
    try {
      var statement = SQLEngine.parse(query, this);
      var original = statement.execute(this, args, true);
      LocalResultSetLifecycleDecorator result;
      if (!statement.isIdempotent()) {
        // fetch all, close and detach
        var prefetched = new InternalResultSet(this);
        original.forEachRemaining(prefetched::add);
        original.close();
        queryCompleted();
        result = new LocalResultSetLifecycleDecorator(prefetched);
      } else {
        // stream, keep open and attach to the current DB
        result = new LocalResultSetLifecycleDecorator(original);
        queryStarted(result);
      }
      return result;
    } finally {
      cleanQueryState();
      getSharedContext().getYouTrackDB().endCommand();
    }
  }

  @Override
  public ResultSet execute(String query, Map args) {
    checkOpenness();
    assert assertIfNotActive();

    currentTx.preProcessRecordsAndExecuteCallCallbacks();
    getSharedContext().getYouTrackDB().startCommand(Optional.empty());
    try {
      preQueryStart();

      var statement = SQLEngine.parse(query, this);
      var original = statement.execute(this, args, true);
      LocalResultSetLifecycleDecorator result;
      if (!statement.isIdempotent()) {
        // fetch all, close and detach
        var prefetched = new InternalResultSet(this);
        original.forEachRemaining(prefetched::add);
        original.close();
        queryCompleted();
        result = new LocalResultSetLifecycleDecorator(prefetched);
      } else {
        // stream, keep open and attach to the current DB
        result = new LocalResultSetLifecycleDecorator(original);

        queryStarted(result);
      }

      return result;
    } finally {
      cleanQueryState();
      getSharedContext().getYouTrackDB().endCommand();
    }
  }

  @Override
  public ResultSet runScript(String language, String script, Object... args) {
    checkOpenness();
    assert assertIfNotActive();
    if (!"sql".equalsIgnoreCase(language)) {
      checkSecurity(Rule.ResourceGeneric.COMMAND, Role.PERMISSION_EXECUTE, language);
    }
    currentTx.preProcessRecordsAndExecuteCallCallbacks();
    getSharedContext().getYouTrackDB().startCommand(Optional.empty());
    try {
      preQueryStart();
      var executor =
          getSharedContext()
              .getYouTrackDB()
              .getScriptManager()
              .getCommandManager()
              .getScriptExecutor(language);

      ((AbstractPaginatedStorage) this.storage).pauseConfigurationUpdateNotifications();
      ResultSet original;
      try {
        original = executor.execute(this, script, args);
      } finally {
        ((AbstractPaginatedStorage) this.storage).fireConfigurationUpdateNotifications();
      }
      var result = new LocalResultSetLifecycleDecorator(original);
      queryStarted(result);
      return result;
    } finally {
      cleanQueryState();
      getSharedContext().getYouTrackDB().endCommand();
    }
  }

  private void cleanQueryState() {
    this.queryState.pop();
  }

  private void queryCompleted() {
    var state = this.queryState.peekLast();

  }

  private void queryStarted(LocalResultSetLifecycleDecorator result) {
    var state = this.queryState.peekLast();
    state.setResultSet(result);
    this.queryStarted(result.getQueryId(), state);
    result.addLifecycleListener(this);
  }

  private void preQueryStart() {
    this.queryState.push(new QueryDatabaseState());
  }

  @Override
  public ResultSet runScript(String language, String script, Map<String, ?> args) {
    checkOpenness();
    assert assertIfNotActive();
    if (!"sql".equalsIgnoreCase(language)) {
      checkSecurity(Rule.ResourceGeneric.COMMAND, Role.PERMISSION_EXECUTE, language);
    }
    currentTx.preProcessRecordsAndExecuteCallCallbacks();
    getSharedContext().getYouTrackDB().startCommand(Optional.empty());
    try {
      preQueryStart();
      var executor =
          sharedContext
              .getYouTrackDB()
              .getScriptManager()
              .getCommandManager()
              .getScriptExecutor(language);
      ResultSet original;

      ((AbstractPaginatedStorage) this.storage).pauseConfigurationUpdateNotifications();
      try {
        original = executor.execute(this, script, args);
      } finally {
        ((AbstractPaginatedStorage) this.storage).fireConfigurationUpdateNotifications();
      }

      var result = new LocalResultSetLifecycleDecorator(original);
      queryStarted(result);
      return result;
    } finally {
      cleanQueryState();
      getSharedContext().getYouTrackDB().endCommand();
    }
  }

  public LocalResultSetLifecycleDecorator query(ExecutionPlan plan, Map<Object, Object> params) {
    checkOpenness();
    assert assertIfNotActive();
    currentTx.preProcessRecordsAndExecuteCallCallbacks();
    getSharedContext().getYouTrackDB().startCommand(Optional.empty());
    try {
      preQueryStart();
      var ctx = new BasicCommandContext();
      ctx.setDatabaseSession(this);
      ctx.setInputParameters(params);

      var result = new LocalResultSet(this, (InternalExecutionPlan) plan);
      var decorator = new LocalResultSetLifecycleDecorator(result);
      queryStarted(decorator);

      return decorator;
    } finally {
      cleanQueryState();
      getSharedContext().getYouTrackDB().endCommand();
    }
  }


  @Override
  public void queryStarted(String id, ResultSet resultSet) {
    // to nothing just compatibility
  }

  public YouTrackDBConfig getConfig() {
    assert assertIfNotActive();
    return config;
  }


  @Override
  public void recycle(final DBRecord record) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int addBlobCluster(final String iClusterName, final Object... iParameters) {
    assert assertIfNotActive();
    int id;
    if (!existsCluster(iClusterName)) {
      id = addCluster(iClusterName, iParameters);
    } else {
      id = getClusterIdByName(iClusterName);
    }
    getMetadata().getSchema().addBlobCluster(id);
    return id;
  }

  /**
   * Deletes a entity. Behavior depends by the current running transaction if any. If no transaction
   * is running then the record is deleted immediately. If an Optimistic transaction is running then
   * the record will be deleted at commit time. The current transaction will continue to see the
   * record as deleted, while others not. If a Pessimistic transaction is running, then an exclusive
   * lock is acquired against the record. Current transaction will continue to see the record as
   * deleted, while others cannot access to it since it's locked.
   *
   * <p>If MVCC is enabled and the version of the entity is different by the version stored in
   * the database, then a {@link ConcurrentModificationException} exception is thrown.
   *
   * @param record record to delete
   */
  public void delete(@Nonnull DBRecord record) {
    checkOpenness();
    assert assertIfNotActive();

    record.delete();
  }

  public void afterCreateOperations(final RecordAbstract id, String clusterName) {
    assert assertIfNotActive();

    checkSecurity(Role.PERMISSION_CREATE, id, clusterName);

    if (id instanceof EntityImpl entity) {
      if (!getSharedContext().getSecurity().canCreate(this, entity)) {
        throw new SecurityException(getDatabaseName(),
            "Cannot update record "
                + entity
                + ": the resource has restricted access due to security policies");
      }

      SchemaImmutableClass clazz;
      clazz = entity.getImmutableSchemaClass(this);
      ensureLinksConsistencyAfterModification(entity, clazz);

      if (clazz != null) {
        checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_CREATE, clazz.getName());
        if (clazz.isScheduler()) {
          getSharedContext().getScheduler().initScheduleRecord(entity);
        }
        if (clazz.isUser()) {
          entity.validate();
          SecurityUserImpl.encodePassword(this, entity);
        }
        if (clazz.isTriggered()) {
          ClassTrigger.onRecordBeforeCreate(entity, this);
        }
        if (clazz.isRestricted()) {
          RestrictedAccessHook.onRecordBeforeCreate(entity, this);
        }
        if (clazz.isFunction()) {
          FunctionLibraryImpl.validateFunctionRecord(entity);
        }

        entity.propertyEncryption = PropertyEncryptionNone.instance();

        if (clazz.isUser() || clazz.isRole() || clazz.isSecurityPolicy()) {
          sharedContext.getSecurity().incrementVersion(this);

        }
        if (clazz.isTriggered()) {
          ClassTrigger.onRecordAfterCreate(entity, this);
        }
      }
    }

    callbackHooks(RecordHook.TYPE.CREATE, id);
  }

  public void afterUpdateOperations(final RecordAbstract id, String clusterName) {
    assert assertIfNotActive();

    checkSecurity(Role.PERMISSION_UPDATE, id, clusterName);

    if (id instanceof EntityImpl entity) {

      SchemaImmutableClass clazz = null;
      clazz = entity.getImmutableSchemaClass(this);
      ensureLinksConsistencyAfterModification(entity, clazz);

      if (clazz != null) {
        if (clazz.isScheduler()) {
          getSharedContext().getScheduler().preHandleUpdateScheduleInTx(this, entity);
        }
        if (clazz.isUser()) {
          SecurityUserImpl.encodePassword(this, entity);
        }
        if (clazz.isTriggered()) {
          ClassTrigger.onRecordBeforeUpdate(entity, this);
        }
        if (clazz.isRestricted()) {
          if (!RestrictedAccessHook.isAllowed(
              this, entity, RestrictedOperation.ALLOW_UPDATE, true)) {
            throw new SecurityException(getDatabaseName(),
                "Cannot update record "
                    + entity.getIdentity()
                    + ": the resource has restricted access");
          }
        }
        if (clazz.isFunction()) {
          FunctionLibraryImpl.validateFunctionRecord(entity);
        }
        if (!getSharedContext().getSecurity().canUpdate(this, entity)) {
          throw new SecurityException(getDatabaseName(),
              "Cannot update record "
                  + entity.getIdentity()
                  + ": the resource has restricted access due to security policies");
        }
        entity.propertyEncryption = PropertyEncryptionNone.instance();

        if (clazz.isUser() || clazz.isRole() || clazz.isSecurityPolicy()) {
          sharedContext.getSecurity().incrementVersion(this);
        }

        if (clazz.isTriggered()) {
          ClassTrigger.onRecordAfterUpdate(entity, this);
        }
      }
    }

    callbackHooks(RecordHook.TYPE.UPDATE, id);
  }

  public void afterDeleteOperations(final RecordAbstract id, java.lang.String clusterName) {
    assert assertIfNotActive();
    checkSecurity(Role.PERMISSION_DELETE, id, clusterName);

    if (id instanceof EntityImpl entity) {
      ensureLinksConsistencyAfterDeletion(entity);

      SchemaImmutableClass clazz = null;
      clazz = entity.getImmutableSchemaClass(this);
      if (clazz != null) {
        if (clazz.isTriggered()) {
          ClassTrigger.onRecordBeforeDelete(entity, this);
        }
        if (clazz.isRestricted()) {
          if (!RestrictedAccessHook.isAllowed(
              this, entity, RestrictedOperation.ALLOW_DELETE, true)) {
            throw new SecurityException(getDatabaseName(),
                "Cannot delete record "
                    + entity.getIdentity()
                    + ": the resource has restricted access");
          }
        }

        if (!getSharedContext().getSecurity().canDelete(this, entity)) {
          throw new SecurityException(getDatabaseName(),
              "Cannot delete record "
                  + entity.getIdentity()
                  + ": the resource has restricted access due to security policies");
        }
        if (clazz.isTriggered()) {
          ClassTrigger.onRecordAfterDelete(entity, this);
        } else if (clazz.isSequence()) {
          SequenceLibraryImpl.onAfterSequenceDropped((FrontendTransactionImpl) this.currentTx,
              entity);
        } else if (clazz.isFunction()) {
          FunctionLibraryImpl.onAfterFunctionDropped((FrontendTransactionImpl) this.currentTx,
              entity);
        } else if (clazz.isScheduler()) {
          SchedulerImpl.onAfterEventDropped((FrontendTransactionImpl) this.currentTx, entity);
        }
      }
    }

    callbackHooks(RecordHook.TYPE.DELETE, id);
  }

  @Override
  public void afterReadOperations(RecordAbstract identifiable) {
    assert assertIfNotActive();
    if (identifiable instanceof EntityImpl entity) {
      SchemaImmutableClass clazz = null;
      clazz = entity.getImmutableSchemaClass(this);
      if (clazz != null) {
        if (clazz.isTriggered()) {
          ClassTrigger.onRecordAfterRead(entity, this);
        }
      }
    }

    callbackHooks(RecordHook.TYPE.READ, identifiable);
  }

  @Override
  public boolean beforeReadOperations(RecordAbstract identifiable) {
    assert assertIfNotActive();
    if (identifiable instanceof EntityImpl entity) {
      var clazz = entity.getImmutableSchemaClass(this);
      if (clazz != null) {
        if (clazz.isTriggered()) {
          ClassTrigger.onRecordBeforeRead(entity, this);
        }
        if (clazz.isRestricted()) {
          if (!RestrictedAccessHook.isAllowed(this, entity, RestrictedOperation.ALLOW_READ,
              false)) {
            return true;
          }
        }
        try {
          checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_READ, clazz.getName());
        } catch (SecurityException e) {
          return true;
        }

        if (!getSharedContext().getSecurity().canRead(this, entity)) {
          return true;
        }

        entity.propertyAccess = new PropertyAccess(this, entity,
            getSharedContext().getSecurity());
        entity.propertyEncryption = PropertyEncryptionNone.instance();
      }
    }

    return false;
  }

  @Override
  public void afterCommitOperations() {
    assert assertIfNotActive();

    for (var operation : currentTx.getRecordOperationsInternal()) {
      var record = operation.record;

      if (record instanceof EntityImpl entity) {
        SchemaImmutableClass clazz = null;
        clazz = entity.getImmutableSchemaClass(this);
        if (clazz != null) {
          if (operation.type == RecordOperation.CREATED) {
            if (clazz.isSequence()) {
              ((SequenceLibraryProxy) getMetadata().getSequenceLibrary())
                  .getDelegate()
                  .onSequenceCreated(this, entity);
            }
            if (clazz.isScheduler()) {
              getMetadata().getScheduler().scheduleEvent(this, new ScheduledEvent(entity, this));
            }
            if (clazz.isFunction()) {
              this.getSharedContext().getFunctionLibrary().createdFunction(this, entity);
            }
          } else if (operation.type == RecordOperation.UPDATED) {
            if (clazz.isFunction()) {
              this.getSharedContext().getFunctionLibrary().updatedFunction(this, entity);
            }
            if (clazz.isScheduler()) {
              getSharedContext().getScheduler().postHandleUpdateScheduleAfterTxCommit(this, entity);
            }
          } else if (operation.type == RecordOperation.DELETED) {
            if (clazz.isFunction()) {
              this.getSharedContext().getFunctionLibrary()
                  .onFunctionDropped(this, entity.getIdentity());
            } else if (clazz.isSequence()) {
              ((SequenceLibraryProxy) getMetadata().getSequenceLibrary())
                  .getDelegate()
                  .onSequenceDropped(this, entity.getIdentity());
            } else if (clazz.isScheduler()) {
              getSharedContext().getScheduler().onEventDropped(this, entity.getIdentity());
            }
          }
        } else {
          var schemaId = metadata.getSchemaInternal().getIdentity();

          if (record.getIdentity().equals(schemaId)) {
            var schema = sharedContext.getSchema();
            for (var listener : sharedContext.browseListeners()) {
              listener.onSchemaUpdate(this, getDatabaseName(), schema);
            }
          } else if (record.getIdentity()
              .equals(metadata.getIndexManagerInternal().getIdentity())) {
            var indexManager = sharedContext.getIndexManager();
            for (var listener : sharedContext.browseListeners()) {
              listener.onIndexManagerUpdate(this, getDatabaseName(), indexManager);
            }
          }
        }

        LiveQueryHook.addOp(entity, operation.type, this);
        LiveQueryHookV2.addOp(this, entity, operation.type);
      }
    }

    super.afterCommitOperations();

    LiveQueryHook.notifyForTxChanges(this);
    LiveQueryHookV2.notifyForTxChanges(this);
  }


  @Override
  public void set(ATTRIBUTES_INTERNAL attribute, Object value) {
    assert assertIfNotActive();

    if (attribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    final var stringValue = IOUtils.getStringContent(value != null ? value.toString() : null);
    final var storage = this.storage;

    if (attribute == ATTRIBUTES_INTERNAL.VALIDATION) {
      var validation = Boolean.parseBoolean(stringValue);
      storage.setValidation(validation);
    } else {
      throw new IllegalArgumentException(
          "Option '" + attribute + "' not supported on alter database");
    }

  }

  @Override
  protected void afterRollbackOperations() {
    assert assertIfNotActive();

    super.afterRollbackOperations();
    LiveQueryHook.removePendingDatabaseOps(this);
    LiveQueryHookV2.removePendingDatabaseOps(this);
  }

  public String getClusterName(final @Nonnull DBRecord record) {
    assert assertIfNotActive();

    var clusterId = record.getIdentity().getClusterId();
    if (clusterId == RID.CLUSTER_ID_INVALID) {
      // COMPUTE THE CLUSTER ID
      SchemaClassInternal schemaClass = null;
      if (record instanceof EntityImpl) {
        SchemaImmutableClass result = null;
        result = ((EntityImpl) record).getImmutableSchemaClass(this);
        schemaClass = result;
      }
      if (schemaClass != null) {
        // FIND THE RIGHT CLUSTER AS CONFIGURED IN CLASS
        if (schemaClass.isAbstract()) {
          throw new SchemaException(getDatabaseName(),
              "Entity belongs to abstract class '"
                  + schemaClass.getName()
                  + "' and cannot be saved");
        }
        clusterId = schemaClass.getClusterForNewInstance((EntityImpl) record);
        return getClusterNameById(clusterId);
      } else {
        throw new SchemaException(getDatabaseName(),
            "Cannot find the cluster id for record "
                + record
                + " because the schema class is not defined");
      }

    } else {
      return getClusterNameById(clusterId);
    }
  }


  @Override
  public boolean executeExists(@Nonnull RID rid) {
    checkOpenness();
    assert assertIfNotActive();
    try {
      checkSecurity(
          Rule.ResourceGeneric.CLUSTER,
          Role.PERMISSION_READ,
          getClusterNameById(rid.getClusterId()));

      DBRecord record = getTransactionInternal().getRecord(rid);
      if (record == FrontendTransactionImpl.DELETED_RECORD) {
        // DELETED IN TX
        return false;
      }
      if (record != null) {
        return true;
      }

      if (!rid.isPersistent()) {
        return false;
      }

      if (localCache.findRecord(rid) != null) {
        return true;
      }

      return storage.recordExists(this, rid);
    } catch (Exception t) {
      throw BaseException.wrapException(
          new DatabaseException(getDatabaseName(),
              "Error on retrieving record "
                  + rid
                  + " (cluster: "
                  + storage.getPhysicalClusterNameById(rid.getClusterId())
                  + ")"),
          t, getDatabaseName());
    }
  }

  /**
   * {@inheritDoc}
   */
  public void checkSecurity(
      final Rule.ResourceGeneric resourceGeneric,
      final String resourceSpecific,
      final int iOperation) {
    assert assertIfNotActive();

    if (user != null) {
      try {
        user.allow(this, resourceGeneric, resourceSpecific, iOperation);
      } catch (SecurityAccessException e) {

        if (LogManager.instance().isDebugEnabled()) {
          LogManager.instance()
              .debug(
                  this,
                  "User '%s' tried to access the reserved resource '%s.%s', operation '%s'",
                  getCurrentUser(),
                  resourceGeneric,
                  resourceSpecific,
                  iOperation);
        }

        throw e;
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  public void checkSecurity(
      final Rule.ResourceGeneric iResourceGeneric,
      final int iOperation,
      final Object... iResourcesSpecific) {
    assert assertIfNotActive();

    if (iResourcesSpecific == null || iResourcesSpecific.length == 0) {
      checkSecurity(iResourceGeneric, null, iOperation);
    } else {
      for (var target : iResourcesSpecific) {
        checkSecurity(iResourceGeneric, target == null ? null : target.toString(), iOperation);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  public void checkSecurity(
      final Rule.ResourceGeneric iResourceGeneric,
      final int iOperation,
      final Object iResourceSpecific) {
    checkOpenness();
    assert assertIfNotActive();

    checkSecurity(
        iResourceGeneric,
        iResourceSpecific == null ? null : iResourceSpecific.toString(),
        iOperation);
  }

  @Override
  @Deprecated
  public void checkSecurity(final String iResource, final int iOperation) {
    assert assertIfNotActive();
    final var resourceSpecific = Rule.mapLegacyResourceToSpecificResource(iResource);
    final var resourceGeneric =
        Rule.mapLegacyResourceToGenericResource(iResource);

    if (resourceSpecific == null || resourceSpecific.equals("*")) {
      checkSecurity(resourceGeneric, null, iOperation);
    }

    checkSecurity(resourceGeneric, resourceSpecific, iOperation);
  }

  @Override
  @Deprecated
  public void checkSecurity(
      final String iResourceGeneric, final int iOperation, final Object iResourceSpecific) {
    assert assertIfNotActive();
    final var resourceGeneric =
        Rule.mapLegacyResourceToGenericResource(iResourceGeneric);
    if (iResourceSpecific == null || iResourceSpecific.equals("*")) {
      checkSecurity(resourceGeneric, iOperation, (Object) null);
    }

    checkSecurity(resourceGeneric, iOperation, iResourceSpecific);
  }

  @Override
  @Deprecated
  public void checkSecurity(
      final String iResourceGeneric, final int iOperation, final Object... iResourcesSpecific) {
    assert assertIfNotActive();
    final var resourceGeneric =
        Rule.mapLegacyResourceToGenericResource(iResourceGeneric);
    checkSecurity(resourceGeneric, iOperation, iResourcesSpecific);
  }

  @Override
  public int addCluster(final String iClusterName, final Object... iParameters) {
    assert assertIfNotActive();
    return storage.addCluster(this, iClusterName, iParameters);
  }

  @Override
  public int addCluster(final String iClusterName, final int iRequestedId) {
    assert assertIfNotActive();
    return storage.addCluster(this, iClusterName, iRequestedId);
  }

  public RecordConflictStrategy getConflictStrategy() {
    assert assertIfNotActive();
    return getStorageInfo().getRecordConflictStrategy();
  }

  public DatabaseSessionEmbedded setConflictStrategy(final String iStrategyName) {
    assert assertIfNotActive();
    storage.setConflictStrategy(
        YouTrackDBEnginesManager.instance().getRecordConflictStrategy().getStrategy(iStrategyName));
    return this;
  }

  public DatabaseSessionEmbedded setConflictStrategy(final RecordConflictStrategy iResolver) {
    assert assertIfNotActive();
    storage.setConflictStrategy(iResolver);
    return this;
  }

  @Override
  public long getClusterRecordSizeByName(final String clusterName) {
    assert assertIfNotActive();
    try {
      return storage.getClusterRecordsSizeByName(clusterName);
    } catch (Exception e) {
      throw BaseException.wrapException(
          new DatabaseException(getDatabaseName(),
              "Error on reading records size for cluster '" + clusterName + "'"),
          e, getDatabaseName());
    }
  }

  @Override
  public long getClusterRecordSizeById(final int clusterId) {
    assert assertIfNotActive();
    try {
      return storage.getClusterRecordsSizeById(clusterId);
    } catch (Exception e) {
      throw BaseException.wrapException(
          new DatabaseException(getDatabaseName(),
              "Error on reading records size for cluster with id '" + clusterId + "'"),
          e, getDatabaseName());
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countClusterElements(int iClusterId, boolean countTombstones) {
    assert assertIfNotActive();
    final var name = getClusterNameById(iClusterId);
    if (name == null) {
      return 0;
    }
    checkSecurity(Rule.ResourceGeneric.CLUSTER, Role.PERMISSION_READ, name);
    assert assertIfNotActive();
    return storage.count(this, iClusterId, countTombstones);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countClusterElements(int[] iClusterIds, boolean countTombstones) {
    assert assertIfNotActive();
    String name;
    for (var iClusterId : iClusterIds) {
      name = getClusterNameById(iClusterId);
      checkSecurity(Rule.ResourceGeneric.CLUSTER, Role.PERMISSION_READ, name);
    }
    return storage.count(this, iClusterIds, countTombstones);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countClusterElements(final String iClusterName) {
    checkSecurity(Rule.ResourceGeneric.CLUSTER, Role.PERMISSION_READ, iClusterName);
    assert assertIfNotActive();

    final var clusterId = getClusterIdByName(iClusterName);
    if (clusterId < 0) {
      throw new IllegalArgumentException("Cluster '" + iClusterName + "' was not found");
    }
    return storage.count(this, clusterId);
  }

  @Override
  public boolean dropCluster(final String iClusterName) {
    assert assertIfNotActive();
    final var clusterId = getClusterIdByName(iClusterName);
    var schema = metadata.getSchema();

    var clazz = schema.getClassByClusterId(clusterId);
    if (clazz != null) {
      throw new DatabaseException(this, "Cannot drop cluster '" + getClusterNameById(clusterId)
          + "' because it is mapped to class '" + clazz.getName() + "'");
    }
    if (schema.getBlobClusters().contains(clusterId)) {
      schema.removeBlobCluster(iClusterName);
    }
    getLocalCache().freeCluster(clusterId);
    checkForClusterPermissions(iClusterName);
    return dropClusterInternal(iClusterName);
  }

  protected boolean dropClusterInternal(final String iClusterName) {
    assert assertIfNotActive();
    return storage.dropCluster(this, iClusterName);
  }

  @Override
  public boolean dropCluster(final int clusterId) {
    assert assertIfNotActive();

    checkSecurity(
        Rule.ResourceGeneric.CLUSTER, Role.PERMISSION_DELETE, getClusterNameById(clusterId));

    var schema = metadata.getSchema();
    final var clazz = schema.getClassByClusterId(clusterId);
    if (clazz != null) {
      throw new DatabaseException(this, "Cannot drop cluster '" + getClusterNameById(clusterId)
          + "' because it is mapped to class '" + clazz.getName() + "'");
    }

    getLocalCache().freeCluster(clusterId);
    if (schema.getBlobClusters().contains(clusterId)) {
      schema.removeBlobCluster(getClusterNameById(clusterId));
    }

    checkForClusterPermissions(getClusterNameById(clusterId));

    final var clusterName = getClusterNameById(clusterId);
    if (clusterName == null) {
      return false;
    }

    final var iteratorCluster = browseCluster(clusterName);
    if (iteratorCluster == null) {
      return false;
    }

    executeInTxBatches(iteratorCluster, (session, record) -> delete(record));

    return dropClusterInternal(clusterId);
  }

  public boolean dropClusterInternal(int clusterId) {
    assert assertIfNotActive();
    return storage.dropCluster(this, clusterId);
  }

  @Override
  public long getSize() {
    assert assertIfNotActive();
    return storage.getSize(this);
  }

  public DatabaseStats getStats() {
    assert assertIfNotActive();
    var stats = new DatabaseStats();
    stats.loadedRecords = loadedRecordsCount;
    stats.minLoadRecordTimeMs = minRecordLoadMs;
    stats.maxLoadRecordTimeMs = minRecordLoadMs;
    stats.averageLoadRecordTimeMs =
        loadedRecordsCount == 0 ? 0 : (this.totalRecordLoadMs / loadedRecordsCount);

    stats.prefetchedRidbagsCount = ridbagPrefetchCount;
    stats.minRidbagPrefetchTimeMs = minRidbagPrefetchMs;
    stats.maxRidbagPrefetchTimeMs = maxRidbagPrefetchMs;
    stats.ridbagPrefetchTimeMs = totalRidbagPrefetchMs;
    return stats;
  }

  public void addRidbagPrefetchStats(long execTimeMs) {
    assert assertIfNotActive();
    this.ridbagPrefetchCount++;
    totalRidbagPrefetchMs += execTimeMs;
    if (this.ridbagPrefetchCount == 1) {
      this.minRidbagPrefetchMs = execTimeMs;
      this.maxRidbagPrefetchMs = execTimeMs;
    } else {
      this.minRidbagPrefetchMs = Math.min(this.minRidbagPrefetchMs, execTimeMs);
      this.maxRidbagPrefetchMs = Math.max(this.maxRidbagPrefetchMs, execTimeMs);
    }
  }

  public void resetRecordLoadStats() {
    assert assertIfNotActive();
    this.loadedRecordsCount = 0L;
    this.totalRecordLoadMs = 0L;
    this.minRecordLoadMs = 0L;
    this.maxRecordLoadMs = 0L;
    this.ridbagPrefetchCount = 0L;
    this.totalRidbagPrefetchMs = 0L;
    this.minRidbagPrefetchMs = 0L;
    this.maxRidbagPrefetchMs = 0L;
  }

  @Override
  public String incrementalBackup(final Path path) throws UnsupportedOperationException {
    checkOpenness();
    assert assertIfNotActive();
    checkSecurity(Rule.ResourceGeneric.DATABASE, "backup", Role.PERMISSION_EXECUTE);

    return storage.incrementalBackup(this, path.toAbsolutePath().toString(), null);
  }

  @Override
  public RecordMetadata getRecordMetadata(final RID rid) {
    assert assertIfNotActive();
    return storage.getRecordMetadata(this, rid);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void freeze(final boolean throwException) {
    checkOpenness();
    assert assertIfNotActive();
    if (!(storage instanceof FreezableStorageComponent)) {
      LogManager.instance()
          .error(
              this,
              "Only local paginated storage supports freeze. If you are using remote client please"
                  + " use OServerAdmin instead",
              null);

      return;
    }

    freezeDurationMetric.timed(() -> {
      final var storage = getFreezableStorage();
      if (storage != null) {
        storage.freeze(this, throwException);
      }
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void freeze() {
    assert assertIfNotActive();
    freeze(false);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    checkOpenness();
    assert assertIfNotActive();
    if (!(storage instanceof FreezableStorageComponent)) {
      LogManager.instance()
          .error(
              this,
              "Only local paginated storage supports release. If you are using remote client please"
                  + " use OServerAdmin instead",
              null);
      return;
    }

    releaseDurationMetric.timed(() -> {
      final var storage = getFreezableStorage();
      if (storage != null) {
        storage.release(this);
      }
    });
  }

  private FreezableStorageComponent getFreezableStorage() {
    var s = storage;
    if (s instanceof FreezableStorageComponent) {
      return (FreezableStorageComponent) s;
    } else {
      LogManager.instance()
          .error(
              this, "Storage of type " + s.getType() + " does not support freeze operation", null);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  public BTreeCollectionManager getBTreeCollectionManager() {
    assert assertIfNotActive();
    return storage.getSBtreeCollectionManager();
  }

  @Override
  public void reload() {
    assert assertIfNotActive();

    if (this.isClosed()) {
      throw new DatabaseException(getDatabaseName(), "Cannot reload a closed db");
    }
    metadata.reload();
    storage.reload(this);
  }

  @Override
  public void internalCommit(@Nonnull FrontendTransactionImpl transaction) {
    assert assertIfNotActive();
    this.storage.commit(transaction);
  }

  public void internalClose(boolean recycle) {
    if (status != STATUS.OPEN) {
      return;
    }

    assert assertIfNotActive();
    try {
      closeActiveQueries();
      localCache.shutdown();

      if (isClosed()) {
        status = STATUS.CLOSED;
        return;
      }

      try {
        rollback(true);
      } catch (Exception e) {
        LogManager.instance().error(this, "Exception during rollback of active transaction", e);
      }

      callOnCloseListeners();

      status = STATUS.CLOSED;
      if (!recycle) {
        sharedContext = null;

        if (storage != null) {
          storage.close(this);
        }
      }

    } finally {
      // ALWAYS RESET TL
      activeSession.remove();
    }
  }


  @Override
  public String getClusterRecordConflictStrategy(int clusterId) {
    assert assertIfNotActive();
    return storage.getClusterRecordConflictStrategy(clusterId);
  }

  @Override
  public int[] getClustersIds(@Nonnull Set<String> filterClusters) {
    assert assertIfNotActive();
    return storage.getClustersIds(filterClusters);
  }

  public void startExclusiveMetadataChange() {
    assert assertIfNotActive();
    ((AbstractPaginatedStorage) storage).startDDL();
  }

  public void endExclusiveMetadataChange() {
    assert assertIfNotActive();
    ((AbstractPaginatedStorage) storage).endDDL();
  }

  @Override
  public long truncateClass(String name, boolean polimorfic) {
    assert assertIfNotActive();
    this.checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_UPDATE);
    var clazz = getClass(name);
    if (clazz.isSubClassOf(SecurityShared.RESTRICTED_CLASSNAME)) {
      throw new SecurityException(getDatabaseName(),
          "Class '"
              + getDatabaseName()
              + "' cannot be truncated because has record level security enabled (extends '"
              + SecurityShared.RESTRICTED_CLASSNAME
              + "')");
    }

    int[] clusterIds;
    if (polimorfic) {
      clusterIds = clazz.getPolymorphicClusterIds();
    } else {
      clusterIds = clazz.getClusterIds();
    }
    long count = 0;
    for (var id : clusterIds) {
      if (id < 0) {
        continue;
      }
      final var clusterName = getClusterNameById(id);
      if (clusterName == null) {
        continue;
      }
      count += truncateClusterInternal(clusterName);
    }
    return count;
  }

  @Override
  public void truncateClass(String name) {
    assert assertIfNotActive();
    truncateClass(name, true);
  }

  @Override
  public long truncateClusterInternal(String clusterName) {
    assert assertIfNotActive();
    checkSecurity(Rule.ResourceGeneric.CLUSTER, Role.PERMISSION_DELETE, clusterName);
    checkForClusterPermissions(clusterName);

    var id = getClusterIdByName(clusterName);
    if (id == -1) {
      throw new DatabaseException(getDatabaseName(),
          "Cluster with name " + clusterName + " does not exist");
    }
    final var clazz = getMetadata().getSchema().getClassByClusterId(id);
    if (clazz != null) {
      checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_DELETE, clazz.getName());
    }

    var count = new long[]{0};
    final var iteratorCluster =
        new RecordIteratorCluster<>(this, id, true);

    executeInTxBatches(iteratorCluster, (session, record) -> {
      delete(record);
      count[0]++;
    });

    return count[0];
  }

  @Override
  public void truncateCluster(String clusterName) {
    assert assertIfNotActive();
    truncateClusterInternal(clusterName);
  }

  @Override
  public TransactionMeters transactionMeters() {
    return transactionMeters;
  }

  private void ensureLinksConsistencyAfterDeletion(@Nonnull EntityImpl entity) {
    var properties = entity.getPropertyNamesInternal(true, false);

    for (var propertyName : properties) {
      if (propertyName.charAt(0) == EntityImpl.OPPOSITE_LINK_CONTAINER_PREFIX) {
        var oppositeLinksContainer = (RidBag) entity.getPropertyInternal(propertyName);

        for (var link : oppositeLinksContainer) {
          var oppositeEntity = (EntityImpl) link.getEntitySilently(this);
          //skip self-links and already deleted entities
          if (oppositeEntity == null || oppositeEntity.equals(entity)) {
            continue;
          }

          var linkName = propertyName.substring(1);
          var oppositeLinkProperty = oppositeEntity.getPropertyInternal(linkName);

          if (oppositeLinkProperty == null) {
            throw new IllegalStateException("Cannot remove link " + entity.getIdentity()
                + " from opposite entity because system property "
                + linkName + " does not exist");
          }
          switch (oppositeLinkProperty) {
            case Identifiable identifiable -> {
              if (identifiable.getIdentity().equals(entity.getIdentity())) {
                oppositeEntity.setPropertyInternal(linkName, null);
              } else {
                throw new IllegalStateException(
                    "Cannot remove link" + linkName + ":" + entity.getIdentity()
                        + " from opposite entity because it does not exist");
              }
            }
            case LinkList linkList -> {
              var removed = linkList.remove(entity);
              if (!removed) {
                throw new IllegalStateException(
                    "Cannot remove link " + linkName + ":" + entity.getIdentity()
                        + " from opposite entity because it does not exist in the list");
              }
            }
            case LinkSet linkSet -> {
              var removed = linkSet.remove(entity);
              if (!removed) {
                throw new IllegalStateException(
                    "Cannot remove link " + linkName + ":" + entity.getIdentity()
                        + " from opposite entity because it does not exist in the set");
              }
            }
            case LinkMap linkMap -> {
              var removed = false;
              var entrySetIterator = linkMap.entrySet().iterator();
              while (entrySetIterator.hasNext()) {
                var entry = entrySetIterator.next();

                if (entry.getValue().equals(entity)) {
                  entrySetIterator.remove();
                  removed = true;
                  break;
                }
              }

              if (!removed) {
                throw new IllegalStateException(
                    "Cannot remove link " + linkName + ":" + entity.getIdentity()
                        + " from opposite entity because it does not exist in the map");
              }
            }
            case RidBag ridBag -> {
              assert ridBag.contains(entity.getIdentity());
              ridBag.remove(entity.getIdentity());
            }
            default -> {
              throw new IllegalStateException("Unexpected type of link property: "
                  + oppositeLinkProperty.getClass().getName());
            }
          }
        }
      }
    }
  }

  private void ensureLinksConsistencyAfterModification(@Nonnull EntityImpl entity,
      @Nullable SchemaImmutableClass clazz) {
    var dirtyProperties = entity.getDirtyPropertiesBetweenCallbacksInternal(false,
        false);
    if (clazz != null) {
      if (clazz.isVertexType()) {
        dirtyProperties = filterVertexProperties(dirtyProperties);
      } else if (clazz.isEdgeType()) {
        dirtyProperties = filterEdgeProperties(dirtyProperties);
      }
    }

    var linksToRemove = new HashSet<RecordId>();
    var linksToAdd = new HashSet<RecordId>();

    for (var propertyName : dirtyProperties) {
      var originalValue = entity.getOriginalValue(propertyName);
      var currentPropertyValue = entity.getPropertyInternal(propertyName);

      if (originalValue == currentPropertyValue) {
        var timeLine = entity.getCollectionTimeLine(propertyName);
        if (timeLine != null) {
          if (originalValue instanceof LinkList || originalValue instanceof LinkSet) {
            for (var event : timeLine.getMultiValueChangeEvents()) {
              switch (event.getChangeType()) {
                case ADD -> {
                  linksToAdd.add((RecordId) event.getValue());
                }
                case REMOVE -> {
                  linksToRemove.add((RecordId) event.getValue());
                }
                case UPDATE -> {
                  linksToAdd.add((RecordId) event.getValue());
                  linksToRemove.add((RecordId) event.getOldValue());
                }
              }
            }
          } else if (originalValue instanceof LinkMap) {
            for (var event : timeLine.getMultiValueChangeEvents()) {
              switch (event.getChangeType()) {
                case ADD -> {
                  linksToAdd.add((RecordId) event.getKey());
                }
                case REMOVE -> {
                  linksToRemove.add((RecordId) event.getKey());
                }
              }
            }
          }
        } else {
          accumulateLinkContainer(originalValue, linksToRemove);
          accumulateLinkContainer(currentPropertyValue, linksToAdd);
        }
      } else {
        accumulateLinkContainer(originalValue, linksToRemove);
        accumulateLinkContainer(currentPropertyValue, linksToAdd);
      }

      var oppositeLinkBagPropertyName = EntityImpl.OPPOSITE_LINK_CONTAINER_PREFIX + propertyName;
      for (var linkToAdd : linksToAdd) {
        var oppositeRecord = load(linkToAdd);
        if (!oppositeRecord.isEntity()) {
          continue;
        }

        var oppositeEntity = (EntityImpl) oppositeRecord;
        //skip self-links
        if (oppositeEntity.equals(entity)) {
          continue;
        }
        var linkBag = (RidBag) oppositeEntity.getPropertyInternal(oppositeLinkBagPropertyName);

        if (linkBag == null) {
          linkBag = new RidBag(this);
          oppositeEntity.setPropertyInternal(oppositeLinkBagPropertyName, linkBag);
        }

        linkBag.add(linkToAdd);
      }
      for (var linkToRemove : linksToRemove) {
        var oppositeEntity = (EntityImpl) loadEntity(linkToRemove);
        var linkBag = (RidBag) oppositeEntity.getPropertyInternal(oppositeLinkBagPropertyName);

        if (linkBag != null) {
          assert linkBag.contains(linkToRemove);
          linkBag.remove(linkToRemove);
        } else {
          throw new IllegalStateException("Cannot remove link " + linkToRemove
              + " from opposite entity because required system property "
              + oppositeLinkBagPropertyName + " does not exist");
        }
      }
    }
  }

  private static void accumulateLinkContainer(Object value,
      HashSet<RecordId> links) {
    if (value == null) {
      return;
    }

    switch (value) {
      case Identifiable identifiable -> {
        if (identifiable instanceof Entity entity) {
          if (!entity.isEmbedded()) {
            links.add((RecordId) identifiable.getIdentity());
          }
        } else {
          links.add((RecordId) identifiable.getIdentity());
        }
      }
      case LinkList linkList -> {
        for (var link : linkList) {
          links.add((RecordId) link);
        }
      }
      case LinkSet linkSet -> {
        for (var link : linkSet) {
          links.add((RecordId) link);
        }
      }
      case LinkMap linkMap -> {
        for (var link : linkMap.values()) {
          links.add((RecordId) link);
        }
      }
      case RidBag ridBag -> {
        for (var link : ridBag) {
          links.add((RecordId) link);
        }
      }
      default -> {
      }
    }

  }

  private static List<String> filterEdgeProperties(Collection<String> properties) {
    var result = new ArrayList<String>(properties.size());
    for (var property : properties) {
      if (!EdgeInternal.isEdgeConnectionProperty(property)) {
        result.add(property);
      }
    }

    return result;
  }

  private static List<String> filterVertexProperties(@Nonnull Collection<String> properties) {
    var result = new ArrayList<String>(properties.size());
    for (var property : properties) {
      if (!VertexEntityImpl.isConnectionToEdge(Direction.BOTH, property)) {
        result.add(property);
      }
    }

    return result;
  }

}
