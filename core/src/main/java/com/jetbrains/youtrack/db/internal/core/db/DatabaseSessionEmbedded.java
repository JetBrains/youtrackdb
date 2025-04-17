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
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
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
import com.jetbrains.youtrack.db.api.record.RecordHook.TYPE;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.Stopwatch;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.conflict.RecordConflictStrategy;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkMapIml;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerEmbedded;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorCollection;
import com.jetbrains.youtrack.db.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrack.db.internal.core.metadata.function.FunctionLibraryImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.metadata.security.ImmutableUser;
import com.jetbrains.youtrack.db.internal.core.metadata.security.PropertyEncryptionNone;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
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
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.FreezableStorageComponent;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.LinkCollectionsBTreeManager;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionImpl;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
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
public class DatabaseSessionEmbedded extends DatabaseSessionAbstract<IndexManagerEmbedded>
    implements QueryLifecycleListener {

  private YouTrackDBConfigImpl config;
  private final Storage storage; // todo: make this final when "removeStorage" is removed

  private final Stopwatch freezeDurationMetric;
  private final Stopwatch releaseDurationMetric;

  private final TransactionMeters transactionMeters;

  private boolean ensureLinkConsistency = true;

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

  @Override
  public DatabaseSession open(final String iUserName, final String iUserPassword) {
    throw new UnsupportedOperationException("Use YouTrackDB");
  }

  public void init(YouTrackDBConfigImpl config, SharedContext<IndexManagerEmbedded> sharedContext) {
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
  @Override
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
  public void internalCreate(YouTrackDBConfigImpl config, SharedContext<IndexManagerEmbedded> ctx) {
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

  protected void createMetadata(SharedContext<IndexManagerEmbedded> shared) {
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

  @Override
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
  @Override
  public DatabaseSessionInternal copy() {
    assertIfNotActive();
    var storage = (Storage) getSharedContext().getStorage();
    storage.open(this, null, null, getConfiguration());
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
    var indexManager = sharedContext.getIndexManager();
    if (indexManager.autoRecreateIndexesAfterCrash(this)) {
      indexManager.recreateIndexes(this);
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
    if (currentTx.isCallBackProcessingInProgress()) {
      throw new CommandExecutionException(getDatabaseName(),
          "Cannot execute query while transaction processing callbacks. If you called this method in beforeCallbackXXX method "
              +
              "please move it to the afterCallbackXXX method");
    }
    try {
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
    } catch (Exception e) {
      rollback(true);
      throw e;
    }
  }

  @Override
  public ResultSet query(String query, @SuppressWarnings("rawtypes") Map args) {
    return query(query, true, args);
  }

  @Override
  public ResultSet query(String query, boolean syncTx, @SuppressWarnings("rawtypes") Map args)
      throws CommandSQLParsingException, CommandExecutionException {
    checkOpenness();
    assert assertIfNotActive();
    if (currentTx.isCallBackProcessingInProgress()) {
      throw new CommandExecutionException(getDatabaseName(),
          "Cannot execute query while transaction processing callbacks. If you called this method in beforeCallbackXXX method "
              +
              "please move it to the afterCallbackXXX method");
    }

    beginReadOnly();
    try {
      if (syncTx) {
        currentTx.preProcessRecordsAndExecuteCallCallbacks();
      }
      getSharedContext().getYouTrackDB().startCommand(Optional.empty());
      preQueryStart();
      try {
        var statement = SQLEngine.parse(query, this);
        if (!statement.isIdempotent()) {
          throw new CommandExecutionException(getDatabaseName(),
              "Cannot execute query on non idempotent statement: " + query);
        }
        @SuppressWarnings("unchecked")
        var original = statement.execute(this, args, true);
        var result = new LocalResultSetLifecycleDecorator(original);
        queryStarted(result);
        return result;
      } finally {
        cleanQueryState();
        getSharedContext().getYouTrackDB().endCommand();
      }
    } catch (Exception e) {
      rollback(true);
      throw e;
    }
  }

  @Override
  public ResultSet execute(String query, Object[] args) {
    checkOpenness();
    assert assertIfNotActive();

    if (currentTx.isCallBackProcessingInProgress()) {
      throw new CommandExecutionException(getDatabaseName(),
          "Cannot execute SQL command while transaction processing callbacks. If you called this method in beforeCallbackXXX method "
              +
              "please move it to the afterCallbackXXX method");
    }
    try {
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
    } catch (Exception e) {
      rollback(true);
      throw e;
    }
  }

  @Override
  public ResultSet execute(String query, @SuppressWarnings("rawtypes") Map args) {
    checkOpenness();
    assert assertIfNotActive();

    if (currentTx.isCallBackProcessingInProgress()) {
      throw new CommandExecutionException(getDatabaseName(),
          "Cannot execute SQL command while transaction processing callbacks. If you called this method in beforeCallbackXXX method "
              +
              "please move it to the afterCallbackXXX method");
    }
    try {
      currentTx.preProcessRecordsAndExecuteCallCallbacks();
      getSharedContext().getYouTrackDB().startCommand(Optional.empty());
      try {
        preQueryStart();

        var statement = SQLEngine.parse(query, this);
        @SuppressWarnings("unchecked")
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
    } catch (Exception e) {
      rollback(true);
      throw e;
    }
  }

  @Override
  public ResultSet runScript(String language, String script, Object... args) {
    checkOpenness();
    assert assertIfNotActive();
    if (currentTx.isCallBackProcessingInProgress()) {
      throw new CommandExecutionException(getDatabaseName(),
          "Cannot execute SQL script while transaction processing callbacks. If you called this method in beforeCallbackXXX method "
              +
              "please move it to the afterCallbackXXX method");
    }
    try {
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

        ((AbstractStorage) this.storage).pauseConfigurationUpdateNotifications();
        ResultSet original;
        try {
          original = executor.execute(this, script, args);
        } finally {
          ((AbstractStorage) this.storage).fireConfigurationUpdateNotifications();
        }
        var result = new LocalResultSetLifecycleDecorator(original);
        queryStarted(result);
        return result;
      } finally {
        cleanQueryState();
        getSharedContext().getYouTrackDB().endCommand();
      }
    } catch (Exception e) {
      rollback(true);
      throw e;
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
    if (currentTx.isCallBackProcessingInProgress()) {
      throw new CommandExecutionException(getDatabaseName(),
          "Cannot execute SQL script while transaction processing callbacks. If you called this method in beforeCallbackXXX method "
              +
              "please move it to the afterCallbackXXX method");
    }
    if (!"sql".equalsIgnoreCase(language)) {
      checkSecurity(Rule.ResourceGeneric.COMMAND, Role.PERMISSION_EXECUTE, language);
    }

    try {
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

        ((AbstractStorage) this.storage).pauseConfigurationUpdateNotifications();
        try {
          original = executor.execute(this, script, args);
        } finally {
          ((AbstractStorage) this.storage).fireConfigurationUpdateNotifications();
        }

        var result = new LocalResultSetLifecycleDecorator(original);
        queryStarted(result);
        return result;
      } finally {
        cleanQueryState();
        getSharedContext().getYouTrackDB().endCommand();
      }
    } catch (Exception e) {
      rollback(true);
      throw e;
    }

  }

  public LocalResultSetLifecycleDecorator query(ExecutionPlan plan, Map<Object, Object> params) {
    checkOpenness();
    assert assertIfNotActive();
    if (currentTx.isCallBackProcessingInProgress()) {
      throw new CommandExecutionException(getDatabaseName(),
          "Cannot execute query transaction processing callbacks. If you called this method in beforeCallbackXXX method "
              +
              "please move it to the afterCallbackXXX method");
    }

    try {
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
    } catch (Exception e) {
      rollback(true);
      throw e;
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
  public int addBlobCollection(final String iCollectionName, final Object... iParameters) {
    assert assertIfNotActive();
    int id;
    if (!existsCollection(iCollectionName)) {
      id = addCollection(iCollectionName, iParameters);
    } else {
      id = getCollectionIdByName(iCollectionName);
    }
    getMetadata().getSchema().addBlobCollection(id);
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
  @Override
  public void delete(@Nonnull DBRecord record) {
    checkOpenness();
    assert assertIfNotActive();

    record.delete();
  }

  @Override
  public void beforeCreateOperations(final RecordAbstract recordAbstract, String collectionName) {
    assert assertIfNotActive();

    checkSecurity(Role.PERMISSION_CREATE, recordAbstract, collectionName);

    if (recordAbstract instanceof EntityImpl entity) {
      if (!getSharedContext().getSecurity().canCreate(this, entity)) {
        throw new SecurityException(getDatabaseName(),
            "Cannot update record "
                + entity
                + ": the resource has restricted access due to security policies");
      }

      var clazz = entity.getImmutableSchemaClass(this);
      ensureLinksConsistencyBeforeModification(entity);

      if (clazz != null) {
        checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_CREATE, clazz.getName());
        if (clazz.isUser()) {
          entity.validate();
        } else if (clazz.isFunction()) {
          FunctionLibraryImpl.validateFunctionRecord(entity);
        }

        entity.propertyEncryption = PropertyEncryptionNone.instance();
      }
    }

    callbackHooks(RecordHook.TYPE.BEFORE_CREATE, recordAbstract);
  }

  @Override
  public void afterCreateOperations(RecordAbstract recordAbstract) {
    assert assertIfNotActive();

    if (recordAbstract instanceof EntityImpl entity) {
      var clazz = entity.getImmutableSchemaClass(this);
      if (clazz != null) {
        if (clazz.isUser()) {
          SecurityUserImpl.encodePassword(this, entity);
          sharedContext.getSecurity().incrementVersion(this);
        }
        if (clazz.isScheduler()) {
          getSharedContext().getScheduler().initScheduleRecord(entity);
        }
        if (clazz.isRole() || clazz.isSecurityPolicy()) {
          sharedContext.getSecurity().incrementVersion(this);
        }
      }
    }

    callbackHooks(RecordHook.TYPE.AFTER_CREATE, recordAbstract);
  }

  @Override
  public void beforeUpdateOperations(final RecordAbstract recordAbstract, String collectionName) {
    assert assertIfNotActive();

    checkSecurity(Role.PERMISSION_UPDATE, recordAbstract, collectionName);

    if (recordAbstract instanceof EntityImpl entity) {

      var clazz = entity.getImmutableSchemaClass(this);
      ensureLinksConsistencyBeforeModification(entity);

      if (clazz != null) {
        if (clazz.isScheduler()) {
          getSharedContext().getScheduler().preHandleUpdateScheduleInTx(this, entity);
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
      }
    }

    callbackHooks(RecordHook.TYPE.BEFORE_UPDATE, recordAbstract);
  }

  @Override
  public void afterUpdateOperations(RecordAbstract recordAbstract) {
    assert assertIfNotActive();

    if (recordAbstract instanceof EntityImpl entity) {
      var clazz = entity.getImmutableSchemaClass(this);
      if (clazz != null) {

        if (clazz.isUser()) {
          SecurityUserImpl.encodePassword(this, entity);
          sharedContext.getSecurity().incrementVersion(this);
        } else if (clazz.isRole() || clazz.isSecurityPolicy()) {
          sharedContext.getSecurity().incrementVersion(this);
        }
      }
    }

    callbackHooks(TYPE.AFTER_UPDATE, recordAbstract);
  }

  @Override
  public void beforeDeleteOperations(final RecordAbstract recordAbstract,
      java.lang.String collectionName) {
    assert assertIfNotActive();
    checkSecurity(Role.PERMISSION_DELETE, recordAbstract, collectionName);

    if (recordAbstract instanceof EntityImpl entity) {
      ensureLinksConsistencyBeforeDeletion(entity);

      var clazz = entity.getImmutableSchemaClass(this);
      if (clazz != null) {
        if (!getSharedContext().getSecurity().canDelete(this, entity)) {
          throw new SecurityException(getDatabaseName(),
              "Cannot delete record "
                  + entity.getIdentity()
                  + ": the resource has restricted access due to security policies");
        }

      }
    }

    callbackHooks(RecordHook.TYPE.BEFORE_DELETE, recordAbstract);
  }

  @Override
  public void afterDeleteOperations(RecordAbstract recordAbstract) {
    assert assertIfNotActive();
    if (recordAbstract instanceof EntityImpl entity) {
      var clazz = entity.getImmutableSchemaClass(this);
      if (clazz != null) {
        if (clazz.isSequence()) {
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

    callbackHooks(TYPE.AFTER_DELETE, recordAbstract);
  }

  @Override
  public void afterReadOperations(RecordAbstract identifiable) {
    assert assertIfNotActive();
    callbackHooks(RecordHook.TYPE.READ, identifiable);
  }

  @Override
  public boolean beforeReadOperations(RecordAbstract identifiable) {
    assert assertIfNotActive();
    if (identifiable instanceof EntityImpl entity) {
      var clazz = entity.getImmutableSchemaClass(this);
      if (clazz != null) {
        try {
          checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_READ, clazz.getName());
        } catch (SecurityException e) {
          return true;
        }

        if (!getSharedContext().getSecurity().canRead(this, entity)) {
          return true;
        }

        entity.initPropertyAccess();
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

  @Override
  public String getCollectionName(final @Nonnull DBRecord record) {
    assert assertIfNotActive();

    var collectionId = record.getIdentity().getCollectionId();
    if (collectionId == RID.COLLECTION_ID_INVALID) {
      // COMPUTE THE COLLECTION ID
      SchemaClassInternal schemaClass = null;
      if (record instanceof EntityImpl) {
        SchemaImmutableClass result = null;
        result = ((EntityImpl) record).getImmutableSchemaClass(this);
        schemaClass = result;
      }
      if (schemaClass != null) {
        // FIND THE RIGHT COLLECTION AS CONFIGURED IN CLASS
        if (schemaClass.isAbstract()) {
          throw new SchemaException(getDatabaseName(),
              "Entity belongs to abstract class '"
                  + schemaClass.getName()
                  + "' and cannot be saved");
        }
        collectionId = schemaClass.getCollectionForNewInstance((EntityImpl) record);
        return getCollectionNameById(collectionId);
      } else {
        throw new SchemaException(getDatabaseName(),
            "Cannot find the collection id for record "
                + record
                + " because the schema class is not defined");
      }

    } else {
      return getCollectionNameById(collectionId);
    }
  }


  @Override
  public boolean executeExists(@Nonnull RID rid) {
    checkOpenness();
    assert assertIfNotActive();
    try {
      checkSecurity(
          Rule.ResourceGeneric.COLLECTION,
          Role.PERMISSION_READ,
          getCollectionNameById(rid.getCollectionId()));

      var txInternal = getTransactionInternal();
      if (txInternal.isDeletedInTx(rid)) {
        // DELETED IN TX
        return false;
      }
      DBRecord record = getTransactionInternal().getRecord(rid);
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
                  + " (collection: "
                  + storage.getPhysicalCollectionNameById(rid.getCollectionId())
                  + ")"),
          t, getDatabaseName());
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
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
  @Override
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
  @Override
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
  public int addCollection(final String iCollectionName, final Object... iParameters) {
    assert assertIfNotActive();
    return storage.addCollection(this, iCollectionName, iParameters);
  }

  @Override
  public int addCollection(final String iCollectionName, final int iRequestedId) {
    assert assertIfNotActive();
    return storage.addCollection(this, iCollectionName, iRequestedId);
  }

  @Override
  public RecordConflictStrategy getConflictStrategy() {
    assert assertIfNotActive();
    return getStorageInfo().getRecordConflictStrategy();
  }

  @Override
  public DatabaseSessionEmbedded setConflictStrategy(final String iStrategyName) {
    assert assertIfNotActive();
    storage.setConflictStrategy(
        YouTrackDBEnginesManager.instance().getRecordConflictStrategy().getStrategy(iStrategyName));
    return this;
  }

  @Override
  public DatabaseSessionEmbedded setConflictStrategy(final RecordConflictStrategy iResolver) {
    assert assertIfNotActive();
    storage.setConflictStrategy(iResolver);
    return this;
  }

  @Override
  public long getCollectionRecordSizeByName(final String collectionName) {
    assert assertIfNotActive();
    try {
      return storage.getCollectionRecordsSizeByName(collectionName);
    } catch (Exception e) {
      throw BaseException.wrapException(
          new DatabaseException(getDatabaseName(),
              "Error on reading records size for collection '" + collectionName + "'"),
          e, getDatabaseName());
    }
  }

  @Override
  public long getCollectionRecordSizeById(final int collectionId) {
    assert assertIfNotActive();
    try {
      return storage.getCollectionRecordsSizeById(collectionId);
    } catch (Exception e) {
      throw BaseException.wrapException(
          new DatabaseException(getDatabaseName(),
              "Error on reading records size for collection with id '" + collectionId + "'"),
          e, getDatabaseName());
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countCollectionElements(int iCollectionId, boolean countTombstones) {
    assert assertIfNotActive();
    final var name = getCollectionNameById(iCollectionId);
    if (name == null) {
      return 0;
    }
    checkSecurity(Rule.ResourceGeneric.COLLECTION, Role.PERMISSION_READ, name);
    assert assertIfNotActive();
    return storage.count(this, iCollectionId, countTombstones);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countCollectionElements(int[] iCollectionIds, boolean countTombstones) {
    assert assertIfNotActive();
    String name;
    for (var iCollectionId : iCollectionIds) {
      name = getCollectionNameById(iCollectionId);
      checkSecurity(Rule.ResourceGeneric.COLLECTION, Role.PERMISSION_READ, name);
    }
    return storage.count(this, iCollectionIds, countTombstones);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countCollectionElements(final String iCollectionName) {
    checkSecurity(Rule.ResourceGeneric.COLLECTION, Role.PERMISSION_READ, iCollectionName);
    assert assertIfNotActive();

    final var collectionId = getCollectionIdByName(iCollectionName);
    if (collectionId < 0) {
      throw new IllegalArgumentException("Collection '" + iCollectionName + "' was not found");
    }
    return storage.count(this, collectionId);
  }

  @Override
  public boolean dropCollection(final String iCollectionName) {
    assert assertIfNotActive();
    final var collectionId = getCollectionIdByName(iCollectionName);
    var schema = metadata.getSchema();

    var clazz = schema.getClassByCollectionId(collectionId);
    if (clazz != null) {
      throw new DatabaseException(this,
          "Cannot drop collection '" + getCollectionNameById(collectionId)
              + "' because it is mapped to class '" + clazz.getName() + "'");
    }
    if (schema.getBlobCollections().contains(collectionId)) {
      schema.removeBlobCollection(iCollectionName);
    }
    getLocalCache().freeCollection(collectionId);
    return dropCollectionInternal(iCollectionName);
  }

  protected boolean dropCollectionInternal(final String iCollectionName) {
    assert assertIfNotActive();
    return storage.dropCollection(this, iCollectionName);
  }

  @Override
  public boolean dropCollection(final int collectionId) {
    assert assertIfNotActive();

    checkSecurity(
        Rule.ResourceGeneric.COLLECTION, Role.PERMISSION_DELETE,
        getCollectionNameById(collectionId));

    var schema = metadata.getSchema();
    final var clazz = schema.getClassByCollectionId(collectionId);
    if (clazz != null) {
      throw new DatabaseException(this,
          "Cannot drop collection '" + getCollectionNameById(collectionId)
              + "' because it is mapped to class '" + clazz.getName() + "'");
    }

    getLocalCache().freeCollection(collectionId);
    if (schema.getBlobCollections().contains(collectionId)) {
      schema.removeBlobCollection(getCollectionNameById(collectionId));
    }

    final var collectionName = getCollectionNameById(collectionId);
    if (collectionName == null) {
      return false;
    }

    final var iteratorCollection = browseCollection(collectionName);
    if (iteratorCollection == null) {
      return false;
    }

    executeInTxBatches(iteratorCollection, (session, record) -> delete(record));

    return dropCollectionInternal(collectionId);
  }

  @Override
  public boolean dropCollectionInternal(int collectionId) {
    assert assertIfNotActive();
    return storage.dropCollection(this, collectionId);
  }

  @Override
  public long getSize() {
    assert assertIfNotActive();
    return storage.getSize(this);
  }

  @Override
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

  @Override
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

  @Override
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

  @Nullable
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
  @Override
  public LinkCollectionsBTreeManager getBTreeCollectionManager() {
    assert assertIfNotActive();
    return storage.getLinkCollectionsBtreeCollectionManager();
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

  @Override
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
  public String getCollectionRecordConflictStrategy(int collectionId) {
    assert assertIfNotActive();
    return storage.getCollectionRecordConflictStrategy(collectionId);
  }

  @Override
  public int[] getCollectionsIds(@Nonnull Set<String> filterCollections) {
    assert assertIfNotActive();
    return storage.getCollectionsIds(filterCollections);
  }

  @Override
  public void startExclusiveMetadataChange() {
    assert assertIfNotActive();
    ((AbstractStorage) storage).startDDL();
  }

  @Override
  public void endExclusiveMetadataChange() {
    assert assertIfNotActive();
    ((AbstractStorage) storage).endDDL();
  }

  @Override
  public long truncateClass(String name, boolean polimorfic) {
    assert assertIfNotActive();
    this.checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_UPDATE);
    var clazz = getClass(name);
    int[] collectionIds;
    if (polimorfic) {
      collectionIds = clazz.getPolymorphicCollectionIds();
    } else {
      collectionIds = clazz.getCollectionIds();
    }
    long count = 0;
    for (var id : collectionIds) {
      if (id < 0) {
        continue;
      }
      final var collectionName = getCollectionNameById(id);
      if (collectionName == null) {
        continue;
      }
      count += truncateCollectionInternal(collectionName);
    }
    return count;
  }

  @Override
  public void truncateClass(String name) {
    assert assertIfNotActive();
    truncateClass(name, true);
  }

  @Override
  public long truncateCollectionInternal(String collectionName) {
    assert assertIfNotActive();
    checkSecurity(Rule.ResourceGeneric.COLLECTION, Role.PERMISSION_DELETE, collectionName);

    var id = getCollectionIdByName(collectionName);
    if (id == -1) {
      throw new DatabaseException(getDatabaseName(),
          "Collection with name " + collectionName + " does not exist");
    }
    final var clazz = getMetadata().getSchema().getClassByCollectionId(id);
    if (clazz != null) {
      checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_DELETE, clazz.getName());
    }

    var count = new long[]{0};
    final var iteratorCollection =
        new RecordIteratorCollection<>(this, id, true);

    executeInTxBatches(iteratorCollection, (session, record) -> {
      delete(record);
      count[0]++;
    });

    return count[0];
  }

  @Override
  public void truncateCollection(String collectionName) {
    assert assertIfNotActive();
    truncateCollectionInternal(collectionName);
  }

  @Override
  public TransactionMeters transactionMeters() {
    return transactionMeters;
  }

  private void ensureLinksConsistencyBeforeDeletion(@Nonnull EntityImpl entity) {
    if (!ensureLinkConsistency) {
      return;
    }

    var properties = entity.getPropertyNamesInternal(true, false);
    var linksToUpdateMap = new HashMap<RecordId, int[]>();
    for (var propertyName : properties) {
      linksToUpdateMap.clear();

      if (propertyName.charAt(0) == EntityImpl.OPPOSITE_LINK_CONTAINER_PREFIX) {
        var oppositeLinksContainer = (LinkBag) entity.getPropertyInternal(propertyName, false);

        for (var link : oppositeLinksContainer) {
          var transaction = getActiveTransaction();
          var oppositeEntity = (EntityImpl) transaction.loadEntityOrNull(link);
          //skip self-links and already deleted entities
          if (oppositeEntity == null || oppositeEntity.equals(entity)) {
            continue;
          }

          var linkName = propertyName.substring(1);
          var oppositeLinkProperty = oppositeEntity.getPropertyInternal(linkName, false);

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
            case EntityLinkListImpl linkList -> {
              var removed = linkList.remove(entity);
              if (!removed) {
                throw new IllegalStateException(
                    "Cannot remove link " + linkName + ":" + entity.getIdentity()
                        + " from opposite entity because it does not exist in the list");
              }
            }
            case EntityLinkSetImpl linkSet -> {
              var removed = linkSet.remove(entity);
              if (!removed) {
                throw new IllegalStateException(
                    "Cannot remove link " + linkName + ":" + entity.getIdentity()
                        + " from opposite entity because it does not exist in the set");
              }
            }
            case EntityLinkMapIml linkMap -> {
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
            case LinkBag linkBag -> {
              assert linkBag.contains(entity.getIdentity());
              var removed = linkBag.remove(entity.getIdentity());
              if (!removed) {
                throw new IllegalStateException(
                    "Cannot remove link " + linkName + ":" + entity.getIdentity()
                        + " from opposite entity because it does not exist in link bag");
              }
            }
            default -> {
              throw new IllegalStateException("Unexpected type of link property: "
                  + oppositeLinkProperty.getClass().getName());
            }
          }
        }
      } else {
        if (entity.isVertex()) {
          if (VertexEntityImpl.isEdgeProperty(propertyName)) {
            continue;
          }
        } else if (entity.isEdge()) {
          if (EdgeInternal.isEdgeConnectionProperty(propertyName)) {
            continue;
          }
        }

        var currentPropertyValue = entity.getPropertyInternal(propertyName);
        subtractFromLinksContainer(currentPropertyValue, linksToUpdateMap);
        updateOppositeLinks(entity, propertyName, linksToUpdateMap);
      }
    }
  }

  private void ensureLinksConsistencyBeforeModification(@Nonnull final EntityImpl entity) {
    if (!ensureLinkConsistency) {
      return;
    }

    var dirtyProperties = entity.getDirtyPropertiesBetweenCallbacksInternal(false,
        false);
    if (entity.isVertex()) {
      dirtyProperties = filterVertexProperties(dirtyProperties);
    } else if (entity.isEdge()) {
      dirtyProperties = filterEdgeProperties(dirtyProperties);
    }

    var linksToUpdateMap = new HashMap<RecordId, int[]>();
    for (var propertyName : dirtyProperties) {
      linksToUpdateMap.clear();

      var originalValue = entity.getOriginalValue(propertyName);
      var currentPropertyValue = entity.getPropertyInternal(propertyName, false);

      if (originalValue == null) {
        var timeLine = entity.getCollectionTimeLine(propertyName);
        if (timeLine != null) {
          if (currentPropertyValue instanceof EntityLinkListImpl
              || currentPropertyValue instanceof EntityLinkSetImpl ||
              currentPropertyValue instanceof LinkBag
              || currentPropertyValue instanceof EntityLinkMapIml) {
            for (var event : timeLine.getMultiValueChangeEvents()) {
              switch (event.getChangeType()) {
                case ADD -> {
                  assert event.getValue() != null;
                  incrementLinkCounter((RecordId) event.getValue(), linksToUpdateMap);
                }
                case REMOVE -> {
                  assert event.getOldValue() != null;
                  decrementLinkCounter((RecordId) event.getOldValue(), linksToUpdateMap);
                }
                case UPDATE -> {
                  assert event.getValue() != null;
                  assert event.getOldValue() != null;
                  incrementLinkCounter((RecordId) event.getValue(), linksToUpdateMap);
                  decrementLinkCounter((RecordId) event.getOldValue(), linksToUpdateMap);
                }
              }
            }
          }
        } else {
          subtractFromLinksContainer(originalValue, linksToUpdateMap);
          addToLinksContainer(currentPropertyValue, linksToUpdateMap);
        }
      } else {
        subtractFromLinksContainer(originalValue, linksToUpdateMap);
        addToLinksContainer(currentPropertyValue, linksToUpdateMap);
      }

      updateOppositeLinks(entity, propertyName, linksToUpdateMap);
    }
  }

  private void updateOppositeLinks(@Nonnull EntityImpl entity, String propertyName,
      HashMap<RecordId, int[]> linksToUpdateMap) {
    var oppositeLinkBagPropertyName = EntityImpl.OPPOSITE_LINK_CONTAINER_PREFIX + propertyName;
    for (var entitiesToUpdate : linksToUpdateMap.entrySet()) {
      var oppositeLink = entitiesToUpdate.getKey();
      var diff = entitiesToUpdate.getValue()[0];

      if (currentTx.isDeletedInTx(oppositeLink)) {
        if (diff > 0) {
          throw new IllegalStateException("Cannot add link " + entity.getIdentity()
              + " to opposite entity because it was deleted in transaction");
        }
        continue;
      }

      var oppositeRecord = load(oppositeLink);
      if (!oppositeRecord.isEntity()) {
        continue;
      }

      var oppositeEntity = (EntityImpl) oppositeRecord;
      //skip self-links
      if (oppositeEntity.equals(entity)) {
        continue;
      }
      var linkBag = (LinkBag) oppositeEntity.getPropertyInternal(oppositeLinkBagPropertyName);

      if (linkBag == null) {
        if (diff < 0) {
          throw new IllegalStateException("Cannot remove link " + propertyName + " for " + entity
              + " from opposite entity " + oppositeEntity + " because it does not exist");
        }
        linkBag = new LinkBag(this);
        oppositeEntity.setPropertyInternal(oppositeLinkBagPropertyName, linkBag);
      }

      var rid = entity.getIdentity();
      for (var i = 0; i < Math.abs(diff); i++) {
        if (diff > 0) {
          linkBag.add(rid);
          assert linkBag.contains(rid);
        } else {
          var removed = linkBag.remove(entity.getIdentity());
          if (!removed) {
            throw new IllegalStateException("Cannot remove link " + rid
                + " from opposite entity because it does not exist");
          }
        }
      }

      if (linkBag.isEmpty()) {
        oppositeEntity.removePropertyInternal(oppositeLinkBagPropertyName);
      }
    }
  }

  private static void decrementLinkCounter(@Nonnull RecordId recordId,
      @Nonnull HashMap<RecordId, int[]> linksToUpdateMap) {
    linksToUpdateMap.compute(
        recordId,
        (key, value) -> {
          if (value == null) {
            return new int[]{-1};
          } else {
            value[0]--;
            if (value[0] == 0) {
              return null;
            }
            return value;
          }
        });
  }

  private static void incrementLinkCounter(@Nonnull RecordId recordId,
      @Nonnull HashMap<RecordId, int[]> linksToUpdateMap) {
    linksToUpdateMap.compute(
        recordId,
        (key, value) -> {
          if (value == null) {
            return new int[]{1};
          } else {
            value[0]++;
            return value;
          }
        });
  }

  private static void addToLinksContainer(Object value,
      HashMap<RecordId, int[]> links) {
    if (value == null) {
      return;
    }

    switch (value) {
      case Identifiable identifiable -> {
        if (identifiable instanceof Entity entity) {
          if (!entity.isEmbedded()) {
            incrementLinkCounter((RecordId) identifiable.getIdentity(), links);
          }
        } else {
          incrementLinkCounter((RecordId) identifiable.getIdentity(), links);
        }
      }
      case EntityLinkListImpl linkList -> {
        for (var link : linkList) {
          incrementLinkCounter((RecordId) link, links);
        }
      }
      case EntityLinkSetImpl linkSet -> {
        for (var link : linkSet) {
          incrementLinkCounter((RecordId) link, links);
        }
      }
      case EntityLinkMapIml linkMap -> {
        for (var link : linkMap.values()) {
          incrementLinkCounter((RecordId) link, links);
        }
      }
      case LinkBag linkBag -> {
        for (var link : linkBag) {
          incrementLinkCounter((RecordId) link, links);
        }
      }
      default -> {
      }
    }
  }

  private static void subtractFromLinksContainer(Object value,
      HashMap<RecordId, int[]> links) {
    if (value == null) {
      return;
    }

    switch (value) {
      case Identifiable identifiable -> {
        if (identifiable instanceof Entity entity) {
          if (!entity.isEmbedded()) {
            decrementLinkCounter((RecordId) identifiable.getIdentity(), links);
          }
        } else {
          decrementLinkCounter((RecordId) identifiable.getIdentity(), links);
        }
      }
      case EntityLinkListImpl linkList -> {
        for (var link : linkList) {
          decrementLinkCounter((RecordId) link, links);
        }
      }
      case EntityLinkSetImpl linkSet -> {
        for (var link : linkSet) {
          decrementLinkCounter((RecordId) link, links);
        }
      }
      case EntityLinkMapIml linkMap -> {
        for (var link : linkMap.values()) {
          decrementLinkCounter((RecordId) link, links);
        }
      }
      case LinkBag linkBag -> {
        for (var link : linkBag) {
          decrementLinkCounter((RecordId) link, links);
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

  public void disableLinkConsistencyCheck() {
    this.ensureLinkConsistency = false;
  }

  public void enableLinkConsistencyCheck() {
    this.ensureLinkConsistency = true;
  }
}
