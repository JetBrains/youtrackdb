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
package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.api.exception.ConfigurationException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.exception.SchemaNotCreatedException;
import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.concur.resource.CloseableInStorage;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.types.ModifiableInteger;
import com.jetbrains.youtrack.db.internal.common.util.ArrayUtils;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseRecordThreadLocal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.MetadataUpdateListener;
import com.jetbrains.youtrack.db.internal.core.db.ScenarioThreadLocal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.clusterselection.ClusterSelectionFactory;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

/**
 * Shared schema class. It's shared by all the database instances that point to the same storage.
 */
public abstract class SchemaShared implements CloseableInStorage {

  private static final int NOT_EXISTENT_CLUSTER_ID = -1;
  public static final int CURRENT_VERSION_NUMBER = 4;
  public static final int VERSION_NUMBER_V4 = 4;
  // this is needed for guarantee the compatibility to 2.0-M1 and 2.0-M2 no changed associated with
  // it
  public static final int VERSION_NUMBER_V5 = 5;

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  protected final Map<String, SchemaClassImpl> dirtyClasses = new HashMap<>();
  protected final Map<String, LazySchemaClass> classesRefs = new HashMap<>();
  protected final Int2ObjectOpenHashMap<SchemaClass> clustersToClasses = new Int2ObjectOpenHashMap<>();

  private final ClusterSelectionFactory clusterSelectionFactory = new ClusterSelectionFactory();

  private final ModifiableInteger modificationCounter = new ModifiableInteger();
  private final List<GlobalProperty> properties = new ArrayList<>();
  private final Map<String, GlobalProperty> propertiesByNameType = new HashMap<>();
  private IntOpenHashSet blobClusters = new IntOpenHashSet();
  private volatile int version = 0;
  private volatile RecordId identity;
  protected volatile ImmutableSchema snapshot;

  protected static Set<String> internalClasses = new HashSet<String>();

