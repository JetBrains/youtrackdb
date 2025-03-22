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
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.common.concur.resource.CloseableInStorage;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.types.ModifiableInteger;
import com.jetbrains.youtrack.db.internal.common.util.ArrayUtils;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shared schema class. It's shared by all the database instances that point to the same storage.
 */
public abstract class SchemaShared implements CloseableInStorage {

  public static final int CURRENT_VERSION_NUMBER = 4;
  public static final int VERSION_NUMBER_V4 = 4;
  // this is needed for guarantee the compatibility to 2.0-M1 and 2.0-M2 no changed associated with
  // it
  public static final int VERSION_NUMBER_V5 = 5;

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  protected final Map<String, SchemaClassImpl> classes = new HashMap<>();
  protected final Int2ObjectOpenHashMap<SchemaClassImpl> clustersToClasses = new Int2ObjectOpenHashMap<>();

  private final ClusterSelectionFactory clusterSelectionFactory = new ClusterSelectionFactory();

  private final ModifiableInteger modificationCounter = new ModifiableInteger();
  private final List<GlobalProperty> properties = new ArrayList<>();
  private final Map<String, GlobalProperty> propertiesByNameType = new HashMap<>();
  private IntOpenHashSet blobClusters = new IntOpenHashSet();
  private volatile int version = 0;
  private volatile RecordId identity;
  protected volatile ImmutableSchema snapshot;

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

  @SuppressWarnings("JavaExistingMethodCanBeUsed")
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

  public ImmutableSchema makeSnapshot(DatabaseSessionInternal session) {
    if (snapshot == null) {
      // Is null only in the case that is asked while the schema is created
      // all the other cases are already protected by a write lock
      acquireSchemaReadLock(session);
      try {
        if (snapshot == null) {
          snapshot = new ImmutableSchema(this, session);
        }
      } finally {
        releaseSchemaReadLock(session);
      }
    }
    return snapshot;
  }

  public void forceSnapshot(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      snapshot = new ImmutableSchema(this, session);
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public ClusterSelectionFactory getClusterSelectionFactory() {
    return clusterSelectionFactory;
  }

  public int countClasses(DatabaseSessionInternal session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_READ);

    acquireSchemaReadLock(session);
    try {
      return classes.size();
    } finally {
      releaseSchemaReadLock(session);
    }
  }


  public SchemaClassImpl createClass(DatabaseSessionInternal sesion, final String className) {
    return createClass(sesion, className, null, (int[]) null);
  }

  public SchemaClassImpl createClass(
      DatabaseSessionInternal session, final String iClassName, final SchemaClassImpl iSuperClass) {
    return createClass(session, iClassName, iSuperClass, null);
  }

  public SchemaClassImpl createClass(
      DatabaseSessionInternal session, String iClassName, SchemaClassImpl... superClasses) {
    return createClass(session, iClassName, null, superClasses);
  }

  public SchemaClassImpl getOrCreateClass(DatabaseSessionInternal session,
      final String iClassName) {
    return getOrCreateClass(session, iClassName, (SchemaClassImpl) null);
  }

  public SchemaClassImpl getOrCreateClass(
      DatabaseSessionInternal session, final String iClassName, final SchemaClassImpl superClass) {
    return getOrCreateClass(
        session, iClassName,
        superClass == null ? new SchemaClassImpl[0] : new SchemaClassImpl[]{superClass});
  }

  public abstract SchemaClassImpl getOrCreateClass(
      DatabaseSessionInternal session, final String iClassName,
      final SchemaClassImpl... superClasses);

  public SchemaClassImpl createAbstractClass(DatabaseSessionInternal session,
      final String className) {
    return createClass(session, className, null, new int[]{-1});
  }

  public SchemaClassImpl createAbstractClass(
      DatabaseSessionInternal session, final String className, final SchemaClassImpl superClass) {
    return createClass(session, className, superClass, new int[]{-1});
  }

