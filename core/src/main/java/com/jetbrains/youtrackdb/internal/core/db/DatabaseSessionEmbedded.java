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

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.api.exception.HighLevelException;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.common.listener.ListenerManger;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Stopwatch;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.TimeRate;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMonitoringMode;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.TransactionMetricsListener;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.cache.LocalRecordCache;
import com.jetbrains.youtrackdb.internal.core.cache.WeakValueHashMap;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.conflict.RecordConflictStrategy;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedMapImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkMapIml;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordElement;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Blob;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.EmbeddedEntity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RecordHook;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RecordHook.TYPE;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandScriptException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.LinksConsistencyException;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityAccessException;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityException;
import com.jetbrains.youtrackdb.internal.core.exception.SessionNotActivatedException;
import com.jetbrains.youtrackdb.internal.core.exception.TransactionBlockedException;
import com.jetbrains.youtrackdb.internal.core.exception.TransactionException;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorClass;
import com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorCollection;
import com.jetbrains.youtrackdb.internal.core.metadata.Metadata;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.function.FunctionLibraryImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.security.ImmutableUser;
import com.jetbrains.youtrackdb.internal.core.metadata.security.PropertyEncryptionNone;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule.ResourceGeneric;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.security.auth.AuthenticationInfo;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.SequenceLibraryImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.SequenceLibraryProxy;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.query.collection.embedded.EmbeddedList;
import com.jetbrains.youtrackdb.internal.core.query.collection.embedded.EmbeddedMap;
import com.jetbrains.youtrackdb.internal.core.query.collection.embedded.EmbeddedSet;
import com.jetbrains.youtrackdb.internal.core.query.collection.links.LinkList;
import com.jetbrains.youtrackdb.internal.core.query.collection.links.LinkMap;
import com.jetbrains.youtrackdb.internal.core.query.collection.links.LinkSet;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EdgeEntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EdgeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EmbeddedEntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.RecordBytes;
import com.jetbrains.youtrackdb.internal.core.record.impl.VertexEntityImpl;
import com.jetbrains.youtrackdb.internal.core.schedule.ScheduledEvent;
import com.jetbrains.youtrackdb.internal.core.schedule.SchedulerImpl;
import com.jetbrains.youtrackdb.internal.core.security.SecurityUser;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AggregateProjectionCalculationStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ProjectionCalculationStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.cache.AggregateCacheTapStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.cache.AggregateState;
import com.jetbrains.youtrackdb.internal.core.sql.executor.cache.CacheKey;
import com.jetbrains.youtrackdb.internal.core.sql.executor.cache.CacheableShape;
import com.jetbrains.youtrackdb.internal.core.sql.executor.cache.CachedEntry;
import com.jetbrains.youtrackdb.internal.core.sql.executor.cache.CachedResultSetView;
import com.jetbrains.youtrackdb.internal.core.sql.executor.cache.DeltaBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.executor.cache.IdempotentExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.cache.NonDeterministicQueryDetector;
import com.jetbrains.youtrackdb.internal.core.sql.executor.cache.QueryResultCache;
import com.jetbrains.youtrackdb.internal.core.sql.executor.cache.ShapeClassifier;
import com.jetbrains.youtrackdb.internal.core.sql.executor.cache.TxDeltaCursor;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ResultMapper;
import com.jetbrains.youtrackdb.internal.core.sql.parser.LocalResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.LocalResultSetLifecycleDecorator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAlterClassStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAlterPropertyStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLCreateClassStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLCreateIndexStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLCreatePropertyStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLDropClassStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLDropIndexStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLDropPropertyStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNestedProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjectionItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLTruncateClassStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.storage.RawBuffer;
import com.jetbrains.youtrackdb.internal.core.storage.RawPageBuffer;
import com.jetbrains.youtrackdb.internal.core.storage.StorageReadResult;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailedException;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManager;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction.TXSTATUS;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionNoTx;
import com.jetbrains.youtrackdb.internal.core.tx.RollbackException;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import com.jetbrains.youtrackdb.internal.core.tx.TxBiConsumer;
import com.jetbrains.youtrackdb.internal.core.tx.TxBiFunction;
import com.jetbrains.youtrackdb.internal.core.tx.TxConsumer;
import com.jetbrains.youtrackdb.internal.core.tx.TxFunction;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseSessionEmbedded extends ListenerManger<SessionListener>
    implements AutoCloseable, QueryLifecycleListener {

  @SuppressWarnings("unused")
  public enum STATUS {
    OPEN, CLOSED, IMPORTING
  }

  public enum ATTRIBUTES {
    DATEFORMAT, DATE_TIME_FORMAT, TIMEZONE, LOCALE_COUNTRY, LOCALE_LANGUAGE, CHARSET,
  }

  public enum ATTRIBUTES_INTERNAL {
    VALIDATION
  }

  public record TransactionMeters(
      TimeRate totalTransactions,
      TimeRate writeTransactions,
      TimeRate writeRollbackTransactions) {

    @SuppressWarnings("unused")
    public static TransactionMeters NOOP = new TransactionMeters(
        TimeRate.NOOP,
        TimeRate.NOOP,
        TimeRate.NOOP);
  }

  private static final Logger logger = LoggerFactory.getLogger(DatabaseSessionEmbedded.class);

  private static final byte recordType = EntityImpl.RECORD_TYPE;
  private final boolean serverMode;

  private YouTrackDBConfigImpl config;
  private final AbstractStorage storage;

  private final Stopwatch freezeDurationMetric;
  private final Stopwatch releaseDurationMetric;

  private final TransactionMeters transactionMeters;

  private boolean ensureLinkConsistency = true;

  private final HashMap<String, Object> properties = new HashMap<>();
  private final HashSet<Identifiable> inHook = new HashSet<>();

  private RecordSerializer serializer = RecordSerializerBinary.INSTANCE;
  private String url;

  private STATUS status;
  private MetadataDefault metadata;
  private ImmutableUser user;

  private final ArrayList<RecordHook> hooks = new ArrayList<>();
  private boolean retainRecords = true;
  private final LocalRecordCache localCache = new LocalRecordCache();
  private final CurrentStorageComponentsFactory componentsFactory;
  private boolean initialized = false;
  private FrontendTransaction currentTx;

  private SharedContext sharedContext;

  private boolean prefetchRecords;

  private final Map<String, ResultSet> activeQueries;
  private final int resultSetReportThreshold;

  // database stats!
  private long loadedRecordsCount;
  private long totalRecordLoadMs;
  private long minRecordLoadMs;
  private long ridbagPrefetchCount;
  private long totalRidbagPrefetchMs;
  private long minRidbagPrefetchMs;
  private long maxRidbagPrefetchMs;

  private final ThreadLocal<Boolean> activeSession = new ThreadLocal<>();

  public DatabaseSessionEmbedded(final AbstractStorage storage, boolean serverMode) {
    super(false);
    this.serverMode = serverMode;
    // in server mode we don't enable result set auto-closing
    this.activeQueries = serverMode ? new HashMap<>() : new WeakValueHashMap<>();

    activateOnCurrentThread();

    try {
      status = STATUS.CLOSED;
      // OVERWRITE THE URL
      url = storage.getURL();
      this.storage = storage;
      this.componentsFactory = storage.getComponentsFactory();

      init();

      final var metrics = YouTrackDBEnginesManager.instance().getMetricsRegistry();
      freezeDurationMetric =
          metrics.databaseMetric(CoreMetrics.DATABASE_FREEZE_DURATION, getDatabaseName());
      releaseDurationMetric =
          metrics.databaseMetric(CoreMetrics.DATABASE_RELEASE_DURATION, getDatabaseName());

      this.transactionMeters = new TransactionMeters(
          metrics.databaseMetric(CoreMetrics.TRANSACTION_RATE, getDatabaseName()),
          metrics.databaseMetric(CoreMetrics.TRANSACTION_WRITE_RATE, getDatabaseName()),
          metrics.databaseMetric(CoreMetrics.TRANSACTION_WRITE_ROLLBACK_RATE, getDatabaseName()));

      this.resultSetReportThreshold =
          getConfiguration().getValueAsInteger(
              GlobalConfiguration.QUERY_RESULT_SET_OPEN_WARNING_THRESHOLD);

    } catch (Exception t) {
      activeSession.remove();
      throw BaseException.wrapException(
          new DatabaseException(
              getDatabaseName(), "Error on opening database "),
          t, getDatabaseName());
    }
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

      var serializeName = storage.getRecordSerializer();
      if (serializeName == null) {
        throw new DatabaseException(getDatabaseName(),
            "Impossible to open database from version before 2.x use export import instead");
      }

      if (storage.getRecordSerializerVersion()
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

        checkSecurity(ResourceGeneric.DATABASE, Role.PERMISSION_READ);
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

              checkSecurity(ResourceGeneric.DATABASE, Role.PERMISSION_READ);
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

  public void internalCreate(YouTrackDBConfigImpl config, SharedContext ctx) {
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
        it.hasNext();) {
      it.next().onCreate(this);
    }
  }

  private void createMetadata(SharedContext shared) {
    assert assertIfNotActive();
    metadata.init(shared);

    shared.create(this);
  }

  private void loadMetadata() {
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

  public void set(final ATTRIBUTES iAttribute, final Object iValue) {
    assert assertIfNotActive();

    checkOpenness();

    if (iAttribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    final var stringValue = IOUtils.getStringContent(iValue != null ? iValue.toString() : null);
    final var storage = this.storage;
    switch (iAttribute) {
      case DATEFORMAT -> {
        if (stringValue == null) {
          throw new IllegalArgumentException("date format is null");
        }

        // Validate format by formatting the current instant
        new SimpleDateFormat(stringValue).format(Date.from(Instant.now()));

        storage.setDateFormat(stringValue);
      }

      case DATE_TIME_FORMAT -> {
        if (stringValue == null) {
          throw new IllegalArgumentException("date format is null");
        }

        // Validate format by formatting the current instant
        new SimpleDateFormat(stringValue).format(Date.from(Instant.now()));

        storage.setDateTimeFormat(stringValue);
      }

      case TIMEZONE -> {
        if (stringValue == null) {
          throw new IllegalArgumentException("Timezone can't be null");
        }

        // for backward compatibility, until 2.1.13 YouTrackDB accepted timezones in lowercase as well
        var timeZoneValue = TimeZone.getTimeZone(stringValue.toUpperCase(Locale.ENGLISH));
        if (timeZoneValue.equals(TimeZone.getTimeZone("GMT"))) {
          timeZoneValue = TimeZone.getTimeZone(stringValue);
        }

        storage.setTimeZone(timeZoneValue);
      }

      case LOCALE_COUNTRY -> storage.setLocaleCountry(stringValue);

      case LOCALE_LANGUAGE -> storage.setLocaleLanguage(stringValue);

      case CHARSET -> storage.setCharset(stringValue);

      default -> throw new IllegalArgumentException(
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

  @SuppressWarnings("unused")
  public DatabaseSessionEmbedded setCustom(final String name, final Object iValue) {
    assert assertIfNotActive();

    checkOpenness();

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
   * Returns a copy of current database if it's open. The returned instance can be used by another
   * thread without affecting current instance. The database copy is not set in thread local.
   */
  public DatabaseSessionEmbedded copy() {
    assertIfNotActive();

    checkOpenness();

    var storage = getSharedContext().getStorage();
    storage.open(this, null, null, getConfiguration());
    String user;
    if (getCurrentUser() != null) {
      user = getCurrentUser().getName(this);
    } else {
      user = null;
    }

    var database = new DatabaseSessionEmbedded(storage, this.serverMode);
    database.init(config, this.sharedContext);
    database.internalOpen(user, null, false);
    database.callOnOpenListeners();

    database.activateOnCurrentThread();
    return database;
  }

  public boolean isClosed() {
    return status == STATUS.CLOSED || storage.isClosed(this);
  }

  public void rebuildIndexes() {
    assert assertIfNotActive();

    checkOpenness();

    var indexManager = sharedContext.getIndexManager();
    if (indexManager.autoRecreateIndexesAfterCrash(this)) {
      indexManager.recreateIndexes(this);
    }
  }

  private void installHooksEmbedded() {
    assert assertIfNotActive();
    hooks.clear();
  }

  public AbstractStorage getStorage() {
    return storage;
  }

  public ResultSet query(String query, Object... args) {
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
      getSharedContext().getYouTrackDB().startCommand(null);
      try {
        var statement = SQLEngine.parse(query, this);
        if (!statement.isIdempotent()) {
          throw new CommandExecutionException(getDatabaseName(),
              "Cannot execute query on non idempotent statement: " + query);
        }
        var served = serveThroughCache(statement, args);
        return queryStartedLifecycle(served);
      } finally {
        getSharedContext().getYouTrackDB().endCommand();
      }
    } catch (Exception e) {
      rollback();
      throw e;
    }
  }

  public ResultSet query(String query, @SuppressWarnings("rawtypes") Map args) {
    return query(query, true, args);
  }

  public ResultSet query(String query, boolean syncTx, @SuppressWarnings("rawtypes") Map args)
      throws CommandSQLParsingException, CommandExecutionException {
    assert assertIfNotActive();

    checkOpenness();

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
      getSharedContext().getYouTrackDB().startCommand(null);
      try {
        var statement = SQLEngine.parse(query, this);
        if (!statement.isIdempotent()) {
          throw new CommandExecutionException(getDatabaseName(),
              "Cannot execute query on non idempotent statement: " + query);
        }
        @SuppressWarnings("unchecked")
        Map<Object, Object> typedArgs = args;
        var served = serveThroughCache(statement, typedArgs);
        return queryStartedLifecycle(served);
      } finally {
        getSharedContext().getYouTrackDB().endCommand();
      }
    } catch (Exception e) {
      rollback();
      throw e;
    }
  }

  /**
   * Shared cache entry point for both {@code query()} overloads. Decides whether the parsed,
   * already-confirmed-idempotent {@code statement} is eligible for the tx-result cache and, when it
   * is, serves the result from the cache (hit) or populates a fresh entry (miss); otherwise it runs
   * the ordinary uncached execution and returns its result unchanged.
   *
   * <p>The gate bypasses to uncached execution when any of the following holds: the feature flag is
   * off (the transaction's cache is {@code null}); a cache code path is already on the stack ({@code
   * cacheCodeDepth > 0}, the re-entrancy guard, so a {@code query()} issued from a UDF in a WHERE
   * clause does not recurse into the cache); the statement is not a SELECT or MATCH; the statement
   * references a non-deterministic function or per-row context variable; or the classified shape is
   * not one whose delta/view path is wired yet ({@code RECORD}, {@code K0_NONE}, and the {@code
   * AGGREGATE_*} family route through the cache; {@code MATCH_TUPLE_MULTI} is classified but not yet
   * wired, so it bypasses here. The {@code AGGREGATE_*} miss takes its own eager-drive populate path
   * with a side-tap splice, and falls back uncached on an untappable plan shape).
   *
   * @param statement the parsed, idempotent query statement
   * @param args      the positional {@code Object[]} or named {@code Map<Object,Object>} parameters
   *                  (or {@code null}); used to build the cache key and the view's command context
   */
  private ResultSet serveThroughCache(@Nonnull SQLStatement statement, @Nullable Object args) {
    // The cache lives only on a real transaction; a no-tx command path (FrontendTransactionNoTx) has
    // no cache, so route it straight to uncached execution. The query() overloads call
    // beginReadOnly() first, so this is normally a FrontendTransactionImpl, but the guard keeps the
    // path safe if a query ever runs outside an active transaction.
    if (!(currentTx instanceof FrontendTransactionImpl tx)) {
      return executeUncached(statement, args);
    }
    var cache = tx.getQueryResultCache();
    // Feature off (null cache) or re-entrant call: run the plain uncached path. The tx-level
    // re-entrancy depth brackets the whole lookup-and-view scope below (and, via the view, the lazy
    // stream pull during iteration), so any query() launched while we are inside it sees depth > 0
    // here and never touches the cache. The non-determinism and shape walks deliberately do NOT run
    // here: they descend the full AST, so running them ahead of the lookup would re-pay the
    // classification cost on every cache HIT — the exact work the cache exists to save. They run
    // only on the miss/populate branch below, where the result is needed to build the entry; a hit
    // trusts the stored entry's shape because the statement was already proven deterministic and
    // RECORD/K0_NONE-shaped when that entry was populated.
    if (cache == null
        || tx.getCacheCodeDepth() > 0
        || !(statement instanceof SQLSelectStatement || statement instanceof SQLMatchStatement)) {
      return executeUncached(statement, args);
    }

    var key = (args instanceof Map)
        ? CacheKey.forParams(statement, asParamMap(args))
        : CacheKey.forArgs(statement, (Object[]) args);

    // A key already routed out of the cache (entry overflowed its size cap, or a K0_NONE entry
    // exceeded its re-population strike limit) stays uncached for the rest of the transaction; do
    // not build a doomed entry.
    if (cache.isNonCacheable(key)) {
      return executeUncached(statement, args);
    }

    // Two-guard bracketing: bump the tx-level depth around the entire lookup-and-view scope so a
    // nested query() (e.g. a UDF in WHERE) bypasses the cache; the cache's own inFlightLookup guards
    // the lookup call. Decremented in the finally so an exception mid-lookup still restores the
    // depth. The view extends this bracket across its own iteration (see CachedResultSetView), so
    // the guard is not released here for the hit/miss view returned below — only the bare-lookup
    // window is.
    tx.enterCacheCode();
    boolean viewOwnsGuard = false;
    try {
      var hit = cache.lookup(key, tx.getMutationVersion());
      if (hit != null) {
        // Hit: the stored entry was proven deterministic and RECORD/K0_NONE at populate, so neither
        // the non-determinism walk nor the shape classifier runs again — the entry carries its shape.
        var view = buildView(hit, tx, args);
        viewOwnsGuard = true;
        return view;
      }
      // Miss: now (and only now) pay for the AST analysis needed to decide whether to populate an
      // entry. A non-deterministic statement or an unwired shape bypasses the cache uncached.
      if (NonDeterministicQueryDetector.containsNonDeterministicReference(statement)) {
        return executeUncached(statement, args);
      }
      var shape = ShapeClassifier.classify(statement);
      if (isAggregateShape(shape) && statement instanceof SQLSelectStatement select) {
        // AGGREGATE_* shapes take a separate populate path: the side-tap must observe every
        // contributing record, so the miss builds a per-execution plan copy, splices the tap upstream
        // of the aggregation step, and eager-drives it (RECORD/K0_NONE lazy-pull cannot seed an
        // aggregate). On any unexpected plan shape (e.g. a hardwired CountFromClassStep) it falls back
        // to uncached execution and counts a splice failure. The two-guard contract is preserved: a
        // built CachedResultSetView owns the cache-code guard, an uncached fallback leaves
        // viewOwnsGuard false so the finally below releases it — identical to the RECORD path.
        var aggregateResult =
            populateAndBuildAggregateView(select, args, key, shape, tx, cache);
        viewOwnsGuard = aggregateResult instanceof CachedResultSetView;
        return aggregateResult;
      }
      if (shape != CacheableShape.RECORD && shape != CacheableShape.K0_NONE) {
        // MATCH_TUPLE_MULTI is classified but its delta/view path is not wired yet; route it to
        // uncached execution until that path lands.
        return executeUncached(statement, args);
      }
      var result = populateAndBuildView(statement, args, key, shape, tx, cache);
      // Transfer the guard to the view only when one was actually built. The populate path has a
      // fallback that returns the unwrapped uncached result when execution did not yield a
      // LocalResultSet (no entry, no view): on that branch no view exists to release the guard, so
      // ownership must NOT transfer or the finally would skip release and leak the depth bump for the
      // rest of the transaction. A built view is always a CachedResultSetView, so the instanceof check
      // distinguishes the two outcomes exactly.
      viewOwnsGuard = result instanceof CachedResultSetView;
      return result;
    } finally {
      // Release the bare-lookup guard only when no view took ownership of it. When a view was
      // returned, the view holds the guard for its iteration lifetime and releases it on close /
      // exhaustion (paired with its entry pin); releasing here would reopen the cache to a
      // re-entrant query() while the view is still being consumed. On the populate fallback path
      // (no view built) viewOwnsGuard stays false, so the guard is released here exactly once.
      if (!viewOwnsGuard) {
        tx.exitCacheCode();
      }
    }
  }

  /** Runs the ordinary uncached execution path, dispatching on the parameter shape. */
  private ResultSet executeUncached(@Nonnull SQLStatement statement, @Nullable Object args) {
    if (args instanceof Map) {
      return statement.execute(this, asParamMap(args), true);
    }
    return statement.execute(this, (Object[]) args, true);
  }

  @SuppressWarnings("unchecked")
  private static Map<Object, Object> asParamMap(@Nonnull Object args) {
    return (Map<Object, Object>) args;
  }

  /**
   * Populates a fresh cache entry for a miss: stamps the populate mutation version before driving the
   * executor (so the delta builder later admits only post-populate mutations), runs the real
   * execution, lifts the live stream and plan off the resulting {@link LocalResultSet}, wraps the
   * stream in an {@link IdempotentExecutionStream}, stores the entry, and returns a view over it. The
   * entry is the sole owner of the paused stream and its plan, closing both on evict or tx-end; the
   * view never closes the stream. The wrapper is also threaded back into the {@code LocalResultSet}'s
   * stream slot, but that {@code LocalResultSet} is orphaned here (never returned, never registered in
   * the active-query set), so its close never fires — the wrapper's idempotency is a defensive guard
   * against a future second owner, not a live double-close path (see {@link IdempotentExecutionStream}
   * and {@link CachedEntry#close()}).
   */
  private ResultSet populateAndBuildView(
      @Nonnull SQLStatement statement,
      @Nullable Object args,
      @Nonnull CacheKey key,
      @Nonnull CacheableShape shape,
      @Nonnull FrontendTransactionImpl tx,
      @Nonnull QueryResultCache cache) {
    // Stamp before execution: the LocalResultSet constructor calls plan.start(), so the populate
    // version must be captured first so the delta builder later filters in only post-populate ops.
    var populateMutationVersion = tx.getMutationVersion();

    var original = executeUncached(statement, args);
    // SELECT/MATCH execution returns a LocalResultSet; if a future shape returns something else, the
    // cache cannot lift its stream, so fall back to returning the uncached result unwrapped. The
    // cache.put below is the only mutation of cache state on this path and runs after this gate, so
    // this early return leaves the cache untouched (only the miss already counted by lookup) — no
    // half-built entry, no dangling liveViewCount. The aggregate-splice fallback in a later track
    // inherits the same contract.
    if (!(original instanceof LocalResultSet localResult)) {
      return original;
    }

    var plan = localResult.getInternalExecutionPlan();
    var ctx = plan.getContext();

    // A single-alias MATCH folds onto the RECORD path (Etap A): the executor stream yields projected
    // RETURN tuples, but the cache must store raw, RID-identifiable records so the RECORD skip-set /
    // sorted-merge stay RID-addressable. Map the projected stream back to raw single-record rows and
    // carry the RETURN projector on the entry; the view re-derives the tuple at the emit boundary.
    //
    // Scope this fold strictly to the RECORD shape. A single-alias MATCH can still classify K0_NONE
    // (statement SKIP/LIMIT, while:/maxDepth:/optional:), and the K0_NONE replay path
    // (CachedResultSetView.computeNextK0None) never applies a returnProjector — it emits stored rows
    // verbatim. Installing the raw-record mapper + projector on a K0_NONE entry would store raw
    // entities and then emit them unprojected, so the view would return raw records instead of the
    // RETURN tuple a fresh execution yields. Gating on shape==RECORD keeps a K0_NONE single-alias MATCH
    // on the normal replay path, where it stores and emits the executor's real RETURN tuples.
    var matchOrigin =
        (shape == CacheableShape.RECORD && statement instanceof SQLMatchStatement match)
            ? singleAliasOrigin(match) : null;
    ExecutionStream lifted = localResult.getStream();
    if (matchOrigin != null) {
      var alias = matchOrigin.getAlias();
      // A single-alias MATCH always names its bound alias; without one the projector/mapper cannot key
      // the record, so this would be a classify/populate divergence rather than a recoverable shape.
      assert alias != null : "single-alias MATCH origin has no alias";
      lifted = lifted.map(rawAliasRecordMapper(alias));
    }
    var wrapped = new IdempotentExecutionStream(lifted);
    // Thread the wrapper back into the LocalResultSet's stream slot too. That LocalResultSet is
    // orphaned (not returned, not registered in activeQueries), so its close never fires; the entry
    // is the sole closer today. The wrapper's idempotency is the defensive guard for a future second
    // owner, not a live double-close path.
    localResult.setStream(wrapped);

    var entry = new CachedEntry(
        shape,
        effectiveFromClasses(statement),
        whereClauseOf(statement),
        orderByOf(statement),
        wrapped,
        plan,
        ctx,
        populateMutationVersion);
    if (matchOrigin != null && statement instanceof SQLMatchStatement match) {
      assert shape == CacheableShape.RECORD : "Etap-A projector installed on a non-RECORD entry";
      entry.setReturnProjector(buildMatchReturnProjector(match, matchOrigin, args));
    }
    cache.put(key, entry);

    return buildView(entry, tx, args);
  }

  /** Whether a classified shape is one of the {@code AGGREGATE_*} family the aggregate path serves. */
  private static boolean isAggregateShape(@Nonnull CacheableShape shape) {
    return shape == CacheableShape.AGGREGATE_COUNT
        || shape == CacheableShape.AGGREGATE_SUM
        || shape == CacheableShape.AGGREGATE_AVG
        || shape == CacheableShape.AGGREGATE_MIN
        || shape == CacheableShape.AGGREGATE_MAX
        || shape == CacheableShape.AGGREGATE_COUNT_DISTINCT;
  }

  /**
   * Populates a fresh cache entry for an aggregate-shape miss and returns a view over it, or falls back
   * to a plain uncached execution (counting a splice failure) on any unexpected plan shape.
   *
   * <p>Unlike the RECORD/K0_NONE populate, the aggregate path cannot reuse {@link
   * #populateAndBuildView}: a collapsed aggregate result carries only the scalar, so the per-RID
   * material a delta replay needs must be seeded by a side-tap that observes every contributing record
   * before the aggregation collapses them. The path therefore builds its own per-execution plan copy
   * ({@code select.createExecutionPlan} returns a private {@code copy(ctx)} or a fresh plan, never the
   * shared cached instance, so rewiring its {@code prev} pointers cannot corrupt other callers), finds
   * the aggregation step, splices an {@link AggregateCacheTapStep} upstream of it, and eager-drives the
   * plan to full drain so the tap observes every contributor. The single resulting scalar is fully
   * computed at populate, so the entry holds no live stream (its stream/plan/ctx are {@code null} on the
   * {@link CachedEntry}).
   *
   * <p>It independently re-mirrors the three Track-1 populate contracts: stamp the populate mutation
   * version BEFORE driving so the delta builder admits only post-populate ops; release the cache-code
   * guard on every exit (the caller's {@code viewOwnsGuard} transfer covers the view-built path, this
   * method's own fallbacks return an uncached result so the guard is released by the caller's finally);
   * and close the plan idempotently (the outer finally closes it, the entry holds no stream to close).
   *
   * @param select the parsed single-aggregate SELECT (already shape-gated by the caller)
   */
  private ResultSet populateAndBuildAggregateView(
      @Nonnull SQLSelectStatement select,
      @Nullable Object args,
      @Nonnull CacheKey key,
      @Nonnull CacheableShape shape,
      @Nonnull FrontendTransactionImpl tx,
      @Nonnull QueryResultCache cache) {
    var metadata = ShapeClassifier.aggregateMetadata(select);
    if (metadata == null || metadata.kind() != shape) {
      // The classifier accepted the shape but the metadata derivation found no clean single-aggregate
      // (e.g. a computed-expression argument that classify routed elsewhere): there is nothing to seed,
      // so run uncached and count a splice failure.
      cache.incrementSpliceFailures();
      return executeUncached(select, args);
    }

    // Build a command context mirroring SQLSelectStatement.execute's setup so :param bindings resolve
    // identically when the side-tap drives the plan.
    var ctx = freshContext(args);

    // Stamp BEFORE building/driving the plan so the delta builder later admits only post-populate ops.
    var populateMutationVersion = tx.getMutationVersion();

    var plan = select.createExecutionPlan(ctx, false);
    if (!(plan instanceof SelectExecutionPlan selectPlan)) {
      plan.close();
      cache.incrementSpliceFailures();
      return executeUncached(select, args);
    }

    var aggregateStep = findAggregateStep(selectPlan);
    if (aggregateStep == null) {
      // No AggregateProjectionCalculationStep to splice above — e.g. a bare or single-field-indexed
      // COUNT(*) the planner hardwired to a CountFromClassStep. Fall back uncached and count the splice
      // failure (the global splice-failure rate picks it up through the metric bridge).
      selectPlan.close();
      cache.incrementSpliceFailures();
      return executeUncached(select, args);
    }

    var state = new AggregateState(metadata.kind(), metadata.propertyName(), metadata.alias());
    // Install the contributor cap BEFORE driving so an over-cap populate routes the key non-cacheable
    // mid-drive; the put below is then a no-op (the cache's nonCacheableKeys guard closes the entry).
    cache.installAggregateOverflowGuard(key, state);
    spliceTap(aggregateStep, new AggregateCacheTapStep(state, ctx));

    try {
      // Eager-drive: pull the plan's single-row stream to exhaustion. The aggregation is blocking, so
      // draining it forces every upstream (tapped) record to be observed before the scalar is emitted.
      var stream = plan.start();
      try {
        while (stream.hasNext(ctx)) {
          stream.next(ctx);
        }
      } finally {
        stream.close(ctx);
      }
    } finally {
      // The entry holds no live stream (the scalar is fully computed), so the plan is closed here and
      // the entry is built with null stream/plan/ctx. Idempotent close mirrors the uncached path.
      plan.close();
    }

    // The scalar is fully in the seeded state; the entry carries no stream/plan/ctx to lazy-pull.
    var entry = new CachedEntry(
        shape,
        effectiveFromClasses(select),
        select.getWhereClause(),
        select.getOrderBy(),
        null,
        null,
        null,
        populateMutationVersion);
    entry.setAggregateState(state);
    // put is a no-op if the cap already routed the key non-cacheable during the drive; otherwise it
    // stores the entry. Either way the view below is built directly over this entry's seeded state.
    cache.put(key, entry);

    return buildView(entry, tx, args);
  }

  /**
   * Finds the aggregation step the side-tap splices above. Returns the first {@link
   * AggregateProjectionCalculationStep} in the plan's step chain, or {@code null} when the plan has none
   * (the hardwired-COUNT fallback case). The plan is a SELECT plan, so a present aggregation step is the
   * single one the planner emits for a single-aggregate query.
   */
  @Nullable private static AggregateProjectionCalculationStep findAggregateStep(
      @Nonnull SelectExecutionPlan plan) {
    for (var step : plan.getSteps()) {
      if (step instanceof AggregateProjectionCalculationStep aggregateStep) {
        return aggregateStep;
      }
    }
    return null;
  }

  /**
   * Splices {@code tap} into the plan chain immediately upstream of the aggregation step. When a
   * pre-aggregate {@link ProjectionCalculationStep} sits directly above the aggregation (every value
   * aggregate carries one, projecting the per-row field), the tap is spliced ABOVE that projection: the
   * projection strips record identity, and {@link AggregateState#observe} requires a non-null RID, so the
   * tap must see the raw filtered records. The projection still sits between the tap and the aggregation,
   * leaving the collapsed scalar unchanged. Otherwise (e.g. COUNT(*) over a WHERE, no pre-aggregate
   * projection) the tap is spliced directly upstream of the aggregation.
   */
  private static void spliceTap(
      @Nonnull AggregateProjectionCalculationStep aggregateStep,
      @Nonnull AggregateCacheTapStep tap) {
    // AggregateProjectionCalculationStep extends ProjectionCalculationStep, so the instanceof must
    // exclude the aggregation step itself; only a *plain* pre-aggregate projection is the splice-above
    // case. Both step types expose prev through AbstractExecutionStep.
    ExecutionStepInternal spliceBelow = aggregateStep;
    var prev = aggregateStep.prev;
    if (prev instanceof ProjectionCalculationStep
        && !(prev instanceof AggregateProjectionCalculationStep)) {
      spliceBelow = (AbstractExecutionStep) prev;
    }
    var below = (AbstractExecutionStep) spliceBelow;
    var upstream = below.prev;
    tap.setPrevious(upstream);
    if (upstream != null) {
      upstream.setNext(tap);
    }
    below.setPrevious(tap);
    tap.setNext(below);
  }

  /**
   * Builds the consumer-facing view over a cached entry. RECORD entries carry a {@link TxDeltaCursor}
   * reconciling post-populate mutations; K0_NONE entries replay directly (the lookup-time version
   * gate already proved no mutation happened since populate, so there is nothing to merge). The view's
   * command context for the merge is the entry's populate-time context when still live, else a fresh
   * context with the same parameter bindings. Rebuilding from params alone is sound for every shape
   * that reaches the delta path: only RECORD-classified plain SELECTs build a delta, and a statement
   * referencing any per-row context variable ({@code $current}, {@code $parent}, a {@code LET}
   * binding, ...) is excluded upstream — the non-determinism detector bypasses {@code $}-prefixed
   * identifiers, and the shape classifier routes {@code LET} to K0_NONE (which builds no delta). So
   * the WHERE re-eval and ORDER BY {@code compare} that run against the rebuilt context never read any
   * context state beyond the {@code :param} bindings, which the rebuild reproduces exactly. A future
   * shape broadening that lets a context-variable query reach the delta path must revisit this (the
   * populate context's variables would then need to be carried, not just its params).
   */
  private ResultSet buildView(
      @Nonnull CachedEntry entry, @Nonnull FrontendTransactionImpl tx, @Nullable Object args) {
    var ctx = entry.getCtx();
    if (ctx == null) {
      // The entry's stream has been exhausted and its context released; rebuild an equivalent context
      // so ORDER BY comparison and WHERE re-evaluation still resolve the same :param bindings. Sound
      // because the wired delta-path shapes carry no non-param context state (see method Javadoc).
      ctx = freshContext(args);
    }
    // The view takes ownership of the open cache-code guard (entered in serveThroughCache) and holds
    // it for its whole iteration lifetime, releasing it exactly once on close / exhaustion. This
    // keeps a re-entrant query() — e.g. a UDF in WHERE evaluated during the lazy stream pull, which
    // happens after serveThroughCache has returned — bypassed for the entire view consumption, not
    // just the synchronous lookup window.
    if (isAggregateShape(entry.getShape())) {
      // Aggregate: copy the seeded state and replay the post-populate mutations onto the copy; the view
      // emits the single replayed scalar. The entry holds no stream, so the view's plan slot is null.
      var aggregateDelta = DeltaBuilder.buildForAggregate(entry, tx, ctx);
      return CachedResultSetView.forAggregate(entry, aggregateDelta, this, tx, entry.getPlan(),
          ctx);
    }
    TxDeltaCursor delta = entry.getShape() == CacheableShape.RECORD
        ? DeltaBuilder.buildForRecord(entry, tx, ctx)
        : null;
    return new CachedResultSetView(entry, delta, this, tx, entry.getPlan(), ctx);
  }

  /** A command context carrying the query's parameter bindings, mirroring the executor's setup. */
  private CommandContext freshContext(@Nullable Object args) {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(this);
    Map<Object, Object> params = new HashMap<>();
    if (args instanceof Map) {
      params.putAll(asParamMap(args));
    } else if (args instanceof Object[] positional) {
      for (var i = 0; i < positional.length; i++) {
        params.put(i, positional[i]);
      }
    }
    ctx.setInputParameters(params);
    return ctx;
  }

  /**
   * The subclass closure of the statement's read classes for the entry's delta class filter. A
   * plain SELECT resolves its FROM target class; a single-alias MATCH (Etap A) resolves its one
   * bound alias's class so the RECORD delta filter is non-empty and reconciles in-tx mutations;
   * anything without a resolvable single class target (subquery target, RID target, multi-alias
   * MATCH) yields the empty set, so the delta filter matches nothing and the entry behaves as a pure
   * replay — correct because only RECORD-classified plain SELECTs and single-alias MATCH reach the
   * delta path here.
   */
  private Set<String> effectiveFromClasses(@Nonnull SQLStatement statement) {
    if (statement instanceof SQLSelectStatement select && select.getTarget() != null) {
      var item = select.getTarget().getItem();
      if (item != null) {
        SchemaClass fromClass = item.getSchemaClass(this);
        return CachedEntry.computeEffectiveFromClasses(fromClass);
      }
    }
    if (statement instanceof SQLMatchStatement match) {
      var origin = singleAliasOrigin(match);
      if (origin != null) {
        var className = origin.getClassName(null);
        if (className != null) {
          SchemaClass fromClass = getMetadata().getImmutableSchemaSnapshot().getClass(className);
          return CachedEntry.computeEffectiveFromClasses(fromClass);
        }
      }
    }
    return Set.of();
  }

  @Nullable private SQLWhereClause whereClauseOf(@Nonnull SQLStatement statement) {
    if (statement instanceof SQLSelectStatement select) {
      return select.getWhereClause();
    }
    if (statement instanceof SQLMatchStatement match) {
      var origin = singleAliasOrigin(match);
      // The single bound alias's pattern WHERE re-evaluates against a mutated record at delta-build,
      // exactly like a plain SELECT's WHERE; its properties are unqualified relative to the alias, so
      // it reads the raw record directly. A null (no where:) matches every record.
      return origin == null ? null : origin.getFilter();
    }
    return null;
  }

  @Nullable private SQLOrderBy orderByOf(@Nonnull SQLStatement statement) {
    if (statement instanceof SQLSelectStatement select) {
      return select.getOrderBy();
    }
    if (statement instanceof SQLMatchStatement match) {
      // The statement ORDER BY ranks the projected RETURN tuples (e.g. ORDER BY u.name). The view and
      // delta builder compare projected merge heads, so the entry carries it verbatim; the classifier
      // already proved every ORDER BY item is record-local for the single-alias fold.
      return singleAliasOrigin(match) == null ? null : match.getOrderBy();
    }
    return null;
  }

  /**
   * The origin vertex node of a single-alias MATCH (one match expression, no traversal edge), or
   * {@code null} when the statement is multi-alias or not a single-binding shape. Mirrors the
   * single-alias test the shape classifier uses, evaluated here with the session available so the
   * three populate helpers above resolve the alias's class / WHERE / ORDER BY consistently.
   */
  @Nullable private static SQLMatchFilter singleAliasOrigin(@Nonnull SQLMatchStatement match) {
    var expressions = match.getMatchExpressions();
    if (expressions.size() != 1) {
      return null;
    }
    SQLMatchExpression expr = expressions.getFirst();
    var origin = expr.getOrigin();
    if (origin == null || !expr.getItems().isEmpty()) {
      return null;
    }
    return origin;
  }

  /**
   * Builds the Etap-A RETURN projector for a single-alias MATCH: a transform that takes a raw cached
   * or inject record row (an identifiable {@link Result} wrapping the single bound record), wraps it
   * back into the {@code {alias -> record}} shape the MATCH executor's first step produces, and runs
   * the RETURN projection over it so the emitted row is the RETURN tuple a fresh MATCH would yield
   * (e.g. {@code RETURN u, u.name}). The projection is rebuilt from the statement's RETURN items the
   * same way the MATCH planner builds it, then applied per row. The command context carries the
   * query's parameter bindings so a parameterized RETURN/projection resolves identically.
   */
  private Function<Result, Result> buildMatchReturnProjector(
      @Nonnull SQLMatchStatement match, @Nonnull SQLMatchFilter origin, @Nullable Object args) {
    var alias = origin.getAlias();
    var returnItems = match.getReturnItems();
    var returnAliases = match.getReturnAliases();
    var returnNested = match.getReturnNestedProjections();
    var projectionItems = new ArrayList<SQLProjectionItem>(returnItems.size());
    for (var i = 0; i < returnItems.size(); i++) {
      SQLIdentifier itemAlias = i < returnAliases.size() ? returnAliases.get(i) : null;
      SQLNestedProjection nested = i < returnNested.size() ? returnNested.get(i) : null;
      projectionItems.add(new SQLProjectionItem(returnItems.get(i), itemAlias, nested));
    }
    var projection = new SQLProjection(projectionItems, false);
    var projectorCtx = freshContext(args);
    return rawRow -> {
      // The raw row's own record is the single bound record (the cache stores raw ResultInternal rows
      // wrapping it, and the delta builder injects new ResultInternal(session, record) rows the same
      // way). Re-wrap it into the alias-keyed { alias -> record } row the MATCH executor's first step
      // emits, then run the RETURN projection over it.
      var entity = rawRow.asEntityOrNull();
      // A well-formed single-alias MATCH binds exactly one class-constrained record per row, so the raw
      // cached / injected row always carries that record. A null here means the binding vanished
      // between stream production and projection (a deleted/unreadable record the RID skip-set should
      // have suppressed) — an edge the design treats as not-reached. Fail loudly rather than emit a
      // non-identifiable empty tuple (new ResultInternal(session, null) leaves identifiable null), which
      // would be a silent wrong row; a future shape-broadening that lets a null binding through must
      // surface here rather than corrupt the result.
      if (entity == null) {
        throw new IllegalStateException(
            "single-alias MATCH RETURN projector found no bound record for alias " + alias);
      }
      var aliasRow = new ResultInternal(this);
      aliasRow.setProperty(alias, entity);
      return projection.calculateSingle(projectorCtx, aliasRow, false);
    };
  }

  /**
   * Maps a populated single-alias MATCH stream row (the executor's projected RETURN tuple, which
   * still carries the bound record under the alias key) back to a raw, RID-identifiable single-record
   * {@link Result}. Storing the raw record — not the projected tuple — keeps the RECORD skip-set /
   * sorted-merge RID-addressable; the {@code returnProjector} re-derives the tuple at the view emit
   * boundary. The stream is already ordered by the statement ORDER BY (the executor sorted the
   * projected tuples), so the raw-record cache stream stays in the same projected order.
   */
  @Nonnull
  private ResultMapper rawAliasRecordMapper(@Nonnull String alias) {
    return (projectedRow, ctx) -> {
      var entity = projectedRow.getEntity(alias);
      // A single-alias MATCH binds exactly one record per row; the alias key always resolves to it. A
      // null binding would make new ResultInternal(session, null) a non-identifiable empty row, which
      // the RID skip-set cannot address and which emits as a silent wrong tuple. Fail loudly instead so
      // a future shape-broadening that lets a null binding through surfaces here rather than corrupting
      // the cached result.
      if (entity == null) {
        throw new IllegalStateException(
            "single-alias MATCH row carries no record under alias " + alias);
      }
      return new ResultInternal(this, entity);
    };
  }

  /**
   * Bulk-DML cache invalidation hook. A {@code TRUNCATE CLASS} run mid-transaction removes stored
   * records without flowing through {@code addRecordOperation}, so the cache cannot see the change via
   * the delta build and must drop every entry. Called per statement from both the single-statement
   * command path ({@code executeInternal}) and the script path ({@code SqlScriptExecutor}), so a
   * TRUNCATE embedded in a script invalidates a sibling {@code query()}'s cached entry the same way the
   * direct command does. Regular INSERT/UPDATE/DELETE need no hook here: they land in {@code
   * recordOperations} and the next query's delta build picks them up.
   * Schema DDL (CREATE/DROP/ALTER CLASS|PROPERTY|INDEX) is unreachable mid-transaction (the schema is
   * immutable for the life of a transaction), guarded by a {@code Java assert} canary so a future
   * relaxation that lets schema DDL run mid-tx surfaces loudly in test builds rather than silently
   * serving a stale cache.
   */
  public void invalidateCacheForBulkDml(@Nonnull SQLStatement statement) {
    // executeInternal does not begin a transaction, so currentTx may be a no-tx placeholder
    // (FrontendTransactionNoTx) with no cache to invalidate; only a real transaction carries one.
    if (!(currentTx instanceof FrontendTransactionImpl tx)) {
      return;
    }
    var cache = tx.getQueryResultCache();
    if (cache == null) {
      return;
    }
    if (statement instanceof SQLTruncateClassStatement) {
      cache.invalidateAll();
      return;
    }
    // Schema-immutability canary: schema DDL must not reach the cache hook while a transaction is
    // active. TRUNCATE CLASS (handled above) is the only legitimately mid-tx-runnable DDL; every
    // other schema-DDL statement throws before any cache effect would matter, so reaching here is a
    // contract breach.
    assert !isSchemaDdl(statement)
        : "Schema DDL reached the tx-result cache hook while a transaction was active: "
            + statement.getClass().getSimpleName();
  }

  /** Whether the statement is a schema-DDL statement (CREATE/DROP/ALTER CLASS|PROPERTY|INDEX). */
  private static boolean isSchemaDdl(@Nonnull SQLStatement statement) {
    return statement instanceof SQLCreateClassStatement
        || statement instanceof SQLDropClassStatement
        || statement instanceof SQLAlterClassStatement
        || statement instanceof SQLCreatePropertyStatement
        || statement instanceof SQLDropPropertyStatement
        || statement instanceof SQLAlterPropertyStatement
        || statement instanceof SQLCreateIndexStatement
        || statement instanceof SQLDropIndexStatement;
  }

  public ResultSet execute(String query, Object... args) {
    return executeInternal(query, null, args);
  }

  public ResultSet execute(String query, @SuppressWarnings("rawtypes") Map args) {
    return executeInternal(query, null, args);
  }

  public ResultSet execute(SQLStatement statement, Map<?, ?> args) {
    return executeInternal(null, statement, args);
  }

  @SuppressWarnings("unchecked")
  private ResultSet executeInternal(
      String stringStatement,
      SQLStatement parsedStatement,
      Object args) {

    assert assertIfNotActive();

    checkOpenness();

    if (currentTx.isCallBackProcessingInProgress()) {
      throw new CommandExecutionException(getDatabaseName(),
          "Cannot execute SQL command while transaction processing callbacks. If you called this method in beforeCallbackXXX method "
              +
              "please move it to the afterCallbackXXX method");
    }
    try {
      currentTx.preProcessRecordsAndExecuteCallCallbacks();
      getSharedContext().getYouTrackDB().startCommand(null);
      try {
        var statement =
            (parsedStatement != null) ? parsedStatement : SQLEngine.parse(stringStatement, this);
        invalidateCacheForBulkDml(statement);
        ResultSet original;
        switch (args) {
          case Map<?, ?> map -> {
            original = statement.execute(this, (Map<Object, Object>) map, true);
          }
          case Object[] objects -> original = statement.execute(this, objects, true);
          case null -> original = statement.execute(this, (Object[]) null, true);
          default -> original = statement.execute(this, new Object[] {args}, true);
        }

        if (!statement.isIdempotent()) {
          // fetch all, close and detach
          var prefetched = new InternalResultSet(this);
          original.forEachRemaining(prefetched::add);
          original.close();
          var result = new LocalResultSetLifecycleDecorator(prefetched);
          return result;
        } else {
          // stream, keep open and attach to the current DB
          return queryStartedLifecycle(original);
        }
      } finally {
        getSharedContext().getYouTrackDB().endCommand();
      }
    } catch (Exception e) {
      rollback();
      throw e;
    }
  }

  public ResultSet computeScript(String language, String script, Object... args) {
    assert assertIfNotActive();

    checkOpenness();

    if (currentTx.isCallBackProcessingInProgress()) {
      throw new CommandExecutionException(getDatabaseName(),
          "Cannot execute SQL script while transaction processing callbacks. If you called this method in beforeCallbackXXX method "
              +
              "please move it to the afterCallbackXXX method");
    }
    try {
      if (!"sql".equalsIgnoreCase(language)) {
        checkSecurity(ResourceGeneric.COMMAND, Role.PERMISSION_EXECUTE, language);
      }
      currentTx.preProcessRecordsAndExecuteCallCallbacks();
      getSharedContext().getYouTrackDB().startCommand(null);
      try {
        var executor =
            getSharedContext()
                .getYouTrackDB()
                .getScriptManager()
                .getCommandManager()
                .getScriptExecutor(language);

        this.storage.pauseConfigurationUpdateNotifications();
        ResultSet original;
        try {
          original = executor.execute(this, script, args);
        } finally {
          this.storage.fireConfigurationUpdateNotifications();
        }
        return queryStartedLifecycle(original);
      } finally {
        getSharedContext().getYouTrackDB().endCommand();
      }
    } catch (Exception e) {
      rollback();
      throw e;
    }
  }

  /**
   * Registers the result set for lifecycle tracking. If the result set is already a
   * {@link LocalResultSet}, adds lifecycle tracking directly to avoid the decorator
   * overhead. Otherwise, wraps in a {@link LocalResultSetLifecycleDecorator}.
   */
  private ResultSet queryStartedLifecycle(ResultSet original) {
    if (original instanceof LocalResultSet localResult) {
      this.queryStarted(localResult.getQueryId(), localResult);
      localResult.addLifecycleListener(this);
      return localResult;
    } else {
      var result = new LocalResultSetLifecycleDecorator(original);
      this.queryStarted(result.getQueryId(), result);
      result.addLifecycleListener(this);
      return result;
    }
  }

  public ResultSet computeScript(String language, String script, Map<String, ?> args) {
    assert assertIfNotActive();

    checkOpenness();

    if (currentTx.isCallBackProcessingInProgress()) {
      throw new CommandExecutionException(getDatabaseName(),
          "Cannot execute SQL script while transaction processing callbacks. If you called this method in beforeCallbackXXX method "
              +
              "please move it to the afterCallbackXXX method");
    }
    if (!"sql".equalsIgnoreCase(language)) {
      checkSecurity(ResourceGeneric.COMMAND, Role.PERMISSION_EXECUTE, language);
    }

    try {
      currentTx.preProcessRecordsAndExecuteCallCallbacks();
      getSharedContext().getYouTrackDB().startCommand(null);
      try {
        var executor =
            sharedContext
                .getYouTrackDB()
                .getScriptManager()
                .getCommandManager()
                .getScriptExecutor(language);
        ResultSet original;

        this.storage.pauseConfigurationUpdateNotifications();
        try {
          original = executor.execute(this, script, args);
        } finally {
          this.storage.fireConfigurationUpdateNotifications();
        }

        return queryStartedLifecycle(original);
      } finally {
        getSharedContext().getYouTrackDB().endCommand();
      }
    } catch (Exception e) {
      rollback();
      throw e;
    }

  }

  public ResultSet query(ExecutionPlan plan, Map<Object, Object> params) {
    assert assertIfNotActive();

    checkOpenness();

    if (currentTx.isCallBackProcessingInProgress()) {
      throw new CommandExecutionException(getDatabaseName(),
          "Cannot execute query transaction processing callbacks. If you called this method in beforeCallbackXXX method "
              +
              "please move it to the afterCallbackXXX method");
    }

    try {
      currentTx.preProcessRecordsAndExecuteCallCallbacks();
      getSharedContext().getYouTrackDB().startCommand(null);
      try {
        var ctx = new BasicCommandContext();
        ctx.setDatabaseSession(this);
        ctx.setInputParameters(params);

        var result = new LocalResultSet(this, (InternalExecutionPlan) plan);
        this.queryStarted(result.getQueryId(), result);
        result.addLifecycleListener(this);
        return result;
      } finally {
        getSharedContext().getYouTrackDB().endCommand();
      }
    } catch (Exception e) {
      rollback();
      throw e;
    }
  }

  public YouTrackDBConfig getConfig() {
    assert assertIfNotActive();
    return config;
  }

  public void addBlobCollection(final String iCollectionName, final Object... iParameters) {
    assert assertIfNotActive();

    checkOpenness();

    int id;
    if (!existsCollection(iCollectionName)) {
      id = addCollection(iCollectionName, iParameters);
    } else {
      id = getCollectionIdByName(iCollectionName);
    }
    getMetadata().getSchema().addBlobCollection(id);
  }

  public Blob newBlob(byte[] bytes) {
    assert assertIfNotActive();

    checkOpenness();

    var blob = new RecordBytes(new ChangeableRecordId(), this, bytes);
    blob.setInternalStatus(RecordElement.STATUS.LOADED);

    var tx = (FrontendTransactionImpl) currentTx;
    tx.addRecordOperation(blob, RecordOperation.CREATED);

    return blob;
  }

  public Blob newBlob() {
    assert assertIfNotActive();

    checkOpenness();

    var blob = new RecordBytes(this, new ChangeableRecordId());
    blob.setInternalStatus(RecordElement.STATUS.LOADED);

    var tx = (FrontendTransactionImpl) currentTx;
    tx.addRecordOperation(blob, RecordOperation.CREATED);

    return blob;
  }

  /**
   * Creates a entity with specific class.
   *
   * @param className the name of class that should be used as a class of created entity.
   * @return new instance of entity.
   */
  public EntityImpl newInstance(final String className) {
    assert assertIfNotActive();

    checkOpenness();

    var cls = getMetadata().getImmutableSchemaSnapshot().getClass(className);
    if (cls == null) {
      throw new IllegalArgumentException("Class " + className + " not found");
    }

    if (cls.isVertexType()) {
      throw new IllegalArgumentException(
          "The class " + cls.getName()
              + " is a vertex type and cannot be used to create an entity, "
              + "please use newVertex() method");
    }
    if (cls.isEdgeType()) {
      throw new IllegalArgumentException(
          "The class " + cls.getName() + " is an edge type and cannot be used to create an entity, "
              + "please use newEdge() method");
    }
    var entity = new EntityImpl(new ChangeableRecordId(), this, className);
    entity.setInternalStatus(RecordElement.STATUS.LOADED);

    currentTx.addRecordOperation(entity, RecordOperation.CREATED);
    //init default property values, can not do that in constructor as it is not registerd in tx
    entity.convertPropertiesToClassAndInitDefaultValues(entity.getImmutableSchemaClass(this));
    return entity;
  }

  public EntityImpl newInternalInstance() {
    assert assertIfNotActive();

    checkOpenness();

    var collectionId = getCollectionIdByName(MetadataDefault.COLLECTION_INTERNAL_NAME);
    var rid = new ChangeableRecordId();

    rid.setCollectionId(collectionId);

    var entity = new EntityImpl(this, rid);
    entity.setInternalStatus(RecordElement.STATUS.LOADED);

    var tx = (FrontendTransactionImpl) currentTx;
    tx.addRecordOperation(entity, RecordOperation.CREATED);

    return entity;
  }

  public EdgeEntityImpl newEdgeInternal(final String className) {
    var cls = getMetadata().getImmutableSchemaSnapshot().getClass(className);
    if (cls == null) {
      throw new IllegalArgumentException("Class " + className + " not found");
    }
    if (!cls.isEdgeType()) {
      throw new IllegalArgumentException(
          "The class " + cls.getName()
              + " is not an edge type and cannot be used to create an edge, "
              + "please use newInstance() method");
    }

    checkSecurity(ResourceGeneric.CLASS, Role.PERMISSION_CREATE, className);
    var edge = new EdgeEntityImpl(new ChangeableRecordId(), this, className);
    currentTx.addRecordOperation(edge, RecordOperation.CREATED);

    edge.convertPropertiesToClassAndInitDefaultValues(edge.getImmutableSchemaClass(this));

    return edge;
  }

  private EdgeInternal addEdgeInternal(
      final Vertex toVertex,
      final Vertex inVertex,
      String className) {
    Objects.requireNonNull(toVertex, "From vertex is null");
    Objects.requireNonNull(inVertex, "To vertex is null");

    EdgeInternal edge;
    EntityImpl outEntity;
    EntityImpl inEntity;

    if (getTransactionInternal().isDeletedInTx(toVertex.getIdentity())) {
      throw new RecordNotFoundException(getDatabaseName(),
          toVertex.getIdentity(), "The vertex " + toVertex.getIdentity() + " has been deleted");
    }

    if (getTransactionInternal().isDeletedInTx(inVertex.getIdentity())) {
      throw new RecordNotFoundException(getDatabaseName(),
          inVertex.getIdentity(), "The vertex " + inVertex.getIdentity() + " has been deleted");
    }

    outEntity = (EntityImpl) toVertex;
    inEntity = (EntityImpl) inVertex;

    Schema schema = getMetadata().getImmutableSchemaSnapshot();
    final var edgeType = schema.getClass(className);

    if (edgeType == null) {
      throw new IllegalArgumentException("Class " + className + " does not exist");
    }

    className = edgeType.getName();

    final var outFieldName = Vertex.getEdgeLinkFieldName(Direction.OUT, className);
    final var inFieldName = Vertex.getEdgeLinkFieldName(Direction.IN, className);

    // All edges are record-based: primary RID is the edge record,
    // secondary RID is the opposite vertex
    var edgeEntity = newEdgeInternal(className);
    var tx = getActiveTransaction();
    edgeEntity.setPropertyInternal(EdgeInternal.DIRECTION_OUT, tx.load(toVertex));
    edgeEntity.setPropertyInternal(Edge.DIRECTION_IN, inEntity);

    // OUT-VERTEX ---> edge record as primary, IN-VERTEX as secondary
    VertexEntityImpl.createLink(this, outEntity, edgeEntity, inEntity, outFieldName);

    // IN-VERTEX ---> edge record as primary, OUT-VERTEX as secondary
    VertexEntityImpl.createLink(this, inEntity, edgeEntity, outEntity, inFieldName);
    edge = edgeEntity;
    // OK

    return edge;
  }

  public Edge newEdge(Vertex from, Vertex to, String type) {
    assert assertIfNotActive();

    checkOpenness();

    var cl = getMetadata().getImmutableSchemaSnapshot().getClass(type);
    if (cl == null || !cl.isEdgeType()) {
      throw new IllegalArgumentException(type + " is not an edge class");
    }
    if (cl.isAbstract()) {
      throw new IllegalArgumentException(
          type + " is an abstract class and cannot be used for edge creation");
    }

    return (Edge) addEdgeInternal(from, to, type);
  }

  @Nullable public RecordAbstract executeReadRecord(
      @Nonnull RecordIdInternal rid,
      @Nullable RawBuffer prefetchedBuffer,
      boolean throwExceptionIfRecordNotFound) {
    checkOpenness();

    getMetadata().makeThreadLocalSchemaSnapshot();
    try {
      checkSecurity(
          ResourceGeneric.COLLECTION,
          Role.PERMISSION_READ,
          getCollectionNameById(rid.getCollectionId()));
      // SEARCH IN LOCAL TX
      var txInternal = getTransactionInternal();
      if (txInternal.isDeletedInTx(rid)) {
        // DELETED IN TX
        return createRecordNotFoundResult(rid, throwExceptionIfRecordNotFound);
      }

      var record = getTransactionInternal().getRecord(rid);
      if (record == null) {
        record = localCache.findRecord(rid);
      }
      if (record != null && record.isUnloaded()) {
        throw new IllegalStateException(
            "Unloaded record with rid " + rid + " was found in local cache");
      }

      if (record != null) {
        if (beforeReadOperations(record)) {
          return createRecordNotFoundResult(rid, throwExceptionIfRecordNotFound);
        }

        afterReadOperations(record);
        if (record instanceof EntityImpl entity) {
          entity.checkClass(this);
        }

        localCache.updateRecord(record, this);

        assert !record.isUnloaded();
        assert record.getSession() == this;

        return record;
      }

      loadedRecordsCount++;

      if (!rid.isValidPosition()) {
        throw new DatabaseException(getDatabaseName(), "Invalid record id " + rid);
      }

      final StorageReadResult readResult;
      if (prefetchedBuffer != null) {
        readResult = prefetchedBuffer;
      } else {
        try {
          var tx = getActiveTransaction();
          readResult = storage.readRecord(rid, tx.getAtomicOperation());
        } catch (RecordNotFoundException e) {
          if (throwExceptionIfRecordNotFound) {
            throw e;
          } else {
            return null;
          }
        }
      }

      if (readResult instanceof RawBuffer recordBuffer) {
        record =
            YouTrackDBEnginesManager.instance()
                .getRecordFactoryManager()
                .newInstance(recordBuffer.recordType(), rid, this);
        final var rec = record;
        rec.unsetDirty();

        if (record.getRecordType() != recordBuffer.recordType()) {
          throw new DatabaseException(getDatabaseName(),
              "Record type is different from the one in the database");
        }

        record.recordSerializer = serializer;
        record.fill(recordBuffer.version(), recordBuffer.buffer(), false);

        if (record instanceof EntityImpl entity) {
          entity.checkClass(this);
        }

        localCache.updateRecord(record, this);
        record.fromStream(recordBuffer.buffer());
      } else if (readResult instanceof RawPageBuffer pageBuffer) {
        // Zero-copy PageFrame path: store PageFrame reference for lazy
        // deserialization at property-access time.
        record =
            YouTrackDBEnginesManager.instance()
                .getRecordFactoryManager()
                .newInstance(pageBuffer.recordType(), rid, this);
        record.unsetDirty();

        if (record.getRecordType() != pageBuffer.recordType()) {
          throw new DatabaseException(getDatabaseName(),
              "Record type is different from the one in the database");
        }

        record.recordSerializer = serializer;

        if (record instanceof EntityImpl entity) {
          entity.fillFromPage(
              pageBuffer.recordVersion(), pageBuffer.recordType(),
              pageBuffer.pageFrame(), pageBuffer.stamp(),
              pageBuffer.contentOffset(), pageBuffer.contentLength());
          entity.checkClass(this);
        } else {
          // Non-EntityImpl (e.g., Blob): extract bytes from PageFrame.
          // toRawBuffer() validates the stamp after byte extraction and throws
          // OptimisticReadFailedException if the page was modified. Retry until
          // we get a consistent byte[] copy.
          var tx = getActiveTransaction();
          var currentResult = (StorageReadResult) pageBuffer;
          while (true) {
            try {
              var rawBuffer = currentResult.toRawBuffer();
              record.fill(rawBuffer.version(), rawBuffer.buffer(), false);
              record.fromStream(rawBuffer.buffer());
              break;
            } catch (OptimisticReadFailedException e) {
              currentResult = storage.readRecord(rid, tx.getAtomicOperation());
            }
          }
        }
        localCache.updateRecord(record, this);
      } else {
        throw new IllegalStateException(
            "Unknown StorageReadResult type: " + readResult.getClass());
      }

      if (beforeReadOperations(record)) {
        return createRecordNotFoundResult(rid, throwExceptionIfRecordNotFound);
      }

      afterReadOperations(record);

      assert !record.isUnloaded();
      assert record.getSession() == this;

      return record;
    } catch (RecordNotFoundException t) {
      throw t;
    } catch (Exception t) {
      if (rid.isTemporary()) {
        throw BaseException.wrapException(
            new DatabaseException(getDatabaseName(),
                "Error on retrieving record using temporary RID: " + rid),
            t, getDatabaseName());
      } else {
        throw BaseException.wrapException(
            new DatabaseException(getDatabaseName(),
                "Error on retrieving record "
                    + rid
                    + " (collection: "
                    + storage.getPhysicalCollectionNameById(rid.getCollectionId())
                    + ")"),
            t, getDatabaseName());
      }
    } finally {
      getMetadata().clearThreadLocalSchemaSnapshot();
    }
  }

  @Nullable private RecordAbstract createRecordNotFoundResult(RecordIdInternal rid,
      boolean throwExceptionIfRecordNotFound) {
    if (throwExceptionIfRecordNotFound) {
      throw new RecordNotFoundException(getDatabaseName(), rid);
    } else {
      return null;
    }
  }

  @SuppressWarnings("unused")
  public Edge newRegularEdge(String iClassName, Vertex from, Vertex to) {
    assert assertIfNotActive();

    checkOpenness();

    var cl = getMetadata().getImmutableSchemaSnapshot().getClass(iClassName);

    if (cl == null || !cl.isEdgeType()) {
      throw new IllegalArgumentException(iClassName + " is not an edge class");
    }

    return addEdgeInternal(from, to, iClassName);
  }

  public EmbeddedEntity newEmbeddedEntity(SchemaClass schemaClass) {
    assert assertIfNotActive();

    checkOpenness();

    return new EmbeddedEntityImpl(schemaClass != null ? schemaClass.getName() : null, this);
  }

  public Vertex newVertex(final String className) {
    assert assertIfNotActive();

    checkOpenness();

    var cls = getMetadata().getImmutableSchemaSnapshot().getClass(className);
    if (cls == null) {
      throw new IllegalArgumentException("Class " + className + " not found");
    }
    if (!cls.isVertexType()) {
      throw new IllegalArgumentException(
          "The class " + cls.getName()
              + " is not a vertex type and cannot be used to create a vertex, "
              + "please use newInstance() method");
    }

    checkSecurity(ResourceGeneric.CLASS, Role.PERMISSION_CREATE, className);
    var vertex = new VertexEntityImpl(new ChangeableRecordId(), this, className);
    currentTx.addRecordOperation(vertex, RecordOperation.CREATED);
    vertex.convertPropertiesToClassAndInitDefaultValues(vertex.getImmutableSchemaClass(this));

    return vertex;
  }

  public EmbeddedEntity newEmbeddedEntity(String schemaClass) {
    assert assertIfNotActive();

    checkOpenness();

    return new EmbeddedEntityImpl(schemaClass, this);
  }

  public EmbeddedEntity newEmbeddedEntity() {
    assert assertIfNotActive();

    checkOpenness();

    return new EmbeddedEntityImpl(this);
  }

  private FrontendTransactionImpl newTxInstance(long txId) {
    assert assertIfNotActive();

    return new FrontendTransactionImpl(this, txId, false);
  }

  private FrontendTransactionImpl newReadOnlyTxInstance(long txId) {
    assert assertIfNotActive();

    return new FrontendTransactionImpl(this, txId, true);
  }

  public void setNoTxMode() {
    if (!(currentTx instanceof FrontendTransactionNoTx)) {
      currentTx = new FrontendTransactionNoTx(this);
    }
  }

  public FrontendTransactionImpl begin() {
    assert assertIfNotActive();

    checkOpenness();

    if (currentTx.isActive()) {
      currentTx.beginInternal();
      return (FrontendTransactionImpl) currentTx;
    }

    begin(newTxInstance(FrontendTransactionImpl.generateTxId()));
    return (FrontendTransactionImpl) currentTx;
  }

  public void beginReadOnly() {
    assert assertIfNotActive();

    checkOpenness();

    if (currentTx.isActive()) {
      return;
    }

    begin(newReadOnlyTxInstance(FrontendTransactionImpl.generateTxId()));
  }

  private void init() {
    assert assertIfNotActive();
    currentTx = new FrontendTransactionNoTx(this);
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
    assert assertIfNotActive();

    checkOpenness();

    record.delete();
  }

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
        checkSecurity(ResourceGeneric.CLASS, Role.PERMISSION_CREATE, clazz.getName());
        if (clazz.isUser()) {
          entity.validate();
        } else if (clazz.isFunction()) {
          FunctionLibraryImpl.validateFunctionRecord(entity);
        }

        entity.propertyEncryption = PropertyEncryptionNone.instance();
      }
    }

    callbackHooks(TYPE.BEFORE_CREATE, recordAbstract);
  }

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

    callbackHooks(TYPE.AFTER_CREATE, recordAbstract);
  }

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

    callbackHooks(TYPE.BEFORE_UPDATE, recordAbstract);
  }

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

  public void beforeDeleteOperations(final RecordAbstract recordAbstract,
      String collectionName) {
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

    callbackHooks(TYPE.BEFORE_DELETE, recordAbstract);
  }

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

  public void afterReadOperations(RecordAbstract identifiable) {
    assert assertIfNotActive();

    callbackHooks(TYPE.READ, identifiable);
  }

  public UUID uuid() {
    assert assertIfNotActive();
    checkOpenness();

    return storage.getUuid();
  }

  public boolean beforeReadOperations(RecordAbstract identifiable) {
    assert assertIfNotActive();

    if (identifiable instanceof EntityImpl entity) {
      var clazz = entity.getImmutableSchemaClass(this);
      if (clazz != null) {
        try {
          checkSecurity(ResourceGeneric.CLASS, Role.PERMISSION_READ, clazz.getName());
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

  public void afterCommitOperations(Map<RID, RID> updatedRids) {
    assert assertIfNotActive();

    for (var operation : currentTx.getRecordOperationsInternal()) {
      var record = operation.record;

      if (record instanceof EntityImpl entity) {
        var clazz = entity.getImmutableSchemaClass(this);
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

      }
    }

    for (var listener : browseListeners()) {
      try {
        listener.onAfterTxCommit(currentTx, updatedRids);
      } catch (Exception e) {
        final var message =
            "Error after the transaction has been committed. The transaction remains valid. The"
                + " exception caught was on execution of "
                + listener.getClass()
                + ".onAfterTxCommit() `%08X`";

        LogManager.instance().error(this, message, e, System.identityHashCode(e));

        throw BaseException.wrapException(
            new TransactionBlockedException(getDatabaseName(), message), e,
            getDatabaseName());
      }
    }

  }

  public void set(ATTRIBUTES_INTERNAL attribute, Object value) {
    assert assertIfNotActive();

    checkOpenness();

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

  public void afterRollbackOperations() {
    assert assertIfNotActive();

    for (var listener : browseListeners()) {
      try {
        listener.onAfterTxRollback(currentTx);
      } catch (Exception t) {
        LogManager.instance()
            .error(this, "Error after transaction rollback `%08X`", t, System.identityHashCode(t));
      }
    }

  }

  public String getCollectionName(final @Nonnull DBRecord record) {
    assert assertIfNotActive();

    checkOpenness();

    var collectionId = record.getIdentity().getCollectionId();
    if (collectionId == RID.COLLECTION_ID_INVALID) {
      // COMPUTE THE COLLECTION ID
      SchemaClassInternal schemaClass = null;
      if (record instanceof EntityImpl entity) {
        schemaClass = entity.getImmutableSchemaClass(this);
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

  public boolean executeExists(@Nonnull RID rid) {
    assert assertIfNotActive();

    checkOpenness();

    try {
      checkSecurity(
          ResourceGeneric.COLLECTION,
          Role.PERMISSION_READ,
          getCollectionNameById(rid.getCollectionId()));

      var tx = getActiveTransaction();
      if (tx.isDeletedInTx(rid)) {
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

      return storage.recordExists(this, rid, tx.getAtomicOperation());
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

  public void checkSecurity(
      final ResourceGeneric resourceGeneric,
      final String resourceSpecific,
      final int iOperation) {
    assert assertIfNotActive();

    checkOpenness();

    if (user != null) {
      try {
        user.allow(this, resourceGeneric, resourceSpecific, iOperation);
      } catch (SecurityAccessException e) {

        if (logger.isDebugEnabled()) {
          LogManager.instance()
              .debug(
                  this,
                  "User '%s' tried to access the reserved resource '%s.%s', operation '%s'",
                  logger, getCurrentUser(),
                  resourceGeneric,
                  resourceSpecific,
                  iOperation);
        }

        throw e;
      }
    }
  }

  public void checkSecurity(
      final ResourceGeneric iResourceGeneric,
      final int iOperation,
      final Object... iResourcesSpecific) {
    assert assertIfNotActive();

    checkOpenness();

    if (iResourcesSpecific == null || iResourcesSpecific.length == 0) {
      checkSecurity(iResourceGeneric, null, iOperation);
    } else {
      for (var target : iResourcesSpecific) {
        checkSecurity(iResourceGeneric, target == null ? null : target.toString(), iOperation);
      }
    }
  }

  public void checkSecurity(
      final ResourceGeneric iResourceGeneric,
      final int iOperation,
      final Object iResourceSpecific) {
    assert assertIfNotActive();

    checkOpenness();

    checkSecurity(
        iResourceGeneric,
        iResourceSpecific == null ? null : iResourceSpecific.toString(),
        iOperation);
  }

  @Deprecated
  public void checkSecurity(final String iResource, final int iOperation) {
    assert assertIfNotActive();

    checkOpenness();

    final var resourceSpecific = Rule.mapLegacyResourceToSpecificResource(iResource);
    final var resourceGeneric =
        Rule.mapLegacyResourceToGenericResource(iResource);

    if (resourceSpecific == null || resourceSpecific.equals("*")) {
      checkSecurity(resourceGeneric, null, iOperation);
    }

    checkSecurity(resourceGeneric, resourceSpecific, iOperation);
  }

  @Deprecated
  public void checkSecurity(
      final String iResourceGeneric, final int iOperation, final Object iResourceSpecific) {
    assert assertIfNotActive();

    checkOpenness();

    final var resourceGeneric =
        Rule.mapLegacyResourceToGenericResource(iResourceGeneric);
    if (iResourceSpecific == null || iResourceSpecific.equals("*")) {
      checkSecurity(resourceGeneric, iOperation, (Object) null);
    }

    checkSecurity(resourceGeneric, iOperation, iResourceSpecific);
  }

  @Deprecated
  public void checkSecurity(
      final String iResourceGeneric, final int iOperation, final Object... iResourcesSpecific) {
    assert assertIfNotActive();

    checkOpenness();

    final var resourceGeneric =
        Rule.mapLegacyResourceToGenericResource(iResourceGeneric);
    checkSecurity(resourceGeneric, iOperation, iResourcesSpecific);
  }

  public int addCollection(final String iCollectionName, final Object... iParameters) {
    assert assertIfNotActive();

    checkOpenness();

    return storage.addCollection(this, iCollectionName, iParameters);
  }

  @SuppressWarnings("unused")
  public int addCollection(final String iCollectionName, final int iRequestedId) {
    assert assertIfNotActive();

    checkOpenness();

    return storage.addCollection(this, iCollectionName, iRequestedId);
  }

  @SuppressWarnings("unused")
  public RecordConflictStrategy getConflictStrategy() {
    assert assertIfNotActive();

    return storage.getRecordConflictStrategy();
  }

  @SuppressWarnings("unused")
  public DatabaseSessionEmbedded setConflictStrategy(final String iStrategyName) {
    assert assertIfNotActive();

    checkOpenness();

    storage.setConflictStrategy(
        YouTrackDBEnginesManager.instance().getRecordConflictStrategy().getStrategy(iStrategyName));
    return this;
  }

  @SuppressWarnings("unused")
  public DatabaseSessionEmbedded setConflictStrategy(final RecordConflictStrategy iResolver) {
    assert assertIfNotActive();

    checkOpenness();

    storage.setConflictStrategy(iResolver);
    return this;
  }

  public long countCollectionElements(int iCollectionId, boolean countTombstones) {
    assert assertIfNotActive();

    checkOpenness();

    final var name = getCollectionNameById(iCollectionId);
    if (name == null) {
      return 0;
    }
    checkSecurity(ResourceGeneric.COLLECTION, Role.PERMISSION_READ, name);
    assert assertIfNotActive();
    return storage.count(this, iCollectionId, countTombstones);
  }

  public long countCollectionElements(int[] iCollectionIds, boolean countTombstones) {
    assert assertIfNotActive();

    checkOpenness();

    String name;
    for (var iCollectionId : iCollectionIds) {
      name = getCollectionNameById(iCollectionId);
      checkSecurity(ResourceGeneric.COLLECTION, Role.PERMISSION_READ, name);
    }
    return storage.count(this, iCollectionIds, countTombstones);
  }

  public long countCollectionElements(final String iCollectionName) {
    assert assertIfNotActive();

    checkOpenness();

    checkSecurity(ResourceGeneric.COLLECTION, Role.PERMISSION_READ, iCollectionName);

    final var collectionId = getCollectionIdByName(iCollectionName);
    if (collectionId < 0) {
      throw new IllegalArgumentException("Collection '" + iCollectionName + "' was not found");
    }
    return storage.count(this, collectionId);
  }

  /**
   * Returns the approximate number of records in the collection identified by its numeric ID. The
   * count is maintained incrementally on each create/delete and is O(1) to read. Under snapshot
   * isolation the value reflects the latest committed state, so it may be slightly stale for
   * concurrent readers.
   *
   * @param collectionId the numeric identifier of the collection
   * @return the approximate record count
   * @throws IllegalArgumentException if the collection does not exist
   */
  public long getApproximateCollectionCount(final int collectionId) {
    assert assertIfNotActive();

    checkOpenness();

    final var name = getCollectionNameById(collectionId);
    if (name == null) {
      throw new IllegalArgumentException(
          "Collection with id " + collectionId + " was not found");
    }
    checkSecurity(ResourceGeneric.COLLECTION, Role.PERMISSION_READ, name);

    return storage.getApproximateRecordsCount(collectionId);
  }

  /**
   * Returns the approximate number of records in the named collection. The count is maintained
   * incrementally on each create/delete and is O(1) to read. Under snapshot isolation the value
   * reflects the latest committed state, so it may be slightly stale for concurrent readers.
   *
   * @param collectionName the name of the collection
   * @return the approximate record count
   * @throws IllegalArgumentException if the collection does not exist
   */
  public long getApproximateCollectionCount(final String collectionName) {
    assert assertIfNotActive();

    checkOpenness();

    checkSecurity(ResourceGeneric.COLLECTION, Role.PERMISSION_READ, collectionName);

    final var collectionId = getCollectionIdByName(collectionName);
    if (collectionId < 0) {
      throw new IllegalArgumentException(
          "Collection '" + collectionName + "' was not found");
    }
    return storage.getApproximateRecordsCount(collectionId);
  }

  /**
   * Returns the sum of approximate record counts across the given collection IDs. Each individual
   * count is O(1) to read. Security checks are assumed to be done upstream (via {@code
   * SchemaClassImpl.readableCollections()}).
   *
   * @param collectionIds the numeric identifiers of the collections
   * @return the total approximate record count across all collections
   */
  public long getApproximateCollectionElementsCount(final int[] collectionIds) {
    assert assertIfNotActive();

    checkOpenness();

    long total = 0;
    for (var collectionId : collectionIds) {
      if (collectionId > -1) {
        total += storage.getApproximateRecordsCount(collectionId);
      }
    }
    return total;
  }

  public void dropCollection(final String iCollectionName) {
    assert assertIfNotActive();

    checkOpenness();

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

    localCache.freeCollection(collectionId);
    dropCollectionInternal(iCollectionName);
  }

  private void dropCollectionInternal(final String iCollectionName) {
    assert assertIfNotActive();
    storage.dropCollection(this, iCollectionName);
  }

  @SuppressWarnings("unused")
  public boolean dropCollection(final int collectionId) {
    assert assertIfNotActive();

    checkOpenness();

    checkSecurity(
        ResourceGeneric.COLLECTION, Role.PERMISSION_DELETE,
        getCollectionNameById(collectionId));

    var schema = metadata.getSchema();
    final var clazz = schema.getClassByCollectionId(collectionId);
    if (clazz != null) {
      throw new DatabaseException(this,
          "Cannot drop collection '" + getCollectionNameById(collectionId)
              + "' because it is mapped to class '" + clazz.getName() + "'");
    }

    localCache.freeCollection(collectionId);
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

  public boolean dropCollectionInternal(int collectionId) {
    assert assertIfNotActive();

    checkOpenness();

    return storage.dropCollection(this, collectionId);
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

  public void resetRecordLoadStats() {
    assert assertIfNotActive();
    this.loadedRecordsCount = 0L;
    this.totalRecordLoadMs = 0L;
    this.minRecordLoadMs = 0L;
    this.ridbagPrefetchCount = 0L;
    this.totalRidbagPrefetchMs = 0L;
    this.minRidbagPrefetchMs = 0L;
    this.maxRidbagPrefetchMs = 0L;
  }

  public String backup(final Path path) {
    assert assertIfNotActive();

    checkOpenness();
    checkSecurity(ResourceGeneric.DATABASE, "backup", Role.PERMISSION_EXECUTE);

    return storage.backup(path);
  }

  public String fullBackup(final Path path) {
    assert assertIfNotActive();

    checkOpenness();
    checkSecurity(ResourceGeneric.DATABASE, "backup", Role.PERMISSION_EXECUTE);

    return storage.fullBackup(path);
  }

  public void backup(Supplier<Iterator<String>> ibuFilesSupplier,
      Function<String, InputStream> ibuInputStreamSupplier,
      Function<String, OutputStream> ibuOutputStreamSupplier,
      Consumer<String> ibuFileRemover) {
    assert assertIfNotActive();

    checkOpenness();

    checkSecurity(Rule.ResourceGeneric.DATABASE, "backup", Role.PERMISSION_EXECUTE);

    storage.backup(ibuFilesSupplier, ibuInputStreamSupplier, ibuOutputStreamSupplier,
        ibuFileRemover);
  }

  @Nullable public TimeZone getDatabaseTimeZone() {
    assert assertIfNotActive();

    checkOpenness();

    return storage.getTimeZone();
  }

  public void freeze(final boolean throwException) {
    assert assertIfNotActive();

    checkOpenness();

    freezeDurationMetric.timed(() -> storage.freeze(this, throwException));
  }

  public void freeze() {
    assert assertIfNotActive();
    freeze(false);
  }

  public void release() {
    assert assertIfNotActive();

    checkOpenness();

    releaseDurationMetric.timed(() -> storage.release(this));
  }

  public LinkCollectionsBTreeManager getBTreeCollectionManager() {
    assert assertIfNotActive();
    return storage.getLinkCollectionsBtreeCollectionManager();
  }

  public void reload() {
    assert assertIfNotActive();

    checkOpenness();

    if (this.isClosed()) {
      throw new DatabaseException(getDatabaseName(), "Cannot reload a closed db");
    }
    metadata.reload();
    storage.reload(this);
  }

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
        rollback();
      } catch (Exception e) {
        LogManager.instance().error(this, "Exception during rollback of active transaction", e);
      }

      callOnCloseListeners();

      status = STATUS.CLOSED;
      if (!recycle) {
        sharedContext = null;
        storage.close(this);
      }

    } finally {
      // ALWAYS RESET TL
      activeSession.remove();
    }
  }

  @SuppressWarnings("unused")
  public String getCollectionRecordConflictStrategy(int collectionId) {
    assert assertIfNotActive();

    checkOpenness();

    return storage.getCollectionRecordConflictStrategy(collectionId);
  }

  public int[] getCollectionsIds(@Nonnull Set<String> filterCollections) {
    assert assertIfNotActive();

    checkOpenness();

    return storage.getCollectionsIds(filterCollections);
  }

  public long truncateClass(String name, boolean polimorfic) {
    assert assertIfNotActive();

    checkOpenness();

    this.checkSecurity(ResourceGeneric.CLASS, Role.PERMISSION_UPDATE);
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

  @SuppressWarnings("unused")
  public void truncateClass(String name) {
    assert assertIfNotActive();
    truncateClass(name, true);
  }

  public long truncateCollectionInternal(String collectionName) {
    assert assertIfNotActive();

    checkOpenness();

    checkSecurity(ResourceGeneric.COLLECTION, Role.PERMISSION_DELETE, collectionName);

    var id = getCollectionIdByName(collectionName);
    if (id == -1) {
      throw new DatabaseException(getDatabaseName(),
          "Collection with name " + collectionName + " does not exist");
    }
    final var clazz = getMetadata().getSchema().getClassByCollectionId(id);
    if (clazz != null) {
      checkSecurity(ResourceGeneric.CLASS, Role.PERMISSION_DELETE, clazz.getName());
    }

    try (var iteratorCollection = new RecordIteratorCollection<>(this, id, true)) {

      final var count = new long[] {0};
      executeInTxBatches(iteratorCollection, (session, record) -> {
        delete(record);
        count[0]++;
      });

      return count[0];
    }
  }

  public TransactionMeters transactionMeters() {
    return transactionMeters;
  }

  public void callOnOpenListeners() {
    assert assertIfNotActive();

    wakeupOnOpenDbLifecycleListeners();
  }

  public void callOnCloseListeners() {
    assert assertIfNotActive();

    wakeupOnCloseDbLifecycleListeners();
    wakeupOnCloseListeners();
  }

  private void wakeupOnOpenDbLifecycleListeners() {
    for (var it = YouTrackDBEnginesManager.instance()
        .getDbLifecycleListeners();
        it.hasNext();) {
      it.next().onOpen(this);
    }
  }

  private void wakeupOnCloseDbLifecycleListeners() {
    for (var it = YouTrackDBEnginesManager.instance()
        .getDbLifecycleListeners();
        it.hasNext();) {
      it.next().onClose(this);
    }
  }

  private void wakeupOnCloseListeners() {
    for (var listener : getListenersCopy()) {
      try {
        listener.onClose(this);
      } catch (Exception e) {
        LogManager.instance().error(this, "Error during call of database listener", e);
      }
    }
  }

  public static byte getRecordType() {
    return recordType;
  }

  public long countCollectionElements(final int[] iCollectionIds) {
    assert assertIfNotActive();
    return countCollectionElements(iCollectionIds, false);
  }

  public long countCollectionElements(final int iCollectionId) {
    assert assertIfNotActive();
    return countCollectionElements(iCollectionId, false);
  }

  public MetadataDefault getMetadata() {
    assert assertIfNotActive();
    checkOpenness();

    return metadata;
  }

  public boolean isRetainRecords() {
    assert assertIfNotActive();
    return retainRecords;
  }

  public DatabaseSessionEmbedded setRetainRecords(boolean retainRecords) {
    assert assertIfNotActive();
    this.retainRecords = retainRecords;
    return this;
  }

  public void setStatus(final STATUS status) {
    assert assertIfNotActive();
    this.status = status;
  }

  public void setInternal(final ATTRIBUTES iAttribute, final Object iValue) {
    set(iAttribute, iValue);
  }

  public SecurityUser getCurrentUser() {
    assert assertIfNotActive();

    checkOpenness();
    return user;
  }

  @SuppressWarnings("unused")
  @Nullable public String getCurrentUserName() {
    var user = getCurrentUser();
    if (user == null) {
      return null;
    }

    return user.getName(this);
  }

  public void setUser(final SecurityUser user) {
    assert assertIfNotActive();
    if (user instanceof SecurityUserImpl) {
      final Metadata metadata = getMetadata();
      if (metadata != null) {
        final var security = sharedContext.getSecurity();
        this.user = new ImmutableUser(this, security.getVersion(this), user);
      } else {
        this.user = new ImmutableUser(this, -1, user);
      }
    } else {
      this.user = (ImmutableUser) user;
    }
  }

  public void reloadUser() {
    assert assertIfNotActive();

    if (user != null) {
      if (user.checkIfAllowed(this, ResourceGeneric.CLASS, SecurityUserImpl.CLASS_NAME,
          Role.PERMISSION_READ)
          != null) {

        Metadata metadata = getMetadata();
        if (metadata != null) {
          final var security = sharedContext.getSecurity();
          final var secGetUser = security.getUser(this, user.getName(this));
          if (secGetUser != null) {
            user = new ImmutableUser(this, security.getVersion(this), secGetUser);
          } else {
            throw new SecurityException(url, "User not found");
          }
        } else {
          throw new SecurityException(url, "Metadata not found");
        }
      }
    }
  }

  @SuppressWarnings({"unused", "SameReturnValue"})
  public boolean isMVCC() {
    assert assertIfNotActive();
    return true;
  }

  @SuppressWarnings({"unused", "MethodMayBeStatic"})
  public DatabaseSessionEmbedded setMVCC(boolean mvcc) {
    throw new UnsupportedOperationException();
  }

  public RecordHook registerHook(final @Nonnull RecordHook iHookImpl) {
    assert assertIfNotActive();

    checkOpenness();

    if (!hooks.contains(iHookImpl)) {
      hooks.add(iHookImpl);
    }

    return iHookImpl;
  }

  public void unregisterHook(final @Nonnull RecordHook iHookImpl) {
    assert assertIfNotActive();

    checkOpenness();

    if (hooks.remove(iHookImpl)) {
      iHookImpl.onUnregister();
    }
  }

  public LocalRecordCache getLocalCache() {
    return localCache;
  }

  @Nonnull
  public RID refreshRid(@Nonnull RID rid) {
    if (rid.isPersistent()) {
      return rid;
    }

    checkTxActive();
    var record = currentTx.getRecordEntry(rid);
    if (record == null) {
      throw new RecordNotFoundException(this, rid);
    }

    return record.record.getIdentity();
  }

  public List<RecordHook> getHooks() {
    assert assertIfNotActive();

    checkOpenness();

    return Collections.unmodifiableList(hooks);
  }

  public void deleteInternal(@Nonnull RecordAbstract record) {
    assert assertIfNotActive();

    checkOpenness();

    if (record instanceof EntityImpl entity) {
      ensureEdgeConsistencyOnDeletion(entity);
    }

    currentTx.preProcessRecordsAndExecuteCallCallbacks();
    record.dirty++;

    try {
      checkTxActive();
      currentTx.deleteRecord(record);
    } catch (BaseException e) {
      throw e;
    } catch (Exception e) {
      if (record instanceof EntityImpl entity) {
        throw BaseException.wrapException(
            new DatabaseException(getDatabaseName(),
                "Error on deleting record "
                    + record.getIdentity()
                    + " of class '"
                    + entity.getSchemaClassName()
                    + "'"),
            e, getDatabaseName());
      } else {
        throw BaseException.wrapException(
            new DatabaseException(getDatabaseName(),
                "Error on deleting record " + record.getIdentity()),
            e, getDatabaseName());
      }
    }
  }

  /**
   * Callback the registered hooks if any.
   *
   * @param type   Hook type. Define when hook is called.
   * @param record Record received in the callback
   */
  public void callbackHooks(final TYPE type, final RecordAbstract record) {
    assert assertIfNotActive();
    if (record == null || hooks.isEmpty() || record.getIdentity().getCollectionId() == 0) {
      return;
    }

    var identity = record.getIdentity().copy();
    if (!pushInHook(identity)) {
      return;
    }

    try {
      for (var hook : hooks) {
        hook.onTrigger(type, record);
      }
    } finally {
      popInHook(identity);
    }
  }

  public boolean isValidationEnabled() {
    assert assertIfNotActive();
    return (Boolean) get(ATTRIBUTES_INTERNAL.VALIDATION);
  }

  public void setValidationEnabled(final boolean iEnabled) {
    assert assertIfNotActive();
    set(ATTRIBUTES_INTERNAL.VALIDATION, iEnabled);
  }

  @Nullable public ContextConfiguration getConfiguration() {
    assert assertIfNotActive();

    return storage.getContextConfiguration();
  }

  @Override
  public void close() {
    internalClose(false);
  }

  public STATUS getStatus() {

    return status;
  }

  public String getDatabaseName() {
    return storage.getName();
  }

  public String getURL() {
    if (url == null) {
      url = storage.getURL();
    }

    return url;
  }

  public int getCollections() {
    assert assertIfNotActive();

    return storage.getCollections();
  }

  public boolean existsCollection(final String iCollectionName) {
    assert assertIfNotActive();
    checkOpenness();

    return storage.getCollectionNames()
        .contains(iCollectionName.toLowerCase(Locale.ENGLISH));
  }

  public Collection<String> getCollectionNames() {
    assert assertIfNotActive();

    checkOpenness();

    return storage.getCollectionNames();
  }

  public int getCollectionIdByName(final String iCollectionName) {
    if (iCollectionName == null) {
      return -1;
    }

    assert assertIfNotActive();

    checkOpenness();

    return storage.getCollectionIdByName(iCollectionName.toLowerCase(Locale.ENGLISH));
  }

  @Nullable public String getCollectionNameById(final int collectionId) {
    if (collectionId < 0) {
      return null;
    }

    assert assertIfNotActive();

    checkOpenness();

    return storage.getPhysicalCollectionNameById(collectionId);
  }

  public Object setProperty(final String iName, final Object iValue) {
    assert assertIfNotActive();

    checkOpenness();

    if (iValue == null) {
      return properties.remove(iName.toLowerCase(Locale.ENGLISH));
    } else {
      return properties.put(iName.toLowerCase(Locale.ENGLISH), iValue);
    }
  }

  public Object getProperty(final String iName) {
    assert assertIfNotActive();

    checkOpenness();

    return properties.get(iName.toLowerCase(Locale.ENGLISH));
  }

  public Iterator<Entry<String, Object>> getProperties() {
    assert assertIfNotActive();

    checkOpenness();

    return properties.entrySet().iterator();
  }

  public Object get(final ATTRIBUTES iAttribute) {
    assert assertIfNotActive();

    checkOpenness();

    if (iAttribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    return switch (iAttribute) {
      case DATEFORMAT -> storage.getDateFormat();
      case DATE_TIME_FORMAT -> storage.getDateTimeFormat();
      case TIMEZONE -> storage.getTimeZone().getID();
      case LOCALE_COUNTRY -> storage.getLocaleCountry();
      case LOCALE_LANGUAGE -> storage.getLocaleLanguage();
      case CHARSET -> storage.getCharset();
    };
  }

  public Object get(ATTRIBUTES_INTERNAL attribute) {
    assert assertIfNotActive();

    if (attribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    checkOpenness();

    if (attribute == ATTRIBUTES_INTERNAL.VALIDATION) {
      return storage.isValidationEnabled();
    }

    throw new IllegalArgumentException("attribute is not supported: " + attribute);
  }

  public FrontendTransaction getTransactionInternal() {
    assert assertIfNotActive();
    return currentTx;
  }

  /**
   * Returns the schema of the database.
   *
   * @return the schema of the database
   */
  public Schema getSchema() {
    assert assertIfNotActive();
    return getMetadata().getSchema();
  }

  @Nonnull
  @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"}) // Intentional convenience cast API
  public <RET extends DBRecord> RET load(final RID recordId) {
    assert assertIfNotActive();

    checkOpenness();

    return (RET) currentTx.loadRecord(recordId);
  }

  public boolean exists(RID rid) {
    assert assertIfNotActive();

    checkOpenness();

    return currentTx.exists(rid);
  }

  public BinarySerializerFactory getSerializerFactory() {
    assert assertIfNotActive();
    return componentsFactory.binarySerializerFactory;
  }

  @SuppressWarnings("unused")
  public void setPrefetchRecords(boolean prefetchRecords) {
    assert assertIfNotActive();

    checkOpenness();

    this.prefetchRecords = prefetchRecords;
  }

  @SuppressWarnings("unused")
  public boolean isPrefetchRecords() {
    assert assertIfNotActive();

    checkOpenness();

    return prefetchRecords;
  }

  public int assignAndCheckCollection(DBRecord record) {
    assert assertIfNotActive();

    if (!storage.isAssigningCollectionIds()) {
      return RID.COLLECTION_ID_INVALID;
    }

    var rid = (RecordIdInternal) record.getIdentity();
    SchemaClassInternal schemaClass = null;
    // if collection id is not set yet try to find it out
    if (rid.getCollectionId() <= RID.COLLECTION_ID_INVALID) {
      if (record instanceof EntityImpl entity) {
        schemaClass = entity.getImmutableSchemaClass(this);
        if (schemaClass != null) {
          if (schemaClass.isAbstract()) {
            throw new SchemaException(getDatabaseName(),
                "Entity belongs to abstract class "
                    + schemaClass.getName()
                    + " and cannot be saved");
          }

          return schemaClass.getCollectionForNewInstance(entity);
        } else {
          throw new DatabaseException(getDatabaseName(),
              "Cannot save (1) entity " + record + ": no class or collection defined");
        }
      } else {
        if (record instanceof RecordBytes) {
          var blobs = getBlobCollectionIds();
          if (blobs.length == 0) {
            throw new DatabaseException(getDatabaseName(),
                "Cannot save blob (2) " + record + ": no collection defined");
          } else {
            return blobs[ThreadLocalRandom.current().nextInt(blobs.length)];
          }

        } else {
          throw new DatabaseException(getDatabaseName(),
              "Cannot save (3) entity " + record + ": no class or collection defined");
        }
      }
    } else {
      if (record instanceof EntityImpl entity) {
        schemaClass = entity.getImmutableSchemaClass(this);
      }
    }
    // If the collection id was set check is validity
    if (rid.getCollectionId() > RID.COLLECTION_ID_INVALID) {
      if (schemaClass != null) {
        var messageCollectionName = getCollectionNameById(rid.getCollectionId());
        checkRecordClass(schemaClass, messageCollectionName, rid);
        if (!schemaClass.hasCollectionId(rid.getCollectionId())) {
          throw new IllegalArgumentException(
              "Collection name '"
                  + messageCollectionName
                  + "' (id="
                  + rid.getCollectionId()
                  + ") is not configured to store the class '"
                  + schemaClass.getName()
                  + "', valid are "
                  + Arrays.toString(schemaClass.getCollectionIds()));
        }
      }
    }

    var collectionId = rid.getCollectionId();
    if (collectionId < 0) {
      throw new DatabaseException(getDatabaseName(),
          "Impossible to set collection for record " + record + " class : " + schemaClass);
    }

    return collectionId;
  }

  @SuppressWarnings("UnusedReturnValue")
  public int begin(FrontendTransactionImpl transaction) {
    assert assertIfNotActive();

    // CHECK IT'S NOT INSIDE A HOOK
    if (!inHook.isEmpty()) {
      throw new IllegalStateException("Cannot begin a transaction while a hook is executing");
    }

    if (currentTx.isActive()) {
      if (currentTx instanceof FrontendTransactionImpl) {
        return currentTx.beginInternal();
      }
    }

    // WAKE UP LISTENERS
    for (var listener : browseListeners()) {
      try {
        listener.onBeforeTxBegin(currentTx);
      } catch (Exception e) {
        LogManager.instance().error(this, "Error before tx begin", e);
      }
    }

    currentTx = transaction;
    return currentTx.beginInternal();
  }

  /**
   * Creates a new EntityImpl.
   */
  public EntityImpl newInstance() {
    assert assertIfNotActive();
    return newInstance(Entity.DEFAULT_CLASS_NAME);
  }

  public Entity newEntity() {
    assert assertIfNotActive();
    return newInstance(Entity.DEFAULT_CLASS_NAME);
  }

  @SuppressWarnings("TypeParameterUnusedInFormals") // Intentional convenience cast API
  public <T extends DBRecord> T createOrLoadRecordFromJson(String json) {
    assert assertIfNotActive();

    checkOpenness();

    //noinspection unchecked
    return (T) JSONSerializerJackson.INSTANCE.fromString(this, json);
  }

  public Entity createOrLoadEntityFromJson(String json) {
    assert assertIfNotActive();

    checkOpenness();

    var result = JSONSerializerJackson.INSTANCE.fromString(this, json);

    if (result instanceof Entity entity) {
      return entity;
    }

    throw new DatabaseException(getDatabaseName(), "The record is not an entity");
  }

  public Entity newEntity(String className) {
    assert assertIfNotActive();
    return newInstance(className);
  }

  public Entity newEntity(SchemaClass clazz) {
    assert assertIfNotActive();
    return newInstance(clazz.getName());
  }

  public Vertex newVertex(SchemaClass type) {
    assert assertIfNotActive();
    if (type == null) {
      return newVertex("V");
    }
    return newVertex(type.getName());
  }

  public Edge newEdge(Vertex from, Vertex to, SchemaClass type) {
    assert assertIfNotActive();
    if (type == null) {
      return newEdge(from, to, "E");
    }

    return newEdge(from, to, type.getName());
  }

  public RecordIteratorClass browseClass(final @Nonnull String className) {
    assert assertIfNotActive();
    return browseClass(className, true);
  }

  public RecordIteratorClass browseClass(
      final @Nonnull String className, final boolean iPolymorphic) {
    return browseClass(className, iPolymorphic, true);
  }

  public RecordIteratorClass browseClass(@Nonnull String className, boolean iPolymorphic,
      boolean forwardDirection) {
    assert assertIfNotActive();

    checkOpenness();

    if (getMetadata().getImmutableSchemaSnapshot().getClass(className) == null) {
      throw new IllegalArgumentException(
          "Class '" + className + "' not found in current database");
    }

    checkSecurity(ResourceGeneric.CLASS, Role.PERMISSION_READ, className);
    return new RecordIteratorClass(this, className, iPolymorphic, forwardDirection);
  }

  @SuppressWarnings("unused")
  public RecordIteratorClass browseClass(@Nonnull SchemaClass clz) {
    assert assertIfNotActive();

    checkOpenness();

    checkSecurity(ResourceGeneric.CLASS, Role.PERMISSION_READ, clz.getName());
    return new RecordIteratorClass(this, (SchemaClassInternal) clz,
        true, true);
  }

  public <REC extends RecordAbstract> RecordIteratorCollection<REC> browseCollection(
      final String iCollectionName) {
    assert assertIfNotActive();

    checkOpenness();

    checkSecurity(ResourceGeneric.COLLECTION, Role.PERMISSION_READ, iCollectionName);
    return new RecordIteratorCollection<>(this, getCollectionIdByName(iCollectionName), true);
  }

  public Iterable<SessionListener> getListeners() {
    assert assertIfNotActive();
    return getListenersCopy();
  }

  /**
   * Returns the number of the records of the class iClassName.
   */
  public long countClass(final String iClassName) {
    assert assertIfNotActive();
    return countClass(iClassName, true);
  }

  /**
   * Returns the number of the records of the class iClassName considering also sub classes if
   * polymorphic is true.
   */
  public long countClass(final String iClassName, final boolean iPolymorphic) {
    assert assertIfNotActive();

    final var cls =
        (SchemaImmutableClass) getMetadata().getImmutableSchemaSnapshot().getClass(iClassName);
    if (cls == null) {
      throw new IllegalArgumentException("Class not found in database");
    }

    return countClass(cls, iPolymorphic);
  }

  private long countClass(final SchemaImmutableClass cls, final boolean iPolymorphic) {
    assert assertIfNotActive();

    checkOpenness();

    var totalOnDb = cls.countImpl(iPolymorphic, this);
    return adjustCountForTransaction(totalOnDb, cls.getName(), iPolymorphic);
  }

  /**
   * Returns the approximate number of records for the named class, including subclasses.
   */
  public long approximateCountClass(final String className) {
    assert assertIfNotActive();
    return approximateCountClass(className, true);
  }

  /**
   * Returns the approximate number of records for the named class, optionally including subclasses.
   * Uses the O(1) approximate record count from the storage layer, adjusted for any pending
   * transaction operations (creates/deletes).
   */
  public long approximateCountClass(final String className, final boolean isPolymorphic) {
    assert assertIfNotActive();

    final var cls =
        (SchemaImmutableClass) getMetadata().getImmutableSchemaSnapshot().getClass(className);
    if (cls == null) {
      throw new IllegalArgumentException("Class '" + className + "' not found in database");
    }

    return approximateCountClass(cls, isPolymorphic);
  }

  private long approximateCountClass(
      final SchemaImmutableClass cls, final boolean isPolymorphic) {
    assert assertIfNotActive();

    checkOpenness();

    var totalOnDb = cls.approximateCountImpl(isPolymorphic, this);
    return adjustCountForTransaction(totalOnDb, cls.getName(), isPolymorphic);
  }

  /**
   * Adjusts a base record count for uncommitted transaction operations. Scans the active
   * transaction's pending record operations and increments/decrements the count for any
   * creates/deletes that match the given class (polymorphically or exactly, as requested).
   *
   * @param baseCount    the committed record count (exact or approximate)
   * @param className    the class name to match
   * @param isPolymorphic whether to include subclass records in the adjustment
   * @return the adjusted count reflecting pending transaction state
   */
  private long adjustCountForTransaction(
      long baseCount, String className, boolean isPolymorphic) {
    long deletedInTx = 0;
    long addedInTx = 0;
    if (getTransactionInternal().isActive()) {
      for (var op : getTransactionInternal().getRecordOperationsInternal()) {
        if (op.type == RecordOperation.DELETED) {
          final DBRecord rec = op.record;
          if (rec instanceof EntityImpl entity) {
            var schemaClass = entity.getImmutableSchemaClass(this);
            if (schemaClass != null) {
              if (isPolymorphic) {
                if (schemaClass.isSubClassOf(className)) {
                  deletedInTx++;
                }
              } else {
                if (className.equals(schemaClass.getName())) {
                  deletedInTx++;
                }
              }
            }
          }
        }
        if (op.type == RecordOperation.CREATED) {
          final DBRecord rec = op.record;
          if (rec instanceof EntityImpl entity) {
            var schemaClass = entity.getImmutableSchemaClass(this);
            if (schemaClass != null) {
              if (isPolymorphic) {
                if (schemaClass.isSubClassOf(className)) {
                  addedInTx++;
                }
              } else {
                if (className.equals(schemaClass.getName())) {
                  addedInTx++;
                }
              }
            }
          }
        }
      }
    }

    return (baseCount + addedInTx) - deletedInTx;
  }

  public Map<RID, RID> commit() {
    return commitImpl(null, null, null);
  }

  /// Commit the current transaction with transaction metrics collection enabled.
  /// The listener, mode, and tracking ID are passed through to the frontend
  /// transaction so that timing data is captured around the storage commit.
  public void monitoredCommit(
      @Nonnull TransactionMetricsListener listener,
      @Nonnull QueryMonitoringMode mode,
      @Nonnull String trackingId) {
    commitImpl(listener, mode, trackingId);
  }

  private Map<RID, RID> commitImpl(
      @Nullable TransactionMetricsListener listener,
      @Nullable QueryMonitoringMode mode,
      @Nullable String trackingId) {
    assert assertIfNotActive();

    checkOpenness();

    if (currentTx.getStatus() == TXSTATUS.ROLLBACKING) {
      throw new RollbackException("Transaction is rolling back");
    }

    if (!currentTx.isActive()) {
      throw new TransactionException(getDatabaseName(),
          "No active transaction to commit. Call begin() first");
    }

    if (currentTx.amountOfNestedTxs() > 1) {
      // This just do count down no real commit here
      return currentTx.commitInternal();
    }

    // WAKE UP LISTENERS

    try {
      beforeCommitOperations();
    } catch (BaseException e) {
      try {
        rollback();
      } catch (Exception re) {
        LogManager.instance()
            .error(this, "Exception during rollback `%08X`", re, System.identityHashCode(re));
      }
      throw e;
    }
    try {
      if (listener != null) {
        assert mode != null && trackingId != null;
        return currentTx.monitoredCommitInternal(listener, mode, trackingId);
      } else {
        return currentTx.commitInternal();
      }
    } catch (RuntimeException e) {

      if ((e instanceof HighLevelException) || (e instanceof NeedRetryException)) {
        if (logger.isDebugEnabled()) {
          LogManager.instance()
              .debug(this, "Error on transaction commit `%08X`", logger, e,
                  System.identityHashCode(e));
        }
      } else {
        LogManager.instance()
            .error(this, "Error on transaction commit `%08X`", e, System.identityHashCode(e));
      }

      // WAKE UP ROLLBACK LISTENERS
      try {
        // ROLLBACK TX AT DB LEVEL
        if (currentTx.isActive()) {
          currentTx.rollbackInternal();
        }
      } catch (Exception re) {
        LogManager.instance()
            .error(
                this, "Error during transaction rollback `%08X`", re, System.identityHashCode(re));
      }

      // WAKE UP ROLLBACK LISTENERS
      throw e;
    }
  }

  private void beforeCommitOperations() {
    assert assertIfNotActive();
    for (var listener : browseListeners()) {
      try {
        listener.onBeforeTxCommit(currentTx);
      } catch (Exception e) {
        LogManager.instance()
            .error(
                this,
                "Cannot commit the transaction: caught exception on execution of"
                    + " %s.onBeforeTxCommit() `%08X`",
                e,
                listener.getClass().getName(),
                System.identityHashCode(e));
        throw BaseException.wrapException(
            new TransactionException(getDatabaseName(),
                "Cannot commit the transaction: caught exception on execution of "
                    + listener.getClass().getName()
                    + "#onBeforeTxCommit()"),
            e, getDatabaseName());
      }
    }
  }

  public final void beforeRollbackOperations() {
    assert assertIfNotActive();
    for (var listener : browseListeners()) {
      try {
        listener.onBeforeTxRollback(currentTx);
      } catch (Exception t) {
        LogManager.instance()
            .error(this, "Error before transaction rollback `%08X`", t, System.identityHashCode(t));
      }
    }
  }

  public void rollback() {
    assert assertIfNotActive();

    checkOpenness();

    if (currentTx.isActive()) {
      // WAKE UP LISTENERS
      currentTx.rollbackInternal();
      // WAKE UP LISTENERS
    }
  }

  @SuppressWarnings("unused")
  public CurrentStorageComponentsFactory getStorageVersions() {
    assert assertIfNotActive();
    return componentsFactory;
  }

  public RecordSerializer getSerializer() {
    assert assertIfNotActive();
    return serializer;
  }

  /**
   * Sets serializer for the database which will be used for entity serialization.
   *
   * @param serializer the serializer to set.
   */
  public void setSerializer(RecordSerializer serializer) {
    assert assertIfNotActive();
    this.serializer = serializer;
  }

  @SuppressWarnings("unused")
  public void resetInitialization() {
    assert assertIfNotActive();

    checkOpenness();

    for (var h : hooks) {
      h.onUnregister();
    }

    hooks.clear();
    close();

    initialized = false;
  }

  public void checkSecurity(final int operation, final Identifiable record, String collection) {
    assert assertIfNotActive();

    checkOpenness();

    if (collection == null) {
      collection = getCollectionNameById(record.getIdentity().getCollectionId());
    }
    checkSecurity(ResourceGeneric.COLLECTION, operation, collection);

    if (record instanceof EntityImpl entity) {
      var clazzName = entity.getSchemaClassName();
      if (clazzName != null) {
        checkSecurity(ResourceGeneric.CLASS, operation, clazzName);
      }
    }
  }

  /**
   * Use #activateOnCurrentThread instead.
   */
  @Deprecated
  @SuppressWarnings("InlineMeSuggester")
  public void setCurrentDatabaseInThreadLocal() {
    activateOnCurrentThread();
  }

  /**
   * Activates current database instance on current thread.
   */
  public void activateOnCurrentThread() {
    activeSession.set(true);
  }

  public boolean isActiveOnCurrentThread() {
    var isActive = activeSession.get();
    return isActive != null && isActive;
  }

  private void checkOpenness() {
    if (status == STATUS.CLOSED) {
      throw new DatabaseException(getDatabaseName(), "Database '" + getURL() + "' is closed");
    }
  }

  private void popInHook(Identifiable id) {
    inHook.remove(id);
  }

  private boolean pushInHook(Identifiable id) {
    return inHook.add(id);
  }

  private void checkRecordClass(
      final SchemaClass recordClass, final String iCollectionName, final RecordIdInternal rid) {
    assert assertIfNotActive();

    final var collectionIdClass =
        metadata.getImmutableSchemaSnapshot().getClassByCollectionId(rid.getCollectionId());
    if ((recordClass == null && collectionIdClass != null)
        || (collectionIdClass == null && recordClass != null)
        || (recordClass != null && !recordClass.equals(collectionIdClass))) {
      throw new IllegalArgumentException(
          "Record saved into collection '"
              + iCollectionName
              + "' should be saved with class '"
              + collectionIdClass
              + "' but has been created with class '"
              + recordClass
              + "'");
    }
  }

  // Called via assert, always returns true (throws otherwise)
  @SuppressWarnings("SameReturnValue")
  public boolean assertIfNotActive() {
    var currentDatabase = activeSession.get();

    if (currentDatabase == null || !currentDatabase) {
      throw new SessionNotActivatedException(getDatabaseName());
    }

    return true;
  }

  public int[] getBlobCollectionIds() {
    assert assertIfNotActive();
    return getMetadata().getSchema().getBlobCollections().toIntArray();
  }

  public SharedContext getSharedContext() {
    assert assertIfNotActive();
    return sharedContext;
  }

  public void queryStarted(String id, ResultSet resultSet) {
    assert assertIfNotActive();

    final var activeQueriesSize = activeQueries.size();

    if (this.resultSetReportThreshold > 0 &&
        activeQueriesSize > 1 &&
        activeQueriesSize % resultSetReportThreshold == 0) {
      var msg =
          "This database instance has "
              + activeQueriesSize
              + " open command/query result sets, please make sure you close them with"
              + " ResultSet.close()";
      LogManager.instance().warn(this, msg);
      if (logger.isDebugEnabled()) {
        activeQueries.values().stream()
            .map(ResultSet::getExecutionPlan)
            .forEach(plan -> LogManager.instance().debug(this, plan.toString(), logger));
      }
    }

    this.activeQueries.put(id, resultSet);
    getListeners().forEach((it) -> it.onCommandStart(this, resultSet));
  }

  @Override
  public void queryClosed(String id) {
    assert assertIfNotActive();

    @SuppressWarnings("resource")
    var removed = this.activeQueries.remove(id);
    getListeners().forEach((it) -> it.onCommandEnd(this, removed));
  }

  public void closeActiveQueries() {
    for (var rs : new ArrayList<>(activeQueries.values())) {
      rs.close();
    }
  }

  @SuppressWarnings("unused")
  public Map<String, ResultSet> getActiveQueries() {
    assert assertIfNotActive();

    return activeQueries;
  }

  @SuppressWarnings("unused")
  public ResultSet getActiveQuery(String id) {
    assert assertIfNotActive();

    return activeQueries.get(id);
  }

  @SuppressWarnings("unused")
  public boolean isCollectionEdge(int collection) {
    assert assertIfNotActive();

    var clazz = getMetadata().getImmutableSchemaSnapshot().getClassByCollectionId(collection);
    return clazz != null && clazz.isEdgeType();
  }

  @SuppressWarnings("unused")
  public boolean isCollectionVertex(int collection) {
    assert assertIfNotActive();

    var clazz = getMetadata().getImmutableSchemaSnapshot().getClassByCollectionId(collection);
    return clazz != null && clazz.isVertexType();
  }

  public <X extends Exception> void executeInTx(
      @Nonnull TxConsumer<Transaction, X> code) throws X {
    executeInTxInternal(code::accept);
  }

  @Nullable public <R, X extends Exception> R computeInTx(
      TxFunction<Transaction, R, X> supplier) throws X {
    return computeInTxInternal(supplier::apply);
  }

  public <T, X extends Exception> void executeInTxBatches(
      @Nonnull Iterator<T> iterator, int batchSize,
      TxBiConsumer<Transaction, T, X> consumer) throws X {
    executeInTxBatchesInternal(iterator, batchSize, consumer::accept);
  }

  public <T, X extends Exception> void executeInTxBatches(Iterator<T> iterator,
      TxBiConsumer<Transaction, T, X> consumer) throws X {
    executeInTxBatchesInternal(iterator, consumer::accept);
  }

  @SuppressWarnings("unused")
  public <T, X extends Exception> void executeInTxBatches(Stream<T> stream,
      TxBiConsumer<Transaction, T, X> consumer) throws X {
    executeInTxBatchesInternal(stream, consumer::accept);
  }

  public <X extends Exception> void executeInTxInternal(
      @Nonnull TxConsumer<FrontendTransactionImpl, X> code) throws X {
    if (currentTx.getStatus() == TXSTATUS.COMMITTING ||
        currentTx.getStatus() == TXSTATUS.ROLLBACKING) {
      throw new TransactionException(getDatabaseName(),
          "Cannot start a new transaction while a transaction is committing or rolling back");
    }
    var ok = false;
    assert assertIfNotActive();
    begin();
    try {
      code.accept((FrontendTransactionImpl) currentTx);
      ok = true;
    } finally {
      finishTx(ok);
    }
  }

  public <T, X extends Exception> void executeInTxBatches(
      Iterable<T> iterable, int batchSize, TxBiConsumer<Transaction, T, X> consumer) throws X {
    executeInTxBatches(iterable.iterator(), batchSize, consumer);
  }

  public <T, X extends Exception> void forEachInTx(Iterator<T> iterator,
      TxBiConsumer<Transaction, T, X> consumer) throws X {
    assert assertIfNotActive();
    forEachInTx(iterator, (db, t) -> {
      consumer.accept(db, t);
      return true;
    });
  }

  @SuppressWarnings("unused")
  public <T, X extends Exception> void forEachInTx(Iterable<T> iterable,
      TxBiConsumer<Transaction, T, X> consumer) throws X {
    assert assertIfNotActive();

    forEachInTx(iterable.iterator(), consumer);
  }

  @SuppressWarnings("unused")
  public <T, X extends Exception> void forEachInTx(Stream<T> stream,
      TxBiConsumer<Transaction, T, X> consumer) throws X {
    assert assertIfNotActive();

    try (var s = stream) {
      forEachInTx(s.iterator(), consumer);
    }
  }

  public <T, X extends Exception> void forEachInTx(Iterator<T> iterator,
      TxBiFunction<Transaction, T, Boolean, X> consumer) throws X {
    var ok = false;
    assert assertIfNotActive();

    var tx = begin();
    try {
      while (iterator.hasNext()) {
        var cont = consumer.apply(tx, iterator.next());
        commit();

        if (!cont) {
          break;
        }
        begin();
      }

      ok = true;
    } finally {
      finishTx(ok);
    }
  }

  @SuppressWarnings("unused")
  public <T, X extends Exception> void forEachInTx(Iterable<T> iterable,
      TxBiFunction<Transaction, T, Boolean, X> consumer) throws X {
    assert assertIfNotActive();

    forEachInTx(iterable.iterator(), consumer);
  }

  @SuppressWarnings("unused")
  public <T, X extends Exception> void forEachInTx(Stream<T> stream,
      TxBiFunction<Transaction, T, Boolean, X> consumer) throws X {
    assert assertIfNotActive();

    try (stream) {
      forEachInTx(stream.iterator(), consumer);
    }
  }

  private void finishTx(boolean ok) {
    if (currentTx.isActive()) {
      if (ok && currentTx.getStatus() != TXSTATUS.ROLLBACKING) {
        commit();
      } else {
        if (isActiveOnCurrentThread()) {
          rollback();
        } else {
          currentTx.rollbackInternal();
        }
      }
    }
  }

  public <T, X extends Exception> void executeInTxBatchesInternal(
      @Nonnull Iterator<T> iterator, int batchSize,
      TxBiConsumer<FrontendTransaction, T, X> consumer) throws X {
    var ok = false;
    assert assertIfNotActive();
    var counter = 0;

    begin();
    try {
      while (iterator.hasNext()) {
        consumer.accept(currentTx, iterator.next());
        counter++;

        if (counter % batchSize == 0) {
          commit();
          begin();
        }
      }

      ok = true;
    } finally {
      finishTx(ok);
    }
  }

  public <T, X extends Exception> void executeInTxBatchesInternal(
      Iterator<T> iterator, TxBiConsumer<FrontendTransaction, T, X> consumer) throws X {
    assert assertIfNotActive();

    executeInTxBatchesInternal(
        iterator,
        getConfiguration().getValueAsInteger(GlobalConfiguration.TX_BATCH_SIZE),
        consumer);
  }

  public <T, X extends Exception> void executeInTxBatches(
      Iterable<T> iterable, TxBiConsumer<Transaction, T, X> consumer) throws X {
    assert assertIfNotActive();
    executeInTxBatches(
        iterable,
        getConfiguration().getValueAsInteger(GlobalConfiguration.TX_BATCH_SIZE),
        consumer);
  }

  @SuppressWarnings("unused")
  public <T, X extends Exception> void executeInTxBatches(
      Stream<T> stream, int batchSize, TxBiConsumer<Transaction, T, X> consumer) throws X {
    assert assertIfNotActive();

    try (stream) {
      executeInTxBatches(stream.iterator(), batchSize, consumer);
    }
  }

  public <T, X extends Exception> void executeInTxBatchesInternal(Stream<T> stream,
      TxBiConsumer<FrontendTransaction, T, X> consumer) throws X {
    assert assertIfNotActive();

    try (stream) {
      executeInTxBatchesInternal(stream.iterator(), consumer);
    }
  }

  public <R, X extends Exception> R computeInTxInternal(TxFunction<FrontendTransaction, R, X> code)
      throws X {
    assert assertIfNotActive();
    var ok = false;
    begin();
    try {
      var result = code.apply(currentTx);
      ok = true;
      return result;
    } finally {
      finishTx(ok);
    }
  }

  public int activeTxCount() {
    assert assertIfNotActive();

    checkOpenness();

    var transaction = getTransactionInternal();
    return transaction.amountOfNestedTxs();
  }

  public <T> EmbeddedList<T> newEmbeddedList() {
    assert assertIfNotActive();

    checkOpenness();

    return new EntityEmbeddedListImpl<>();
  }

  public <T> EmbeddedList<T> newEmbeddedList(int size) {
    assert assertIfNotActive();

    checkOpenness();

    return new EntityEmbeddedListImpl<>(size);
  }

  public <T> EmbeddedList<T> newEmbeddedList(Collection<T> list) {
    assert assertIfNotActive();

    checkOpenness();

    //noinspection unchecked
    return (EmbeddedList<T>) PropertyTypeInternal.EMBEDDEDLIST.copy(list, this);
  }

  public EmbeddedList<String> newEmbeddedList(String[] source) {
    assert assertIfNotActive();

    checkOpenness();

    var trackedList = new EntityEmbeddedListImpl<String>(source.length);
    trackedList.addAll(Arrays.asList(source));
    return trackedList;
  }

  public EmbeddedList<Date> newEmbeddedList(Date[] source) {
    assert assertIfNotActive();

    checkOpenness();

    var trackedList = new EntityEmbeddedListImpl<Date>(source.length);
    trackedList.addAll(Arrays.asList(source));
    return trackedList;
  }

  public EmbeddedList<Byte> newEmbeddedList(byte[] source) {
    assert assertIfNotActive();

    checkOpenness();

    var trackedList = new EntityEmbeddedListImpl<Byte>(source.length);
    for (var b : source) {
      trackedList.add(b);
    }
    return trackedList;
  }

  public EmbeddedList<Short> newEmbeddedList(short[] source) {
    assert assertIfNotActive();

    checkOpenness();

    var trackedList = new EntityEmbeddedListImpl<Short>(source.length);
    for (var s : source) {
      trackedList.add(s);
    }
    return trackedList;
  }

  public EmbeddedList<Integer> newEmbeddedList(int[] source) {
    assert assertIfNotActive();

    checkOpenness();

    var trackedList = new EntityEmbeddedListImpl<Integer>(source.length);
    for (var i : source) {
      trackedList.add(i);
    }
    return trackedList;
  }

  public EmbeddedList<Long> newEmbeddedList(long[] source) {
    assert assertIfNotActive();

    checkOpenness();

    var trackedList = new EntityEmbeddedListImpl<Long>(source.length);
    for (var l : source) {
      trackedList.add(l);
    }
    return trackedList;
  }

  public EmbeddedList<Float> newEmbeddedList(float[] source) {
    assert assertIfNotActive();

    checkOpenness();

    var trackedList = new EntityEmbeddedListImpl<Float>(source.length);
    for (var f : source) {
      trackedList.add(f);
    }
    return trackedList;
  }

  public EmbeddedList<Double> newEmbeddedList(double[] source) {
    assert assertIfNotActive();

    checkOpenness();

    var trackedList = new EntityEmbeddedListImpl<Double>(source.length);
    for (var d : source) {
      trackedList.add(d);
    }
    return trackedList;
  }

  public EmbeddedList<Boolean> newEmbeddedList(boolean[] source) {
    assert assertIfNotActive();

    checkOpenness();

    var trackedList = new EntityEmbeddedListImpl<Boolean>(source.length);
    for (var b : source) {
      trackedList.add(b);
    }
    return trackedList;
  }

  public LinkList newLinkList() {
    assert assertIfNotActive();

    checkOpenness();

    return new EntityLinkListImpl(this);
  }

  public LinkList newLinkList(int size) {
    assert assertIfNotActive();

    checkOpenness();

    return new EntityLinkListImpl(this, size);
  }

  public LinkList newLinkList(Collection<? extends Identifiable> source) {
    assert assertIfNotActive();

    checkOpenness();

    var list = new EntityLinkListImpl(this, source.size());
    list.addAll(source);
    return list;
  }

  public <T> EmbeddedSet<T> newEmbeddedSet() {
    assert assertIfNotActive();

    checkOpenness();

    return new EntityEmbeddedSetImpl<>();
  }

  public <T> EmbeddedSet<T> newEmbeddedSet(int size) {
    assert assertIfNotActive();

    checkOpenness();

    return new EntityEmbeddedSetImpl<>(size);
  }

  public <T> EmbeddedSet<T> newEmbeddedSet(Collection<T> set) {
    assert assertIfNotActive();

    checkOpenness();

    //noinspection unchecked
    return (EmbeddedSet<T>) PropertyTypeInternal.EMBEDDEDSET.copy(set, this);
  }

  public LinkSet newLinkSet() {
    assert assertIfNotActive();

    checkOpenness();

    return new EntityLinkSetImpl(this);
  }

  public LinkSet newLinkSet(Collection<? extends Identifiable> source) {
    assert assertIfNotActive();

    checkOpenness();

    var linkSet = new EntityLinkSetImpl(this);
    linkSet.addAll(source);
    return linkSet;
  }

  public <V> EmbeddedMap<V> newEmbeddedMap() {
    assert assertIfNotActive();

    checkOpenness();

    return new EntityEmbeddedMapImpl<>();
  }

  public <V> EmbeddedMap<V> newEmbeddedMap(int size) {
    assert assertIfNotActive();

    checkOpenness();

    return new EntityEmbeddedMapImpl<>(size);
  }

  public <V> EmbeddedMap<V> newEmbeddedMap(Map<String, V> map) {
    assert assertIfNotActive();

    checkOpenness();

    //noinspection unchecked
    return (EmbeddedMap<V>) PropertyTypeInternal.EMBEDDEDMAP.copy(map, this);
  }

  public LinkMap newLinkMap() {
    assert assertIfNotActive();

    checkOpenness();

    return new EntityLinkMapIml(this);
  }

  public LinkMap newLinkMap(int size) {
    assert assertIfNotActive();

    checkOpenness();

    return new EntityLinkMapIml(size, this);
  }

  public LinkMap newLinkMap(Map<String, ? extends Identifiable> source) {
    assert assertIfNotActive();

    checkOpenness();

    var linkMap = new EntityLinkMapIml(source.size(), this);
    linkMap.putAll(source);
    return linkMap;
  }

  @Nonnull
  public FrontendTransactionImpl getActiveTransaction() {
    assert assertIfNotActive();

    checkOpenness();

    if (currentTx.isActive()) {
      return (FrontendTransactionImpl) currentTx;
    }

    throw new DatabaseException(this, "There is no active transaction in session");
  }

  @Nullable public FrontendTransactionImpl getActiveTransactionOrNull() {
    assert assertIfNotActive();

    checkOpenness();

    if (currentTx.isActive()) {
      return (FrontendTransactionImpl) currentTx;
    }

    return null;
  }

  private void checkTxActive() {
    if (currentTx == null || !currentTx.isActive()) {
      throw new TransactionException(getDatabaseName(), "There is no active transaction");
    }
  }

  private void ensureEdgeConsistencyOnDeletion(@Nonnull EntityImpl entity) {
    if (entity.isVertex()) {
      VertexEntityImpl.deleteLinks(entity.asVertex());
    } else if (entity.isEdge()) {
      EdgeEntityImpl.deleteLinks(this, entity.asEdge());
    }
  }

  private void ensureLinksConsistencyBeforeDeletion(@Nonnull EntityImpl entity) {
    if (!ensureLinkConsistency) {
      return;
    }

    var properties = entity.getPropertyNamesInternal(true, false);
    var linksToUpdateMap = new HashMap<RecordIdInternal, int[]>();
    for (var propertyName : properties) {
      linksToUpdateMap.clear();

      if (propertyName.charAt(0) == EntityImpl.OPPOSITE_LINK_CONTAINER_PREFIX) {
        var oppositeLinksContainer = (LinkBag) entity.getPropertyInternal(propertyName, false);

        for (var link : oppositeLinksContainer) {
          var transaction = getActiveTransaction();
          var oppositeEntity = (EntityImpl) transaction.loadEntityOrNull(link.primaryRid());
          //skip self-links and already deleted entities
          if (oppositeEntity == null || oppositeEntity.equals(entity)) {
            continue;
          }

          var linkName = propertyName.substring(1);
          var oppositeLinkProperty = oppositeEntity.getPropertyInternal(linkName, false);

          if (oppositeLinkProperty == null) {
            throw new LinksConsistencyException(this, "Cannot remove link " + entity.getIdentity()
                + " from opposite entity because system property "
                + linkName + " does not exist");
          }
          switch (oppositeLinkProperty) {
            case Identifiable identifiable -> {
              if (identifiable.getIdentity().equals(entity.getIdentity())) {
                oppositeEntity.setPropertyInternal(linkName, null);
              } else {
                throw new LinksConsistencyException(this,
                    "Cannot remove link" + linkName + ":" + entity.getIdentity()
                        + " from opposite entity because it does not exist");
              }
            }
            case EntityLinkListImpl linkList -> {
              var removed = linkList.remove(entity);
              if (!removed) {
                throw new LinksConsistencyException(this,
                    "Cannot remove link " + linkName + ":" + entity.getIdentity()
                        + " from opposite entity because it does not exist in the list");
              }
            }
            case EntityLinkSetImpl linkSet -> {
              var removed = linkSet.remove(entity);
              if (!removed) {
                throw new LinksConsistencyException(this,
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
                throw new LinksConsistencyException(this,
                    "Cannot remove link " + linkName + ":" + entity.getIdentity()
                        + " from opposite entity because it does not exist in the map");
              }
            }
            case LinkBag linkBag -> {
              var removed = linkBag.remove(entity.getIdentity());
              if (!removed) {
                throw new LinksConsistencyException(this,
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

    var linksToUpdateMap = new HashMap<RecordIdInternal, int[]>();
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
            var isLinkBag = currentPropertyValue instanceof LinkBag;
            for (var event : timeLine.getMultiValueChangeEvents()) {
              switch (event.getChangeType()) {
                case ADD -> {
                  var addedRid = isLinkBag ? event.getKey() : event.getValue();
                  assert addedRid != null;
                  incrementLinkCounter((RecordIdInternal) addedRid, linksToUpdateMap);
                }
                case REMOVE -> {
                  var removedRid = isLinkBag ? event.getKey() : event.getOldValue();
                  assert removedRid != null;
                  decrementLinkCounter((RecordIdInternal) removedRid, linksToUpdateMap);
                }
                case UPDATE -> {
                  assert event.getValue() != null;
                  assert event.getOldValue() != null;
                  incrementLinkCounter((RecordIdInternal) event.getValue(), linksToUpdateMap);
                  decrementLinkCounter((RecordIdInternal) event.getOldValue(), linksToUpdateMap);
                }
              }
            }
          }
        } else {
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
      Map<RecordIdInternal, int[]> linksToUpdateMap) {
    var oppositeLinkBagPropertyName = EntityImpl.getOppositeLinkBagPropertyName(propertyName);
    for (var entitiesToUpdate : linksToUpdateMap.entrySet()) {
      var oppositeLink = entitiesToUpdate.getKey();
      var diff = entitiesToUpdate.getValue()[0];

      if (currentTx.isDeletedInTx(oppositeLink)) {
        if (diff > 0) {
          throw new LinksConsistencyException(this, "Cannot add link " + entity.getIdentity()
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
          // Invariant: if we're removing a back-reference, the opposite entity
          // must have the link bag property. For new entities created and deleted
          // in the same TX, the CREATED callback runs first (adding back-refs),
          // so this path is not expected for new entities.
          assert !entity.getIdentity().isNew()
              : "New entity " + entity.getIdentity()
                  + " has missing opposite link bag " + oppositeLinkBagPropertyName
                  + " on " + oppositeEntity.getIdentity();
          throw new LinksConsistencyException(this,
              "Cannot remove link " + propertyName + " for " + entity
                  + " from opposite entity " + oppositeEntity
                  + " because required property does not exist");
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
            // Invariant: if the link bag exists but doesn't contain the entity,
            // the entity must have been added by a prior CREATED callback.
            // For new entities, the CREATED callback always runs before the
            // DELETED callback within preProcessRecordsAndExecuteCallCallbacks.
            assert !entity.getIdentity().isNew()
                : "New entity " + entity.getIdentity()
                    + " not found in opposite link bag " + oppositeLinkBagPropertyName
                    + " on " + oppositeEntity.getIdentity();
            throw new LinksConsistencyException(this, "Cannot remove link " + rid
                + " from opposite entity because it does not exist in opposite link bag : "
                + oppositeLinkBagPropertyName);
          }
        }
      }

      if (linkBag.isEmpty()) {
        oppositeEntity.removePropertyInternal(oppositeLinkBagPropertyName);
      }
    }
  }

  private static void decrementLinkCounter(@Nonnull RecordIdInternal recordId,
      @Nonnull Map<RecordIdInternal, int[]> linksToUpdateMap) {
    linksToUpdateMap.compute(
        recordId,
        (key, value) -> {
          if (value == null) {
            return new int[] {-1};
          } else {
            value[0]--;
            if (value[0] == 0) {
              return null;
            }
            return value;
          }
        });
  }

  private static void incrementLinkCounter(@Nonnull RecordIdInternal recordId,
      @Nonnull Map<RecordIdInternal, int[]> linksToUpdateMap) {
    linksToUpdateMap.compute(
        recordId,
        (key, value) -> {
          if (value == null) {
            return new int[] {1};
          } else {
            value[0]++;
            return value;
          }
        });
  }

  private static void addToLinksContainer(Object value,
      Map<RecordIdInternal, int[]> links) {
    if (value == null) {
      return;
    }

    switch (value) {
      case Identifiable identifiable -> {
        if (identifiable instanceof Entity entity) {
          if (!entity.isEmbedded()) {
            incrementLinkCounter((RecordIdInternal) identifiable.getIdentity(), links);
          }
        } else {
          incrementLinkCounter((RecordIdInternal) identifiable.getIdentity(), links);
        }
      }
      case EntityLinkListImpl linkList -> {
        for (var link : linkList) {
          incrementLinkCounter((RecordIdInternal) link, links);
        }
      }
      case EntityLinkSetImpl linkSet -> {
        for (var link : linkSet) {
          incrementLinkCounter((RecordIdInternal) link, links);
        }
      }
      case EntityLinkMapIml linkMap -> {
        for (var link : linkMap.values()) {
          incrementLinkCounter((RecordIdInternal) link, links);
        }
      }
      case LinkBag linkBag -> {
        for (var link : linkBag) {
          incrementLinkCounter((RecordIdInternal) link.primaryRid(), links);
        }
      }
      default -> {
      }
    }
  }

  private static void subtractFromLinksContainer(Object value,
      Map<RecordIdInternal, int[]> links) {
    if (value == null) {
      return;
    }

    switch (value) {
      case Identifiable identifiable -> {
        if (identifiable instanceof Entity entity) {
          if (!entity.isEmbedded()) {
            decrementLinkCounter((RecordIdInternal) identifiable.getIdentity(), links);
          }
        } else {
          decrementLinkCounter((RecordIdInternal) identifiable.getIdentity(), links);
        }
      }
      case EntityLinkListImpl linkList -> {
        for (var link : linkList) {
          decrementLinkCounter((RecordIdInternal) link, links);
        }
      }
      case EntityLinkSetImpl linkSet -> {
        for (var link : linkSet) {
          decrementLinkCounter((RecordIdInternal) link, links);
        }
      }
      case EntityLinkMapIml linkMap -> {
        for (var link : linkMap.values()) {
          decrementLinkCounter((RecordIdInternal) link, links);
        }
      }
      case LinkBag linkBag -> {
        for (var link : linkBag) {
          decrementLinkCounter((RecordIdInternal) link.primaryRid(), links);
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

  // --- Default methods migrated from DatabaseSessionEmbedded ---

  @SuppressWarnings("unused")
  public ResultSet computeSQLScript(String script, Object... args)
      throws CommandExecutionException, CommandScriptException {
    return computeScript("sql", script, args);
  }

  @SuppressWarnings("unused")
  public ResultSet computeGremlinScript(String script, Object... args)
      throws CommandExecutionException, CommandScriptException {
    return computeScript("gremlin", script, args);
  }

  public void executeScript(String language, String script, Object... args)
      throws CommandExecutionException, CommandScriptException {
    computeScript(language, script, args).close();
  }

  public void executeSQLScript(String script, Object... args)
      throws CommandExecutionException, CommandScriptException {
    executeScript("sql", script, args);
  }

  @SuppressWarnings("unused")
  public void executeGremlinScript(String script, Object... args)
      throws CommandExecutionException, CommandScriptException {
    executeScript("gremlin", script, args);
  }

  @SuppressWarnings("unused")
  public ResultSet computeSQLScript(String script, Map<String, ?> args)
      throws CommandExecutionException, CommandScriptException {
    return computeScript("sql", script, args);
  }

  @SuppressWarnings("unused")
  public ResultSet computeGremlinScript(String script, Map<String, ?> args) {
    return computeScript("gremlin", script, args);
  }

  public void executeScript(String language, String script, Map<String, ?> args)
      throws CommandExecutionException, CommandScriptException {
    computeScript(language, script, args).close();
  }

  @SuppressWarnings("unused")
  public void executeSQLScript(String script, Map<String, ?> args)
      throws CommandExecutionException, CommandScriptException {
    executeScript("sql", script, args);
  }

  @SuppressWarnings("unused")
  public void executeGremlinScript(String script, Map<String, ?> args)
      throws CommandExecutionException, CommandScriptException {
    executeScript("gremlin", script, args);
  }

  // --- Default methods migrated from DatabaseSessionEmbedded ---

  @SuppressWarnings("unused")
  @Nullable public <R> R transaction(Function<Transaction, R> action) {
    return computeInTx(action::apply);
  }

  public boolean isTxActive() {
    return activeTxCount() > 0;
  }

  // --- Default methods migrated from DatabaseSessionEmbedded ---

  @SuppressWarnings("unused")
  public void realClose() {
    throw new UnsupportedOperationException();
  }

  @SuppressWarnings("unused")
  public void reuse() {
    throw new UnsupportedOperationException();
  }

  @Nullable @SuppressWarnings("TypeParameterUnusedInFormals")
  public <RET extends DBRecord> RET loadOrNull(RID recordId) {
    try {
      return load(recordId);
    } catch (RecordNotFoundException e) {
      return null;
    }
  }

  @Nonnull
  public Entity loadEntity(RID id) throws DatabaseException, RecordNotFoundException {
    var record = load(id);
    if (record instanceof Entity element) {
      return element;
    }

    throw new DatabaseException(getDatabaseName(),
        "Record with id " + id + " is not an entity, but a "
            + record.getClass().getSimpleName());
  }

  @Nonnull
  public Vertex loadVertex(RID id) throws DatabaseException, RecordNotFoundException {
    var record = load(id);
    if (record instanceof Vertex vertex) {
      return vertex;
    }

    throw new DatabaseException(getDatabaseName(),
        "Record with id " + id + " is not a vertex, but a "
            + record.getClass().getSimpleName());
  }

  @Nonnull
  public Edge loadEdge(RID id) throws DatabaseException, RecordNotFoundException {
    var record = load(id);

    if (record instanceof Edge edge) {
      return edge;
    }

    throw new DatabaseException(getDatabaseName(),
        "Record with id " + id + " is not an edge, but a "
            + record.getClass().getSimpleName());
  }

  @Nonnull
  public Blob loadBlob(RID id) throws DatabaseException, RecordNotFoundException {
    var record = load(id);

    if (record instanceof Blob blob) {
      return blob;
    }

    throw new DatabaseException(getDatabaseName(),
        "Record with id " + id + " is not a blob, but a "
            + record.getClass().getSimpleName());
  }

  public Vertex newVertex() {
    return newVertex("V");
  }

  public Edge newEdge(Vertex from, Vertex to) {
    return newEdge(from, to, "E");
  }

  public void command(String query, Object... args)
      throws CommandSQLParsingException, CommandExecutionException {
    execute(query, args).close();
  }

  public void command(String query, @SuppressWarnings("rawtypes") Map args)
      throws CommandSQLParsingException, CommandExecutionException {
    execute(query, args).close();
  }

  public void command(
      com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement statement,
      Map<?, ?> args) throws CommandSQLParsingException, CommandExecutionException {
    execute(statement, args).close();
  }

  public SchemaClassInternal getClassInternal(String className) {
    var schema = getMetadata().getSchemaInternal();
    return schema.getClassInternal(className);
  }

  public SchemaClass createVertexClass(String className) throws SchemaException {
    return createClass(className, SchemaClass.VERTEX_CLASS_NAME);
  }

  public SchemaClass createEdgeClass(String className) {
    var edgeClass = createClass(className, SchemaClass.EDGE_CLASS_NAME);

    edgeClass.createProperty(Edge.DIRECTION_IN, PropertyType.LINK);
    edgeClass.createProperty(Edge.DIRECTION_OUT, PropertyType.LINK);

    return edgeClass;
  }

  public SchemaClass getClass(String className) {
    var schema = getSchema();
    return schema.getClass(className);
  }

  public SchemaClass createClass(String className, String... superclasses)
      throws SchemaException {
    var schema = getSchema();
    SchemaClass[] superclassInstances = null;
    if (superclasses != null) {
      superclassInstances = new SchemaClass[superclasses.length];
      for (var i = 0; i < superclasses.length; i++) {
        var superclass = superclasses[i];
        var superClass = schema.getClass(superclass);
        if (superClass == null) {
          throw new SchemaException(
              getDatabaseName(), "Class " + superclass + " does not exist");
        }
        superclassInstances[i] = superClass;
      }
    }
    var result = schema.getClass(className);
    if (result != null) {
      throw new SchemaException(
          getDatabaseName(), "Class " + className + " already exists");
    }
    if (superclassInstances == null) {
      return schema.createClass(className);
    } else {
      return schema.createClass(className, superclassInstances);
    }
  }

  public SchemaClass createAbstractClass(String className, String... superclasses)
      throws SchemaException {
    var schema = getSchema();
    SchemaClass[] superclassInstances = null;
    if (superclasses != null) {
      superclassInstances = new SchemaClass[superclasses.length];
      for (var i = 0; i < superclasses.length; i++) {
        var superclass = superclasses[i];
        var superClass = schema.getClass(superclass);
        if (superClass == null) {
          throw new SchemaException(
              getDatabaseName(), "Class " + superclass + " does not exist");
        }
        superclassInstances[i] = superClass;
      }
    }
    var result = schema.getClass(className);
    if (result != null) {
      throw new SchemaException(
          getDatabaseName(), "Class " + className + " already exists");
    }
    if (superclassInstances == null) {
      return schema.createAbstractClass(className);
    } else {
      return schema.createAbstractClass(className, superclassInstances);
    }
  }

  public SchemaClass createClassIfNotExist(String className, String... superclasses)
      throws SchemaException {
    var schema = getSchema();

    var result = schema.getClass(className);
    if (result == null) {
      result = createClass(className, superclasses);
    }

    return result;
  }

  public Index getIndex(String indexName) {
    var indexManager = getSharedContext().getIndexManager();
    return indexManager.getIndex(indexName);
  }
}
