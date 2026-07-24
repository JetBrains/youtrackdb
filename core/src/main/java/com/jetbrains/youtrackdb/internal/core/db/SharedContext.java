package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.listener.ListenerManger;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlanCache;
import com.jetbrains.youtrackdb.internal.core.gql.parser.GqlStatementCache;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.index.IndexManagerEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.Indexes;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.function.FunctionLibraryImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaShared;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.SequenceLibraryImpl;
import com.jetbrains.youtrackdb.internal.core.query.live.LiveQueryHook;
import com.jetbrains.youtrackdb.internal.core.query.live.LiveQueryHookV2.LiveQueryOps;
import com.jetbrains.youtrackdb.internal.core.schedule.SchedulerImpl;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlExecutionPlanCache;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlStatementCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

public class SharedContext extends ListenerManger<MetadataUpdateListener> {

  /**
   * Matches the names of the storage-birth blob collections ({@code $blob0..N-1}) created by
   * {@link AbstractStorage} inside the storage-create atomic operation. Derived from the shared
   * {@link MetadataDefault#BLOB_COLLECTION_NAME_PREFIX} constant the creator loop uses, so the
   * two sides of the name contract cannot drift apart. Storage collection names are stored
   * lower-cased, so no case folding is needed here.
   */
  private static final Pattern BLOB_COLLECTION_NAME_PATTERN =
      Pattern.compile(Pattern.quote(MetadataDefault.BLOB_COLLECTION_NAME_PREFIX) + "\\d+");

  /**
   * Storage-configuration property written as the LAST act of {@link #create}: its presence
   * means "the genesis sequence ran to completion" — NOT "users exist" (it is written for the
   * system database and under {@code CREATE_DEFAULT_USERS=false} too). The open path refuses a
   * database that lacks it (a half-genesis crash corpse must be discarded and re-created, never
   * silently reopened); {@code drop()} tolerates the refusal so the discard itself always works.
   * The marker write is its own durability event, so a crash after a completed genesis but
   * before the marker is durable yields an accepted fail-closed FALSE refusal (design W9a).
   */
  public static final String GENESIS_COMPLETED_PROPERTY = "genesisCompleted";

  protected YouTrackDBInternalEmbedded youtrackDB;
  protected AbstractStorage storage;
  protected SchemaShared schema;
  protected SecurityInternal security;

  protected FunctionLibraryImpl functionLibrary;
  protected SchedulerImpl scheduler;
  protected SequenceLibraryImpl sequenceLibrary;
  protected LiveQueryHook.LiveQueryOps liveQueryOps;
  protected LiveQueryOps liveQueryOpsV2;
  protected YqlStatementCache yqlStatementCache;
  protected GqlStatementCache gqlStatementCache;
  protected YqlExecutionPlanCache yqlExecutionPlanCache;
  protected GqlExecutionPlanCache gqlExecutionPlanCache;
  protected volatile boolean loaded = false;
  protected Map<String, Object> resources;
  protected StringCache stringCache;
  protected IndexManagerEmbedded indexManager;

  /**
   * The storage-scoped serialization point for schema- and index-changing transactions. One per
   * storage, shared by every session on it: a schema transaction engages it on its first schema or
   * index write and a second schema transaction blocks on it (single-writer by locking). It is not
   * re-created across {@link #reInit}/{@link #close} because it serializes live in-flight schema
   * transactions, which do not span a storage re-init.
   */
  private final MetadataWriteMutex metadataWriteMutex = new MetadataWriteMutex();

  private final ReentrantLock lock = new ReentrantLock();

  public SharedContext(AbstractStorage storage, YouTrackDBInternalEmbedded youtrackDB) {
    super(true);

    this.youtrackDB = youtrackDB;
    this.storage = storage;

    init(storage);
  }

