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

  protected final Map<String, SchemaClassImpl> classes = new HashMap<>();
  protected final Int2ObjectOpenHashMap<SchemaClassImpl> collectionsToClasses =
      new Int2ObjectOpenHashMap<>();

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
   * transaction to mutate in isolation. The copy is seeded by a serialize-then-re-parse round
   * trip rather than a field-level clone: each {@link SchemaClassImpl} binds back to its
   * {@link SchemaShared} through a final {@code owner} field and links to its relatives by direct
   * object reference, so a clone would leave those references pointing at the shared instances. The
   * re-parse constructs fresh class objects bound to the copy, and the cross-class derived state a
   * schema write recomputes (inheritance, {@code polymorphicCollectionIds}, subclass sets, the
   * global-property table) stays inside the copy with no extra code.
   *
   * <p>Mechanically the seed is {@code copy.fromStream(session, this.toStream(session))}: this
   * committed instance serializes into its root record, and the fresh copy re-parses that record.
   * The re-parse rebinds each class's committed per-class record RID from the {@code "classes"} link
   * set (the per-class-record format), so a commit later writes the right record. The committed root
   * {@code identity} is copied onto the new instance first, so the copy serializes back to the same
   * root record at commit and so any save inside {@code fromStream} resolves the right record.
   *
   * <p>The committed-side serialization runs under this instance's schema write lock, which is what
   * {@link #toStream} asserts and what makes the read of the committed class graph exclusive against
   * concurrent committed-schema mutation. The copy's own {@code fromStream} takes the copy's
   * (separate) write lock internally. Record loads and writes during the round trip ride the caller's
   * already-open user transaction; this method does not open or commit a transaction of its own,
   * because the whole point of the tx-local view is that the change defers to the user transaction's
   * commit.
   *
   * @param session the session whose open transaction the round-trip record I/O rides
   * @return a fresh {@link SchemaShared} of this instance's concrete type, private to the caller
   */
  public SchemaShared copyForTx(DatabaseSessionEmbedded session) {
    lock.writeLock().lock();
    try {
      final var serialized = toStream(session);
      final var copy = newInstanceForCopy();
      // Copy the committed root identity before the re-parse: the copy must serialize back to the
      // same root record at commit, and a save triggered from inside fromStream resolves identity.
      copy.identity = this.identity;
      copy.fromStream(session, serialized);
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
      if (collectionId < 0) {
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
   * Binds POJO to EntityImpl.
   */
  public EntityImpl toStream(@Nonnull DatabaseSessionEmbedded session) {
    // The body mutates shared state (per-class recordId binds, link-set adds/removes) and writes
    // records. The caller holds the schema write lock, and that write lock is the exclusivity
    // guarantee for these mutations; no additional synchronization is taken here.
    assert lock.isWriteLockedByCurrentThread()
        : "toStream() mutates shared schema state and must be called under the schema write lock";
    EntityImpl entity = session.load(identity);
    entity.setProperty("schemaVersion", CURRENT_VERSION_NUMBER);

    // The root record links one standalone record per class, mirroring the index manager's
    // CONFIG_INDEXES link set. Aliases that share an impl are written once; the link set ends
    // up holding exactly the live classes' record RIDs. Records that were linked before but no
    // longer back a live class (a dropped class) are deleted and unlinked below.
    Set<SchemaClassImpl> realClasses = new HashSet<>(classes.values());

    LinkSet classLinks = entity.getOrCreateLinkSet("classes");
    // Snapshot the previously-linked records so drops can be detected as a set difference.
    final Set<RID> previouslyLinked = new HashSet<>();
    for (var link : classLinks) {
      previouslyLinked.add(link.getIdentity());
    }

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
        // future save, so the rollback case self-heals here rather than requiring a reload.
        classRecord = session.newInternalInstance();
        c.setRecordId(classRecord.getIdentity());
        classLinks.add(classRecord.getIdentity());
      } else {
        classRecord = session.load(boundRid);
      }
      c.toStream(session, classRecord);
      assert c.getRecordId() != null
          : "schema class '" + c.getName() + "' must have a bound record id before it joins the"
              + " live-record set written to the root link set";
      liveRecords.add(c.getRecordId());
    }

    // Drop the records that backed classes removed since the last save, and unlink them.
    for (var rid : previouslyLinked) {
      if (!liveRecords.contains(rid)) {
        classLinks.remove(rid);
        EntityImpl droppedRecord = session.load(rid);
        droppedRecord.delete();
      }
    }

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
    return entity;
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
      if (collectionId < 0) {
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
