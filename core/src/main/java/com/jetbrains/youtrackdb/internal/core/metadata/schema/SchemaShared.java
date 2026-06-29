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
package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.internal.common.concur.resource.CloseableInStorage;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableInteger;
import com.jetbrains.youtrackdb.internal.common.util.ArrayUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaNotCreatedException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection.CollectionSelectionFactory;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.GlobalProperty;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;
import com.jetbrains.youtrackdb.internal.core.query.collection.links.LinkSet;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shared schema class. It's shared by all the database instances that point to the same storage.
 */
public abstract class SchemaShared implements CloseableInStorage {

  // Version 6 introduces the per-class-record format: the root schema record links one standalone
  // record per class instead of holding every class in a single embedded set. Versions 4 and 5 are
  // the previous embedded-set formats; both are now reject-and-redirect-only at open time, since
  // their bytes cannot be parsed by the link-set reader. Migration is operator-driven export/import.
  public static final int CURRENT_VERSION_NUMBER = 6;
  public static final int VERSION_NUMBER_V4 = 4;
  // VERSION_NUMBER_V5 marked the 2.0-M1/2.0-M2 embedded-set form; retained for reference only.
  public static final int VERSION_NUMBER_V5 = 5;

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  /**
   * Whether the current thread holds this schema's write lock. Used by the metadata-write mutex
   * engage-order assertion to prove the mutex is engaged strictly above this shared metadata lock
   * (never from inside its acquisition), which is what keeps the four-lock order acyclic.
   */
  public boolean isWriteLockHeldByCurrentThread() {
    return lock.isWriteLockedByCurrentThread();
  }

  protected final Map<String, SchemaClassImpl> classes = new HashMap<>();
  protected final Int2ObjectOpenHashMap<SchemaClassImpl> collectionsToClasses =
      new Int2ObjectOpenHashMap<>();

  /**
   * The single collection id an abstract class carries. An abstract class has no real storage
   * collection, so it owns only this marker; it never appears in {@link #collectionsToClasses} or in
   * the storage layer.
   */
  public static final int ABSTRACT_COLLECTION_ID = -1;

  /**
   * The highest (closest to zero) id a provisional collection can carry. A class created inside a
   * schema transaction does not allocate a real storage collection during the transaction; it
   * carries a provisional id drawn from the sub-range {@code <= -2}, resolved to a real id at commit
   * (mirroring temp RIDs). The sub-range starts at {@code -2} rather than {@code -1} so it cannot
   * collide with {@link #ABSTRACT_COLLECTION_ID}: the schema layer tests {@code collectionId < 0} to
   * spot a special id, so a provisional id that fell on {@code -1} would be mistaken for the abstract
   * marker. The in-memory maps treat a provisional id as a pending-real id (reverse map populated,
   * uniqueness validated); the file/storage layer keeps skipping every negative id.
   */
  public static final int PROVISIONAL_COLLECTION_ID_CEILING = -2;

  /**
   * Whether {@code collectionId} is a provisional id allocated for a transaction-local create
   * (drawn from the {@code <= -2} sub-range), as distinct from the abstract-class marker
   * {@link #ABSTRACT_COLLECTION_ID} ({@code -1}) and from a real (non-negative) collection id.
   */
  public static boolean isProvisionalCollectionId(int collectionId) {
    return collectionId <= PROVISIONAL_COLLECTION_ID_CEILING;
  }

  /**
   * Monotonically increasing counter for generating unique collection names.
   * Each new collection gets a name like {@code <lowercase_classname>_<counter>}.
   * Protected by the schema write lock.
   */
  protected int collectionCounter;

  private final CollectionSelectionFactory collectionSelectionFactory =
      new CollectionSelectionFactory();

  private final ModifiableInteger modificationCounter = new ModifiableInteger();
  private final List<GlobalPropertyImpl> properties = new ArrayList<>();
  private final Map<String, GlobalPropertyImpl> propertiesByNameType = new HashMap<>();
  private IntOpenHashSet blobCollections = new IntOpenHashSet();
  private volatile int version = 0;
  private volatile RecordIdInternal identity;
  protected volatile ImmutableSchema snapshot;

  /**
   * True only for a transaction-local copy built by {@link #copyForTx}. A tx-local copy is the
   * private working schema a schema-changing transaction mutates in isolation; its mutations defer
   * to the user transaction's commit instead of persisting eagerly. The de-guarded mutation entry
   * points read this flag to decide whether to take the legacy top-level save path (committed
   * instance, no active transaction) or the transaction-local path (record the change and let the
   * commit promote it). The committed shared instance leaves this {@code false}.
   */
  protected boolean txLocal;

  private final ReentrantLock snapshotLock = new ReentrantLock();

  protected static Set<String> internalClasses = new HashSet<>();

  static {
    internalClasses.add("ouser");
    internalClasses.add(Role.CLASS_NAME.toLowerCase(Locale.ROOT));
    internalClasses.add("osecuritypolicy");
    internalClasses.add("oidentity");
    internalClasses.add("ofunction");
    internalClasses.add("osequence");
    internalClasses.add("otrigger");
    internalClasses.add("oschedule");
    internalClasses.add("orids");
    internalClasses.add("o");
    internalClasses.add("v");
    internalClasses.add("e");
    internalClasses.add("le");
  }

  protected static final class CollectionIdsAreEmptyException extends Exception {

  }

  public SchemaShared() {
  }