  static {
    internalClasses.add("ouser");
    internalClasses.add("orole");
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

  public void markDirty() {
    for (Map.Entry<String, LazySchemaClass> lazySchemaClassEntry : classesRefs.entrySet()) {
      LazySchemaClass lazyClass = lazySchemaClassEntry.getValue();
      if (lazyClass != null && lazyClass.isLoadedWithoutInheritance()) {
        this.dirtyClasses.put(lazySchemaClassEntry.getKey(),
            (SchemaClassImpl) lazyClass.getDelegate());
      }
    }
  }

  public void markClassDirty(SchemaClass dirtyClass) {
    this.dirtyClasses.put(normalizeClassName(dirtyClass.getName()), (SchemaClassImpl) dirtyClass);
  }

  public void markSuperClassesDirty(DatabaseSessionInternal session, SchemaClass dirtyClass) {
    Collection<SchemaClass> superClasses = dirtyClass.getAllSuperClasses(session);
    for (SchemaClass superClass : superClasses) {
      markClassDirty(superClass);
    }
  }

  public void markSubClassesDirty(DatabaseSessionInternal session, SchemaClass dirtyClass) {
    Collection<SchemaClass> subClasses = dirtyClass.getAllSubclasses(session);
    for (SchemaClass subClass : subClasses) {
      markClassDirty(subClass);
    }
  }

  protected static final class ClusterIdsAreEmptyException extends Exception {

  }

  public SchemaShared() {
  }

  public static Character checkClassNameIfValid(String iName) throws SchemaException {
    if (iName == null) {
      throw new IllegalArgumentException("Name is null");
    }

    //    iName = iName.trim();
    //
    //    final int nameSize = iName.length();
    //
    //    if (nameSize == 0)
    //      throw new IllegalArgumentException("Name is empty");
    //
    //    for (int i = 0; i < nameSize; ++i) {
    //      final char c = iName.charAt(i);
    //      if (c == ':' || c == ',' || c == ';' || c == ' ' || c == '@' || c == '=' || c == '.' ||
    // c == '#')
    //        // INVALID CHARACTER
    //        return c;
    //    }

    return null;
  }

  public static Character checkFieldNameIfValid(String iName) {
    if (iName == null) {
      throw new IllegalArgumentException("Name is null");
    }

    iName = iName.trim();

    final int nameSize = iName.length();

    if (nameSize == 0) {
      throw new IllegalArgumentException("Name is empty");
    }

    for (int i = 0; i < nameSize; ++i) {
      final char c = iName.charAt(i);
      if (c == ':' || c == ',' || c == ';' || c == ' ' || c == '=')
      // INVALID CHARACTER
      {
        return c;
      }
    }

    return null;
  }

  public static Character checkIndexNameIfValid(String iName) {
    if (iName == null) {
      throw new IllegalArgumentException("Name is null");
    }

    iName = iName.trim();

    final int nameSize = iName.length();

    if (nameSize == 0) {
      throw new IllegalArgumentException("Name is empty");
    }

    for (int i = 0; i < nameSize; ++i) {
      final char c = iName.charAt(i);
      if (c == ':' || c == ',' || c == ';' || c == ' ' || c == '=')
      // INVALID CHARACTER
      {
        return c;
      }
    }

    return null;
  }

  public ImmutableSchema makeSnapshot(DatabaseSessionInternal database) {
    if (snapshot == null) {
      // Is null only in the case that is asked while the schema is created
      // all the other cases are already protected by a write lock
      acquireSchemaReadLock();
      try {
        if (snapshot == null) {
          snapshot = new ImmutableSchema(this, database);
        }
      } finally {
        releaseSchemaReadLock();
      }
    }
    return snapshot;
  }

  public void forceSnapshot(DatabaseSessionInternal database) {
    acquireSchemaReadLock();
    try {
      snapshot = new ImmutableSchema(this, database);
    } finally {
      releaseSchemaReadLock();
    }
  }

  public ClusterSelectionFactory getClusterSelectionFactory() {
    return clusterSelectionFactory;
  }

  public int countClasses(DatabaseSessionInternal database) {
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_READ);

    acquireSchemaReadLock();
    try {
      return classesRefs.size();
    } finally {
      releaseSchemaReadLock();
    }
  }


  /**
   * Callback invoked when the schema is loaded, after all the initializations.
   */
  public void onPostIndexManagement(DatabaseSessionInternal session) {
    for (LazySchemaClass c : classesRefs.values()) {
      c.loadIfNeededWithTemplate(session, createClassInstance(
          // it's not an issue to set class id as a name since it will be immediately replaced by fromStream method
          c.getId().toString()));
      if (c.getDelegate() instanceof SchemaClassImpl) {
        ((SchemaClassImpl) c.getDelegate()).onPostIndexManagement(session);
      }
    }
  }

  public SchemaClass createClass(DatabaseSessionInternal database, final String className) {
    return createClass(database, className, null, (int[]) null);
  }

  public SchemaClass createClass(
      DatabaseSessionInternal database, final String iClassName, final SchemaClass iSuperClass) {
    return createClass(database, iClassName, iSuperClass, null);
  }

  public SchemaClass createClass(
      DatabaseSessionInternal database, String iClassName, SchemaClass... superClasses) {
    return createClass(database, iClassName, null, superClasses);
  }

  public SchemaClass getOrCreateClass(DatabaseSessionInternal database, final String iClassName) {
    return getOrCreateClass(database, iClassName, (SchemaClass) null);
  }

  public SchemaClass getOrCreateClass(
      DatabaseSessionInternal database, final String iClassName, final SchemaClass superClass) {
    return getOrCreateClass(
        database, iClassName,
        superClass == null ? new SchemaClass[0] : new SchemaClass[]{superClass});
  }

  public abstract SchemaClass getOrCreateClass(
      DatabaseSessionInternal database, final String iClassName, final SchemaClass... superClasses);

