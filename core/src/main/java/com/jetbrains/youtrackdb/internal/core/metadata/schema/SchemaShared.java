package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.common.util.ArrayUtils;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.SessionMetadata;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.Nullable;

public final class SchemaShared {

  public static final int CURRENT_VERSION_NUMBER = 4;

  public static final String SCHEMA_CLASS_NAME_INDEX = "$SchemaClassNameIndex";
  public static final String SCHEMA_CLASS_COLLECTION_INDEX = "$SchemaClassCollectionIndex";
  public static final String GLOBAL_PROPERTY_INDEX = "$GlobalPropertyIndex";

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private volatile RecordIdInternal identity;
  private volatile SchemaSnapshot snapshot;

  private final ReentrantLock snapshotLock = new ReentrantLock();

  private static Set<String> internalClasses = new HashSet<>();

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
  }

  protected static final class CollectionIdsAreEmptyException extends Exception {

  }

  public SchemaShared() {
  }

  @Nullable
  public static Character checkClassNameIfValid(String name) throws SchemaException {
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
  @Nullable
  public static Character checkPropertyNameIfValid(String iName) {
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

  @Nullable
  public static Character checkIndexNameIfValid(String iName) {
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

  public SchemaSnapshot makeSnapshot(DatabaseSessionEmbedded session) {
    var snapshot = this.snapshot;

    if (snapshot == null) {
      acquireSchemaReadLock();
      try {
        snapshotLock.lock();
        try {
          if (this.snapshot == null) {
            this.snapshot = new SchemaSnapshot(this, session);
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


  public long countClasses(DatabaseSessionInternal session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_READ);
    acquireSchemaReadLock();
    try {
      return session.countCollectionElements(SessionMetadata.COLLECTION_NAME_SCHEMA_CLASS);
    } finally {
      releaseSchemaReadLock();
    }
  }


  public SchemaClassShared createClass(DatabaseSessionEmbedded sesion, final String className) {
    return createClass(sesion, className, null, (int[]) null);
  }

  public SchemaClassShared createClass(
      DatabaseSessionEmbedded session, final String iClassName,
      final SchemaClassShared iSuperClass) {
    return createClass(session, iClassName, iSuperClass, null);
  }

  public SchemaClassShared createClass(
      DatabaseSessionEmbedded session, String iClassName, SchemaClassShared... superClasses) {
    return createClass(session, iClassName, null, superClasses);
  }

  public SchemaClassShared getOrCreateClass(DatabaseSessionEmbedded session,
      final String iClassName) {
    return getOrCreateClass(session, iClassName, (SchemaClassShared) null);
  }

  public SchemaClassShared getOrCreateClass(
      DatabaseSessionEmbedded session, final String iClassName,
      final SchemaClassShared superClass) {
    return getOrCreateClass(
        session, iClassName,
        superClass == null ? new SchemaClassShared[0] : new SchemaClassShared[]{superClass});
  }

  public SchemaClassShared createAbstractClass(DatabaseSessionEmbedded session,
      final String className) {
    return createClass(session, className, null, new int[]{-1});
  }

  public SchemaClassShared createAbstractClass(
      DatabaseSessionEmbedded session, final String className, final SchemaClassShared superClass) {
    return createClass(session, className, superClass, new int[]{-1});
  }

  public SchemaClassShared createAbstractClass(
      DatabaseSessionEmbedded session, String iClassName, SchemaClassShared... superClasses) {
    return createClass(session, iClassName, new int[]{-1}, superClasses);
  }

  public SchemaClassShared createClass(
      DatabaseSessionEmbedded session,
      final String className,
      final SchemaClassShared superClass,
      int[] collectionIds) {
    return createClass(session, className, collectionIds, superClass);
  }

  public SchemaClassShared getClassByCollectionId(DatabaseSessionEmbedded session,
      int collectionId) {
    acquireSchemaReadLock();
    try {
      var index = session.getIndex(SCHEMA_CLASS_COLLECTION_INDEX);
      var result = index.getRids(session, collectionId).findFirst();

      return result.map(rid -> new SchemaClassShared(this, session.load(rid))).orElse(null);
    } finally {
      releaseSchemaReadLock();
    }
  }


  public boolean existsClass(DatabaseSessionEmbedded session, final String className) {
    if (className == null) {
      return false;
    }

    acquireSchemaReadLock();
    try {
      var index = session.getIndex(SCHEMA_CLASS_COLLECTION_INDEX);
      return index.getRids(session, className).findAny().isPresent();
    } finally {
      releaseSchemaReadLock();
    }
  }

  @Nullable
  public SchemaClassShared getClass(final Class<?> iClass) {
    if (iClass == null) {
      return null;
    }

    return getClass(iClass.getSimpleName());
  }

  @Nullable
  public SchemaClassShared getClass(final String iClassName) {
    if (iClassName == null) {
      return null;
    }

    acquireSchemaReadLock();
    try {
      return classes.get(iClassName.toLowerCase(Locale.ENGLISH));
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

  public void acquireSchemaWriteLock() {
    lock.writeLock().lock();
  }


  public void releaseSchemaWriteLock() {
    lock.writeLock().unlock();
  }

  void changeClassName(
      DatabaseSessionEmbedded session,
      final String oldName,
      final String newName,
      final SchemaClassShared cls) {
    if (oldName != null && oldName.equalsIgnoreCase(newName)) {
      throw new IllegalArgumentException(
          "Class '" + oldName + "' cannot be renamed with the same name");
    }

    acquireSchemaWriteLock();
    try {
      var nameIndex = session.getIndex(SCHEMA_CLASS_NAME_INDEX);
      if (newName != null
          && (nameIndex.getRids(session, newName).findAny().isPresent())) {
        throw new IllegalArgumentException("Class '" + newName + "' is already present in schema");
      }

      if (oldName != null) {
        nameIndex.remove(session.getActiveTransaction(), oldName);
      }

      if (newName != null) {
        nameIndex.put(session.getActiveTransaction(), newName,
            cls.getSchemaClassEntity().getIdentity());
      }
    } finally {
      releaseSchemaWriteLock();
    }
  }

  public Collection<SchemaClassShared> getClasses(DatabaseSessionInternal session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_READ);
    acquireSchemaReadLock();
    try {

      return new HashSet<>(classes.values());
    } finally {
      releaseSchemaReadLock();
    }
  }

  public Set<SchemaClassShared> getClassesRelyOnCollection(
      DatabaseSessionInternal session, final String collectionName) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_READ);

    acquireSchemaReadLock();
    try {
      final var collectionId = session.getCollectionIdByName(collectionName);
      final Set<SchemaClassShared> result = new HashSet<>();
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


  public void create(final DatabaseSessionInternal session) {
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

  public RecordIdInternal getIdentity() {
    acquireSchemaReadLock();
    try {
      return identity;
    } finally {
      releaseSchemaReadLock();
    }
  }

  @Nullable
  public GlobalPropertyImpl getGlobalPropertyById(int id) {
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
      DatabaseSessionInternal session, final String name, final PropertyTypeInternal type,
      final Integer id) {

    acquireSchemaWriteLock();
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

  GlobalPropertyImpl findOrCreateGlobalProperty(final String name,
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

  private void saveInternal(DatabaseSessionInternal session) {

    var tx = session.getTransactionInternal();
    if (tx.isActive()) {
      throw new SchemaException(session.getDatabaseName(),
          "Cannot change the schema while a transaction is active. Schema changes are not"
              + " transactional");
    }

    session.executeInTx(transaction -> toStream(session));

    forceSnapshot();
  }

  private void addCollectionClassMap(final SchemaClassShared cls) {
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


  public SchemaClassShared createClass(
      DatabaseSessionEmbedded session,
      final String className,
      int[] collectionIds,
      SchemaClassShared... superClasses) {
    final var wrongCharacter = SchemaShared.checkClassNameIfValid(className);
    //noinspection ConstantValue
    if (wrongCharacter != null) {
      throw new SchemaException(session.getDatabaseName(),
          "Invalid class name found. Character '"
              + wrongCharacter
              + "' cannot be used in class name '"
              + className
              + "'");
    }

    SchemaClassShared result;
    var retry = 0;

    while (true) {
      try {
        result = doCreateClass(session, className, collectionIds, retry, superClasses);
        break;
      } catch (CollectionIdsAreEmptyException ignore) {
        classes.remove(className.toLowerCase(Locale.ENGLISH));
        collectionIds = createCollections(session, className);
        retry++;
      }
    }
    return result;
  }

  public SchemaClassShared createClass(
      DatabaseSessionEmbedded session,
      final String className,
      int collections,
      SchemaClassShared... superClasses) {
    final var wrongCharacter = SchemaShared.checkClassNameIfValid(className);
    //noinspection ConstantValue
    if (wrongCharacter != null) {
      throw new SchemaException(session.getDatabaseName(),
          "Invalid class name found. Character '"
              + wrongCharacter
              + "' cannot be used in class name '"
              + className
              + "'");
    }

    return doCreateClass(session, className, collections, superClasses);
  }

  private SchemaClassShared doCreateClass(
      DatabaseSessionEmbedded session,
      final String className,
      final int collections,
      SchemaClassShared... superClasses) {
    SchemaClassShared result;

    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_CREATE);
    if (superClasses != null) {
      SchemaClassShared.checkParametersConflict(session, Arrays.asList(superClasses));
    }
    acquireSchemaWriteLock();
    try {

      final var key = className.toLowerCase(Locale.ENGLISH);
      if (classes.containsKey(key)) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '" + className + "' already exists in current database");
      }
      List<SchemaClassShared> superClassesList = new ArrayList<>();
      if (superClasses != null) {
        for (var superClass : superClasses) {
          // Filtering for null
          if (superClass != null) {
            superClassesList.add(superClass);
          }
        }
      }

      final int[] collectionIds;
      if (collections > 0) {
        collectionIds = createCollections(session, className, collections);
      } else {
        // ABSTRACT
        collectionIds = new int[]{-1};
      }

      doRealCreateClass(session, className, superClassesList,
          collectionIds);

      result = classes.get(className.toLowerCase(Locale.ENGLISH));
      // WAKE UP DB LIFECYCLE LISTENER
      for (var it = YouTrackDBEnginesManager.instance()
          .getDbLifecycleListeners();
          it.hasNext(); ) {
        //noinspection deprecation
        it.next().onCreateClass(session, result);
      }

      for (var oSessionListener : session.getListeners()) {
        oSessionListener.onCreateClass(session, new SchemaClassProxy(result, session));
      }

    } catch (CollectionIdsAreEmptyException e) {
      throw BaseException.wrapException(
          new SchemaException(session.getDatabaseName(), "Cannot create class '" + className + "'"),
          e,
          session.getDatabaseName());
    } finally {
      releaseSchemaWriteLock(session);
    }

    return result;
  }

  private void doRealCreateClass(
      DatabaseSessionEmbedded database,
      String className,
      List<SchemaClassShared> superClassesList,
      int[] collectionIds)
      throws CollectionIdsAreEmptyException {
    createClassInternal(database, className, collectionIds, superClassesList);
  }

  private void createClassInternal(
      DatabaseSessionEmbedded session,
      final String className,
      final int[] collectionIdsToAdd,
      final List<SchemaClassShared> superClasses)
      throws CollectionIdsAreEmptyException {
    acquireSchemaWriteLock();
    try {
      if (className == null || className.isEmpty()) {
        throw new SchemaException(session.getDatabaseName(), "Found class name null or empty");
      }

      checkEmbedded(session);

      checkCollectionsAreAbsent(collectionIdsToAdd);

      final int[] collectionIds;
      if (collectionIdsToAdd == null || collectionIdsToAdd.length == 0) {
        throw new CollectionIdsAreEmptyException();

      } else {
        collectionIds = collectionIdsToAdd;
      }

      session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_CREATE);

      final var key = className.toLowerCase(Locale.ENGLISH);

      if (classes.containsKey(key)) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '" + className + "' already exists in current database");
      }

      var cls = createClassInstance(className, collectionIds);

      classes.put(key, cls);

      if (superClasses != null && !superClasses.isEmpty()) {
        cls.setSuperClassesInternal(session, superClasses, true);
        for (var superClass : superClasses) {
          // UPDATE INDEXES
          final var collectionsToIndex = superClass.getPolymorphicCollectionIds();
          final var collectionNames = new String[collectionsToIndex.length];
          for (var i = 0; i < collectionsToIndex.length; i++) {
            collectionNames[i] = session.getCollectionNameById(collectionsToIndex[i]);
          }

          for (var index : superClass.getIndexesInternal(session)) {
            for (var collectionName : collectionNames) {
              if (collectionName != null) {
                session
                    .getSharedContext()
                    .getIndexManager()
                    .addCollectionToIndex(session, collectionName, index.getName(), true);
              }
            }
          }
        }
      }

      addCollectionClassMap(cls);

    } finally {
      releaseSchemaWriteLock();
    }
  }


  @Nullable
  public SchemaClassShared getOrCreateClass(
      DatabaseSessionEmbedded session, final String iClassName,
      final SchemaClassShared... superClasses) {
    if (iClassName == null) {
      return null;
    }

    acquireSchemaReadLock();
    try {
      var cls = classes.get(iClassName.toLowerCase(Locale.ENGLISH));
      if (cls != null) {
        return cls;
      }
    } finally {
      releaseSchemaReadLock();
    }

    SchemaClassShared cls;

    int[] collectionIds = null;
    var retry = 0;

    while (true) {
      try {
        acquireSchemaWriteLock();
        try {
          cls = classes.get(iClassName.toLowerCase(Locale.ENGLISH));
          if (cls != null) {
            return cls;
          }

          cls = doCreateClass(session, iClassName, collectionIds, retry, superClasses);
          addCollectionClassMap(cls);
        } finally {
          releaseSchemaWriteLock(session);
        }
        break;
      } catch (CollectionIdsAreEmptyException ignore) {
        collectionIds = createCollections(session, iClassName);
        retry++;
      }
    }

    return cls;
  }

  private SchemaClassShared doCreateClass(
      DatabaseSessionEmbedded session,
      final String className,
      int[] collectionIds,
      int retry,
      SchemaClassShared... superClasses)
      throws CollectionIdsAreEmptyException {
    SchemaClassShared result;
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_CREATE);
    if (superClasses != null) {
      SchemaClassShared.checkParametersConflict(session, Arrays.asList(superClasses));
    }

    acquireSchemaWriteLock();
    try {

      final var key = className.toLowerCase(Locale.ENGLISH);
      if (classes.containsKey(key) && retry == 0) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '" + className + "' already exists in current database");
      }

      checkCollectionsAreAbsent(collectionIds);

      if (collectionIds == null || collectionIds.length == 0) {
        collectionIds =
            createCollections(
                session,
                className,
                session.getStorageInfo().getConfiguration().getMinimumCollections());
      }
      List<SchemaClassShared> superClassesList = new ArrayList<>();
      if (superClasses != null) {
        for (var superClass : superClasses) {
          if (superClass != null) {
            superClassesList.add(superClass);
          }
        }
      }

      doRealCreateClass(session, className, superClassesList,
          collectionIds);

      result = classes.get(className.toLowerCase(Locale.ENGLISH));
      for (var oSessionListener : session.getListeners()) {
        oSessionListener.onCreateClass(session, new SchemaClassProxy(result, session));
      }

    } finally {
      releaseSchemaWriteLock(session);
    }

    return result;
  }

  private int[] createCollections(DatabaseSessionInternal session, final String iClassName) {
    return createCollections(
        session, iClassName, session.getStorageInfo().getConfiguration().getMinimumCollections());
  }

  private int[] createCollections(
      DatabaseSessionInternal session, String className, int minimumCollections) {
    className = className.toLowerCase(Locale.ENGLISH);

    int[] collectionIds;

    if (internalClasses.contains(className.toLowerCase(Locale.ENGLISH))) {
      // INTERNAL CLASS, SET TO 1
      minimumCollections = 1;
    }

    collectionIds = new int[minimumCollections];
    collectionIds[0] = session.getCollectionIdByName(className);
    if (collectionIds[0] > -1) {
      // CHECK THE COLLECTION HAS NOT BEEN ALREADY ASSIGNED
      final var cls = collectionsToClasses.get(collectionIds[0]);
      if (cls != null) {
        collectionIds[0] = session.addCollection(
            getNextAvailableCollectionName(session, className));
      }
    } else
    // JUST KEEP THE CLASS NAME. THIS IS FOR LEGACY REASONS
    {
      collectionIds[0] = session.addCollection(className);
    }

    for (var i = 1; i < minimumCollections; ++i) {
      collectionIds[i] = session.addCollection(getNextAvailableCollectionName(session, className));
    }

    return collectionIds;
  }

  private static String getNextAvailableCollectionName(
      DatabaseSessionInternal session, final String className) {
    for (var i = 1; ; ++i) {
      final var collectionName = className + "_" + i;
      if (session.getCollectionIdByName(collectionName) < 0)
      // FREE NAME
      {
        return collectionName;
      }
    }
  }

  private void checkCollectionsAreAbsent(final int[] iCollectionIds) {
    if (iCollectionIds == null) {
      return;
    }

    for (var collectionId : iCollectionIds) {
      if (collectionId < 0) {
        continue;
      }

      if (collectionsToClasses.containsKey(collectionId)) {
        throw new SchemaException(
            "Collection with id "
                + collectionId
                + " already belongs to class "
                + collectionsToClasses.get(collectionId));
      }
    }
  }

  @Override
  public void dropClass(DatabaseSessionEmbedded session, final String className) {
    acquireSchemaWriteLock();
    try {
      if (session.getTransactionInternal().isActive()) {
        throw new IllegalStateException("Cannot drop a class inside a transaction");
      }

      if (className == null) {
        throw new IllegalArgumentException("Class name is null");
      }

      session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_DELETE);

      final var key = className.toLowerCase(Locale.ENGLISH);

      var cls = classes.get(key);

      if (cls == null) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '" + className + "' was not found in current database");
      }

      if (!cls.getSubclasses().isEmpty()) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '"
                + className
                + "' cannot be dropped because it has sub classes "
                + cls.getSubclasses()
                + ". Remove the dependencies before trying to drop it again");
      }

      doDropClass(session, className);

      var localCache = session.getLocalCache();
      for (var collectionId : cls.getCollectionIds()) {
        localCache.freeCollection(collectionId);
      }
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  private void doDropClass(DatabaseSessionEmbedded session, String className) {
    dropClassInternal(session, className);
  }

  private void dropClassInternal(DatabaseSessionEmbedded session, final String className) {
    acquireSchemaWriteLock();
    try {
      if (session.getTransactionInternal().isActive()) {
        throw new IllegalStateException("Cannot drop a class inside a transaction");
      }

      if (className == null) {
        throw new IllegalArgumentException("Class name is null");
      }

      session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_DELETE);

      final var key = className.toLowerCase(Locale.ENGLISH);

      final var cls = classes.get(key);
      if (cls == null) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '" + className + "' was not found in current database");
      }

      if (!cls.getSubclasses().isEmpty()) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '"
                + className
                + "' cannot be dropped because it has sub classes "
                + cls.getSubclasses()
                + ". Remove the dependencies before trying to drop it again");
      }

      for (var superClass : cls.getSuperClasses()) {
        // REMOVE DEPENDENCY FROM SUPERCLASS
        superClass.removeBaseClassInternal(session, cls);
      }
      for (var id : cls.getCollectionIds()) {
        if (id != -1) {
          deleteCollection(session, id);
        }
      }

      dropClassIndexes(session, cls);

      classes.remove(key);

      removeCollectionClassMap(cls);

      // WAKE UP DB LIFECYCLE LISTENER
      for (var it = YouTrackDBEnginesManager.instance()
          .getDbLifecycleListeners();
          it.hasNext(); ) {
        //noinspection deprecation
        it.next().onDropClass(session, cls);
      }

      for (var oSessionListener : session.getListeners()) {
        oSessionListener.onDropClass(session, new SchemaClassProxy(cls, session));
      }
    } finally {
      releaseSchemaWriteLock(session);
    }
  }


  private static void dropClassIndexes(DatabaseSessionEmbedded session,
      final SchemaClassShared cls) {
    final var indexManager = session.getSharedContext().getIndexManager();

    for (final var index : indexManager.getClassIndexes(session, cls.getName())) {
      indexManager.dropIndex(session, index.getName());
    }
  }

  private static void deleteCollection(final DatabaseSessionInternal session,
      final int collectionId) {
    final var collectionName = session.getCollectionNameById(collectionId);
    if (collectionName != null) {
      final var iteratorCollection = session.browseCollection(collectionName);
      if (iteratorCollection != null) {
        session.executeInTxBatches(
            iteratorCollection, (s, record) -> record.delete());
        session.dropCollectionInternal(collectionId);
      }
    }

    session.getLocalCache().freeCollection(collectionId);
  }

  private void removeCollectionClassMap(final SchemaClassShared cls) {
    for (var collectionId : cls.getCollectionIds()) {
      if (collectionId < 0) {
        continue;
      }

      collectionsToClasses.remove(collectionId);
    }
  }


}