  protected void init(AbstractStorage storage) {
    stringCache =
        new StringCache(
            storage
                .getContextConfiguration()
                .getValueAsInteger(GlobalConfiguration.DB_STRING_CAHCE_SIZE));
    schema = new SchemaEmbedded();
    security = youtrackDB.getSecuritySystem().newSecurity(storage.getName());
    indexManager = new IndexManagerEmbedded(storage);
    functionLibrary = new FunctionLibraryImpl();
    scheduler = new SchedulerImpl(youtrackDB);
    sequenceLibrary = new SequenceLibraryImpl();
    liveQueryOps = new LiveQueryHook.LiveQueryOps();
    liveQueryOpsV2 = new LiveQueryOps();
    yqlStatementCache =
        new YqlStatementCache(
            storage
                .getContextConfiguration()
                .getValueAsInteger(GlobalConfiguration.STATEMENT_CACHE_SIZE));
    gqlStatementCache =
        new GqlStatementCache(
            storage
                .getContextConfiguration()
                .getValueAsInteger(GlobalConfiguration.STATEMENT_CACHE_SIZE));

    yqlExecutionPlanCache =
        new YqlExecutionPlanCache(
            storage
                .getContextConfiguration()
                .getValueAsInteger(GlobalConfiguration.STATEMENT_CACHE_SIZE));
    this.registerListener(yqlExecutionPlanCache);

    gqlExecutionPlanCache =
        new GqlExecutionPlanCache(
            storage
                .getContextConfiguration()
                .getValueAsInteger(GlobalConfiguration.STATEMENT_CACHE_SIZE));
    this.registerListener(gqlExecutionPlanCache);

    storage
        .setStorageConfigurationUpdateListener(
            update -> {
              for (var listener : browseListeners()) {
                listener.onStorageConfigurationUpdate(storage.getName(), update);
              }
            });
  }

  public void load(DatabaseSessionEmbedded database) {
    if (loaded) {
      return;
    }

    lock.lock();
    try {
      database.executeInTx(transaction -> {
        schema.load(database);
        schema.forceSnapshot();
        indexManager.load(database);
        // The Immutable snapshot should be after index and schema that require and before
        // everything else that use it
        schema.forceSnapshot();
        security.load(database);
        functionLibrary.load(database);
        scheduler.load(database);
        sequenceLibrary.load(database);
        loaded = true;
      });
    } finally {
      lock.unlock();
    }
  }

  public void close() {
    lock.lock();
    try {
      stringCache.close();
      schema.close();
      security.close();
      indexManager.close();
      functionLibrary.close();
      scheduler.close();
      sequenceLibrary.close();
      yqlStatementCache.clear();
      gqlStatementCache.clear();
      yqlExecutionPlanCache.invalidate();
      gqlExecutionPlanCache.invalidate();
      liveQueryOps.close();
      liveQueryOpsV2.close();
      loaded = false;
    } finally {
      lock.unlock();
    }
  }

  public void reload(DatabaseSessionEmbedded database) {
    lock.lock();
    try {
      schema.reload(database);
      indexManager.reload(database);
      // The Immutable snapshot should be after index and schema that require and before everything
      // else that use it
      schema.forceSnapshot();
      security.load(database);
      functionLibrary.load(database);
      sequenceLibrary.load(database);
      scheduler.load(database);
    } finally {
      lock.unlock();
    }
  }