  public SchemaClass createAbstractClass(DatabaseSessionInternal database, final String className) {
    return createClass(database, className, null, new int[]{-1});
  }

  public SchemaClass createAbstractClass(
      DatabaseSessionInternal database, final String className, final SchemaClass superClass) {
    return createClass(database, className, superClass, new int[]{-1});
  }

  public SchemaClass createAbstractClass(
      DatabaseSessionInternal database, String iClassName, SchemaClass... superClasses) {
    return createClass(database, iClassName, new int[]{-1}, superClasses);
  }

  public SchemaClass createClass(
      DatabaseSessionInternal database,
      final String className,
      final SchemaClass superClass,
      int[] clusterIds) {
    return createClass(database, className, clusterIds, superClass);
  }

  public abstract SchemaClass createClass(
      DatabaseSessionInternal database,
      final String className,
      int[] clusterIds,
      SchemaClass... superClasses);

  public abstract SchemaClass createClass(
      DatabaseSessionInternal database,
      final String className,
      int clusters,
      SchemaClass... superClasses);


  public abstract void checkEmbedded();

  void checkClusterCanBeAdded(int clusterId, SchemaClass cls) {
    acquireSchemaReadLock();
    try {
      if (clusterId < 0) {
        return;
      }

      if (blobClusters.contains(clusterId)) {
        throw new SchemaException("Cluster with id " + clusterId + " already belongs to Blob");
      }

      final SchemaClass existingCls = clustersToClasses.get(clusterId);

      if (existingCls != null && (cls == null || !cls.equals(existingCls))) {
        throw new SchemaException(
            "Cluster with id "
                + clusterId
                + " already belongs to the class '"
                + clustersToClasses.get(clusterId)
                + "'");
      }
    } finally {
      releaseSchemaReadLock();
    }
  }

  public SchemaClass getClassByClusterId(int clusterId) {
    acquireSchemaReadLock();
    try {
      return clustersToClasses.get(clusterId);
    } finally {
      releaseSchemaReadLock();
    }
  }

  public abstract void dropClass(DatabaseSessionInternal database, final String className);