  /**
   * Builds a private, transaction-scoped copy of this committed schema for a schema-changing
   * transaction to mutate in isolation. The copy is seeded by a re-parse of the committed root
   * record rather than a field-level clone: each {@link SchemaClassImpl} binds back to its
   * {@link SchemaShared} through a final {@code owner} field and links to its relatives by direct
   * object reference, so a clone would leave those references pointing at the shared instances. The
   * re-parse constructs fresh class objects bound to the copy, and the cross-class derived state a
   * schema write recomputes (inheritance, {@code polymorphicCollectionIds}, subclass sets, the
   * global-property table) stays inside the copy with no extra code.
   *
   * <p>The seed reads the committed root record and re-parses it: {@code copy.fromStream(session,
   * session.load(identity))}. The root record is loaded read-only: the seed does not serialize
   * through {@link #toStream}, so it neither dirties the committed per-class records into the
   * caller's transaction nor rebinds any committed class's record id. Every committed schema change
   * persists the root record synchronously under this same write lock (the
   * {@code releaseSchemaWriteLock} to {@code saveInternal} path), so the persisted root record the
   * seed reads is the live committed state while the lock is held. The re-parse rebinds each class's
   * committed per-class record RID from the {@code "classes"} link set (the per-class-record
   * format) by loading each per-class record read-only, so a commit later writes the right record.
   * The committed root {@code identity} is copied by value onto the new instance first (a shared
   * mutable id reference must not link the committed instance and the copy), so the copy serializes
   * back to the same root record at commit.
   *
   * <p>The read of the committed root record and per-class records runs under this instance's schema
   * write lock, which makes the read of the committed class graph exclusive against concurrent
   * committed-schema mutation. The copy's own {@code fromStream} takes the copy's (separate) write
   * lock internally. The read-only record loads during the re-parse ride the caller's already-open
   * user transaction; this method does not open or commit a transaction of its own, because the
   * whole point of the tx-local view is that the change defers to the user transaction's commit.
   *
   * @param session the session whose open transaction the read-only record loads ride; a
   *                transaction must already be open
   * @return a fresh {@link SchemaShared} of this instance's concrete type, private to the caller
   */
  public SchemaShared copyForTx(DatabaseSessionEmbedded session) {
    // The re-parse loads records and must ride an already-open user transaction; a seed run outside
    // a transaction would auto-commit its loads instead of deferring to the user transaction.
    assert session.getTransactionInternal().isActive()
        : "copyForTx must run inside the caller's open user transaction";
    lock.writeLock().lock();
    try {
      // Read the committed root record read-only: loading it and reading its properties does not
      // enrol it (or the per-class records fromStream loads) as dirty in the caller's transaction,
      // and nothing rebinds a committed class's record id. Under the write lock this persisted
      // record equals the live committed state (saveInternal persists synchronously under the same
      // lock on every committed schema change).
      final EntityImpl committedRoot = session.load(identity);
      // A bootstrapped committed schema always carries global properties in its root record; the
      // fromStream re-parse only triggers a self-save (which throws inside the active user
      // transaction) when global properties are absent, so a missing list means an unseedable
      // schema.
      assert committedRoot.getProperty("globalProperties") != null
          : "copyForTx requires a bootstrapped committed schema carrying global properties";
      final var copy = newInstanceForCopy();
      // Mark the copy tx-local before re-parsing so the de-guarded mutation entry points reached
      // during fromStream (and every later mutation against the copy) take the transaction-local
      // path instead of the legacy eager-persist path.
      copy.txLocal = true;
      // Copy the committed root identity by value before the re-parse: the copy must serialize back
      // to the same root record at commit, and value-copying avoids sharing a mutable record-id
      // reference whose in-place promotion would otherwise be observed by both instances.
      copy.identity = this.identity.copy();
      copy.fromStream(session, committedRoot);
      return copy;
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Creates a fresh, empty instance of this {@link SchemaShared}'s concrete type for {@link
   * #copyForTx} to re-parse into. A subclass returns {@code new <ConcreteType>()}; the design's
   * {@code new SchemaShared()} is spelled this way because {@link SchemaShared} is abstract.
   */
  protected abstract SchemaShared newInstanceForCopy();

  @Nullable public static Character checkClassNameIfValid(String name) throws SchemaException {
    if (name == null) {
      throw new IllegalArgumentException("Name is null");
    }

    name = name.trim();
    final var nameSize = name.length();

    if (nameSize == 0) {
      throw new IllegalArgumentException("Name is empty");
    }

    for (var i = 0; i < nameSize; ++i) {
      final var c = name.charAt(i);
      if (c == ':')
      // INVALID CHARACTER
      {
        return c;
      }
    }

    return null;
  }

  @SuppressWarnings("JavaExistingMethodCanBeUsed")
  @Nullable public static Character checkPropertyNameIfValid(String iName) {
    if (iName == null) {
      throw new IllegalArgumentException("Name is null");
    }

    iName = iName.trim();

    final var nameSize = iName.length();

    if (nameSize == 0) {
      throw new IllegalArgumentException("Name is empty");
    }

    for (var i = 0; i < nameSize; ++i) {
      final var c = iName.charAt(i);
      if (c == ':' || c == ',' || c == ';' || c == ' ' || c == '=')
      // INVALID CHARACTER
      {
        return c;
      }
    }

    return null;
  }

  @Nullable public static Character checkIndexNameIfValid(String iName) {
    if (iName == null) {
      throw new IllegalArgumentException("Name is null");
    }

    iName = iName.trim();

    final var nameSize = iName.length();

    if (nameSize == 0) {
      throw new IllegalArgumentException("Name is empty");
    }

    for (var i = 0; i < nameSize; ++i) {
      final var c = iName.charAt(i);
      if (c == ':' || c == ',' || c == ';' || c == ' ' || c == '=')
      // INVALID CHARACTER
      {
        return c;
      }
    }

    return null;
  }

  public ImmutableSchema makeSnapshot(DatabaseSessionEmbedded session) {
    var snapshot = this.snapshot;

    if (snapshot == null) {
      acquireSchemaReadLock();
      try {
        snapshotLock.lock();
        try {
          if (this.snapshot == null) {
            this.snapshot = new ImmutableSchema(this, session);
          }

          return this.snapshot;
        } finally {
          snapshotLock.unlock();
        }
      } finally {
        releaseSchemaReadLock();
      }
    }

    return snapshot;
  }

  public void forceSnapshot() {
    if (snapshot == null) {
      return;
    }

    snapshotLock.lock();
    try {
      snapshot = null;
    } finally {
      snapshotLock.unlock();
    }
  }

  public CollectionSelectionFactory getCollectionSelectionFactory() {
    return collectionSelectionFactory;
  }

  public int countClasses(DatabaseSessionEmbedded session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_READ);

    acquireSchemaReadLock();
    try {
      return classes.size();
    } finally {
      releaseSchemaReadLock();
    }
  }

  public SchemaClassImpl createClass(DatabaseSessionEmbedded sesion, final String className) {
    return createClass(sesion, className, null, (int[]) null);
  }

  public SchemaClassImpl createClass(
      DatabaseSessionEmbedded session, final String iClassName, final SchemaClassImpl iSuperClass) {
    return createClass(session, iClassName, iSuperClass, null);
  }

  public SchemaClassImpl createClass(
      DatabaseSessionEmbedded session, String iClassName, SchemaClassImpl... superClasses) {
    return createClass(session, iClassName, null, superClasses);
  }

  public SchemaClassImpl getOrCreateClass(DatabaseSessionEmbedded session,
      final String iClassName) {
    return getOrCreateClass(session, iClassName, (SchemaClassImpl) null);
  }

  public SchemaClassImpl getOrCreateClass(
      DatabaseSessionEmbedded session, final String iClassName, final SchemaClassImpl superClass) {
    return getOrCreateClass(
        session, iClassName,
        superClass == null ? new SchemaClassImpl[0] : new SchemaClassImpl[] {superClass});
  }

  public abstract SchemaClassImpl getOrCreateClass(
      DatabaseSessionEmbedded session, final String iClassName,
      final SchemaClassImpl... superClasses);

  public SchemaClassImpl createAbstractClass(DatabaseSessionEmbedded session,
      final String className) {
    return createClass(session, className, null, new int[] {-1});
  }

  public SchemaClassImpl createAbstractClass(
      DatabaseSessionEmbedded session, final String className, final SchemaClassImpl superClass) {
    return createClass(session, className, superClass, new int[] {-1});
  }

  public SchemaClassImpl createAbstractClass(
      DatabaseSessionEmbedded session, String iClassName, SchemaClassImpl... superClasses) {
    return createClass(session, iClassName, new int[] {-1}, superClasses);
  }

  public SchemaClassImpl createClass(
      DatabaseSessionEmbedded session,
      final String className,
      final SchemaClassImpl superClass,
      int[] collectionIds) {
    return createClass(session, className, collectionIds, superClass);
  }

  public abstract SchemaClassImpl createClass(
      DatabaseSessionEmbedded session,
      final String className,
      int[] collectionIds,
      SchemaClassImpl... superClasses);

  public abstract SchemaClassImpl createClass(
      DatabaseSessionEmbedded session,
      final String className,
      int collections,
      SchemaClassImpl... superClasses);

  public abstract void checkEmbedded(DatabaseSessionEmbedded session);

  void checkCollectionCanBeAdded(DatabaseSessionEmbedded session, int collectionId,
      SchemaClassImpl cls) {
    acquireSchemaReadLock();
    try {
      // Skip only the abstract-class marker. A provisional id (<= -2) is validated like a real id:
      // the in-memory maps treat it as pending-real, so a duplicate provisional id within the
      // transaction is rejected here.
      if (collectionId == ABSTRACT_COLLECTION_ID) {
        return;
      }

      if (blobCollections.contains(collectionId)) {
        throw new SchemaException(session.getDatabaseName(),
            "Collection with id " + collectionId + " already belongs to Blob");
      }

      final var existingCls = collectionsToClasses.get(collectionId);

      if (existingCls != null && (cls == null || !cls.equals(existingCls))) {
        throw new SchemaException(session.getDatabaseName(),
            "Collection with id "
                + collectionId
                + " already belongs to the class '"
                + collectionsToClasses.get(collectionId)
                + "'");
      }
    } finally {
      releaseSchemaReadLock();
    }
  }

  public SchemaClassImpl getClassByCollectionId(int collectionId) {
    acquireSchemaReadLock();
    try {
      return collectionsToClasses.get(collectionId);
    } finally {
      releaseSchemaReadLock();
    }
  }

  /**
   * The set of real (non-negative) storage collection ids this schema's classes own. Provisional
   * ids ({@code <= -2}) a tx-local copy may carry are excluded, because they back no real storage
   * collection yet. The commit diffs the committed schema's set against the tx-local schema's set
   * (a set difference) to find collections to create (tx-local minus committed) and to drop
   * (committed minus tx-local); diffing by collection id rather than class name keeps a rename
   * structurally inert.
   */
  @Nonnull
  public IntSet getRealCollectionIds() {
    acquireSchemaReadLock();
    try {
      final var ids = new IntOpenHashSet(collectionsToClasses.size());
      for (final var collectionId : collectionsToClasses.keySet()) {
        if (collectionId >= 0) {
          ids.add(collectionId);
        }
      }
      return ids;
    } finally {
      releaseSchemaReadLock();
    }
  }

  /**
   * Patches every provisional collection id in this (tx-local) schema to the real id the commit
   * created for it, then rebuilds the {@code collectionsToClasses} reverse map wholesale so the
   * resolved real ids index their classes and the provisional ids are gone. Run inside the commit
   * under this copy's write lock, after the real collections are created and before any per-class
   * record serializes through {@link #toStream}: a record that serialized a provisional id would
   * lose its class's collections at the next open. The two-pass order (patch all classes' arrays,
   * then rebuild the reverse map once) settles cross-class references before the map is rebuilt, so
   * a multi-class commit resolves correctly.
   *
   * @param resolution maps each provisional id ({@code <= -2}) this transaction allocated to its
   *     real id ({@code >= 0}); empty when the transaction created no class.
   */
  public void resolveProvisionalCollectionIds(@Nonnull Int2IntMap resolution) {
    lock.writeLock().lock();
    try {
      if (resolution.isEmpty()) {
        return;
      }
      for (final var cls : classes.values()) {
        cls.replaceProvisionalCollectionIds(resolution);
      }
      // Rebuild the reverse map wholesale after every class is patched, so cross-class references
      // settle before the map indexes them. A provisional key is never re-added: addCollectionClassMap
      // re-reads each class's now-real collection ids.
      collectionsToClasses.clear();
      for (final var cls : classes.values()) {
        addCollectionClassMap(cls);
      }
      // After resolution every class's id arrays must be provisional-free. replaceProvisionalCollectionIds
      // patches only ids that are both provisional and present in the resolution map, so a provisional
      // id allocated by a producer but never recorded into the map (a missed recordResolvedCollectionId)
      // would survive silently and then reach toStream as durable corruption. This catches the
      // producer/consumer mismatch at the resolution site, one step before the matching no-provisional-id
      // precondition assert in SchemaClassImpl.toStream guards the actual durable-write boundary.
      assert allCollectionIdsResolved()
          : "a provisional collection id survived resolveProvisionalCollectionIds; a producer "
              + "allocated an id never recorded into the resolution map";
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Whether every class in this (tx-local) schema carries only real or abstract collection ids after
   * resolution — the postcondition {@link #resolveProvisionalCollectionIds} asserts. Reads the
   * per-class id fields directly (the caller holds this schema's write lock) so the scan does not
   * re-enter the schema read lock. Test-time guard only; never consulted in production.
   */
  private boolean allCollectionIdsResolved() {
    for (final var cls : classes.values()) {
      if (isProvisionalCollectionId(cls.defaultCollectionId)) {
        return false;
      }
      for (final var id : cls.collectionIds) {
        if (isProvisionalCollectionId(id)) {
          return false;
        }
      }
      for (final var id : cls.polymorphicCollectionIds) {
        if (isProvisionalCollectionId(id)) {
          return false;
        }
      }
    }
    return true;
  }

  public abstract void dropClass(DatabaseSessionEmbedded session, final String className);

  /**
   * Reloads the schema inside a storage's shared lock.
   */
  public void reload(DatabaseSessionEmbedded session) {
    lock.writeLock().lock();
    try {
      session.executeInTx(
          transaction -> {
            identity = RecordIdInternal.fromString(
                session.getStorage().getSchemaRecordId(), false);

            EntityImpl entity = session.load(identity);
            fromStream(session, entity);
            forceSnapshot();
          });
    } finally {
      lock.writeLock().unlock();
    }
  }

  public boolean existsClass(final String iClassName) {
    if (iClassName == null) {
      return false;
    }

    acquireSchemaReadLock();
    try {
      return classes.containsKey(iClassName);
    } finally {
      releaseSchemaReadLock();
    }
  }

  @Nullable public SchemaClassImpl getClass(final Class<?> iClass) {
    if (iClass == null) {
      return null;
    }

    return getClass(iClass.getSimpleName());
  }

  @Nullable public SchemaClassImpl getClass(final String iClassName) {
    if (iClassName == null) {
      return null;
    }

    acquireSchemaReadLock();
    try {
      return classes.get(iClassName);
    } finally {
      releaseSchemaReadLock();
    }
  }

  public void acquireSchemaReadLock() {
    lock.readLock().lock();
  }

  public void releaseSchemaReadLock() {
    lock.readLock().unlock();
  }

  public void acquireSchemaWriteLock(DatabaseSessionEmbedded session) {
    lock.writeLock().lock();
    modificationCounter.increment();
  }

  public void releaseSchemaWriteLock(DatabaseSessionEmbedded session) {
    releaseSchemaWriteLock(session, true);
  }

  public void releaseSchemaWriteLock(DatabaseSessionEmbedded session, final boolean iSave) {
    int count;
    try {
      if (modificationCounter.intValue() == 1) {
        // if it is embedded storage modification of schema is done by internal methods otherwise it
        // is done by
        // by sql commands and we need to reload local replica

        if (iSave) {
          if (session.getStorage() instanceof AbstractStorage) {
            saveInternal(session);
          } else {
            reload(session);
          }
        } else {
          snapshot = null;
        }
        //noinspection NonAtomicOperationOnVolatileField
        version++;
      }
    } finally {
      modificationCounter.decrement();
      count = modificationCounter.intValue();
      lock.writeLock().unlock();
    }

    assert count >= 0;
  }

  void changeClassName(
      DatabaseSessionEmbedded session,
      final String oldName,
      final String newName,
      final SchemaClassImpl cls) {

    if (oldName != null && oldName.equals(newName)) {
      throw new IllegalArgumentException(
          "Class '" + oldName + "' cannot be renamed with the same name");
    }

    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);

      if (newName != null
          && classes.containsKey(newName)) {
        throw new IllegalArgumentException("Class '" + newName + "' is already present in schema");
      }

      if (oldName != null) {
        classes.remove(oldName);
      }
      if (newName != null) {
        classes.put(newName, cls);
      }

      if (txLocal && newName != null && !session.isSeedingTxSchemaState()) {
        // Transaction-local rename: record the new name only. A renamed class keeps its committed
        // per-class record RID, so the commit rewrites that record under the new name. The old name
        // must not be recorded: a name in the changed-class set that is absent from the tx-local
        // copy reads as a drop at commit, which would delete the renamed class's record. The seeding
        // guard mirrors the create and drop sites; copyForTx re-parses committed classes through
        // other paths and never through a rename, but the guard keeps the recording uniform.
        var txState = session.getTxSchemaState();
        if (txState == null) {
          throw new IllegalStateException(
              "a tx-local rename must run with a seeded tx-local schema state");
        }
        txState.markClassChanged(newName);
      }

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  /**
   * Binds EntityImpl to POJO.
   */
  public void fromStream(DatabaseSessionEmbedded session, EntityImpl entity) {
    lock.writeLock().lock();
    modificationCounter.increment();
    try {
      // READ CURRENT SCHEMA VERSION
      final Integer schemaVersion = entity.getProperty("schemaVersion");
      if (schemaVersion == null) {
        LogManager.instance()
            .error(
                this,
                "Database's schema is empty! Recreating the system classes and allow the opening of"
                    + " the database but double check the integrity of the database",
                null);
        return;
      } else if (schemaVersion != CURRENT_VERSION_NUMBER) {
        // Accept exactly the current format. Both a pre-bump version-4 database and the legacy
        // version-5 (2.0-M1/M2) form now reject-and-redirect to export/import rather than falling
        // through into the per-class link-set parser, which would mis-read their embedded classes.
        // HANDLE SCHEMA UPGRADE
        throw new ConfigurationException(
            session.getDatabaseName(),
            "Database schema is different. Please export your old database with the previous"
                + " version of YouTrackDB and reimport it using the current one.");
      }

      properties.clear();
      propertiesByNameType.clear();
      List<EntityImpl> globalProperties = entity.getProperty("globalProperties");
      var hasGlobalProperties = false;
      if (globalProperties != null) {
        hasGlobalProperties = true;
        for (var oDocument : globalProperties) {
          var prop = new GlobalPropertyImpl();
          prop.fromEntity(oDocument);
          ensurePropertiesSize(prop.getId());
          properties.set(prop.getId(), prop);
          propertiesByNameType.put(prop.getName() + "|" + prop.getType().name(), prop);
        }
      }
      // REGISTER ALL THE CLASSES
      collectionsToClasses.clear();

      final Map<String, SchemaClassImpl> newClasses = new HashMap<>();

      // The root record links one standalone record per class (the per-class-record format).
      // Load each linked record and pair it with its RID so the class can bind its own record
      // identity, exactly as IndexManagerAbstract.load binds each index. The record list also
      // drives the inheritance-rebuild pass below.
      final LinkSet classLinks = entity.getLinkSet("classes");
      final List<EntityImpl> storedClasses = new ArrayList<>();
      if (classLinks != null) {
        for (var link : classLinks) {
          var classRid = link.getIdentity();
          if (classRid == null || !classRid.isPersistent()) {
            throw new ConfigurationException(
                session.getDatabaseName(),
                "Schema root links a class record with a non-persistent identity (" + classRid
                    + "). The schema link set is damaged. Please export your database with a"
                    + " previous version of YouTrackDB and reimport it using the current one.");
          }
          EntityImpl classRecord;
          try {
            classRecord = session.load(classRid);
          } catch (RuntimeException loadFailure) {
            // Preserve the original load failure as the cause so the dangling link is diagnosable.
            throw (ConfigurationException) new ConfigurationException(
                session.getDatabaseName(),
                "Schema root links a class record at " + classRid + " that cannot be loaded. The"
                    + " schema link set is damaged. Please export your database with a previous"
                    + " version of YouTrackDB and reimport it using the current one.")
                .initCause(loadFailure);
          }
          storedClasses.add(classRecord);
        }
      }

      for (var c : storedClasses) {
        String name = c.getProperty("name");

        SchemaClassImpl cls;
        if (classes.containsKey(name)) {
          cls = classes.get(name);
          cls.fromStream(c);
        } else {
          cls = createClassInstance(name);
          cls.fromStream(c);
        }
        var boundRid = c.getIdentity();
        assert boundRid != null && boundRid.isPersistent()
            : "schema class '" + name + "' loaded from the root link set must carry a persistent"
                + " record id, got " + boundRid;
        cls.setRecordId(boundRid);

        newClasses.put(cls.getName(), cls);
        addCollectionClassMap(cls);
      }

      classes.clear();
      classes.putAll(newClasses);

      // REBUILD THE INHERITANCE TREE
      Collection<String> superClassNames;
      String legacySuperClassName;
      List<SchemaClassImpl> superClasses;
      SchemaClassImpl superClass;

      for (var c : storedClasses) {
        superClassNames = c.getProperty("superClasses");
        legacySuperClassName = c.getProperty("superClass");
        if (superClassNames == null) {
          superClassNames = new ArrayList<>();
        }
        if (legacySuperClassName != null && !superClassNames.contains(legacySuperClassName)) {
          superClassNames.add(legacySuperClassName);
        }

        if (!superClassNames.isEmpty()) {
          // HAS A SUPER CLASS or CLASSES
          var cls =
              classes.get((String) c.getProperty("name"));
          superClasses = new ArrayList<>(superClassNames.size());
          for (var superClassName : superClassNames) {

            superClass = classes.get(superClassName);

            // Defensive fallback: legacy databases may have stored lowercased superclass
            // names. If exact-match fails, try case-insensitive scan.
            if (superClass == null) {
              for (var candidate : classes.values()) {
                if (candidate.getName().equalsIgnoreCase(superClassName)) {
                  superClass = candidate;
                  LogManager.instance()
                      .warn(
                          this,
                          "Superclass name '%s' in class '%s' resolved via"
                              + " case-insensitive fallback to '%s'. Consider updating the"
                              + " schema record.",
                          superClassName,
                          cls.getName(),
                          candidate.getName());
                  break;
                }
              }
            }

            if (superClass == null) {
              throw new ConfigurationException(
                  session.getDatabaseName(), "Super class '"
                      + superClassName
                      + "' was declared in class '"
                      + cls.getName()
                      + "' but was not found in schema. Remove the dependency or create the class"
                      + " to continue.");
            }
            superClasses.add(superClass);
          }
          cls.setSuperClassesInternal(session, superClasses, false);
        }
      }

      // COLLECTION COUNTER
      Integer persistedCounter = entity.getProperty("collectionCounter");
      if (persistedCounter != null) {
        collectionCounter = persistedCounter;
      } else {
        // Pre-migration schema: initialize counter safely above any existing _N suffixes.
        collectionCounter = initCollectionCounterFromExisting(session);
      }

      // VIEWS

      if (entity.hasProperty("blobCollections")) {
        blobCollections = new IntOpenHashSet(entity.getEmbeddedSet("blobCollections"));
      }

      if (!hasGlobalProperties) {
        if (session.getStorage() instanceof AbstractStorage) {
          saveInternal(session);
        }
      }

    } finally {
      //noinspection NonAtomicOperationOnVolatileField
      version++;
      modificationCounter.decrement();
      lock.writeLock().unlock();
    }
  }

  protected abstract SchemaClassImpl createClassInstance(String name);

  /**
   * Binds POJO to EntityImpl. Serializes every live class plus the root non-link payload; the
   * committed (non-transaction-local) save path uses this full write, where rewriting the whole
   * schema is correct because there is no per-transaction changed-class signal to narrow it.
   */
  public EntityImpl toStream(@Nonnull DatabaseSessionEmbedded session) {
    // changedClassNames == null selects the full write (every class record); writeRootPayload ==
    // true always writes the root payload. This is the legacy behavior the committed save relies on.
    return toStream(session, null, true);
  }

  /**
   * Serializes the schema selectively for the commit-time write (the per-class-record write
   * amplification win): writes only the changed classes' per-class records, the link-set deltas for
   * created and dropped classes, and the root non-link payload only when it actually changed. A
   * class rename rewrites that class's record but leaves the root link set and payload untouched, so
   * the root is not rewritten; a property-create grows the global-property table on the root, so the
   * root must be rewritten (the root-omission regression this method's payload guard prevents:
   * omitting the root after a property-create restarts into a dangling global reference and a
   * collection-counter that reverts and regenerates a colliding collection name).
   *
   * @param changedClassNames the names of the classes the transaction touched (creates, alters,
   *     drops, renames), matched case-insensitively against the live class names; when {@code null}
   *     every live class is written (the full-write legacy path used by the committed save).
   * @param writeRootPayload whether the root's non-link payload (global-property table, collection
   *     counter, blob-collections set, schema version) changed and so must be persisted. Ignored —
   *     the payload is always written — when {@code changedClassNames} is {@code null}; on the
   *     selective path a {@code false} value keeps the root record out of the write set unless the
   *     class link set also changed.
   */
  public EntityImpl toStream(
      @Nonnull DatabaseSessionEmbedded session,
      @Nullable Set<String> changedClassNames,
      boolean writeRootPayload) {
    // The body mutates shared state (per-class recordId binds, link-set adds/removes) and writes
    // records. The caller holds the schema write lock, and that write lock is the exclusivity
    // guarantee for these mutations; no additional synchronization is taken here.
    assert lock.isWriteLockedByCurrentThread()
        : "toStream() mutates shared schema state and must be called under the schema write lock";

    // Case-insensitive lookup of the changed-class set: markClassChanged records the name as the
    // mutation saw it (a create records its created name, a rename records the new name), and class
    // lookup is case-insensitive throughout the schema layer, so match on the lowercased name rather
    // than risk a case mismatch silently skipping a changed class's record write.
    final Set<String> changedLower;
    if (changedClassNames == null) {
      changedLower = null;
    } else {
      changedLower = new HashSet<>(changedClassNames.size());
      for (var name : changedClassNames) {
        changedLower.add(name.toLowerCase(Locale.ENGLISH));
      }
    }

    EntityImpl entity = session.load(identity);

    // The root record links one standalone record per class, mirroring the index manager's
    // CONFIG_INDEXES link set. Aliases that share an impl are written once; the link set ends
    // up holding exactly the live classes' record RIDs. Records that were linked before but no
    // longer back a live class (a dropped class) are deleted and unlinked below.
    Set<SchemaClassImpl> realClasses = new HashSet<>(classes.values());

    // Read the existing link set read-only first (getLinkSet, not getOrCreateLinkSet) so merely
    // inspecting membership does not dirty the root record: on the selective path a commit that
    // changed neither the link set nor the root payload must leave the root untouched. The mutable
    // handle is acquired lazily, only once a link add or remove is actually required.
    final LinkSet existingLinks = entity.getLinkSet("classes");
    final Set<RID> previouslyLinked = new HashSet<>();
    if (existingLinks != null) {
      for (var link : existingLinks) {
        previouslyLinked.add(link.getIdentity());
      }
    }
    LinkSet classLinks = null;

    final Set<RID> liveRecords = new HashSet<>();
    for (var c : realClasses) {
      EntityImpl classRecord;
      var boundRid = c.getRecordId();
      if (boundRid == null || !boundRid.isPersistent()) {
        // New class, or a class whose previous save rolled back before its temporary record id
        // became persistent. In both cases allocate a fresh standalone record: its temporary RID
        // becomes permanent at commit and the ChangeableRecordId mutates in place, so the bound
        // field and the link both resolve to the persistent RID without a second write. Reusing a
        // non-persistent id would load against a record that never persisted and wedge every
        // future save, so the rollback case self-heals here rather than requiring a reload. A new
        // class is always written regardless of the changed-class filter (it has no record yet),
        // and its link must join the set, so acquire the mutable link handle now.
        classRecord = session.newInternalInstance();
        c.setRecordId(classRecord.getIdentity());
        if (classLinks == null) {
          classLinks = entity.getOrCreateLinkSet("classes");
        }
        classLinks.add(classRecord.getIdentity());
        c.toStream(session, classRecord);
      } else if (changedLower == null
          || changedLower.contains(c.getName().toLowerCase(Locale.ENGLISH))) {
        // An existing class that changed (or every class on the full-write path): rewrite its
        // per-class record in place. The bound RID is unchanged, so its link is already in the set.
        classRecord = session.load(boundRid);
        c.toStream(session, classRecord);
      } else {
        // An unchanged class on the selective path: do not rewrite its per-class record, so it stays
        // out of the commit working set. That is the write-amplification win (no WAL units, no page
        // write for an unchanged class). The record is still loaded read-only, which warms it into
        // the session cache without dirtying it (a read enrols no record operation). The commit-time
        // promotion re-parses the committed schema from every linked per-class record after the
        // atomic operation ends, where a genuine cache-miss read of an untouched record would fail
        // ("atomic operation is not active") on a disk-backed engine; loading it here, inside the
        // active atomic operation, keeps the promotion read serving from the cache on every storage
        // profile. The write-amplification win is about writes, not reads, so the read is free of it.
        session.load(boundRid);
      }
      assert c.getRecordId() != null
          : "schema class '" + c.getName() + "' must have a bound record id before it joins the"
              + " live-record set written to the root link set";
      liveRecords.add(c.getRecordId());
    }

    // Drop the records that backed classes removed since the last save, and unlink them. A drop
    // mutates the link set, so acquire the mutable handle on the first removal.
    for (var rid : previouslyLinked) {
      if (!liveRecords.contains(rid)) {
        if (classLinks == null) {
          classLinks = entity.getOrCreateLinkSet("classes");
        }
        classLinks.remove(rid);
        EntityImpl droppedRecord = session.load(rid);
        droppedRecord.delete();
      }
    }

    // Write the root non-link payload when the caller signals it changed, or whenever the class
    // link set changed (a create or drop already dirtied the root, so writing the current payload
    // keeps the record self-consistent at no extra record cost). On the selective path with no
    // payload change and no link change, the root record is left entirely untouched: it was never
    // dirtied above, so it stays out of the commit working set (the write-amplification win for a
    // rename or an alter that reuses an existing global-property slot).
    final boolean linkSetChanged = classLinks != null;
    if (changedLower == null || writeRootPayload || linkSetChanged) {
      entity.setProperty("schemaVersion", CURRENT_VERSION_NUMBER);
      List<Entity> globalProperties = session.newEmbeddedList();
      for (var globalProperty : properties) {
        if (globalProperty != null) {
          globalProperties.add(globalProperty.toEntity(session));
        }
      }
      entity.setProperty("globalProperties", globalProperties, PropertyType.EMBEDDEDLIST);
      entity.setProperty("collectionCounter", collectionCounter);

      Object propertyValue = session.newEmbeddedSet(blobCollections);
      entity.setProperty("blobCollections", propertyValue, PropertyType.EMBEDDEDSET);
    }
    return entity;
  }

  /**
   * Whether this schema's root non-link payload (the global-property table, the collection counter,
   * and the blob-collections set) differs from {@code committed}'s. The commit consults this on a
   * tx-local schema against the committed schema to decide whether the selective
   * {@link #toStream(DatabaseSessionEmbedded, Set, boolean)} must rewrite the root record: a
   * property-create grows the global-property table, an alter-add-collection advances the counter,
   * and a blob-collection registration grows the blob set, each of which lives only on the root
   * record, so leaving the root out of the write set after one of them would lose the payload at the
   * next open. A rename or an alter that reuses an existing global-property slot changes none of
   * this, so the root stays out of the write set.
   *
   * <p>The global-property table is append-only ({@link #findOrCreateGlobalProperty} only adds, never
   * removes or rewrites a slot), so comparing the slot count plus each slot's name and type catches
   * every table change. The comparison reads both schemas' fields directly; the caller holds the
   * relevant write locks during commit, so no read lock is taken here.
   *
   * @param committed the committed schema instance to compare against; must not be {@code null}.
   */
  public boolean rootPayloadDiffersFrom(@Nonnull SchemaShared committed) {
    if (collectionCounter != committed.collectionCounter) {
      return true;
    }
    if (!blobCollections.equals(committed.blobCollections)) {
      return true;
    }
    if (properties.size() != committed.properties.size()) {
      return true;
    }
    // Compare slot by slot through a string signature so a null padding slot (the global-property
    // table can be sparse) yields a null signature on both sides and compares equal, without a
    // separate null-branch cascade. A real entry's signature changes when its name or type changes.
    for (var id = 0; id < properties.size(); id++) {
      if (!Objects.equals(
          globalPropertySignature(properties.get(id)),
          globalPropertySignature(committed.properties.get(id)))) {
        return true;
      }
    }
    return false;
  }

  /**
   * The name-and-type signature of a global-property slot, or {@code null} for an empty (padding)
   * slot. Used by {@link #rootPayloadDiffersFrom} to compare two tables slot by slot.
   */
  @Nullable private static String globalPropertySignature(@Nullable GlobalPropertyImpl slot) {
    return slot == null ? null : slot.getName() + "|" + slot.getTypeInternal();
  }

  public Collection<SchemaClassImpl> getClasses(DatabaseSessionEmbedded session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_READ);
    acquireSchemaReadLock();
    try {
      return new HashSet<>(classes.values());
    } finally {
      releaseSchemaReadLock();
    }
  }

  public Set<SchemaClassImpl> getClassesRelyOnCollection(
      DatabaseSessionEmbedded session, final String collectionName) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_READ);

    acquireSchemaReadLock();
    try {
      final var collectionId = session.getCollectionIdByName(collectionName);
      final Set<SchemaClassImpl> result = new HashSet<>();
      for (var c : classes.values()) {
        if (ArrayUtils.contains(c.getPolymorphicCollectionIds(), collectionId)) {
          result.add(c);
        }
      }

      return result;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public SchemaShared load(DatabaseSessionEmbedded session) {

    lock.writeLock().lock();
    try {
      identity = RecordIdInternal.fromString(
          session.getStorage().getSchemaRecordId(), false);
      if (!identity.isValidPosition()) {
        throw new SchemaNotCreatedException(session.getDatabaseName(),
            "Schema is not created and cannot be loaded");
      }
      session.executeInTx(
          transaction -> {
            EntityImpl entity = session.load(identity);
            fromStream(session, entity);
          });
      return this;
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void create(final DatabaseSessionEmbedded session) {
    lock.writeLock().lock();
    try {
      var entity = session.computeInTx(transaction -> session.newInternalInstance());

      this.identity = entity.getIdentity();
      session.getStorage().setSchemaRecordId(entity.getIdentity().toString());
      snapshot = null;
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void close() {
  }

  @Deprecated
  public int getVersion() {
    return version;
  }

  public RecordIdInternal getIdentity() {
    acquireSchemaReadLock();
    try {
      return identity;
    } finally {
      releaseSchemaReadLock();
    }
  }

  @Nullable public GlobalPropertyImpl getGlobalPropertyById(int id) {
    acquireSchemaReadLock();
    try {
      if (id >= properties.size()) {
        return null;
      }
      return properties.get(id);
    } finally {
      releaseSchemaReadLock();
    }

  }

  public GlobalProperty createGlobalProperty(
      DatabaseSessionEmbedded session, final String name, final PropertyTypeInternal type,
      final Integer id) {

    acquireSchemaWriteLock(session);
    try {
      GlobalPropertyImpl global;
      if (id < properties.size() && (global = properties.get(id)) != null) {
        if (!global.getName().equals(name)
            || PropertyTypeInternal.convertFromPublicType(global.getType()) != type) {
          throw new SchemaException("A property with id " + id + " already exist ");
        }
        return global;
      }

      global = new GlobalPropertyImpl(name, type, id);
      ensurePropertiesSize(id);
      properties.set(id, global);
      propertiesByNameType.put(global.getName() + "|" + global.getType().name(), global);
      return global;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public List<GlobalProperty> getGlobalProperties() {
    acquireSchemaReadLock();
    try {
      return Collections.unmodifiableList(properties);
    } finally {
      releaseSchemaReadLock();
    }
  }

  protected GlobalPropertyImpl findOrCreateGlobalProperty(final String name,
      final PropertyTypeInternal type) {
    var global = propertiesByNameType.get(name + "|" + type.name());
    if (global == null) {
      var id = properties.size();
      global = new GlobalPropertyImpl(name, type, id);
      properties.add(id, global);
      propertiesByNameType.put(global.getName() + "|" + global.getType().name(), global);
    }
    return global;
  }

  private void saveInternal(DatabaseSessionEmbedded session) {

    if (txLocal) {
      // A mutation against the transaction-local copy must not persist eagerly: the change rides
      // the user transaction and is promoted to the committed schema at commit (the commit-time
      // reconciliation builds the matching per-class records and structure). Persisting here would
      // both break isolation and reintroduce the active-transaction conflict the de-guard removes.
      return;
    }

    var tx = session.getTransactionInternal();
    if (tx.isActive()) {
      throw new SchemaException(session.getDatabaseName(),
          "Cannot change the schema while a transaction is active. Schema changes are not"
              + " transactional");
    }

    session.executeInTx(transaction -> toStream(session));

    forceSnapshot();
  }

  /**
   * Returns the next collection index and increments the counter.
   * Must be called under the schema write lock.
   */
  protected int nextCollectionIndex() {
    assert lock.isWriteLockedByCurrentThread()
        : "nextCollectionIndex() must be called under the schema write lock";
    return collectionCounter++;
  }

  /**
   * Initializes the collection counter from existing collection names for pre-migration schemas.
   * Scans all collection names for the maximum {@code _N} suffix, then sets the counter to
   * {@code max(classes.size(), maxExistingSuffix + 1)}.
   */
  /* visible for testing */ int initCollectionCounterFromExisting(DatabaseSessionEmbedded session) {
    int maxSuffix = -1;
    var collectionNames = session.getStorage().getCollectionNames();
    for (var name : collectionNames) {
      var underscoreIdx = name.lastIndexOf('_');
      if (underscoreIdx >= 0 && underscoreIdx < name.length() - 1) {
        try {
          var suffix = Integer.parseInt(name.substring(underscoreIdx + 1));
          if (suffix > maxSuffix) {
            maxSuffix = suffix;
          }
        } catch (NumberFormatException ignore) {
          // Not a numeric suffix — skip.
        }
      }
    }
    // Use classes.size() as a floor to avoid collisions with legacy collection names
    // that may match class names without _N suffixes (pre-counter naming convention).
    int result = Math.max(classes.size(), maxSuffix + 1);
    assert result >= 0 : "Collection counter must be non-negative, got " + result;
    return result;
  }

  protected void addCollectionClassMap(final SchemaClassImpl cls) {
    for (var collectionId : cls.getCollectionIds()) {
      // Populate the reverse map for provisional ids too: a provisional id (<= -2) is pending-real,
      // so getClassByCollectionId resolves a tx-created class during the transaction. Only the
      // abstract-class marker is skipped (an abstract class owns no collection in this map).
      if (collectionId == ABSTRACT_COLLECTION_ID) {
        continue;
      }

      collectionsToClasses.put(collectionId, cls);
    }
  }

  private void ensurePropertiesSize(int size) {
    while (properties.size() <= size) {
      properties.add(null);
    }
  }

  public int addBlobCollection(DatabaseSessionEmbedded session, int collectionId) {
    acquireSchemaWriteLock(session);
    try {
      checkCollectionCanBeAdded(session, collectionId, null);
      blobCollections.add(collectionId);
    } finally {
      releaseSchemaWriteLock(session);
    }
    return collectionId;
  }

  public void removeBlobCollection(DatabaseSessionEmbedded session, String collectionName) {
    acquireSchemaWriteLock(session);
    try {
      var collectionId = getCollectionId(session, collectionName);
      blobCollections.remove(collectionId);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected static int getCollectionId(DatabaseSessionEmbedded session, final String stringValue) {
    int clId;
    try {
      clId = Integer.parseInt(stringValue);
    } catch (NumberFormatException ignore) {
      clId = session.getCollectionIdByName(stringValue);
    }
    return clId;
  }

  public IntSet getBlobCollections() {
    acquireSchemaReadLock();
    try {
      return IntSets.unmodifiable(blobCollections);
    } finally {
      releaseSchemaReadLock();
    }

  }
}