  public void create(DatabaseSessionEmbedded session) {
    lock.lock();
    try {
      // The root shells stay PRE-transaction (review CQ15): both creates must run as their own
      // top-level commits — joined into an outer transaction, the deferred commit would leave
      // their ChangeableRecordIds provisional when set{Schema,IndexMgr}RecordId stringifies
      // them, persisting a provisional record id into the storage configuration.
      schema.create(session);
      indexManager.create(session);

      // PHASE 1 — ONE schema transaction (D18/Q-G1) spanning every internal-class creator, the
      // O/V/E base classes and the blob registration. Every mutation routes through the
      // session's schema proxy (resolveForWrite → the tx-local schema copy), so the transaction
      // engages the metadata-write mutex on its FIRST schema write (no contention at genesis —
      // the factory monitor spans the whole create) and commits once through the schema-carry
      // path: per-class records + root payload written, every index engine (including
      // OUser.name) BUILT at commit, and the commit owns the single trailing forceSnapshot —
      // the legacy mid-create forceSnapshot is gone with the per-creator self-commits.
      session.executeInTx(transaction -> {
        security.createSecuritySchema(session);
        FunctionLibraryImpl.create(session);
        SequenceLibraryImpl.create(session);
        SchedulerImpl.create(session);

        // CREATE BASE VERTEX AND EDGE CLASSES
        var sessionSchema = session.getMetadata().getSchema();
        sessionSchema.createClass(Entity.DEFAULT_CLASS_NAME);
        sessionSchema.createClass("V");
        sessionSchema.createClass("E");

        // The $blob<i> collections physically exist since storage birth (created by
        // AbstractStorage inside the storage-create atomic operation), so genesis only
        // REGISTERS them in the schema's blob-collection set — inside this transaction a pure
        // tx-local root-payload write picked up by the commit's root diff. The registration
        // routes through the session's schema proxy (review CS47: the direct SchemaShared call
        // would self-commit and throws under an active transaction). The storage's actual
        // $blob* collections are enumerated by name — deliberately NOT re-reading
        // STORAGE_BLOB_COLLECTIONS_COUNT: a second config read routes through the
        // process-global mutable fallback and could observe a different value than storage
        // birth did, registering bogus ids or leaving physical blob collections unregistered.
        // The count is frozen at storage birth by construction. The names are snapshotted
        // defensively (review CQ14): getCollectionNames() returns a live view of the storage's
        // collection map — harmless today (a tx-local registration writes no record), but the
        // copy keeps any future write-path change from turning genesis into a
        // ConcurrentModificationException.
        for (var collectionName : List.copyOf(storage.getCollectionNames())) {
          if (BLOB_COLLECTION_NAME_PATTERN.matcher(collectionName).matches()) {
            sessionSchema.addBlobCollection(storage.getCollectionIdByName(collectionName));
          }
        }
      });

      // PHASE 2 — ONE data transaction (D18/Q-G2): the default roles and users are inserted
      // into the now-committed classes; UNIQUE enforcement on the user inserts resolves against
      // the real OUser.name engine built by the phase-1 commit (I-U4: schema built and
      // committed before any user insert; the mutex is NOT engaged here — phase 2 never
      // touches schema). The system-database skip and CREATE_DEFAULT_USERS handling live
      // inside, as does the trailing predicate-security optimization init.
      security.insertDefaultSecurity(session);

      // create geospatial classes — stays outside the schema transaction (the lucene module is
      // excluded from the build, so this is a no-op in practice)
      try {
        var factory = Indexes.getFactory(SchemaClass.INDEX_TYPE.SPATIAL.toString(),
            "LUCENE");
        if (factory instanceof DatabaseLifecycleListener listener) {
          listener.onCreate(session);
        }
      } catch (IndexException x) {
        // the index does not exist
      }

      // The genesis-completion marker is the LAST act of the sequence (design §A1/CS35): its
      // own durable write, after the phase-2 commit. The open path refuses a database without
      // it, replacing (and strictly stronger than) the old "schema is empty" open-time
      // breadcrumb the bootstrap-valid root silenced.
      storage.setProperty(GENESIS_COMPLETED_PROPERTY, "true");

      loaded = true;
    } finally {
      lock.unlock();
    }
  }

  public void reInit(AbstractStorage storage2, DatabaseSessionEmbedded database) {
    lock.lock();
    try {
      this.close();
      this.storage = storage2;
      this.init(storage2);
      database.getMetadata().init(this);
      this.load(database);
    } finally {
      lock.unlock();
    }
  }

  public SchemaShared getSchema() {
    return schema;
  }

  public SecurityInternal getSecurity() {
    return security;
  }

  public FunctionLibraryImpl getFunctionLibrary() {
    return functionLibrary;
  }

  public SchedulerImpl getScheduler() {
    return scheduler;
  }

  public SequenceLibraryImpl getSequenceLibrary() {
    return sequenceLibrary;
  }

  public LiveQueryHook.LiveQueryOps getLiveQueryOps() {
    return liveQueryOps;
  }

  public LiveQueryOps getLiveQueryOpsV2() {
    return liveQueryOpsV2;
  }

  public YqlStatementCache getYqlStatementCache() {
    return yqlStatementCache;
  }

  public GqlStatementCache getGqlStatementCache() {
    return gqlStatementCache;
  }

  public YqlExecutionPlanCache getYqlExecutionPlanCache() {
    return yqlExecutionPlanCache;
  }

  public GqlExecutionPlanCache getGqlExecutionPlanCache() {
    return gqlExecutionPlanCache;
  }

  public AbstractStorage getStorage() {
    return storage;
  }

  public YouTrackDBInternalEmbedded getYouTrackDB() {
    return youtrackDB;
  }

  public IndexManagerEmbedded getIndexManager() {
    return indexManager;
  }

  public MetadataWriteMutex getMetadataWriteMutex() {
    return metadataWriteMutex;
  }

  public synchronized <T> T getResource(final String name, final Callable<T> factory) {
    if (resources == null) {
      resources = new HashMap<>();
    }
    @SuppressWarnings("unchecked")
    var resource = (T) resources.get(name);
    if (resource == null) {
      try {
        resource = factory.call();
      } catch (Exception e) {
        throw BaseException.wrapException(
            new DatabaseException((String) null,
                String.format("instance creation for '%s' failed", name)),
            e, (String) null);
      }
      resources.put(name, resource);
    }
    return resource;
  }

  public StringCache getStringCache() {
    return this.stringCache;
  }

  public boolean isLoaded() {
    return loaded;
  }

}