  /**
   * Reloads the schema inside a storage's shared lock.
   */
  public void reload(DatabaseSessionInternal database) {
    lock.writeLock().lock();
    try {
      database.executeInTx(
          () -> {
            identity = new RecordId(
                database.getStorageInfo().getConfiguration().getSchemaRecordId());

            EntityImpl entity = database.load(identity);
            fromStream(database, entity);
            forceSnapshot(database);
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
      return classesRefs.containsKey(normalizeClassName(iClassName));
    } finally {
      releaseSchemaReadLock();
    }
  }

  public SchemaClass getClass(DatabaseSessionInternal database, final Class<?> iClass) {
    if (iClass == null) {
      return null;
    }

    return getClass(database, iClass.getSimpleName());
  }

  public LazySchemaClass getLazyClass(final String iClassName) {
    if (iClassName == null) {
      return null;
    }

    acquireSchemaReadLock();
    try {
      String normalizedClassName = normalizeClassName(iClassName);
      return classesRefs.get(normalizedClassName);
    } finally {
      releaseSchemaReadLock();
    }
  }

  public SchemaClassInternal getClass(DatabaseSessionInternal database, final String iClassName) {
    String normalizedClassName = normalizeClassName(iClassName);
    LazySchemaClass lazyClass = getLazyClass(normalizedClassName);
    if (lazyClass == null) {
      return null;
    }
    lazyClass.loadIfNeededWithTemplate(database, createClassInstance(normalizedClassName));
    return lazyClass.getDelegate();
  }

  public void acquireSchemaReadLock() {
    lock.readLock().lock();
  }

  public void releaseSchemaReadLock() {
    lock.readLock().unlock();
  }

  public void acquireSchemaWriteLock(DatabaseSessionInternal database) {
    database.startExclusiveMetadataChange();
    lock.writeLock().lock();
    modificationCounter.increment();
  }

  public void releaseSchemaWriteLock(DatabaseSessionInternal database) {
    releaseSchemaWriteLock(database, true);
  }

  public void releaseSchemaWriteLock(DatabaseSessionInternal database, final boolean iSave) {
    int count;
    try {
      if (modificationCounter.intValue() == 1) {
        // if it is embedded storage modification of schema is done by internal methods otherwise it
        // is done by sql commands, and we need to reload local replica

        if (iSave) {
          if (database.getStorage() instanceof AbstractPaginatedStorage) {
            saveInternal(database);
          } else {
            reload(database);
          }
        } else {
          snapshot = new ImmutableSchema(this, database);
        }
        version++;
      }
    } finally {
      modificationCounter.decrement();
      count = modificationCounter.intValue();
      lock.writeLock().unlock();
      database.endExclusiveMetadataChange();
    }
    assert count >= 0;

    if (count == 0 && database.isRemote()) {
      database.getStorage().reload(database);
    }
  }

  // this method not only changes name, but also creates it, if oldName is null
  void changeClassName(
      DatabaseSessionInternal database,
      final String oldName,
      final String newName,
      final SchemaClassInternal cls) {

    if (oldName != null && oldName.equalsIgnoreCase(newName)) {
      throw new IllegalArgumentException(
          "Class '" + oldName + "' cannot be renamed with the same name");
    }

    acquireSchemaWriteLock(database);
    try {
      checkEmbedded();

      if (newName != null
          && (classesRefs.containsKey(normalizeClassName(newName)))) {
        throw new IllegalArgumentException("Class '" + newName + "' is already present in schema");
      }

      LazySchemaClass existingClass = classesRefs.get(normalizeClassName(cls.getName()));
      if (oldName != null) {
        // todo remove class from storage
        classesRefs.remove(oldName.toLowerCase(Locale.ENGLISH));
      }
      if (newName != null) {
        classesRefs.put(normalizeClassName(newName), existingClass);
      }

    } finally {
      releaseSchemaWriteLock(database);
    }
  }

  /**
   * Binds EntityImpl to POJO.
   */
  public void fromStream(DatabaseSessionInternal session, EntityImpl entity) {
    lock.writeLock().lock();
    modificationCounter.increment();
    try {
      // READ CURRENT SCHEMA VERSION
      final Integer schemaVersion = entity.field("schemaVersion");
      if (schemaVersion == null) {
        LogManager.instance()
            .error(
                this,
                "Database's schema is empty! Recreating the system classes and allow the opening of"
                    + " the database but double check the integrity of the database",
                null);
        return;
      } else if (schemaVersion != CURRENT_VERSION_NUMBER && VERSION_NUMBER_V5 != schemaVersion) {
        // VERSION_NUMBER_V5 is needed for guarantee the compatibility to 2.0-M1 and 2.0-M2 no
        // changed associated with it
        // HANDLE SCHEMA UPGRADE
        throw new ConfigurationException(
            "Database schema is different. Please export your old database with the previous"
                + " version of YouTrackDB and reimport it using the current one.");
      }

      properties.clear();
      propertiesByNameType.clear();
      List<EntityImpl> globalProperties = entity.field("globalProperties");
      boolean hasGlobalProperties = false;
      if (globalProperties != null) {
        hasGlobalProperties = true;
        for (EntityImpl oDocument : globalProperties) {
          GlobalPropertyImpl prop = new GlobalPropertyImpl();
          prop.fromDocument(oDocument);
          ensurePropertiesSize(prop.getId());
          properties.set(prop.getId(), prop);
          propertiesByNameType.put(prop.getName() + "|" + prop.getType().name(), prop);
        }
      }
      // REGISTER ALL THE CLASSES
      clustersToClasses.clear();

      Map<String, RecordId> storedClassesRefsUnsafe = entity.field("classesRefs");
      Map<String, RecordId> storedClassesRefs = storedClassesRefsUnsafe == null
          ? new HashMap<>()
          : storedClassesRefsUnsafe;
      // remove classes present in schema but absent in the db
      Iterator<String> presentClassNamesIterator = classesRefs.keySet().iterator();
      while (presentClassNamesIterator.hasNext()) {
        String presentClassName = presentClassNamesIterator.next();
        if (!storedClassesRefs.containsKey(presentClassName)) {
          presentClassNamesIterator.remove();
        }

      }
      for (Entry<String, RecordId> entry : storedClassesRefs.entrySet()) {
        // skip already loaded classes
        if (classesRefs.containsKey(entry.getKey())) {
          if (dirtyClasses.containsKey(entry.getKey())) {
            dirtyClasses.remove(entry.getKey());
            // mark dirty class unloaded
            // it will be reloaded next time we need to use it
            classesRefs.get(entry.getKey()).unload();
          }
        } else {
          LazySchemaClass lazySchemaClass = LazySchemaClass.fromTemplate(entry.getValue(),
              // create class templates so it could be loaded later properly.
              // this is required since on a later stages we don't have createClassInstance method,
              // and we can always overwrite this instance internals from lazy class with actual db values
              createClassInstance(entry.getKey()));
          classesRefs.put(entry.getKey(), lazySchemaClass);
        }
      }

      for (LazySchemaClass lazySchemaClass : classesRefs.values()) {
        //todo figure out why schema could be null and if new Andrii MR will fix it
        lazySchemaClass.loadIfNeededWithTemplate(session,
            createClassInstance(lazySchemaClass.getId().toString()));
        SchemaClassInternal cls = lazySchemaClass.getDelegate();
        addClusterClassMap(cls);
      }
      // VIEWS

      if (entity.containsField("blobClusters")) {
        blobClusters = new IntOpenHashSet((Set<Integer>) entity.field("blobClusters"));
      }

      if (!hasGlobalProperties) {
        DatabaseSessionInternal database = DatabaseRecordThreadLocal.instance().get();
        if (database.getStorage() instanceof AbstractPaginatedStorage) {
          saveInternal(database);
        }
      }

    } finally {
      version++;
      modificationCounter.decrement();
      lock.writeLock().unlock();
    }
  }

  protected abstract SchemaClassImpl createClassInstance(String name);


  public EntityImpl toNetworkStream() {
    lock.readLock().lock();
    try {
      EntityImpl entity = new EntityImpl();
      entity.setTrackingChanges(false);
      entity.field("schemaVersion", CURRENT_VERSION_NUMBER);

      // why don't we serialise classes?
      Map<String, RecordId> classIds = classesRefs.entrySet().stream()
          .map(e -> Map.entry(e.getKey(), e.getValue().getId()))
          .collect(Collectors.toMap(Entry::getKey, Entry::getValue));

      entity.field("classesRefs", classIds, PropertyType.EMBEDDEDMAP);

      List<EntityImpl> globalProperties = new ArrayList<EntityImpl>();
      for (GlobalProperty globalProperty : properties) {
        if (globalProperty != null) {
          globalProperties.add(((GlobalPropertyImpl) globalProperty).toDocument());
        }
      }

      entity.field("globalProperties", globalProperties, PropertyType.EMBEDDEDLIST);
      entity.field("blobClusters", blobClusters, PropertyType.EMBEDDEDSET);
      return entity;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Binds POJO to EntityImpl.
   */
  public EntityImpl toStream(@Nonnull DatabaseSessionInternal db) {
    lock.readLock().lock();
    try {
      EntityImpl entity = db.load(identity);
      entity.field("schemaVersion", CURRENT_VERSION_NUMBER);

      Map<String, RecordId> classIds = classesRefs.entrySet().stream()
          .map(e -> Map.entry(e.getKey(), e.getValue().getId()))
          .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
      entity.field("classesRefs", classIds, PropertyType.EMBEDDEDMAP);

      List<EntityImpl> globalProperties = new ArrayList<EntityImpl>();
      for (GlobalProperty globalProperty : properties) {
        if (globalProperty != null) {
          globalProperties.add(((GlobalPropertyImpl) globalProperty).toDocument());
        }
      }
      entity.field("globalProperties", globalProperties, PropertyType.EMBEDDEDLIST);
      entity.field("blobClusters", blobClusters, PropertyType.EMBEDDEDSET);
      return entity;
    } finally {
      lock.readLock().unlock();
    }
  }

  public Collection<SchemaClass> getClassesSlow(DatabaseSessionInternal database) {
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_READ);
    acquireSchemaReadLock();
    try {
      HashSet<SchemaClass> result = new HashSet<>(classesRefs.size());
      for (String className : classesRefs.keySet()) {
        SchemaClassInternal loadedClass = getClass(database, className);
        result.add(loadedClass);
      }
      return result;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public Map<String, LazySchemaClass> getClassesRefs(DatabaseSessionInternal database) {
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_READ);
    acquireSchemaReadLock();
    try {
      return new HashMap<>(classesRefs);
    } finally {
      releaseSchemaReadLock();
    }
  }

  public Set<SchemaClass> getClassesRelyOnCluster(
      DatabaseSessionInternal database, final String clusterName) {
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_READ);

    acquireSchemaReadLock();
    try {
      final int clusterId = database.getClusterIdByName(clusterName);
      final Set<SchemaClass> result = new HashSet<SchemaClass>();
      for (LazySchemaClass l : classesRefs.values()) {
        SchemaClassInternal c = l.getDelegate();
        if (ArrayUtils.contains(c.getPolymorphicClusterIds(), clusterId)) {
          result.add(c);
        }
      }

      return result;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public SchemaShared load(DatabaseSessionInternal database) {

    lock.writeLock().lock();
    try {
      identity = new RecordId(database.getStorageInfo().getConfiguration().getSchemaRecordId());
      if (!identity.isValid()) {
        throw new SchemaNotCreatedException("Schema is not created and cannot be loaded");
      }
      database.executeInTx(
          () -> {
            EntityImpl entity = database.load(identity);
            fromStream(database, entity);
          });
      return this;
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void create(final DatabaseSessionInternal database) {
    lock.writeLock().lock();
    try {
      EntityImpl entity =
          database.computeInTx(
              () -> database.save(new EntityImpl(), MetadataDefault.CLUSTER_INTERNAL_NAME));
      this.identity = entity.getIdentity();
      database.getStorage().setSchemaRecordId(entity.getIdentity().toString());
      snapshot = new ImmutableSchema(this, database);
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

  public RecordId getIdentity() {
    acquireSchemaReadLock();
    try {
      return identity;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public GlobalProperty getGlobalPropertyById(int id) {
    if (id >= properties.size()) {
      return null;
    }
    return properties.get(id);
  }

  public GlobalProperty createGlobalProperty(
      final String name,
      final PropertyType type,
      final Integer id
  ) {
    GlobalProperty global;
    if (id < properties.size() && (global = properties.get(id)) != null) {
      if (!global.getName().equals(name) || !global.getType().equals(type)) {
        throw new SchemaException("A property with id " + id + " already exist ");
      }
      return global;
    }

    global = new GlobalPropertyImpl(name, type, id);
    ensurePropertiesSize(id);
    properties.set(id, global);
    propertiesByNameType.put(global.getName() + "|" + global.getType().name(), global);
    return global;
  }

  public List<GlobalProperty> getGlobalProperties() {
    return Collections.unmodifiableList(properties);
  }

  protected GlobalProperty findOrCreateGlobalProperty(final String name, final PropertyType type) {
    GlobalProperty global = propertiesByNameType.get(name + "|" + type.name());
    if (global == null) {
      int id = properties.size();
      global = new GlobalPropertyImpl(name, type, id);
      properties.add(id, global);
      propertiesByNameType.put(global.getName() + "|" + global.getType().name(), global);
    }
    return global;
  }

  protected boolean executeThroughDistributedStorage(DatabaseSessionInternal database) {
    return !database.isLocalEnv();
  }

  private void saveInternal(DatabaseSessionInternal database) {
    var tx = database.getTransaction();
    if (tx.isActive()) {
      throw new SchemaException(
          "Cannot change the schema while a transaction is active. Schema changes are not"
              + " transactional");
    }

    ScenarioThreadLocal.executeAsDistributed(
        () -> {
          database.executeInTx(() -> {
            Collection<SchemaClassImpl> dirtyClasses = this.dirtyClasses.values();
            for (SchemaClassImpl dirtyClass : dirtyClasses) {
              // TODO FIX THIS, for now it's ok, but I need to forbid adding null dirty classes
              if (dirtyClass != null) {
                EntityImpl dirtyClassEntity = dirtyClass.toStream(database);
                // todo replace copy with storing identity in the class like it used to be in schema
                database.save(dirtyClassEntity, MetadataDefault.CLUSTER_INTERNAL_NAME);
                // we don't ever need to remove it, just reload in place
//                loadedClasses.remove(dirtyClassName);
              }
            }
            EntityImpl entity = toStream(database);
            database.save(entity, MetadataDefault.CLUSTER_INTERNAL_NAME);
          });
          return null;
        });

    dirtyClasses.clear();
    forceSnapshot(database);

    for (MetadataUpdateListener listener : database.getSharedContext().browseListeners()) {
      listener.onSchemaUpdate(database, database.getName(), this);
    }
  }

  protected void addClusterClassMap(final SchemaClass cls) {
    for (int clusterId : cls.getClusterIds()) {
      if (clusterId < 0) {
        continue;
      }

      clustersToClasses.put(clusterId, cls);
    }
  }

  private void ensurePropertiesSize(int size) {
    while (properties.size() <= size) {
      properties.add(null);
    }
  }

  public int addBlobCluster(DatabaseSessionInternal database, int clusterId) {
    acquireSchemaWriteLock(database);
    try {
      checkClusterCanBeAdded(clusterId, null);
      blobClusters.add(clusterId);
    } finally {
      releaseSchemaWriteLock(database);
    }
    return clusterId;
  }

  public void removeBlobCluster(DatabaseSessionInternal database, String clusterName) {
    acquireSchemaWriteLock(database);
    try {
      int clusterId = getClusterId(database, clusterName);
      blobClusters.remove(clusterId);
    } finally {
      releaseSchemaWriteLock(database);
    }
  }

  protected int getClusterId(DatabaseSessionInternal database, final String stringValue) {
    int clId;
    try {
      clId = Integer.parseInt(stringValue);
    } catch (NumberFormatException ignore) {
      clId = database.getClusterIdByName(stringValue);
    }
    return clId;
  }

  public int createClusterIfNeeded(DatabaseSessionInternal database, String nameOrId) {
    final String[] parts = nameOrId.split(" ");
    int clId = getClusterId(database, parts[0]);

    if (clId == NOT_EXISTENT_CLUSTER_ID) {
      try {
        clId = Integer.parseInt(parts[0]);
        throw new IllegalArgumentException("Cluster id '" + clId + "' cannot be added");
      } catch (NumberFormatException ignore) {
        clId = database.addCluster(parts[0]);
      }
    }

    return clId;
  }

  public IntSet getBlobClusters() {
    return IntSets.unmodifiable(blobClusters);
  }

  public void sendCommand(DatabaseSessionInternal database, String command) {
    throw new UnsupportedOperationException();
  }

  protected static String normalizeClassName(String className) {
    return className != null
        ? className.toLowerCase(Locale.ENGLISH)
        : null;
  }
}