  public SchemaClassImpl createAbstractClass(
      DatabaseSessionInternal session, String iClassName, SchemaClassImpl... superClasses) {
    return createClass(session, iClassName, new int[]{-1}, superClasses);
  }

  public SchemaClassImpl createClass(
      DatabaseSessionInternal session,
      final String className,
      final SchemaClassImpl superClass,
      int[] clusterIds) {
    return createClass(session, className, clusterIds, superClass);
  }

  public abstract SchemaClassImpl createClass(
      DatabaseSessionInternal session,
      final String className,
      int[] clusterIds,
      SchemaClassImpl... superClasses);

  public abstract SchemaClassImpl createClass(
      DatabaseSessionInternal session,
      final String className,
      int clusters,
      SchemaClassImpl... superClasses);


  public abstract void checkEmbedded(DatabaseSessionInternal session);

  void checkClusterCanBeAdded(DatabaseSessionInternal session, int clusterId, SchemaClassImpl cls) {
    acquireSchemaReadLock(session);
    try {
      if (clusterId < 0) {
        return;
      }

      if (blobClusters.contains(clusterId)) {
        throw new SchemaException(session.getDatabaseName(),
            "Cluster with id " + clusterId + " already belongs to Blob");
      }

      final var existingCls = clustersToClasses.get(clusterId);

      if (existingCls != null && (cls == null || !cls.equals(existingCls))) {
        throw new SchemaException(session.getDatabaseName(),
            "Cluster with id "
                + clusterId
                + " already belongs to the class '"
                + clustersToClasses.get(clusterId)
                + "'");
      }
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public SchemaClassImpl getClassByClusterId(DatabaseSessionInternal session, int clusterId) {
    acquireSchemaReadLock(session);
    try {
      return clustersToClasses.get(clusterId);
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public abstract void dropClass(DatabaseSessionInternal session, final String className);

  /**
   * Reloads the schema inside a storage's shared lock.
   */
  public void reload(DatabaseSessionInternal session) {
    lock.writeLock().lock();
    try {
      session.executeInTx(
          transaction -> {
            identity = new RecordId(
                session.getStorageInfo().getConfiguration().getSchemaRecordId());

            EntityImpl entity = session.load(identity);
            fromStream(session, entity);
            forceSnapshot(session);
          });
    } finally {
      lock.writeLock().unlock();
    }
  }

  public boolean existsClass(DatabaseSessionInternal session, final String iClassName) {
    if (iClassName == null) {
      return false;
    }

    acquireSchemaReadLock(session);
    try {
      return classes.containsKey(iClassName.toLowerCase(Locale.ENGLISH));
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public SchemaClassImpl getClass(DatabaseSessionInternal session, final Class<?> iClass) {
    if (iClass == null) {
      return null;
    }

    return getClass(session, iClass.getSimpleName());
  }

  @Nullable
  public SchemaClassImpl getClass(DatabaseSessionInternal session, final String iClassName) {
    if (iClassName == null) {
      return null;
    }

    acquireSchemaReadLock(session);
    try {
      return classes.get(iClassName.toLowerCase(Locale.ENGLISH));
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public void acquireSchemaReadLock(DatabaseSessionInternal session) {
    lock.readLock().lock();
  }

  public void releaseSchemaReadLock(DatabaseSessionInternal session) {
    lock.readLock().unlock();
  }

  public void acquireSchemaWriteLock(DatabaseSessionInternal session) {
    session.startExclusiveMetadataChange();
    lock.writeLock().lock();
    modificationCounter.increment();
  }

  public void releaseSchemaWriteLock(DatabaseSessionInternal session) {
    releaseSchemaWriteLock(session, true);
  }

  public void releaseSchemaWriteLock(DatabaseSessionInternal session, final boolean iSave) {
    int count;
    try {
      if (modificationCounter.intValue() == 1) {
        // if it is embedded storage modification of schema is done by internal methods otherwise it
        // is done by
        // by sql commands and we need to reload local replica

        if (iSave) {
          if (session.getStorage() instanceof AbstractPaginatedStorage) {
            saveInternal(session);
          } else {
            reload(session);
          }
        } else {
          snapshot = new ImmutableSchema(this, session);
        }
        //noinspection NonAtomicOperationOnVolatileField
        version++;
      }
    } finally {
      modificationCounter.decrement();
      count = modificationCounter.intValue();
      lock.writeLock().unlock();
      session.endExclusiveMetadataChange();
    }
    assert count >= 0;

    if (count == 0 && session.isRemote()) {
      session.getStorage().reload(session);
    }
  }

  void changeClassName(
      DatabaseSessionInternal session,
      final String oldName,
      final String newName,
      final SchemaClassImpl cls) {

    if (oldName != null && oldName.equalsIgnoreCase(newName)) {
      throw new IllegalArgumentException(
          "Class '" + oldName + "' cannot be renamed with the same name");
    }

    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);

      if (newName != null
          && (classes.containsKey(newName.toLowerCase(Locale.ENGLISH)))) {
        throw new IllegalArgumentException("Class '" + newName + "' is already present in schema");
      }

      if (oldName != null) {
        classes.remove(oldName.toLowerCase(Locale.ENGLISH));
      }
      if (newName != null) {
        classes.put(newName.toLowerCase(Locale.ENGLISH), cls);
      }

    } finally {
      releaseSchemaWriteLock(session);
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
      final Integer schemaVersion = entity.getProperty("schemaVersion");
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
      clustersToClasses.clear();

      final Map<String, SchemaClassImpl> newClasses = new HashMap<>();

      Collection<EntityImpl> storedClasses = entity.getProperty("classes");
      for (var c : storedClasses) {
        String name = c.getProperty("name");

        SchemaClassImpl cls;
        if (classes.containsKey(name.toLowerCase(Locale.ENGLISH))) {
          cls = classes.get(name.toLowerCase(Locale.ENGLISH));
          cls.fromStream(session, c);
        } else {
          cls = createClassInstance(name);
          cls.fromStream(session, c);
        }

        newClasses.put(cls.getName(session).toLowerCase(Locale.ENGLISH), cls);
        addClusterClassMap(session, cls);
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
              classes.get(((String) c.getProperty("name")).toLowerCase(Locale.ENGLISH));
          superClasses = new ArrayList<>(superClassNames.size());
          for (var superClassName : superClassNames) {

            superClass = classes.get(superClassName.toLowerCase(Locale.ENGLISH));

            if (superClass == null) {
              throw new ConfigurationException(
                  session.getDatabaseName(), "Super class '"
                  + superClassName
                  + "' was declared in class '"
                  + cls.getName(session)
                  + "' but was not found in schema. Remove the dependency or create the class"
                  + " to continue.");
            }
            superClasses.add(superClass);
          }
          cls.setSuperClassesInternal(session, superClasses);
        }
      }

      // VIEWS

      if (entity.hasProperty("blobClusters")) {
        blobClusters = new IntOpenHashSet(entity.getEmbeddedSet("blobClusters"));
      }

      if (!hasGlobalProperties) {
        if (session.getStorage() instanceof AbstractPaginatedStorage) {
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
  public EntityImpl toStream(@Nonnull DatabaseSessionInternal session) {
    lock.readLock().lock();
    try {
      EntityImpl entity = session.load(identity);
      entity.setProperty("schemaVersion", CURRENT_VERSION_NUMBER);

      // This steps is needed because in classes there are duplicate due to aliases
      Set<SchemaClassImpl> realClases = new HashSet<>(classes.values());

      Set<Entity> classesEntities = session.newEmbeddedSet();
      for (var c : realClases) {
        classesEntities.add(c.toStream(session));
      }
      entity.setProperty("classes", classesEntities, PropertyType.EMBEDDEDSET);

      List<Entity> globalProperties = session.newEmbeddedList();
      for (var globalProperty : properties) {
        if (globalProperty != null) {
          globalProperties.add(((GlobalPropertyImpl) globalProperty).toEntity(session));
        }
      }
      entity.setProperty("globalProperties", globalProperties, PropertyType.EMBEDDEDLIST);
      Object propertyValue = session.newEmbeddedSet(blobClusters);
      entity.setProperty("blobClusters", propertyValue, PropertyType.EMBEDDEDSET);
      return entity;
    } finally {
      lock.readLock().unlock();
    }
  }

  public Collection<SchemaClassImpl> getClasses(DatabaseSessionInternal session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_READ);
    acquireSchemaReadLock(session);
    try {
      return new HashSet<>(classes.values());
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public Set<SchemaClassImpl> getClassesRelyOnCluster(
      DatabaseSessionInternal session, final String clusterName) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_READ);

    acquireSchemaReadLock(session);
    try {
      final var clusterId = session.getClusterIdByName(clusterName);
      final Set<SchemaClassImpl> result = new HashSet<>();
      for (var c : classes.values()) {
        if (ArrayUtils.contains(c.getPolymorphicClusterIds(session), clusterId)) {
          result.add(c);
        }
      }

      return result;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public SchemaShared load(DatabaseSessionInternal session) {

    lock.writeLock().lock();
    try {
      identity = new RecordId(session.getStorageInfo().getConfiguration().getSchemaRecordId());
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

  public void create(final DatabaseSessionInternal session) {
    lock.writeLock().lock();
    try {
      var entity = session.computeInTx(transaction -> session.newInternalInstance());

      this.identity = entity.getIdentity();
      session.getStorage().setSchemaRecordId(entity.getIdentity().toString());
      snapshot = new ImmutableSchema(this, session);
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

  public RecordId getIdentity(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return identity;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public GlobalProperty getGlobalPropertyById(DatabaseSessionInternal session, int id) {
    acquireSchemaReadLock(session);
    try {
      if (id >= properties.size()) {
        return null;
      }
      return properties.get(id);
    } finally {
      releaseSchemaReadLock(session);
    }

  }

  public GlobalProperty createGlobalProperty(
      DatabaseSessionInternal session, final String name, final PropertyTypeInternal type,
      final Integer id) {

    acquireSchemaWriteLock(session);
    try {
      GlobalProperty global;
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

  public List<GlobalProperty> getGlobalProperties(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return Collections.unmodifiableList(properties);
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  protected GlobalProperty findOrCreateGlobalProperty(final String name,
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

    forceSnapshot(session);
  }

  protected void addClusterClassMap(DatabaseSessionInternal session, final SchemaClassImpl cls) {
    for (var clusterId : cls.getClusterIds(session)) {
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

  public int addBlobCluster(DatabaseSessionInternal session, int clusterId) {
    acquireSchemaWriteLock(session);
    try {
      checkClusterCanBeAdded(session, clusterId, null);
      blobClusters.add(clusterId);
    } finally {
      releaseSchemaWriteLock(session);
    }
    return clusterId;
  }

  public void removeBlobCluster(DatabaseSessionInternal session, String clusterName) {
    acquireSchemaWriteLock(session);
    try {
      var clusterId = getClusterId(session, clusterName);
      blobClusters.remove(clusterId);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected static int getClusterId(DatabaseSessionInternal session, final String stringValue) {
    int clId;
    try {
      clId = Integer.parseInt(stringValue);
    } catch (NumberFormatException ignore) {
      clId = session.getClusterIdByName(stringValue);
    }
    return clId;
  }

  public IntSet getBlobClusters(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return IntSets.unmodifiable(blobClusters);
    } finally {
      releaseSchemaReadLock(session);
    }

  }
}
